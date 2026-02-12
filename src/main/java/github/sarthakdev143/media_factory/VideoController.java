package github.sarthakdev143.media_factory;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    @PostMapping(value = "/generate", consumes = "multipart/form-data")
    public ResponseEntity<String> generateAndUpload(
            @RequestParam("image") MultipartFile image,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("duration") int durationSeconds,
            @RequestParam("title") String title,
            @RequestParam("description") String description) {

        File imageFile = null;
        File audioFile = null;
        File outputVideo = null;

        try {
            imageFile = File.createTempFile("media-factory-image-", ".jpg");
            image.transferTo(imageFile);

            audioFile = File.createTempFile("media-factory-audio-", ".mp3");
            audio.transferTo(audioFile);

            outputVideo = File.createTempFile("media-factory-output-", ".mp4");

            VideoGeneratorUploader uploader = new VideoGeneratorUploader(YouTubeServiceFactory.getService());
            uploader.generateVideo(imageFile.getAbsolutePath(), audioFile.getAbsolutePath(),
                    durationSeconds, outputVideo.getAbsolutePath());
            uploader.uploadToYouTube(outputVideo.getAbsolutePath(), title, description);

            return ResponseEntity.ok("Video generated and uploaded successfully!");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error: " + e.getMessage());
        } finally {
            deleteTempFile(imageFile);
            deleteTempFile(audioFile);
            deleteTempFile(outputVideo);
        }
    }

    private void deleteTempFile(File file) {
        if (file != null) {
            try {
                Files.deleteIfExists(file.toPath());
            } catch (Exception ignored) {
                // Cleanup failures are non-fatal.
            }
        }
    }
}
