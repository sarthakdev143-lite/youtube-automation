package github.sarthakdev143.media_factory.integration.video;

import com.google.api.client.http.FileContent;
import com.google.api.client.util.DateTime;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import github.sarthakdev143.media_factory.integration.youtube.YouTubeServiceFactory;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.UploadResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class VideoGeneratorUploader {

    private static final String FFMPEG_PATH_ENV = "FFMPEG_PATH";
    private static final String DEFAULT_FFMPEG_BINARY = "ffmpeg";

    // YouTube API service (you must configure OAuth2)
    private final YouTube youtubeService;

    public VideoGeneratorUploader(YouTube youtubeService) {
        this.youtubeService = youtubeService;
    }

    /**
     * Generates a video from a single image and looping audio
     * @param imagePath path to the image
     * @param audioPath path to audio
     * @param durationSeconds video duration in seconds
     * @param outputPath path to save generated video
     * @throws IOException
     * @throws InterruptedException
     */
    public void generateVideo(String imagePath, String audioPath, int durationSeconds, String outputPath)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>();
        command.add(resolveFfmpegBinary());
        command.add("-stream_loop");
        command.add("-1"); // loop image infinitely
        command.add("-i");
        command.add(imagePath);
        command.add("-stream_loop");
        command.add("-1"); // loop audio infinitely
        command.add("-i");
        command.add(audioPath);
        command.add("-c:v");
        command.add("libx264"); // change to "h264_nvenc" if you have NVIDIA GPU
        command.add("-preset");
        command.add("veryfast");
        command.add("-crf");
        command.add("23");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-t");
        command.add(String.valueOf(durationSeconds));
        command.add("-shortest"); // stop when audio ends
        command.add("-y"); // overwrite if exists
        command.add(outputPath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Print FFmpeg output live
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg failed with exit code " + exitCode);
        }

        System.out.println("Video generated successfully: " + outputPath);
    }

    private String resolveFfmpegBinary() {
        String configuredPath = System.getenv(FFMPEG_PATH_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return configuredPath;
        }
        return DEFAULT_FFMPEG_BINARY;
    }

    /**
     * Uploads video to YouTube.
     * @param videoPath path to video
     * @param title video title
     * @param description video description
     * @param publishOptions metadata/options for upload
     * @return upload result containing generated video id
     * @throws IOException
     */
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
            System.out.println("Uploaded video ID: " + response.getId());
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
            System.out.println("Uploaded video ID without category: " + fallbackResponse.getId());
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

    public static void main(String[] args) throws Exception {
        // Example usage:

        // 1. Set up your OAuth2 YouTube API service here
        YouTube youtubeService = YouTubeServiceFactory.getService(); // <-- implement your OAuth

        VideoGeneratorUploader uploader = new VideoGeneratorUploader(youtubeService);

        // 2. Generate video
        uploader.generateVideo(
                "D:\\media-factory\\image.jpg",
                "D:\\media-factory\\audio.mp3",
                60, // duration in seconds
                "D:\\media-factory\\output.mp4"
        );

        // 3. Upload to YouTube
        uploader.uploadToYouTube(
                "D:\\media-factory\\output.mp4",
                "My Test Video",
                "Created with FFmpeg + Java Automation",
                new PublishOptions(PrivacyStatus.PRIVATE, List.of(), null, null)
        );
    }
}
