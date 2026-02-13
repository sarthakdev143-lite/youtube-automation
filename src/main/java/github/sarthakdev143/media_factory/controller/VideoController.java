package github.sarthakdev143.media_factory.controller;

import github.sarthakdev143.media_factory.dto.VideoJobSubmissionResponse;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
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

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/video")
public class VideoController {

    private static final Logger logger = LoggerFactory.getLogger(VideoController.class);
    private static final int MIN_DURATION_SECONDS = 1;
    private static final int MAX_DURATION_SECONDS = 10 * 60 * 60;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 5000;
    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG_LENGTH = 50;
    private static final int MIN_PUBLISH_DELAY_SECONDS = 5 * 60;
    private static final Set<String> ALLOWED_THUMBNAIL_TYPES = Set.of("image/jpeg", "image/png");
    private static final Pattern CATEGORY_ID_PATTERN = Pattern.compile("^\\d{1,3}$");

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
            @RequestParam("description") String description,
            @RequestParam(value = "privacyStatus", required = false) String privacyStatusInput,
            @RequestParam(value = "tags", required = false) List<String> tagsInput,
            @RequestParam(value = "categoryId", required = false) String categoryIdInput,
            @RequestParam(value = "publishAt", required = false) String publishAtInput,
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail) {

        try {
            validateBaseRequest(image, audio, durationSeconds, title, description);
            PublishOptions publishOptions = validateAndBuildPublishOptions(
                    privacyStatusInput,
                    tagsInput,
                    categoryIdInput,
                    publishAtInput,
                    thumbnail);

            String jobId = videoProcessingService.submitJob(
                    image,
                    audio,
                    durationSeconds,
                    title,
                    description,
                    publishOptions,
                    thumbnail);
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

    private void validateBaseRequest(
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

    private PublishOptions validateAndBuildPublishOptions(
            String privacyStatusInput,
            List<String> tagsInput,
            String categoryIdInput,
            String publishAtInput,
            MultipartFile thumbnail) {

        PrivacyStatus privacyStatus = PrivacyStatus.fromInput(privacyStatusInput);
        List<String> normalizedTags = normalizeTags(tagsInput);
        String normalizedCategoryId = normalizeCategoryId(categoryIdInput);
        Instant publishAt = parsePublishAt(publishAtInput);
        validateThumbnail(thumbnail);

        if (publishAt != null && privacyStatus != PrivacyStatus.PRIVATE) {
            throw new IllegalArgumentException("publishAt can only be used with privacyStatus=PRIVATE.");
        }

        return new PublishOptions(privacyStatus, normalizedTags, normalizedCategoryId, publishAt);
    }

    private List<String> normalizeTags(List<String> tagsInput) {
        if (tagsInput == null || tagsInput.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String tagValue : tagsInput) {
            if (tagValue == null || tagValue.isBlank()) {
                continue;
            }

            for (String candidate : tagValue.split(",")) {
                String tag = candidate.trim();
                if (tag.isEmpty()) {
                    continue;
                }
                if (tag.length() > MAX_TAG_LENGTH) {
                    throw new IllegalArgumentException("Each tag must be at most " + MAX_TAG_LENGTH + " characters.");
                }

                String dedupeKey = tag.toLowerCase(Locale.ROOT);
                if (seen.add(dedupeKey)) {
                    normalized.add(tag);
                }
            }
        }

        if (normalized.size() > MAX_TAGS) {
            throw new IllegalArgumentException("A maximum of " + MAX_TAGS + " unique tags is allowed.");
        }

        return normalized;
    }

    private String normalizeCategoryId(String categoryIdInput) {
        if (categoryIdInput == null || categoryIdInput.isBlank()) {
            return null;
        }

        String normalizedCategoryId = categoryIdInput.trim();
        if (!CATEGORY_ID_PATTERN.matcher(normalizedCategoryId).matches()) {
            throw new IllegalArgumentException("categoryId must match ^\\d{1,3}$.");
        }
        return normalizedCategoryId;
    }

    private Instant parsePublishAt(String publishAtInput) {
        if (publishAtInput == null || publishAtInput.isBlank()) {
            return null;
        }

        String normalizedPublishAt = publishAtInput.trim();
        if (!normalizedPublishAt.endsWith("Z")) {
            throw new IllegalArgumentException("publishAt must be an ISO-8601 UTC instant ending with Z.");
        }

        Instant publishAt;
        try {
            publishAt = Instant.parse(normalizedPublishAt);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("publishAt must be a valid ISO-8601 UTC instant.", ex);
        }

        Instant minimumAllowedPublishTime = Instant.now().plusSeconds(MIN_PUBLISH_DELAY_SECONDS);
        if (publishAt.isBefore(minimumAllowedPublishTime)) {
            throw new IllegalArgumentException("publishAt must be at least 5 minutes in the future.");
        }

        return publishAt;
    }

    private void validateThumbnail(MultipartFile thumbnail) {
        if (thumbnail == null) {
            return;
        }

        if (thumbnail.isEmpty()) {
            throw new IllegalArgumentException("thumbnail must not be empty.");
        }

        String contentType = thumbnail.getContentType();
        if (contentType == null || !ALLOWED_THUMBNAIL_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("thumbnail must have content type image/jpeg or image/png.");
        }
    }

    private void validateMimeType(String fieldName, String contentType, String expectedPrefix) {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(expectedPrefix)) {
            throw new IllegalArgumentException(fieldName + " must have a " + expectedPrefix + "* content type.");
        }
    }

}
