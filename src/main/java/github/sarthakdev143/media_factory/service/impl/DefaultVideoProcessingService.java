package github.sarthakdev143.media_factory.service.impl;

import github.sarthakdev143.media_factory.dto.CompositionCaptionRequest;
import github.sarthakdev143.media_factory.dto.CompositionManifestRequest;
import github.sarthakdev143.media_factory.dto.CompositionSceneRequest;
import github.sarthakdev143.media_factory.dto.CompositionTransitionRequest;
import github.sarthakdev143.media_factory.factory.VideoGeneratorUploaderFactory;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;
import github.sarthakdev143.media_factory.integration.youtube.YouTubeServiceProvider;
import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.TransitionType;
import github.sarthakdev143.media_factory.model.UploadResult;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
import github.sarthakdev143.media_factory.model.composition.CompositionCaptionPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionRenderPlan;
import github.sarthakdev143.media_factory.model.composition.CompositionScenePlan;
import github.sarthakdev143.media_factory.model.composition.CompositionTransitionPlan;
import github.sarthakdev143.media_factory.service.CompositionRenderer;
import github.sarthakdev143.media_factory.service.VideoProcessingService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DefaultVideoProcessingService implements VideoProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultVideoProcessingService.class);

    private final YouTubeServiceProvider youTubeServiceProvider;
    private final VideoGeneratorUploaderFactory uploaderFactory;
    private final CompositionRenderer compositionRenderer;
    private final TaskExecutor taskExecutor;
    private final Map<String, VideoJobStatus> jobs = new ConcurrentHashMap<>();
    private final Counter jobsWithSchedulingCounter;
    private final Counter jobsWithThumbnailCounter;
    private final Counter thumbnailFailureCounter;
    private final Counter uploadFailureCounter;
    private final Counter thumbnailUploadFailureCounter;

    public DefaultVideoProcessingService(
            YouTubeServiceProvider youTubeServiceProvider,
            VideoGeneratorUploaderFactory uploaderFactory,
            CompositionRenderer compositionRenderer,
            TaskExecutor taskExecutor,
            MeterRegistry meterRegistry) {
        this.youTubeServiceProvider = youTubeServiceProvider;
        this.uploaderFactory = uploaderFactory;
        this.compositionRenderer = compositionRenderer;
        this.taskExecutor = taskExecutor;
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
        String jobId = UUID.randomUUID().toString();
        PublishOptions normalizedPublishOptions = normalizePublishOptions(publishOptions);
        Path imagePath = null;
        Path audioPath = null;
        Path thumbnailPath = null;

        try {
            imagePath = Files.createTempFile("media-factory-image-", ".jpg");
            image.transferTo(imagePath);

            audioPath = Files.createTempFile("media-factory-audio-", ".mp3");
            audio.transferTo(audioPath);

            if (thumbnail != null) {
                String thumbnailType = thumbnail.getContentType();
                thumbnailPath = Files.createTempFile("media-factory-thumbnail-", resolveThumbnailSuffix(thumbnailType));
                thumbnail.transferTo(thumbnailPath);
            }
        } catch (IOException e) {
            deleteTempFile(imagePath);
            deleteTempFile(audioPath);
            deleteTempFile(thumbnailPath);
            throw e;
        }

        trackJobMetrics(normalizedPublishOptions, thumbnailPath != null);
        enqueueJob(jobId, normalizedPublishOptions);

        logger.info(
                "Accepted video job {} privacyStatus={} scheduled={} hasThumbnail={}",
                jobId,
                normalizedPublishOptions.privacyStatus(),
                normalizedPublishOptions.isScheduled(),
                thumbnailPath != null);

        Path finalImagePath = imagePath;
        Path finalAudioPath = audioPath;
        Path finalThumbnailPath = thumbnailPath;
        String finalThumbnailType = thumbnail != null ? thumbnail.getContentType() : null;
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

        return jobId;
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

        String jobId = UUID.randomUUID().toString();
        PublishOptions normalizedPublishOptions = normalizePublishOptions(publishOptions);

        Path audioPath = null;
        Path thumbnailPath = null;
        Map<String, Path> assetPaths = new LinkedHashMap<>();

        try {
            audioPath = copyMultipartToTemp(audio, "media-factory-composition-audio-", resolveAudioSuffix(audio));

            for (Map.Entry<String, MultipartFile> entry : safeAssets.entrySet()) {
                String assetId = entry.getKey();
                MultipartFile asset = entry.getValue();
                Path assetPath = copyMultipartToTemp(asset, "media-factory-composition-asset-", resolveAssetSuffix(asset));
                assetPaths.put(assetId, assetPath);
            }

            if (thumbnail != null) {
                thumbnailPath = copyMultipartToTemp(
                        thumbnail,
                        "media-factory-thumbnail-",
                        resolveThumbnailSuffix(thumbnail.getContentType()));
            }
        } catch (IOException e) {
            deleteTempFile(audioPath);
            deleteTempFile(thumbnailPath);
            deleteTempFiles(assetPaths.values());
            throw e;
        }

        trackJobMetrics(normalizedPublishOptions, thumbnailPath != null);
        enqueueJob(jobId, normalizedPublishOptions);

        logger.info(
                "Accepted composition job {} scenes={} privacyStatus={} scheduled={} hasThumbnail={}",
                jobId,
                manifest.scenes().size(),
                normalizedPublishOptions.privacyStatus(),
                normalizedPublishOptions.isScheduled(),
                thumbnailPath != null);

        Path finalAudioPath = audioPath;
        Path finalThumbnailPath = thumbnailPath;
        String finalThumbnailType = thumbnail != null ? thumbnail.getContentType() : null;
        Map<String, Path> finalAssetPaths = new LinkedHashMap<>(assetPaths);

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

        return jobId;
    }

    @Override
    public Optional<VideoJobStatus> getJobStatus(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private void processBasicJob(
            String jobId,
            Path imagePath,
            Path audioPath,
            Path thumbnailPath,
            String thumbnailContentType,
            int durationSeconds,
            String title,
            String description,
            PublishOptions publishOptions) {
        Path outputVideoPath = null;
        updateJobState(jobId, VideoJobState.PROCESSING, "Generating video and uploading to YouTube.");

        try {
            outputVideoPath = Files.createTempFile("media-factory-output-", ".mp4");
            VideoGeneratorUploader uploader = uploaderFactory.create(youTubeServiceProvider.getService());

            uploader.generateVideo(
                    imagePath.toString(),
                    audioPath.toString(),
                    durationSeconds,
                    outputVideoPath.toString());

            completeUpload(jobId, uploader, outputVideoPath, title, description, publishOptions, thumbnailPath, thumbnailContentType);
            logger.info(
                    "Completed video job {} privacyStatus={} scheduled={}",
                    jobId,
                    publishOptions.privacyStatus(),
                    publishOptions.isScheduled());
        } catch (Exception e) {
            logger.error("Video processing job {} failed", jobId, e);
            markJobFailed(jobId, "Video processing failed. Check server logs.");
        } finally {
            deleteTempFile(imagePath);
            deleteTempFile(audioPath);
            deleteTempFile(thumbnailPath);
            deleteTempFile(outputVideoPath);
        }
    }

    private void processCompositionJob(
            String jobId,
            CompositionManifestRequest manifest,
            Map<String, Path> assetPaths,
            Path audioPath,
            Path thumbnailPath,
            String thumbnailContentType,
            String title,
            String description,
            PublishOptions publishOptions) {
        Path outputVideoPath = null;
        updateJobState(jobId, VideoJobState.PROCESSING, "Generating composition and uploading to YouTube.");

        try {
            outputVideoPath = Files.createTempFile("media-factory-composition-output-", ".mp4");
            CompositionRenderPlan renderPlan = buildRenderPlan(manifest, audioPath, assetPaths);
            compositionRenderer.renderComposition(renderPlan, outputVideoPath);

            VideoGeneratorUploader uploader = uploaderFactory.create(youTubeServiceProvider.getService());
            completeUpload(jobId, uploader, outputVideoPath, title, description, publishOptions, thumbnailPath, thumbnailContentType);

            logger.info(
                    "Completed composition job {} privacyStatus={} scheduled={}",
                    jobId,
                    publishOptions.privacyStatus(),
                    publishOptions.isScheduled());
        } catch (Exception e) {
            logger.error("Composition processing job {} failed", jobId, e);
            markJobFailed(jobId, "Video processing failed. Check server logs.");
        } finally {
            deleteTempFile(audioPath);
            deleteTempFile(thumbnailPath);
            deleteTempFile(outputVideoPath);
            deleteTempFiles(assetPaths.values());
        }
    }

    private void completeUpload(
            String jobId,
            VideoGeneratorUploader uploader,
            Path outputVideoPath,
            String title,
            String description,
            PublishOptions publishOptions,
            Path thumbnailPath,
            String thumbnailContentType) throws Exception {
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
            try {
                uploader.uploadThumbnail(videoId, thumbnailPath.toString(), thumbnailContentType);
            } catch (Exception thumbnailError) {
                thumbnailFailureCounter.increment();
                thumbnailUploadFailureCounter.increment();
                warningMessage = combineWarnings(
                        warningMessage,
                        "Video uploaded, but thumbnail upload failed.");
                logger.error("Thumbnail upload failed for job {} and video {}", jobId, videoId, thumbnailError);
            }
        }

        markJobCompleted(jobId, videoId, videoUrl, warningMessage);
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

            scenePlans.add(new CompositionScenePlan(
                    scene.assetId(),
                    scene.type(),
                    durationSeconds,
                    clipStartSeconds,
                    motion,
                    captionPlan,
                    transitionPlan));

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

    private void enqueueJob(String jobId, PublishOptions publishOptions) {
        Instant now = Instant.now();
        jobs.put(jobId, new VideoJobStatus(
                jobId,
                VideoJobState.QUEUED,
                "Job queued.",
                now,
                now,
                publishOptions.privacyStatus(),
                publishOptions.tags(),
                publishOptions.categoryId(),
                publishOptions.publishAt(),
                null,
                null,
                null));
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

    private void updateJobState(String jobId, VideoJobState state, String message) {
        jobs.computeIfPresent(jobId, (ignored, current) -> new VideoJobStatus(
                current.jobId(),
                state,
                message,
                current.createdAt(),
                Instant.now(),
                current.privacyStatus(),
                current.tags(),
                current.categoryId(),
                current.publishAt(),
                current.youtubeVideoId(),
                current.youtubeVideoUrl(),
                current.warningMessage()));
    }

    private void markJobCompleted(String jobId, String videoId, String videoUrl, String warningMessage) {
        String completionMessage = warningMessage == null
                ? "Video generated and uploaded successfully."
                : "Video generated and uploaded with warnings.";

        jobs.computeIfPresent(jobId, (ignored, current) -> new VideoJobStatus(
                current.jobId(),
                VideoJobState.COMPLETED,
                completionMessage,
                current.createdAt(),
                Instant.now(),
                current.privacyStatus(),
                current.tags(),
                current.categoryId(),
                current.publishAt(),
                videoId,
                videoUrl,
                warningMessage));
    }

    private void markJobFailed(String jobId, String message) {
        jobs.computeIfPresent(jobId, (ignored, current) -> new VideoJobStatus(
                current.jobId(),
                VideoJobState.FAILED,
                message,
                current.createdAt(),
                Instant.now(),
                current.privacyStatus(),
                current.tags(),
                current.categoryId(),
                current.publishAt(),
                current.youtubeVideoId(),
                current.youtubeVideoUrl(),
                current.warningMessage()));
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
