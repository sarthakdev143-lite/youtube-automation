package github.sarthakdev143.media_factory.integration.video;

import github.sarthakdev143.media_factory.integration.youtube.YouTubeServiceFactory;

import java.io.*;
import java.util.*;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import com.google.api.client.http.FileContent;

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
     * Uploads video to YouTube
     * @param videoPath path to video
     * @param title video title
     * @param description video description
     * @throws IOException
     */
    public void uploadToYouTube(String videoPath, String title, String description) throws IOException {
        File videoFile = new File(videoPath);

        Video videoObjectDefiningMetadata = new Video();
        VideoStatus status = new VideoStatus();
        status.setPrivacyStatus("private"); // public / unlisted / private
        videoObjectDefiningMetadata.setStatus(status);

        VideoSnippet snippet = new VideoSnippet();
        snippet.setTitle(title);
        snippet.setDescription(description);
        videoObjectDefiningMetadata.setSnippet(snippet);

        FileContent mediaContent = new FileContent("video/mp4", videoFile);

        YouTube.Videos.Insert request = youtubeService.videos()
                .insert(java.util.List.of("snippet", "status"), videoObjectDefiningMetadata, mediaContent);

        Video response = request.execute();
        System.out.println("Uploaded video ID: " + response.getId());
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
                "Created with FFmpeg + Java Automation"
        );
    }
}

