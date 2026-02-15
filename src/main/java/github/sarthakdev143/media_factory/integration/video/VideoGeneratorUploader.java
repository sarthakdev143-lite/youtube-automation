package github.sarthakdev143.media_factory.integration.video;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.FileContent;
import com.google.api.client.util.DateTime;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.UploadResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

public class VideoGeneratorUploader {

    private static final Logger logger = LoggerFactory.getLogger(VideoGeneratorUploader.class);

    private final YouTube youtubeService;
    private final FfmpegCommandBuilder ffmpegCommandBuilder;
    private final FfmpegProcessRunner ffmpegProcessRunner;

    public VideoGeneratorUploader(
            YouTube youtubeService,
            FfmpegCommandBuilder ffmpegCommandBuilder,
            FfmpegProcessRunner ffmpegProcessRunner) {
        this.youtubeService = youtubeService;
        this.ffmpegCommandBuilder = ffmpegCommandBuilder;
        this.ffmpegProcessRunner = ffmpegProcessRunner;
    }

    public VideoGeneratorUploader(YouTube youtubeService) {
        this(youtubeService, null, null);
    }

    public void generateVideo(
            String imagePath,
            String audioPath,
            int durationSeconds,
            String outputPath) throws IOException, InterruptedException {
        generateVideo(imagePath, audioPath, durationSeconds, outputPath, "adhoc-job", progress -> {
        });
    }

    public void generateVideo(
            String imagePath,
            String audioPath,
            int durationSeconds,
            String outputPath,
            String jobId,
            IntConsumer progressConsumer) throws IOException, InterruptedException {
        if (ffmpegCommandBuilder == null || ffmpegProcessRunner == null) {
            throw new IllegalStateException(
                    "FFmpeg runner dependencies are unavailable. Construct with command builder and process runner.");
        }

        Path output = Path.of(outputPath);
        boolean tryNvenc = ffmpegCommandBuilder.nvencAvailable();

        if (tryNvenc) {
            try {
                ffmpegProcessRunner.runCommand(
                        jobId,
                        "encode-basic-nvenc",
                        ffmpegCommandBuilder.buildBasicImageAudioCommand(
                                imagePath,
                                audioPath,
                                durationSeconds,
                                outputPath,
                                true),
                        durationSeconds,
                        output,
                        progressConsumer);
                return;
            } catch (IOException nvencError) {
                if (!ffmpegProcessRunner.isNvencFailure(nvencError)) {
                    throw nvencError;
                }
                logger.warn("[NVENC FALLBACK] jobId={} reason={}", jobId, nvencError.getMessage());
            }
        }

        ffmpegProcessRunner.runCommand(
                jobId,
                "encode-basic-libx264",
                ffmpegCommandBuilder.buildBasicImageAudioCommand(
                        imagePath,
                        audioPath,
                        durationSeconds,
                        outputPath,
                        false),
                durationSeconds,
                output,
                progressConsumer);
    }

    public UploadResult uploadToYouTube(
            String videoPath,
            String title,
            String description,
            PublishOptions publishOptions) throws IOException {
        File videoFile = new File(videoPath);
        PublishOptions resolvedOptions = publishOptions == null
                ? new PublishOptions(PrivacyStatus.PRIVATE, List.of(), null, null)
                : publishOptions;

        try {
            Video response = executeUpload(videoFile, title, description, resolvedOptions);
            return new UploadResult(response.getId());
        } catch (GoogleJsonResponseException categoryError) {
            if (resolvedOptions.categoryId() == null || !isInvalidCategoryError(categoryError)) {
                throw categoryError;
            }

            PublishOptions fallbackOptions = new PublishOptions(
                    resolvedOptions.privacyStatus(),
                    resolvedOptions.tags(),
                    null,
                    resolvedOptions.publishAt());

            Video fallbackResponse = executeUpload(videoFile, title, description, fallbackOptions);
            return new UploadResult(
                    fallbackResponse.getId(),
                    "Invalid categoryId was ignored. Video uploaded without category.");
        }
    }

    private Video executeUpload(
            File videoFile,
            String title,
            String description,
            PublishOptions publishOptions) throws IOException {
        Video videoObjectDefiningMetadata = new Video();
        VideoStatus status = new VideoStatus();
        status.setPrivacyStatus(publishOptions.privacyStatus().toApiValue());
        if (publishOptions.publishAt() != null) {
            status.setPublishAt(new DateTime(publishOptions.publishAt().toEpochMilli()));
        }
        videoObjectDefiningMetadata.setStatus(status);

        VideoSnippet snippet = new VideoSnippet();
        snippet.setTitle(title);
        snippet.setDescription(description);
        if (!publishOptions.tags().isEmpty()) {
            snippet.setTags(publishOptions.tags());
        }
        if (publishOptions.categoryId() != null) {
            snippet.setCategoryId(publishOptions.categoryId());
        }
        videoObjectDefiningMetadata.setSnippet(snippet);

        FileContent mediaContent = new FileContent("video/mp4", videoFile);
        YouTube.Videos.Insert request = youtubeService.videos()
                .insert(List.of("snippet", "status"), videoObjectDefiningMetadata, mediaContent);
        return request.execute();
    }

    private boolean isInvalidCategoryError(GoogleJsonResponseException exception) {
        GoogleJsonError details = exception.getDetails();
        if (details == null || details.getErrors() == null) {
            return false;
        }
        return details.getErrors()
                .stream()
                .map(GoogleJsonError.ErrorInfo::getReason)
                .filter(Objects::nonNull)
                .anyMatch("invalidCategoryId"::equals);
    }

    public void uploadThumbnail(String videoId, String thumbnailPath, String thumbnailContentType) throws IOException {
        FileContent mediaContent = new FileContent(thumbnailContentType, new File(thumbnailPath));
        youtubeService.thumbnails().set(videoId, mediaContent).execute();
    }
}
