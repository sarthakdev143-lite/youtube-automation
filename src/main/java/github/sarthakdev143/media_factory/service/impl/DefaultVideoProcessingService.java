package github.sarthakdev143.media_factory.service.impl;

import github.sarthakdev143.media_factory.dto.CompositionCaptionRequest;
import github.sarthakdev143.media_factory.dto.CompositionColorGradeRequest;
import github.sarthakdev143.media_factory.dto.CompositionManifestRequest;
import github.sarthakdev143.media_factory.dto.CompositionOverlayRequest;
import github.sarthakdev143.media_factory.dto.CompositionSceneRequest;
import github.sarthakdev143.media_factory.dto.CompositionTransitionRequest;
import github.sarthakdev143.media_factory.dto.CompositionVisualEditRequest;
import github.sarthakdev143.media_factory.factory.VideoGeneratorUploaderFactory;
import github.sarthakdev143.media_factory.integration.video.FfmpegProcessRunner;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;
import github.sarthakdev143.media_factory.integration.youtube.YouTubeServiceProvider;
import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.TransitionType;
import github.sarthakdev143.media_factory.model.UploadResult;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
import github.sarthakdev143.media_factory.model.VisualFilterType;
import github.sarthakdev143.media_factory.model.composition.CompositionCaptionPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionColorGradePlan;
import github.sarthakdev143.media_factory.model.composition.CompositionOverlayPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionRenderPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionScenePlan;
import github.sarthakdev143.media_factory.model.composition.CompositionTransitionPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionVisualEditPlan;
import github.sarthakdev143.media_factory.persistence.entity.JobEntity;
import github.sarthakdev143.media_factory.persistence.repository.JobRepository;
import github.sarthakdev143.media_factory.service.CompositionRenderer;
import github.sarthakdev143.media_factory.service.JobInProgressException;
import github.sarthakdev143.media_factory.service.VideoProcessingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class DefaultVideoProcessingService implements VideoProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultVideoProcessingService.class);
    private static final String SHUTDOWN_FAILURE_MESSAGE = "Application shutdown interrupted FFmpeg processing.";

    private final YouTubeServiceProvider youTubeServiceProvider;
    private final VideoGeneratorUploaderFactory uploaderFactory;
    private final CompositionRenderer compositionRenderer;
    private final TaskExecutor taskExecutor;
    private final JobRepository jobRepository;
    private final FfmpegProcessRunner ffmpegProcessRunner;
    private final Counter jobsWithSchedulingCounter;
    private final Counter jobsWithThumbnailCounter;
    private final Counter thumbnailFailureCounter;
    private final Counter uploadFailureCounter;
    private final Counter thumbnailUploadFailureCounter;
    private final Object submissionLock = new Object();

    public DefaultVideoProcessingService(
            YouTubeServiceProvider youTubeServiceProvider,
            VideoGeneratorUploaderFactory uploaderFactory,
            CompositionRenderer compositionRenderer,
            TaskExecutor taskExecutor,
            JobRepository jobRepository,
            FfmpegProcessRunner ffmpegProcessRunner,
            MeterRegistry meterRegistry) {
        this.youTubeServiceProvider = youTubeServiceProvider;
        this.uploaderFactory = uploaderFactory;
        this.compositionRenderer = compositionRenderer;
        this.taskExecutor = taskExecutor;
        this.jobRepository = jobRepository;
        this.ffmpegProcessRunner = ffmpegProcessRunner;
        this.jobsWithSchedulingCounter = meterRegistry.counter("media_factory.jobs.with_scheduling");
        this.jobsWithThumbnailCounter = meterRegistry.counter("media_factory.jobs.with_thumbnail");
        this.thumbnailFailureCounter = meterRegistry.counter("media_factory.thumbnail.failures");
        this.uploadFailureCounter = meterRegistry.counter("media_factory.upload.failures", "phase", "video_upload");
        this.thumbnailUploadFailureCounter = meterRegistry.counter(
                "media_factory.upload.failures",
                "phase",
                "thumbnail_upload");
    }

    @Override
    public String submitJob(
            MultipartFile image,
            MultipartFile audio,
            int durationSeconds,
            String title,
            String description,
            PublishOptions publishOptions,
            MultipartFile thumbnail) throws IOException {
        UUID jobId = UUID.randomUUID();
        PublishOptions normalizedPublishOptions = normalizePublishOptions(publishOptions);

        synchronized (submissionLock) {
            ensureNoActiveJob();
            jobRepository.save(createQueuedJob(jobId, normalizedPublishOptions));
        }

        trackJobMetrics(normalizedPublishOptions, thumbnail != null);
        logger.info(
                "[JOB QUEUED] id={} type=basic privacyStatus={} scheduled={} hasThumbnail={}",
                jobId,
                normalizedPublishOptions.privacyStatus(),
                normalizedPublishOptions.isScheduled(),
                thumbnail != null);

        Path imagePath = null;
        Path audioPath = null;
        Path thumbnailPath = null;
        try {
            imagePath = copyMultipartToTemp(image, "media-factory-image-", ".jpg");
            audioPath = copyMultipartToTemp(audio, "media-factory-audio-", resolveAudioSuffix(audio));
            if (thumbnail != null) {
                thumbnailPath = copyMultipartToTemp(
                        thumbnail,
                        "media-factory-thumbnail-",
                        resolveThumbnailSuffix(thumbnail.getContentType()));
            }
        } catch (IOException copyError) {
            deleteTempFile(imagePath);
            deleteTempFile(audioPath);
            deleteTempFile(thumbnailPath);
            markJobFailed(jobId, "Failed to persist uploaded files: " + copyError.getMessage());
            throw copyError;
        }

        Path finalImagePath = imagePath;
        Path finalAudioPath = audioPath;
        Path finalThumbnailPath = thumbnailPath;
        String finalThumbnailType = thumbnail != null ? thumbnail.getContentType() : null;

        try {
            taskExecutor.execute(() -> processBasicJob(
                    jobId,
                    finalImagePath,
                    finalAudioPath,
                    finalThumbnailPath,
                    finalThumbnailType,
                    durationSeconds,
                    title,
                    description,
                    normalizedPublishOptions));
            return jobId.toString();
        } catch (RuntimeException schedulingError) {
            deleteTempFile(finalImagePath);
            deleteTempFile(finalAudioPath);
            deleteTempFile(finalThumbnailPath);
            markJobFailed(jobId, "Failed to schedule job execution.");
            throw schedulingError;
        }
    }

    @Override
    public String submitCompositionJob(
            Map<String, MultipartFile> assets,
            MultipartFile audio,
            CompositionManifestRequest manifest,
            String title,
            String description,
            PublishOptions publishOptions,
            MultipartFile thumbnail) throws IOException {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest is required.");
        }
        Map<String, MultipartFile> safeAssets = assets == null ? Map.of() : assets;
        UUID jobId = UUID.randomUUID();
        PublishOptions normalizedPublishOptions = normalizePublishOptions(publishOptions);

        synchronized (submissionLock) {
            ensureNoActiveJob();
            jobRepository.save(createQueuedJob(jobId, normalizedPublishOptions));
        }

        trackJobMetrics(normalizedPublishOptions, thumbnail != null);
        logger.info(
                "[JOB QUEUED] id={} type=composition scenes={} privacyStatus={} scheduled={} hasThumbnail={}",
                jobId,
                manifest.scenes().size(),
                normalizedPublishOptions.privacyStatus(),
                normalizedPublishOptions.isScheduled(),
                thumbnail != null);

        Path audioPath = null;
        Path thumbnailPath = null;
        Map<String, Path> assetPaths = new LinkedHashMap<>();

        try {
            audioPath = copyMultipartToTemp(audio, "media-factory-composition-audio-", resolveAudioSuffix(audio));
            for (Map.Entry<String, MultipartFile> entry : safeAssets.entrySet()) {
                Path assetPath = copyMultipartToTemp(
                        entry.getValue(),
                        "media-factory-composition-asset-",
                        resolveAssetSuffix(entry.getValue()));
                assetPaths.put(entry.getKey(), assetPath);
            }

            if (thumbnail != null) {
                thumbnailPath = copyMultipartToTemp(
                        thumbnail,
                        "media-factory-thumbnail-",
                        resolveThumbnailSuffix(thumbnail.getContentType()));
            }
        } catch (IOException copyError) {
            deleteTempFile(audioPath);
            deleteTempFile(thumbnailPath);
            deleteTempFiles(assetPaths.values());
            markJobFailed(jobId, "Failed to persist uploaded files: " + copyError.getMessage());
            throw copyError;
        }

        Path finalAudioPath = audioPath;
        Path finalThumbnailPath = thumbnailPath;
        String finalThumbnailType = thumbnail != null ? thumbnail.getContentType() : null;
        Map<String, Path> finalAssetPaths = new LinkedHashMap<>(assetPaths);

        try {
            taskExecutor.execute(() -> processCompositionJob(
                    jobId,
                    manifest,
                    finalAssetPaths,
                    finalAudioPath,
                    finalThumbnailPath,
                    finalThumbnailType,
                    title,
                    description,
                    normalizedPublishOptions));
            return jobId.toString();
        } catch (RuntimeException schedulingError) {
            deleteTempFile(finalAudioPath);
            deleteTempFile(finalThumbnailPath);
            deleteTempFiles(finalAssetPaths.values());
            markJobFailed(jobId, "Failed to schedule job execution.");
            throw schedulingError;
        }
    }

    @Override
    public Optional<VideoJobStatus> getJobStatus(String jobId) {
        UUID parsedId = parseJobId(jobId);
        if (parsedId == null) {
            return Optional.empty();
        }
        return jobRepository.findById(parsedId).map(JobStatusMapper::toStatus);
    }

    @Override
    public Optional<VideoJobStatus> getActiveJobStatus() {
        return jobRepository.findFirstByStateInOrderByCreatedAtAsc(List.of(VideoJobState.QUEUED, VideoJobState.PROCESSING))
                .map(JobStatusMapper::toStatus);
    }

    @PreDestroy
    public void onShutdown() {
        ffmpegProcessRunner.stopActiveProcess().ifPresent(activeProcess -> {
            UUID jobId = parseJobId(activeProcess.jobId());
            if (jobId != null) {
                markJobFailed(jobId, SHUTDOWN_FAILURE_MESSAGE);
            }
            deleteTempFile(activeProcess.outputPath());
        });
    }

    private void processBasicJob(
            UUID jobId,
            Path imagePath,
            Path audioPath,
            Path thumbnailPath,
            String thumbnailContentType,
            int durationSeconds,
            String title,
            String description,
            PublishOptions publishOptions) {
        Path outputVideoPath = null;
        updateJobForProcessing(jobId, "Rendering video with FFmpeg.");
        logger.info("[JOB STARTED] id={} type=basic", jobId);

        try {
            outputVideoPath = Files.createTempFile("media-factory-output-", ".mp4");
            updateOutputFilePath(jobId, outputVideoPath);

            VideoGeneratorUploader uploader = uploaderFactory.create(youTubeServiceProvider.getService());
            uploader.generateVideo(
                    imagePath.toString(),
                    audioPath.toString(),
                    durationSeconds,
                    outputVideoPath.toString(),
                    jobId.toString(),
                    progressPercent -> updateEncodingProgress(jobId, progressPercent));

            completeUpload(jobId, uploader, outputVideoPath, title, description, publishOptions, thumbnailPath, thumbnailContentType);
            logger.info(
                    "[JOB COMPLETE] id={} type=basic privacyStatus={} scheduled={}",
                    jobId,
                    publishOptions.privacyStatus(),
                    publishOptions.isScheduled());
        } catch (Exception exception) {
            logger.error("[JOB FAILED] id={} reason={}", jobId, exception.getMessage(), exception);
            markJobFailed(jobId, "Video processing failed: " + exception.getMessage());
        } finally {
            deleteTempFile(imagePath);
            deleteTempFile(audioPath);
            deleteTempFile(thumbnailPath);
            deleteTempFile(outputVideoPath);
            clearOutputFilePath(jobId);
        }
    }

    private void processCompositionJob(
            UUID jobId,
            CompositionManifestRequest manifest,
            Map<String, Path> assetPaths,
            Path audioPath,
            Path thumbnailPath,
            String thumbnailContentType,
            String title,
            String description,
            PublishOptions publishOptions) {
        Path outputVideoPath = null;
        updateJobForProcessing(jobId, "Rendering composition timeline with FFmpeg.");
        logger.info("[JOB STARTED] id={} type=composition", jobId);

        try {
            outputVideoPath = Files.createTempFile("media-factory-composition-output-", ".mp4");
            updateOutputFilePath(jobId, outputVideoPath);

            CompositionRenderPlan renderPlan = buildRenderPlan(manifest, audioPath, assetPaths);
            compositionRenderer.renderComposition(
                    renderPlan,
                    outputVideoPath,
                    jobId.toString(),
                    progressPercent -> updateEncodingProgress(jobId, progressPercent));

            VideoGeneratorUploader uploader = uploaderFactory.create(youTubeServiceProvider.getService());
            completeUpload(jobId, uploader, outputVideoPath, title, description, publishOptions, thumbnailPath, thumbnailContentType);

            logger.info(
                    "[JOB COMPLETE] id={} type=composition privacyStatus={} scheduled={}",
                    jobId,
                    publishOptions.privacyStatus(),
                    publishOptions.isScheduled());
        } catch (Exception exception) {
            logger.error("[JOB FAILED] id={} reason={}", jobId, exception.getMessage(), exception);
            markJobFailed(jobId, "Video processing failed: " + exception.getMessage());
        } finally {
            deleteTempFile(audioPath);
            deleteTempFile(thumbnailPath);
            deleteTempFile(outputVideoPath);
            deleteTempFiles(assetPaths.values());
            clearOutputFilePath(jobId);
        }
    }

    private void completeUpload(
            UUID jobId,
            VideoGeneratorUploader uploader,
            Path outputVideoPath,
            String title,
            String description,
            PublishOptions publishOptions,
            Path thumbnailPath,
            String thumbnailContentType) throws Exception {
        updateJob(jobId, job -> {
            job.setState(VideoJobState.PROCESSING);
            job.setProgressPercent(Math.max(job.getProgressPercent(), 96));
            job.setMessage("Uploading video to YouTube.");
        });
        logger.info("[UPLOAD STARTED] id={}", jobId);

        UploadResult uploadResult;
        try {
            uploadResult = uploader.uploadToYouTube(
                    outputVideoPath.toString(),
                    title,
                    description,
                    publishOptions);
        } catch (Exception uploadError) {
            uploadFailureCounter.increment();
            throw uploadError;
        }

        String videoId = uploadResult.videoId();
        String videoUrl = buildVideoUrl(videoId);
        String warningMessage = uploadResult.warningMessage();

        if (thumbnailPath != null) {
            updateJob(jobId, job -> job.setMessage("Uploading custom thumbnail."));
            try {
                uploader.uploadThumbnail(videoId, thumbnailPath.toString(), thumbnailContentType);
            } catch (Exception thumbnailError) {
                thumbnailFailureCounter.increment();
                thumbnailUploadFailureCounter.increment();
                warningMessage = combineWarnings(
                        warningMessage,
                        "Video uploaded, but thumbnail upload failed.");
                logger.error("[JOB WARNING] id={} warning=thumbnail upload failed", jobId, thumbnailError);
            }
        }

        markJobCompleted(jobId, videoId, videoUrl, warningMessage);
        logger.info("[UPLOAD COMPLETE] id={} videoId={}", jobId, videoId);
    }

    private CompositionRenderPlan buildRenderPlan(
            CompositionManifestRequest manifest,
            Path audioPath,
            Map<String, Path> assetPaths) {
        List<CompositionScenePlan> scenePlans = new ArrayList<>();
        double totalDurationSeconds = 0.0;

        for (CompositionSceneRequest scene : manifest.scenes()) {
            double durationSeconds = scene.type() == github.sarthakdev143.media_factory.model.SceneType.IMAGE
                    ? scene.durationSec()
                    : scene.clipDurationSec();
            double clipStartSeconds = scene.clipStartSec() == null ? 0.0 : scene.clipStartSec();
            MotionType motion = scene.motion() == null ? MotionType.NONE : scene.motion();
            CompositionCaptionPlan captionPlan = toCaptionPlan(scene.caption());
            CompositionTransitionPlan transitionPlan = toTransitionPlan(scene.transition());
            CompositionVisualEditPlan visualEditPlan = toVisualEditPlan(scene.visualEdit());

            scenePlans.add(new CompositionScenePlan(
                    scene.assetId(),
                    scene.type(),
                    durationSeconds,
                    clipStartSeconds,
                    motion,
                    captionPlan,
                    transitionPlan,
                    visualEditPlan));

            totalDurationSeconds += durationSeconds;
            if (transitionPlan.type() == TransitionType.CROSSFADE) {
                totalDurationSeconds -= transitionPlan.durationSec();
            }
        }

        return new CompositionRenderPlan(
                manifest.outputPreset(),
                scenePlans,
                audioPath,
                assetPaths,
                totalDurationSeconds);
    }

    private CompositionCaptionPlan toCaptionPlan(CompositionCaptionRequest caption) {
        if (caption == null) {
            return null;
        }
        return new CompositionCaptionPlan(
                caption.text(),
                caption.startOffsetSec(),
                caption.endOffsetSec(),
                caption.position());
    }

    private CompositionTransitionPlan toTransitionPlan(CompositionTransitionRequest transition) {
        if (transition == null || transition.type() == null || transition.type() == TransitionType.CUT) {
            return new CompositionTransitionPlan(TransitionType.CUT, 0.0);
        }
        return new CompositionTransitionPlan(TransitionType.CROSSFADE, transition.transitionDurationSec());
    }

    private CompositionVisualEditPlan toVisualEditPlan(CompositionVisualEditRequest visualEdit) {
        if (visualEdit == null) {
            return new CompositionVisualEditPlan(
                    VisualFilterType.NONE,
                    new CompositionColorGradePlan(0.0, 1.0, 1.0),
                    null);
        }

        CompositionColorGradeRequest colorGrade = visualEdit.colorGrade();
        CompositionColorGradePlan colorGradePlan = colorGrade == null
                ? new CompositionColorGradePlan(0.0, 1.0, 1.0)
                : new CompositionColorGradePlan(
                        colorGrade.brightness() == null ? 0.0 : colorGrade.brightness(),
                        colorGrade.contrast() == null ? 1.0 : colorGrade.contrast(),
                        colorGrade.saturation() == null ? 1.0 : colorGrade.saturation());

        CompositionOverlayRequest overlay = visualEdit.overlay();
        CompositionOverlayPlan overlayPlan = overlay == null
                ? null
                : new CompositionOverlayPlan(
                        overlay.hexColor(),
                        overlay.opacity() == null ? 0.0 : overlay.opacity());

        return new CompositionVisualEditPlan(
                visualEdit.filter() == null ? VisualFilterType.NONE : visualEdit.filter(),
                colorGradePlan,
                overlayPlan);
    }

    private void ensureNoActiveJob() {
        Optional<JobEntity> activeJob = jobRepository.findFirstByStateInOrderByCreatedAtAsc(
                List.of(VideoJobState.QUEUED, VideoJobState.PROCESSING));
        if (activeJob.isPresent()) {
            JobEntity existingJob = activeJob.get();
            throw new JobInProgressException(existingJob.getId().toString(), existingJob.getState());
        }
    }

    private JobEntity createQueuedJob(UUID jobId, PublishOptions publishOptions) {
        JobEntity job = new JobEntity();
        job.setId(jobId);
        job.setState(VideoJobState.QUEUED);
        job.setProgressPercent(0);
        job.setMessage("Job accepted. Starting shortly.");
        job.setErrorMessage(null);
        job.setOutputFilePath(null);
        job.setYoutubeVideoId(null);
        job.setPrivacyStatus(publishOptions.privacyStatus());
        job.setTagsCsv(JobStatusMapper.toTagsCsv(publishOptions.tags()));
        job.setCategoryId(publishOptions.categoryId());
        job.setScheduledPublishTime(publishOptions.publishAt());
        job.setYoutubeVideoUrl(null);
        job.setWarningMessage(null);
        return job;
    }

    private void updateJobForProcessing(UUID jobId, String message) {
        updateJob(jobId, job -> {
            job.setState(VideoJobState.PROCESSING);
            job.setProgressPercent(Math.max(job.getProgressPercent(), 1));
            job.setMessage(message);
            job.setErrorMessage(null);
        });
    }

    private void updateEncodingProgress(UUID jobId, int progressPercent) {
        int boundedProgress = Math.max(0, Math.min(progressPercent, 95));
        AtomicInteger loggedProgress = new AtomicInteger(-1);
        updateJob(jobId, job -> {
            if (boundedProgress <= job.getProgressPercent()) {
                return;
            }
            job.setState(VideoJobState.PROCESSING);
            job.setProgressPercent(boundedProgress);
            job.setMessage("Encoding video with FFmpeg: " + boundedProgress + "%");
            loggedProgress.set(boundedProgress);
        });

        if (loggedProgress.get() >= 0) {
            logger.info("[ENCODING] id={} progress={}%", jobId, loggedProgress.get());
        }
    }

    private void updateOutputFilePath(UUID jobId, Path outputFilePath) {
        updateJob(jobId, job -> job.setOutputFilePath(outputFilePath == null ? null : outputFilePath.toString()));
    }

    private void clearOutputFilePath(UUID jobId) {
        updateJob(jobId, job -> job.setOutputFilePath(null));
    }

    private void markJobCompleted(UUID jobId, String videoId, String videoUrl, String warningMessage) {
        String completionMessage = warningMessage == null
                ? "Video generated and uploaded successfully."
                : "Video generated and uploaded with warnings.";

        updateJob(jobId, job -> {
            job.setState(VideoJobState.COMPLETED);
            job.setProgressPercent(100);
            job.setMessage(completionMessage);
            job.setYoutubeVideoId(videoId);
            job.setYoutubeVideoUrl(videoUrl);
            job.setWarningMessage(warningMessage);
            job.setErrorMessage(null);
        });
    }

    private void markJobFailed(UUID jobId, String reason) {
        updateJob(jobId, job -> {
            if (job.getState() == VideoJobState.COMPLETED) {
                return;
            }
            job.setState(VideoJobState.FAILED);
            job.setMessage(reason);
            job.setErrorMessage(reason);
            job.setProgressPercent(Math.min(job.getProgressPercent(), 99));
        });
    }

    private void updateJob(UUID jobId, Consumer<JobEntity> mutator) {
        jobRepository.findById(jobId).ifPresent(job -> {
            mutator.accept(job);
            jobRepository.save(job);
        });
    }

    private void trackJobMetrics(PublishOptions publishOptions, boolean hasThumbnail) {
        if (publishOptions.isScheduled()) {
            jobsWithSchedulingCounter.increment();
        }
        if (hasThumbnail) {
            jobsWithThumbnailCounter.increment();
        }
    }

    private PublishOptions normalizePublishOptions(PublishOptions publishOptions) {
        if (publishOptions == null) {
            return new PublishOptions(PrivacyStatus.PRIVATE, List.of(), null, null);
        }

        return new PublishOptions(
                publishOptions.privacyStatus(),
                publishOptions.tags(),
                publishOptions.categoryId(),
                publishOptions.publishAt());
    }

    private Path copyMultipartToTemp(MultipartFile file, String prefix, String suffix) throws IOException {
        Path tempFile = Files.createTempFile(prefix, suffix);
        file.transferTo(tempFile);
        return tempFile;
    }

    private String resolveAudioSuffix(MultipartFile audio) {
        String contentType = audio.getContentType();
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.ROOT);
            if (normalized.contains("wav")) {
                return ".wav";
            }
            if (normalized.contains("ogg")) {
                return ".ogg";
            }
            if (normalized.contains("aac")) {
                return ".aac";
            }
        }
        return ".mp3";
    }

    private String resolveAssetSuffix(MultipartFile asset) {
        String originalFilename = asset.getOriginalFilename();
        if (originalFilename != null) {
            int extensionIndex = originalFilename.lastIndexOf('.');
            if (extensionIndex >= 0 && extensionIndex < originalFilename.length() - 1) {
                return originalFilename.substring(extensionIndex);
            }
        }

        String contentType = asset.getContentType();
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.ROOT);
            if (normalized.contains("png")) {
                return ".png";
            }
            if (normalized.contains("jpeg") || normalized.contains("jpg")) {
                return ".jpg";
            }
            if (normalized.contains("gif")) {
                return ".gif";
            }
            if (normalized.contains("webm")) {
                return ".webm";
            }
            if (normalized.contains("quicktime")) {
                return ".mov";
            }
        }

        return ".mp4";
    }

    private String resolveThumbnailSuffix(String contentType) {
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("png")) {
            return ".png";
        }
        return ".jpg";
    }

    private UUID parseJobId(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(jobId);
        } catch (IllegalArgumentException invalidFormat) {
            return null;
        }
    }

    private String buildVideoUrl(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            return null;
        }
        return "https://www.youtube.com/watch?v=" + videoId;
    }

    private String combineWarnings(String existingWarning, String newWarning) {
        if (existingWarning == null || existingWarning.isBlank()) {
            return newWarning;
        }
        if (newWarning == null || newWarning.isBlank()) {
            return existingWarning;
        }
        return existingWarning + " " + newWarning;
    }

    private void deleteTempFiles(Iterable<Path> paths) {
        if (paths == null) {
            return;
        }
        for (Path path : paths) {
            deleteTempFile(path);
        }
    }

    private void deleteTempFile(Path filePath) {
        if (filePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(filePath);
        } catch (Exception ignored) {
            // Cleanup failures are non-fatal.
        }
    }
}
