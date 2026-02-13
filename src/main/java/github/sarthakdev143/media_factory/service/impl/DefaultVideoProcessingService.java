package github.sarthakdev143.media_factory.service.impl;

import github.sarthakdev143.media_factory.factory.VideoGeneratorUploaderFactory;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;
import github.sarthakdev143.media_factory.integration.youtube.YouTubeServiceProvider;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
import github.sarthakdev143.media_factory.service.VideoProcessingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
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

    public DefaultVideoProcessingService(
            YouTubeServiceProvider youTubeServiceProvider,
            VideoGeneratorUploaderFactory uploaderFactory,
            TaskExecutor taskExecutor) {
        this.youTubeServiceProvider = youTubeServiceProvider;
        this.uploaderFactory = uploaderFactory;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public String submitJob(
            MultipartFile image,
            MultipartFile audio,
            int durationSeconds,
            String title,
            String description) throws IOException {
        String jobId = UUID.randomUUID().toString();
        Path imagePath = null;
        Path audioPath = null;

        try {
            imagePath = Files.createTempFile("media-factory-image-", ".jpg");
            image.transferTo(imagePath);
            audioPath = Files.createTempFile("media-factory-audio-", ".mp3");
            audio.transferTo(audioPath);
        } catch (IOException e) {
            deleteTempFile(imagePath);
            deleteTempFile(audioPath);
            throw e;
        }

        Instant now = Instant.now();
        jobs.put(jobId, new VideoJobStatus(jobId, VideoJobState.QUEUED, "Job queued.", now, now));

        Path finalImagePath = imagePath;
        Path finalAudioPath = audioPath;
        taskExecutor.execute(() ->
                processJob(jobId, finalImagePath, finalAudioPath, durationSeconds, title, description));

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
            int durationSeconds,
            String title,
            String description) {
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
            uploader.uploadToYouTube(outputVideoPath.toString(), title, description);

            updateJobState(jobId, VideoJobState.COMPLETED, "Video generated and uploaded successfully.");
        } catch (Exception e) {
            logger.error("Video processing job {} failed", jobId, e);
            updateJobState(jobId, VideoJobState.FAILED, "Video processing failed. Check server logs.");
        } finally {
            deleteTempFile(imagePath);
            deleteTempFile(audioPath);
            deleteTempFile(outputVideoPath);
        }
    }

    private void updateJobState(String jobId, VideoJobState state, String message) {
        jobs.computeIfPresent(jobId, (ignored, current) ->
                new VideoJobStatus(
                        current.jobId(),
                        state,
                        message,
                        current.createdAt(),
                        Instant.now()));
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
