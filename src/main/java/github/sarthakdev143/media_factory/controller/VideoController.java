package github.sarthakdev143.media_factory.controller;

import github.sarthakdev143.media_factory.dto.CompositionCaptionRequest;
import github.sarthakdev143.media_factory.dto.CompositionColorGradeRequest;
import github.sarthakdev143.media_factory.dto.CompositionManifestRequest;
import github.sarthakdev143.media_factory.dto.CompositionOverlayRequest;
import github.sarthakdev143.media_factory.dto.CompositionSceneRequest;
import github.sarthakdev143.media_factory.dto.CompositionTransitionRequest;
import github.sarthakdev143.media_factory.dto.CompositionVisualEditRequest;
import github.sarthakdev143.media_factory.dto.VideoJobSubmissionResponse;
import github.sarthakdev143.media_factory.model.CaptionPosition;
import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.OutputPreset;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.SceneType;
import github.sarthakdev143.media_factory.model.TransitionType;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VisualFilterType;
import github.sarthakdev143.media_factory.service.VideoProcessingService;
import github.sarthakdev143.media_factory.service.impl.CompositionManifestValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.json.JsonParser;
import org.springframework.boot.json.JsonParserFactory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private static final String ASSET_PART_PREFIX = "asset.";
    private static final JsonParser JSON_PARSER = JsonParserFactory.getJsonParser();

    private final VideoProcessingService videoProcessingService;
    private final CompositionManifestValidator compositionManifestValidator;

    public VideoController(
            VideoProcessingService videoProcessingService,
            CompositionManifestValidator compositionManifestValidator) {
        this.videoProcessingService = videoProcessingService;
        this.compositionManifestValidator = compositionManifestValidator;
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

    @PostMapping(value = "/compositions", consumes = "multipart/form-data")
    public ResponseEntity<?> generateCompositionAndUpload(
            @RequestParam("manifest") String manifestJson,
            @RequestParam("audio") MultipartFile audio,
            @RequestParam("title") String title,
            @RequestParam("description") String description,
            @RequestParam(value = "privacyStatus", required = false) String privacyStatusInput,
            @RequestParam(value = "tags", required = false) List<String> tagsInput,
            @RequestParam(value = "categoryId", required = false) String categoryIdInput,
            @RequestParam(value = "publishAt", required = false) String publishAtInput,
            @RequestParam(value = "thumbnail", required = false) MultipartFile thumbnail,
            @RequestParam Map<String, MultipartFile> fileParts) {
        try {
            validateCompositionBaseRequest(audio, title, description);
            PublishOptions publishOptions = validateAndBuildPublishOptions(
                    privacyStatusInput,
                    tagsInput,
                    categoryIdInput,
                    publishAtInput,
                    thumbnail);

            CompositionManifestRequest manifest = parseManifest(manifestJson);
            Map<String, MultipartFile> assetParts = extractAssetParts(fileParts);
            CompositionManifestRequest normalizedManifest = compositionManifestValidator
                    .normalizeAndValidate(manifest, assetParts);

            String jobId = videoProcessingService.submitCompositionJob(
                    assetParts,
                    audio,
                    normalizedManifest,
                    title,
                    description,
                    publishOptions,
                    thumbnail);

            return ResponseEntity.accepted()
                    .body(new VideoJobSubmissionResponse(
                            jobId,
                            VideoJobState.QUEUED,
                            "Composition job accepted. Poll /api/video/status/{jobId} for progress."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid request: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Composition generation/upload failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to generate or upload composition. Please try again.");
        }
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<?> getStatus(@PathVariable String jobId) {
        return videoProcessingService.getJobStatus(jobId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body("Job not found for id: " + jobId));
    }

    private CompositionManifestRequest parseManifest(String manifestJson) {
        if (manifestJson == null || manifestJson.isBlank()) {
            throw new IllegalArgumentException("manifest is required.");
        }

        try {
            Map<String, Object> root = JSON_PARSER.parseMap(manifestJson);
            return mapToManifest(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("manifest must be valid JSON.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private CompositionManifestRequest mapToManifest(Map<String, Object> root) {
        OutputPreset outputPreset = parseEnum(
                OutputPreset.class,
                root.get("outputPreset"),
                "manifest.outputPreset");

        Object scenesValue = root.get("scenes");
        if (!(scenesValue instanceof List<?> sceneList)) {
            throw new IllegalArgumentException("manifest.scenes must be an array.");
        }

        List<CompositionSceneRequest> scenes = new ArrayList<>();
        for (int index = 0; index < sceneList.size(); index++) {
            Object sceneValue = sceneList.get(index);
            if (!(sceneValue instanceof Map<?, ?> sceneMapRaw)) {
                throw new IllegalArgumentException("manifest.scenes[" + index + "] must be an object.");
            }

            Map<String, Object> sceneMap = (Map<String, Object>) sceneMapRaw;
            String path = "manifest.scenes[" + index + "]";

            CompositionCaptionRequest caption = parseCaption(path, sceneMap.get("caption"));
            CompositionTransitionRequest transition = parseTransition(path, sceneMap.get("transition"));
            CompositionVisualEditRequest visualEdit = parseVisualEdit(path, sceneMap.get("visualEdit"));

            scenes.add(new CompositionSceneRequest(
                    asNullableString(sceneMap.get("assetId")),
                    parseEnum(SceneType.class, sceneMap.get("type"), path + ".type"),
                    asNullableDouble(sceneMap.get("durationSec"), path + ".durationSec"),
                    asNullableDouble(sceneMap.get("clipStartSec"), path + ".clipStartSec"),
                    asNullableDouble(sceneMap.get("clipDurationSec"), path + ".clipDurationSec"),
                    parseOptionalEnum(MotionType.class, sceneMap.get("motion"), path + ".motion"),
                    caption,
                    transition,
                    visualEdit));
        }

        return new CompositionManifestRequest(outputPreset, scenes);
    }

    @SuppressWarnings("unchecked")
    private CompositionCaptionRequest parseCaption(String scenePath, Object captionValue) {
        if (captionValue == null) {
            return null;
        }
        if (!(captionValue instanceof Map<?, ?> captionRaw)) {
            throw new IllegalArgumentException(scenePath + ".caption must be an object.");
        }

        Map<String, Object> caption = (Map<String, Object>) captionRaw;
        return new CompositionCaptionRequest(
                asNullableString(caption.get("text")),
                asNullableDouble(caption.get("startOffsetSec"), scenePath + ".caption.startOffsetSec"),
                asNullableDouble(caption.get("endOffsetSec"), scenePath + ".caption.endOffsetSec"),
                parseOptionalEnum(CaptionPosition.class, caption.get("position"), scenePath + ".caption.position"));
    }

    @SuppressWarnings("unchecked")
    private CompositionTransitionRequest parseTransition(String scenePath, Object transitionValue) {
        if (transitionValue == null) {
            return null;
        }
        if (!(transitionValue instanceof Map<?, ?> transitionRaw)) {
            throw new IllegalArgumentException(scenePath + ".transition must be an object.");
        }

        Map<String, Object> transition = (Map<String, Object>) transitionRaw;
        return new CompositionTransitionRequest(
                parseOptionalEnum(TransitionType.class, transition.get("type"), scenePath + ".transition.type"),
                asNullableDouble(transition.get("transitionDurationSec"), scenePath + ".transition.transitionDurationSec"));
    }

    @SuppressWarnings("unchecked")
    private CompositionVisualEditRequest parseVisualEdit(String scenePath, Object visualEditValue) {
        if (visualEditValue == null) {
            return null;
        }
        if (!(visualEditValue instanceof Map<?, ?> visualEditRaw)) {
            throw new IllegalArgumentException(scenePath + ".visualEdit must be an object.");
        }

        Map<String, Object> visualEdit = (Map<String, Object>) visualEditRaw;
        return new CompositionVisualEditRequest(
                parseOptionalEnum(VisualFilterType.class, visualEdit.get("filter"), scenePath + ".visualEdit.filter"),
                parseColorGrade(scenePath, visualEdit.get("colorGrade")),
                parseOverlay(scenePath, visualEdit.get("overlay")));
    }

    @SuppressWarnings("unchecked")
    private CompositionColorGradeRequest parseColorGrade(String scenePath, Object colorGradeValue) {
        if (colorGradeValue == null) {
            return null;
        }
        if (!(colorGradeValue instanceof Map<?, ?> colorGradeRaw)) {
            throw new IllegalArgumentException(scenePath + ".visualEdit.colorGrade must be an object.");
        }

        Map<String, Object> colorGrade = (Map<String, Object>) colorGradeRaw;
        return new CompositionColorGradeRequest(
                asNullableDouble(colorGrade.get("brightness"), scenePath + ".visualEdit.colorGrade.brightness"),
                asNullableDouble(colorGrade.get("contrast"), scenePath + ".visualEdit.colorGrade.contrast"),
                asNullableDouble(colorGrade.get("saturation"), scenePath + ".visualEdit.colorGrade.saturation"));
    }

    @SuppressWarnings("unchecked")
    private CompositionOverlayRequest parseOverlay(String scenePath, Object overlayValue) {
        if (overlayValue == null) {
            return null;
        }
        if (!(overlayValue instanceof Map<?, ?> overlayRaw)) {
            throw new IllegalArgumentException(scenePath + ".visualEdit.overlay must be an object.");
        }

        Map<String, Object> overlay = (Map<String, Object>) overlayRaw;
        return new CompositionOverlayRequest(
                asNullableString(overlay.get("hexColor")),
                asNullableDouble(overlay.get("opacity"), scenePath + ".visualEdit.overlay.opacity"));
    }

    private String asNullableString(Object value) {
        if (value == null) {
            return null;
        }
        return Objects.toString(value, null);
    }

    private Double asNullableDouble(Object value, String fieldPath) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldPath + " must be numeric.");
        }
    }

    private <T extends Enum<T>> T parseOptionalEnum(Class<T> enumType, Object value, String fieldPath) {
        if (value == null) {
            return null;
        }
        return parseEnum(enumType, value, fieldPath);
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumType, Object value, String fieldPath) {
        if (value == null) {
            throw new IllegalArgumentException(fieldPath + " is required.");
        }

        try {
            return Enum.valueOf(enumType, value.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldPath + " has an invalid value: " + value);
        }
    }

    private Map<String, MultipartFile> extractAssetParts(Map<String, MultipartFile> fileParts) {
        if (fileParts == null || fileParts.isEmpty()) {
            return Map.of();
        }

        Map<String, MultipartFile> assets = new LinkedHashMap<>();
        for (Map.Entry<String, MultipartFile> entry : fileParts.entrySet()) {
            String fieldName = entry.getKey();
            if (fieldName == null || !fieldName.startsWith(ASSET_PART_PREFIX)) {
                continue;
            }

            String assetId = fieldName.substring(ASSET_PART_PREFIX.length()).trim();
            if (!assetId.isEmpty()) {
                assets.put(assetId, entry.getValue());
            }
        }
        return assets;
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

        validateTitleAndDescription(title, description);

        if (durationSeconds < MIN_DURATION_SECONDS || durationSeconds > MAX_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    "Duration must be between " + MIN_DURATION_SECONDS + " and " + MAX_DURATION_SECONDS + " seconds.");
        }
    }

    private void validateCompositionBaseRequest(
            MultipartFile audio,
            String title,
            String description) {
        if (audio == null || audio.isEmpty()) {
            throw new IllegalArgumentException("Audio file is required.");
        }

        validateMimeType("audio", audio.getContentType(), "audio/");
        validateTitleAndDescription(title, description);
    }

    private void validateTitleAndDescription(String title, String description) {
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
