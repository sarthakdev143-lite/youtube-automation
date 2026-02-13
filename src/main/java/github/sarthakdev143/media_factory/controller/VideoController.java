package github.sarthakdev143.media_factory.controller;

import github.sarthakdev143.media_factory.dto.VideoJobSubmissionResponse;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.service.VideoProcessingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private static final Logger logger = LoggerFactory.getLogger(VideoController.class);
    private static final int MIN_DURATION_SECONDS = 1;
    private static final int MAX_DURATION_SECONDS = 10 * 60 * 60;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 5000;

    private final VideoProcessingService videoProcessingService;

    public VideoController(VideoProcessingService videoProcessingService) {
        this.videoProcessingService = videoProcessingService;
    }

    @PostMapping(value = "/generate", consumes = "multipart/form-data")
    public ResponseEntity<?> generateAndUpload(
            @RequestParam("image") MultipartFile image,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("duration") int durationSeconds,
            @RequestParam("title") String title,
            @RequestParam("description") String description) {

        try {
            validateRequest(image, audio, durationSeconds, title, description);
            String jobId = videoProcessingService.submitJob(image, audio, durationSeconds, title, description);
            return ResponseEntity.accepted()
                    .body(new VideoJobSubmissionResponse(
                            jobId,
                            VideoJobState.QUEUED,
                            "Video job accepted. Poll /api/video/status/{jobId} for progress."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid request: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Video generation/upload failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to generate or upload video. Please try again.");
        }
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> getStatus(@PathVariable String jobId) {
        return videoProcessingService.getJobStatus(jobId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("Job not found for id: " + jobId));
    }

    private void validateRequest(
            MultipartFile image,
            MultipartFile audio,
            int durationSeconds,
            String title,
            String description) {

        if (image == null || image.isEmpty()) {
            throw new IllegalArgumentException("Image file is required.");
        }
        if (audio == null || audio.isEmpty()) {
            throw new IllegalArgumentException("Audio file is required.");
        }

        validateMimeType("image", image.getContentType(), "image/");
        validateMimeType("audio", audio.getContentType(), "audio/");

        if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    "Duration must be between " + MIN_DURATION_SECONDS + " and " + MAX_DURATION_SECONDS + " seconds.");
        }

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title is required.");
        }
        if (title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Title must be at most " + MAX_TITLE_LENGTH + " characters.");
        }

        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Description is required.");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("Description must be at most " + MAX_DESCRIPTION_LENGTH + " characters.");
        }
    }

    private void validateMimeType(String fieldName, String contentType, String expectedPrefix) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(expectedPrefix)) {
            throw new IllegalArgumentException(fieldName + " must have a " + expectedPrefix + "* content type.");
        }
    }

}
