package github.sarthakdev143.media_factory.service.impl;

import github.sarthakdev143.media_factory.factory.VideoGeneratorUploaderFactory;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;
import github.sarthakdev143.media_factory.integration.youtube.YouTubeServiceProvider;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.UploadResult;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DefaultVideoProcessingService implements VideoProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultVideoProcessingService.class);

    private final YouTubeServiceProvider youTubeServiceProvider;
    private final VideoGeneratorUploaderFactory uploaderFactory;
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
            TaskExecutor taskExecutor,
            MeterRegistry meterRegistry) {
        this.youTubeServiceProvider = youTubeServiceProvider;
        this.uploaderFactory = uploaderFactory;
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

        if (normalizedPublishOptions.isScheduled()) {
            jobsWithSchedulingCounter.increment();
        }
        if (thumbnailPath != null) {
            jobsWithThumbnailCounter.increment();
        }

        Instant now = Instant.now();
        jobs.put(jobId, new VideoJobStatus(
                jobId,
                VideoJobState.QUEUED,
                "Job queued.",
                now,
                now,
                normalizedPublishOptions.privacyStatus(),
                normalizedPublishOptions.tags(),
                normalizedPublishOptions.categoryId(),
                normalizedPublishOptions.publishAt(),
                null,
                null,
                null));

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
        taskExecutor.execute(() -> processJob(
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
    public Optional<VideoJobStatus> getJobStatus(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    private void processJob(
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
            logger.info(
                    "Completed video job {} privacyStatus={} scheduled={} youtubeVideoId={} warning={}",
                    jobId,
                    publishOptions.privacyStatus(),
                    publishOptions.isScheduled(),
                    videoId,
                    warningMessage != null);
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

    private String resolveThumbnailSuffix(String contentType) {
        if (contentType != null && contentType.toLowerCase().contains("png")) {
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
