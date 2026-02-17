package github.sarthakdev143.media_factory.service.impl;
import github.sarthakdev143.media_factory.factory.VideoGeneratorUploaderFactory;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;
import github.sarthakdev143.media_factory.integration.youtube.YouTubeServiceProvider;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.UploadResult;
import github.sarthakdev143.media_factory.model.VideoJobProgressReport;
import github.sarthakdev143.media_factory.model.VideoJobStage;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
import github.sarthakdev143.media_factory.persistence.VideoJob;
import github.sarthakdev143.media_factory.persistence.VideoJobRepository;
import github.sarthakdev143.media_factory.service.ActiveJobConflictException;
import github.sarthakdev143.media_factory.service.VideoProcessingService;
import github.sarthakdev143.media_factory.service.ffmpeg.FfmpegService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class DefaultVideoProcessingService implements VideoProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultVideoProcessingService.class);
    private static final int MAX_DURATION_SECONDS = 21_600;
    private static final int MIN_VIGNETTE_STRENGTH_PERCENT = 0;
    private static final int MAX_VIGNETTE_STRENGTH_PERCENT = 100;
    private static final int DEFAULT_VIGNETTE_STRENGTH_PERCENT = 40;
    private static final int DEFAULT_OUTPUT_FRAME_RATE = 2;
    private static final int SHUTDOWN_GRACE_SECONDS = 5;
    private static final int ENCODING_WEIGHT_PERCENT = 80;
    private static final int UPLOAD_WEIGHT_PERCENT = 19;
    private static final int THUMBNAIL_WEIGHT_PERCENT = 1;
    private static final String DEFAULT_YOUTUBE_CATEGORY_ID = "10";

    private final YouTubeServiceProvider youTubeServiceProvider;
    private final VideoGeneratorUploaderFactory uploaderFactory;
    private final VideoJobRepository videoJobRepository;
    private final FfmpegService ffmpegService;
    private final ExecutorService videoWorkerExecutor;
    private final TransactionTemplate transactionTemplate;
    private final Path inputDirectory;
    private final Path outputDirectory;
    private final Path logDirectory;
    private final long pollIntervalMs;
    private final int outputFrameRate;
    private final long progressPersistIntervalMs;
    private final Duration shutdownAwait;
    private final boolean workerEnabled;

    private final ReentrantLock submissionLock = new ReentrantLock();
    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private final AtomicReference<Process> activeFfmpegProcess = new AtomicReference<>();
    private final AtomicReference<UUID> activeProcessingJobId = new AtomicReference<>();
    private volatile Future<?> workerFuture;

    public DefaultVideoProcessingService(
            YouTubeServiceProvider youTubeServiceProvider,
            VideoGeneratorUploaderFactory uploaderFactory,
            VideoJobRepository videoJobRepository,
            FfmpegService ffmpegService,
            @Qualifier("videoWorkerExecutor") ExecutorService videoWorkerExecutor,
            PlatformTransactionManager transactionManager,
            @Value("${media-factory.work-dir:media-factory-work}") String workDir,
            @Value("${media-factory.worker.poll-interval-ms:1000}") long pollIntervalMs,
            @Value("${media-factory.worker.output-frame-rate:2}") int outputFrameRate,
            @Value("${media-factory.worker.progress-persist-seconds:3}") long progressPersistSeconds,
            @Value("${media-factory.worker.shutdown-await:PT10M}") Duration shutdownAwait,
            @Value("${media-factory.worker.enabled:true}") boolean workerEnabled) throws IOException {
        this.youTubeServiceProvider = youTubeServiceProvider;
        this.uploaderFactory = uploaderFactory;
        this.videoJobRepository = videoJobRepository;
        this.ffmpegService = ffmpegService;
        this.videoWorkerExecutor = videoWorkerExecutor;
        this.pollIntervalMs = Math.max(250L, pollIntervalMs);
        this.outputFrameRate = Math.max(1, outputFrameRate);
        this.progressPersistIntervalMs = Math.max(1000L, progressPersistSeconds * 1000L);
        this.shutdownAwait = shutdownAwait == null ? Duration.ofMinutes(10) : shutdownAwait;
        this.workerEnabled = workerEnabled;

        Path baseDirectory = Path.of(workDir).toAbsolutePath().normalize();
        this.inputDirectory = baseDirectory.resolve("inputs");
        this.outputDirectory = baseDirectory.resolve("outputs");
        this.logDirectory = baseDirectory.resolve("logs");
        Files.createDirectories(this.inputDirectory);
        Files.createDirectories(this.outputDirectory);
        Files.createDirectories(this.logDirectory);

        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWorker() {
        if (!workerEnabled) {
            logger.info("Video worker is disabled by configuration.");
            return;
        }
        if (workerRunning.compareAndSet(false, true)) {
            workerFuture = videoWorkerExecutor.submit(this::workerLoop);
            logger.info("Video processing worker started.");
        }
    }

    @Override
    public String submitJob(
            MultipartFile image,
            MultipartFile audio,
            int durationSeconds,
            int vignetteStrengthPercent,
            String title,
            String description,
            PublishOptions publishOptions,
            MultipartFile thumbnail) throws IOException {

        if (durationSeconds < 1 || durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException("Duration must be between 1 and " + MAX_DURATION_SECONDS + " seconds.");
        }

        int normalizedVignetteStrength = normalizeRequestedVignetteStrength(vignetteStrengthPercent);
        PublishOptions normalizedOptions = normalizePublishOptions(publishOptions);
        String sanitizedTitle = sanitizeText(title, 100);
        String sanitizedDescription = sanitizeText(description, 5000);

        submissionLock.lock();
        try {
            Optional<VideoJob> activeProcessingJob = videoJobRepository.findFirstByStateOrderByCreatedAtAsc(VideoJobState.PROCESSING);
            if (activeProcessingJob.isPresent()) {
                throw new ActiveJobConflictException(toStatus(activeProcessingJob.get()));
            }

            UUID jobId = UUID.randomUUID();
            Path imagePath = null;
            Path audioPath = null;
            Path thumbnailPath = null;
            try {
                imagePath = persistUpload(jobId, "image", image, defaultExtensionForImage(image));
                audioPath = persistUpload(jobId, "audio", audio, defaultExtensionForAudio(audio));
                if (thumbnail != null && !thumbnail.isEmpty()) {
                    thumbnailPath = persistUpload(jobId, "thumbnail", thumbnail, defaultExtensionForThumbnail(thumbnail));
                }
            } catch (Exception ex) {
                safeDelete(imagePath);
                safeDelete(audioPath);
                safeDelete(thumbnailPath);
                throw ex;
            }

            String imagePathString = imagePath.toAbsolutePath().toString();
            String audioPathString = audioPath.toAbsolutePath().toString();
            String thumbnailPathString = thumbnailPath == null ? null : thumbnailPath.toAbsolutePath().toString();
            String tagsJson = serializeTags(normalizedOptions.tags());

            try {
                transactionTemplate.executeWithoutResult(tx -> {
                    Optional<VideoJob> activeJob = videoJobRepository.findFirstByStateOrderByCreatedAtAsc(VideoJobState.PROCESSING);
                    if (activeJob.isPresent()) {
                        throw new ActiveJobConflictException(toStatus(activeJob.get()));
                    }

                    VideoJob videoJob = new VideoJob();
                    videoJob.setId(jobId);
                    videoJob.setState(VideoJobState.QUEUED);
                    videoJob.setJobStage(VideoJobStage.QUEUED);
                    videoJob.setStageDetail("Waiting for worker to pick up this job.");
                    videoJob.setTitle(sanitizedTitle);
                    videoJob.setDescription(sanitizedDescription);
                    videoJob.setPrivacyStatus(normalizedOptions.privacyStatus());
                    videoJob.setTags(tagsJson);
                    videoJob.setCategoryId(normalizedOptions.categoryId());
                    videoJob.setPublishAt(normalizedOptions.publishAt());
                    videoJob.setInputImagePath(imagePathString);
                    videoJob.setInputAudioPath(audioPathString);
                    videoJob.setInputThumbnailPath(thumbnailPathString);
                    videoJob.setDurationSeconds(durationSeconds);
                    videoJob.setVignetteStrengthPercent(normalizedVignetteStrength);
                    videoJob.setProgressPercent(0);
                    videoJob.setGenerationProgressPercent(0);
                    videoJob.setUploadProgressPercent(0);
                    videoJob.setUploadState(null);
                    videoJob.setErrorMessage(null);
                    videoJob.setWarningMessage(null);
                    videoJobRepository.save(videoJob);
                });
            } catch (RuntimeException ex) {
                safeDelete(imagePath);
                safeDelete(audioPath);
                safeDelete(thumbnailPath);
                throw ex;
            }

            logger.info(
                    "[JOB_QUEUED id={} durationSeconds={} vignetteStrengthPercent={}]",
                    jobId,
                    durationSeconds,
                    normalizedVignetteStrength);
            return jobId.toString();
        } finally {
            submissionLock.unlock();
        }
    }

    @Override
    public Optional<VideoJobStatus> getJobStatus(String jobId) {
        UUID uuid = parseJobId(jobId);
        if (uuid == null) {
            return Optional.empty();
        }
        return videoJobRepository.findById(uuid).map(this::toStatus);
    }

    @Override
    public Optional<VideoJobStatus> getActiveJobStatus() {
        Optional<VideoJob> processing = videoJobRepository.findFirstByStateOrderByCreatedAtAsc(VideoJobState.PROCESSING);
        if (processing.isPresent()) {
            return processing.map(this::toStatus);
        }
        return videoJobRepository.findFirstByStateOrderByCreatedAtAsc(VideoJobState.QUEUED).map(this::toStatus);
    }

    @PreDestroy
    public void shutdown() {
        workerRunning.set(false);

        Process process = activeFfmpegProcess.get();
        UUID processingJobId = activeProcessingJobId.get();
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (processingJobId != null) {
                markJobFailedIfProcessing(processingJobId, "Shutdown: job aborted");
            }
        }

        Future<?> future = workerFuture;
        if (future != null) {
            try {
                future.get(shutdownAwait.toSeconds(), TimeUnit.SECONDS);
            } catch (Exception ex) {
                logger.warn("Worker did not terminate cleanly within {}.", shutdownAwait, ex);
            }
        }
    }

    private void workerLoop() {
        while (workerRunning.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Optional<UUID> jobId = claimNextQueuedJobId();
                if (jobId.isEmpty()) {
                    Thread.sleep(pollIntervalMs);
                    continue;
                }
                processClaimedJob(jobId.get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                logger.error("Worker loop error", ex);
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Optional<UUID> claimNextQueuedJobId() {
        return transactionTemplate.execute(status -> {
            if (videoJobRepository.existsByState(VideoJobState.PROCESSING)) {
                return Optional.<UUID>empty();
            }
            Optional<VideoJob> queued = videoJobRepository.findFirstByStateOrderByCreatedAtAsc(VideoJobState.QUEUED);
            if (queued.isEmpty()) {
                return Optional.<UUID>empty();
            }
            VideoJob videoJob = queued.get();
            videoJob.setState(VideoJobState.PROCESSING);
            videoJob.setJobStage(VideoJobStage.PREPARING);
            videoJob.setStageDetail("Preparing inputs and output paths.");
            videoJob.setErrorMessage(null);
            videoJob.setWarningMessage(null);
            videoJob.setProgressPercent(0);
            videoJob.setGenerationProgressPercent(0);
            videoJob.setUploadProgressPercent(0);
            videoJob.setUploadState(null);
            videoJobRepository.save(videoJob);
            return Optional.of(videoJob.getId());
        });
    }

    private void processClaimedJob(UUID jobId) {
        VideoJob job = videoJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }

        activeProcessingJobId.set(jobId);
        AtomicInteger lastPersistedGenerationPercent = new AtomicInteger(-1);
        AtomicLong lastPersistedGenerationAtMs = new AtomicLong(0L);
        AtomicInteger lastPersistedUploadPercent = new AtomicInteger(-1);
        AtomicLong lastPersistedUploadAtMs = new AtomicLong(0L);
        AtomicReference<String> lastPersistedUploadState = new AtomicReference<>();

        Path outputPath = outputDirectory.resolve(jobId + ".mp4").toAbsolutePath().normalize();
        Path logPath = logDirectory.resolve(jobId + ".ffmpeg.log").toAbsolutePath().normalize();
        updateOutputPath(jobId, outputPath);
        transitionStage(jobId, VideoJobStage.PREPARING, "Preparing inputs and output path.");

        int resolvedVignetteStrength = resolveStoredVignetteStrength(job.getVignetteStrengthPercent());
        logger.info(
                "[JOB_STARTED id={} durationSeconds={} vignetteStrengthPercent={}]",
                jobId,
                job.getDurationSeconds(),
                resolvedVignetteStrength);

        try {
            transitionStage(jobId, VideoJobStage.GENERATING, "Generating video with FFmpeg.");
            ffmpegService.runEncoding(
                    Path.of(job.getInputImagePath()),
                    Path.of(job.getInputAudioPath()),
                    outputPath,
                    job.getDurationSeconds(),
                    outputFrameRate > 0 ? outputFrameRate : DEFAULT_OUTPUT_FRAME_RATE,
                    resolvedVignetteStrength,
                    progressPercent -> persistGenerationProgressIfNeeded(
                            jobId,
                            progressPercent,
                            lastPersistedGenerationPercent,
                            lastPersistedGenerationAtMs),
                    process -> activeFfmpegProcess.set(process),
                    logPath);

            persistGenerationProgress(jobId, 100);
            transitionStage(jobId, VideoJobStage.UPLOADING_VIDEO, "Video generated. Uploading to YouTube.");
            persistUploadProgress(jobId, 0, "NOT_STARTED");

            VideoGeneratorUploader uploader = uploaderFactory.create(youTubeServiceProvider.getService());
            UploadResult uploadResult = uploader.uploadToYouTube(
                    outputPath.toString(),
                    job.getTitle(),
                    job.getDescription(),
                    new PublishOptions(job.getPrivacyStatus(), deserializeTags(job.getTags()), job.getCategoryId(), job.getPublishAt()),
                    progress -> {
                        int uploadPercent = (int) Math.round(progress.progressFraction() * 100.0d);
                        persistUploadProgressIfNeeded(
                                jobId,
                                uploadPercent,
                                progress.state(),
                                lastPersistedUploadPercent,
                                lastPersistedUploadAtMs,
                                lastPersistedUploadState);
                        logger.info("[UPLOAD id={} state={} percent={}]", jobId, progress.state(), uploadPercent);
                    });
            persistUploadProgress(jobId, 100, "MEDIA_COMPLETE");

            String warningMessage = uploadResult.warningMessage();
            if (job.getInputThumbnailPath() != null) {
                transitionStage(jobId, VideoJobStage.UPLOADING_THUMBNAIL, "Uploading custom thumbnail to YouTube.");
                try {
                    uploader.uploadThumbnail(
                            uploadResult.videoId(),
                            job.getInputThumbnailPath(),
                            guessContentType(job.getInputThumbnailPath()));
                } catch (Exception thumbnailEx) {
                    String thumbnailFailureReason = safeFailureMessage(thumbnailEx);
                    warningMessage = appendWarning(
                            warningMessage,
                            "Video uploaded, but thumbnail upload failed: " + thumbnailFailureReason);
                    if (thumbnailEx instanceof IllegalArgumentException) {
                        logger.warn("Thumbnail upload skipped for job {}: {}", jobId, thumbnailFailureReason);
                    } else {
                        logger.warn("Thumbnail upload failed for job {}", jobId, thumbnailEx);
                    }
                }
            }

            transitionStage(jobId, VideoJobStage.FINALIZING, "Finalizing completed job status.");
            markJobCompleted(jobId, uploadResult.videoId(), warningMessage);
            cleanupOnSuccess(job, outputPath);
        } catch (Exception ex) {
            logger.error("[JOB_FAILED id={} reason={}]", jobId, ex.getMessage(), ex);
            markJobFailed(jobId, safeFailureMessage(ex));
            markFailureInputs(job);
        } finally {
            activeFfmpegProcess.set(null);
            activeProcessingJobId.set(null);
        }
    }

    private void persistGenerationProgressIfNeeded(
            UUID jobId,
            int newProgressPercent,
            AtomicInteger lastPersistedPercent,
            AtomicLong lastPersistedAtMs) {
        int boundedProgress = Math.max(0, Math.min(100, newProgressPercent));
        long now = System.currentTimeMillis();
        int previousPercent = lastPersistedPercent.get();
        long previousPersistTime = lastPersistedAtMs.get();

        boolean shouldPersist = previousPercent < 0
                || boundedProgress == 100
                || Math.abs(boundedProgress - previousPercent) > 1
                || now - previousPersistTime >= progressPersistIntervalMs;

        if (!shouldPersist) {
            return;
        }

        if (lastPersistedPercent.compareAndSet(previousPercent, boundedProgress)) {
            lastPersistedAtMs.set(now);
            persistGenerationProgress(jobId, boundedProgress);
            logger.info("[ENCODING id={} progress={}]", jobId, boundedProgress);
        }
    }

    private void persistGenerationProgress(UUID jobId, int generationProgressPercent) {
        transactionTemplate.executeWithoutResult(tx -> {
            VideoJob job = videoJobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            if (job.getState() != VideoJobState.PROCESSING) {
                return;
            }
            int boundedGenerationProgress = clampPercent(generationProgressPercent);
            job.setJobStage(VideoJobStage.GENERATING);
            job.setGenerationProgressPercent(boundedGenerationProgress);
            job.setProgressPercent(calculateOverallProgressForGeneration(boundedGenerationProgress));
            videoJobRepository.save(job);
        });
    }

    private void persistUploadProgressIfNeeded(
            UUID jobId,
            int newUploadPercent,
            String uploadState,
            AtomicInteger lastPersistedPercent,
            AtomicLong lastPersistedAtMs,
            AtomicReference<String> lastPersistedUploadState) {
        int boundedProgress = clampPercent(newUploadPercent);
        String normalizedState = normalizeUploadState(uploadState);
        long now = System.currentTimeMillis();
        int previousPercent = lastPersistedPercent.get();
        long previousPersistTime = lastPersistedAtMs.get();
        String previousState = lastPersistedUploadState.get();

        boolean stateChanged = previousState == null || !previousState.equals(normalizedState);
        boolean shouldPersist = previousPercent < 0
                || boundedProgress == 100
                || stateChanged
                || Math.abs(boundedProgress - previousPercent) > 1
                || now - previousPersistTime >= progressPersistIntervalMs;

        if (!shouldPersist) {
            return;
        }

        if (lastPersistedPercent.compareAndSet(previousPercent, boundedProgress)) {
            lastPersistedUploadState.set(normalizedState);
            lastPersistedAtMs.set(now);
            persistUploadProgress(jobId, boundedProgress, normalizedState);
        }
    }

    private void persistUploadProgress(UUID jobId, int uploadProgressPercent, String uploadState) {
        transactionTemplate.executeWithoutResult(tx -> {
            VideoJob job = videoJobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            if (job.getState() != VideoJobState.PROCESSING) {
                return;
            }

            int boundedUploadProgress = clampPercent(uploadProgressPercent);
            job.setJobStage(VideoJobStage.UPLOADING_VIDEO);
            job.setUploadProgressPercent(boundedUploadProgress);
            job.setUploadState(normalizeUploadState(uploadState));
            if (valueOrZero(job.getGenerationProgressPercent()) < 100) {
                job.setGenerationProgressPercent(100);
            }
            job.setProgressPercent(calculateOverallProgressForUpload(boundedUploadProgress));
            videoJobRepository.save(job);
        });
    }

    private void transitionStage(UUID jobId, VideoJobStage stage, String detail) {
        transactionTemplate.executeWithoutResult(tx -> {
            VideoJob job = videoJobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            if (job.getState() != VideoJobState.PROCESSING) {
                return;
            }
            job.setJobStage(stage);
            job.setStageDetail(detail);
            if (stage == VideoJobStage.PREPARING) {
                job.setProgressPercent(0);
            } else if (stage == VideoJobStage.UPLOADING_THUMBNAIL) {
                int thumbnailStartProgress = Math.max(
                        0,
                        ENCODING_WEIGHT_PERCENT + UPLOAD_WEIGHT_PERCENT + THUMBNAIL_WEIGHT_PERCENT - 1);
                job.setProgressPercent(thumbnailStartProgress);
            }
            videoJobRepository.save(job);
        });
    }

    private void markJobCompleted(UUID jobId, String youtubeVideoId, String warningMessage) {
        transactionTemplate.executeWithoutResult(tx -> {
            VideoJob job = videoJobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            job.setState(VideoJobState.COMPLETED);
            job.setJobStage(VideoJobStage.COMPLETED);
            job.setStageDetail("Video generated and uploaded successfully.");
            job.setYoutubeVideoId(youtubeVideoId);
            job.setProgressPercent(100);
            job.setGenerationProgressPercent(100);
            job.setUploadProgressPercent(100);
            job.setUploadState("COMPLETE");
            job.setWarningMessage(warningMessage);
            job.setErrorMessage(null);
            videoJobRepository.save(job);
        });
    }

    private void markJobFailed(UUID jobId, String errorMessage) {
        transactionTemplate.executeWithoutResult(tx -> {
            VideoJob job = videoJobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            job.setState(VideoJobState.FAILED);
            job.setJobStage(VideoJobStage.FAILED);
            job.setStageDetail(errorMessage);
            job.setErrorMessage(errorMessage);
            videoJobRepository.save(job);
        });
    }

    private void markJobFailedIfProcessing(UUID jobId, String errorMessage) {
        transactionTemplate.executeWithoutResult(tx -> {
            VideoJob job = videoJobRepository.findById(jobId).orElse(null);
            if (job == null || job.getState() != VideoJobState.PROCESSING) {
                return;
            }
            job.setState(VideoJobState.FAILED);
            job.setJobStage(VideoJobStage.FAILED);
            job.setStageDetail(errorMessage);
            job.setErrorMessage(errorMessage);
            videoJobRepository.save(job);
        });
    }

    private void updateOutputPath(UUID jobId, Path outputPath) {
        transactionTemplate.executeWithoutResult(tx -> {
            VideoJob job = videoJobRepository.findById(jobId).orElse(null);
            if (job == null) {
                return;
            }
            job.setOutputPath(outputPath.toString());
            videoJobRepository.save(job);
        });
    }

    private Path persistUpload(UUID jobId, String label, MultipartFile source, String fallbackExtension) throws IOException {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException(label + " upload is required");
        }

        String safeBaseName = safeFilenameBase(source.getOriginalFilename(), label);
        String extension = sanitizeExtension(extensionFromName(source.getOriginalFilename()), fallbackExtension);
        String fileName = jobId + "-" + label + "-" + safeBaseName + extension;
        Path targetPath = inputDirectory.resolve(fileName).normalize();

        if (!targetPath.startsWith(inputDirectory)) {
            throw new IOException("Path traversal attempt blocked for " + label);
        }

        try (InputStream inputStream = source.getInputStream()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return targetPath;
    }

    private void cleanupOnSuccess(VideoJob job, Path outputPath) {
        safeDelete(Path.of(job.getInputImagePath()));
        safeDelete(Path.of(job.getInputAudioPath()));
        if (job.getInputThumbnailPath() != null) {
            safeDelete(Path.of(job.getInputThumbnailPath()));
        }
        safeDelete(outputPath);
    }

    private void markFailureInputs(VideoJob job) {
        renameToFailedPrefix(job.getId(), safePath(job.getInputImagePath()));
        renameToFailedPrefix(job.getId(), safePath(job.getInputAudioPath()));
        renameToFailedPrefix(job.getId(), safePath(job.getInputThumbnailPath()));
    }

    private void renameToFailedPrefix(UUID jobId, Path sourcePath) {
        if (sourcePath == null || !Files.exists(sourcePath)) {
            return;
        }

        String fileName = sourcePath.getFileName().toString();
        if (fileName.startsWith("failed-" + jobId + "-")) {
            return;
        }

        Path target = sourcePath.resolveSibling("failed-" + jobId + "-" + fileName);
        try {
            Files.move(sourcePath, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            logger.warn("Could not mark failed input file {} for job {}", sourcePath, jobId, ex);
        }
    }

    private VideoJobStatus toStatus(VideoJob videoJob) {
        VideoJobProgressReport progressReport = buildProgressReport(videoJob);
        return new VideoJobStatus(
                videoJob.getId().toString(),
                videoJob.getState(),
                statusMessage(videoJob),
                videoJob.getCreatedAt(),
                videoJob.getUpdatedAt(),
                videoJob.getProgressPercent(),
                progressReport,
                videoJob.getPrivacyStatus(),
                deserializeTags(videoJob.getTags()),
                videoJob.getCategoryId(),
                videoJob.getPublishAt(),
                videoJob.getYoutubeVideoId(),
                buildVideoUrl(videoJob.getYoutubeVideoId()),
                videoJob.getWarningMessage());
    }

    private String statusMessage(VideoJob job) {
        VideoJobProgressReport progressReport = buildProgressReport(job);
        return switch (job.getState()) {
            case QUEUED -> "Job queued. Waiting for worker to pick it up.";
            case PROCESSING -> formatProcessingMessage(progressReport);
            case COMPLETED -> job.getWarningMessage() == null
                    ? "Video generated and uploaded successfully."
                    : "Video generated and uploaded with warnings.";
            case FAILED -> job.getErrorMessage() == null
                    ? "Video processing failed. Check server logs."
                    : job.getErrorMessage();
        };
    }

    private String formatProcessingMessage(VideoJobProgressReport progressReport) {
        String detail = progressReport.detail();
        String prefix = detail == null || detail.isBlank()
                ? "Processing job."
                : detail;
        return prefix
                + " Overall "
                + progressReport.overallPercent()
                + "% (generation "
                + progressReport.generationPercent()
                + "%, upload "
                + progressReport.uploadPercent()
                + "%).";
    }

    private VideoJobProgressReport buildProgressReport(VideoJob job) {
        VideoJobStage stage = resolveStage(job);
        String detail = job.getStageDetail();
        int overallPercent = clampPercent(job.getProgressPercent());
        int generationPercent = valueOrZero(job.getGenerationProgressPercent());
        int uploadPercent = valueOrZero(job.getUploadProgressPercent());

        if (stage == VideoJobStage.COMPLETED) {
            overallPercent = 100;
            generationPercent = 100;
            uploadPercent = 100;
        } else if (stage == VideoJobStage.FAILED && detail == null) {
            detail = job.getErrorMessage();
        }

        return new VideoJobProgressReport(
                stage,
                detail,
                overallPercent,
                generationPercent,
                uploadPercent,
                normalizeUploadState(job.getUploadState()));
    }

    private VideoJobStage resolveStage(VideoJob job) {
        if (job.getJobStage() != null) {
            return job.getJobStage();
        }
        return switch (job.getState()) {
            case QUEUED -> VideoJobStage.QUEUED;
            case PROCESSING -> VideoJobStage.PREPARING;
            case COMPLETED -> VideoJobStage.COMPLETED;
            case FAILED -> VideoJobStage.FAILED;
        };
    }

    private UUID parseJobId(String jobId) {
        try {
            return UUID.fromString(jobId);
        } catch (Exception ex) {
            return null;
        }
    }

    private PublishOptions normalizePublishOptions(PublishOptions publishOptions) {
        if (publishOptions == null) {
            return new PublishOptions(PrivacyStatus.PRIVATE, List.of(), DEFAULT_YOUTUBE_CATEGORY_ID, null);
        }
        return new PublishOptions(
                publishOptions.privacyStatus(),
                publishOptions.tags(),
                publishOptions.categoryId() == null ? DEFAULT_YOUTUBE_CATEGORY_ID : publishOptions.categoryId(),
                publishOptions.publishAt());
    }

    private int normalizeRequestedVignetteStrength(int vignetteStrengthPercent) {
        if (vignetteStrengthPercent < MIN_VIGNETTE_STRENGTH_PERCENT
                || vignetteStrengthPercent > MAX_VIGNETTE_STRENGTH_PERCENT) {
            throw new IllegalArgumentException(
                    "Vignette strength must be between "
                            + MIN_VIGNETTE_STRENGTH_PERCENT
                            + " and "
                            + MAX_VIGNETTE_STRENGTH_PERCENT
                            + " percent.");
        }
        return vignetteStrengthPercent;
    }

    private int resolveStoredVignetteStrength(Integer storedVignetteStrengthPercent) {
        if (storedVignetteStrengthPercent == null) {
            return DEFAULT_VIGNETTE_STRENGTH_PERCENT;
        }

        if (storedVignetteStrengthPercent < MIN_VIGNETTE_STRENGTH_PERCENT
                || storedVignetteStrengthPercent > MAX_VIGNETTE_STRENGTH_PERCENT) {
            return DEFAULT_VIGNETTE_STRENGTH_PERCENT;
        }

        return storedVignetteStrengthPercent;
    }

    private String serializeTags(List<String> tags) {
        List<String> normalized = tags == null ? List.of() : tags;
        return normalized.stream()
                .map(tag -> URLEncoder.encode(tag, StandardCharsets.UTF_8))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
    }

    private List<String> deserializeTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        return List.of(tagsJson.split(","))
                .stream()
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .map(tag -> URLDecoder.decode(tag, StandardCharsets.UTF_8))
                .toList();
    }

    private String defaultExtensionForImage(MultipartFile image) {
        String contentType = image.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("png")) {
            return ".png";
        }
        return ".jpg";
    }

    private String defaultExtensionForAudio(MultipartFile audio) {
        String contentType = audio.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("wav")) {
            return ".wav";
        }
        if (contentType != null && contentType.toLowerCase().contains("aac")) {
            return ".aac";
        }
        return ".mp3";
    }

    private String defaultExtensionForThumbnail(MultipartFile thumbnail) {
        String contentType = thumbnail.getContentType();
        if (contentType != null && contentType.toLowerCase().contains("png")) {
            return ".png";
        }
        return ".jpg";
    }

    private String extensionFromName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        String fileName = Path.of(originalFilename).getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        return fileName.substring(dotIndex);
    }

    private String sanitizeExtension(String extension, String fallback) {
        String candidate = extension == null || extension.isBlank() ? fallback : extension;
        String normalized = candidate.replaceAll("[^a-zA-Z0-9.]", "");
        if (!normalized.startsWith(".")) {
            normalized = "." + normalized;
        }
        if (normalized.length() > 8) {
            return fallback;
        }
        return normalized;
    }

    private String safeFilenameBase(String originalFilename, String fallback) {
        String candidate = fallback;
        if (originalFilename != null && !originalFilename.isBlank()) {
            try {
                candidate = Path.of(originalFilename).getFileName().toString();
            } catch (InvalidPathException ignored) {
                candidate = fallback;
            }
        }

        int dotIndex = candidate.lastIndexOf('.');
        if (dotIndex > 0) {
            candidate = candidate.substring(0, dotIndex);
        }
        String sanitized = candidate.replaceAll("[^a-zA-Z0-9_-]", "_");
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private String sanitizeText(String source, int maxLength) {
        if (source == null) {
            return "";
        }
        String normalized = source
                .replace("\u0000", "")
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "")
                .trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private String buildVideoUrl(String youtubeVideoId) {
        if (youtubeVideoId == null || youtubeVideoId.isBlank()) {
            return null;
        }
        return "https://www.youtube.com/watch?v=" + youtubeVideoId;
    }

    private int calculateOverallProgressForGeneration(int generationPercent) {
        int boundedGeneration = clampPercent(generationPercent);
        return (int) Math.round((boundedGeneration / 100.0d) * ENCODING_WEIGHT_PERCENT);
    }

    private int calculateOverallProgressForUpload(int uploadPercent) {
        int boundedUpload = clampPercent(uploadPercent);
        int uploadContribution = (int) Math.round((boundedUpload / 100.0d) * UPLOAD_WEIGHT_PERCENT);
        return Math.min(ENCODING_WEIGHT_PERCENT + UPLOAD_WEIGHT_PERCENT, ENCODING_WEIGHT_PERCENT + uploadContribution);
    }

    private int clampPercent(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 100);
    }

    private int valueOrZero(Integer value) {
        if (value == null) {
            return 0;
        }
        return clampPercent(value);
    }

    private String normalizeUploadState(String uploadState) {
        if (uploadState == null || uploadState.isBlank()) {
            return null;
        }
        return uploadState.trim();
    }

    private String appendWarning(String existing, String additional) {
        if (existing == null || existing.isBlank()) {
            return additional;
        }
        if (additional == null || additional.isBlank()) {
            return existing;
        }
        return existing + " " + additional;
    }

    private Path safePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            return Path.of(rawPath).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return null;
        }
    }

    private String guessContentType(String path) {
        try {
            String type = Files.probeContentType(Path.of(path));
            return type == null ? "image/jpeg" : type;
        } catch (IOException ex) {
            return "image/jpeg";
        }
    }

    private String safeFailureMessage(Exception exception) {
        String raw = exception.getMessage();
        if (raw == null || raw.isBlank()) {
            return "Video processing failed. Check server logs.";
        }
        String sanitized = raw.replaceAll("[\\r\\n]+", " ").trim();
        if (sanitized.length() > 1000) {
            return sanitized.substring(0, 1000);
        }
        return sanitized;
    }

    private void safeDelete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ex) {
            logger.warn("Failed to delete file {}", path, ex);
        }
    }
}
