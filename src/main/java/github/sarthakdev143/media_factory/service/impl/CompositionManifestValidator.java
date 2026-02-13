package github.sarthakdev143.media_factory.service.impl;

import github.sarthakdev143.media_factory.dto.CompositionCaptionRequest;
import github.sarthakdev143.media_factory.dto.CompositionColorGradeRequest;
import github.sarthakdev143.media_factory.dto.CompositionManifestRequest;
import github.sarthakdev143.media_factory.dto.CompositionOverlayRequest;
import github.sarthakdev143.media_factory.dto.CompositionSceneRequest;
import github.sarthakdev143.media_factory.dto.CompositionTransitionRequest;
import github.sarthakdev143.media_factory.dto.CompositionVisualEditRequest;
import github.sarthakdev143.media_factory.model.CaptionPosition;
import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.SceneType;
import github.sarthakdev143.media_factory.model.TransitionType;
import github.sarthakdev143.media_factory.model.VisualFilterType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CompositionManifestValidator {

    private static final int MAX_SCENES = 50;
    private static final double MIN_IMAGE_DURATION_SECONDS = 0.5;
    private static final double MAX_IMAGE_DURATION_SECONDS = 600.0;
    private static final double MAX_TOTAL_DURATION_SECONDS = 10.0 * 60.0 * 60.0;
    private static final double MIN_CROSSFADE_DURATION_SECONDS = 0.2;
    private static final double MAX_CROSSFADE_DURATION_SECONDS = 2.0;
    private static final double MIN_BRIGHTNESS = -1.0;
    private static final double MAX_BRIGHTNESS = 1.0;
    private static final double MIN_CONTRAST = 0.2;
    private static final double MAX_CONTRAST = 3.0;
    private static final double MIN_SATURATION = 0.0;
    private static final double MAX_SATURATION = 3.0;
    private static final double MIN_OVERLAY_OPACITY = 0.0;
    private static final double MAX_OVERLAY_OPACITY = 1.0;
    private static final double DEFAULT_BRIGHTNESS = 0.0;
    private static final double DEFAULT_CONTRAST = 1.0;
    private static final double DEFAULT_SATURATION = 1.0;
    private static final double DEFAULT_OVERLAY_OPACITY = 0.25;
    private static final double EPSILON = 1e-9;
    private static final Pattern DURATION_PATTERN = Pattern.compile("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final String FFMPEG_PATH_ENV = "FFMPEG_PATH";
    private static final String DEFAULT_FFMPEG_BINARY = "ffmpeg";

    public CompositionManifestRequest normalizeAndValidate(
            CompositionManifestRequest manifest,
            Map<String, MultipartFile> assetsById) {
        if (manifest == null) {
            throw new IllegalArgumentException("manifest is required.");
        }
        if (manifest.outputPreset() == null) {
            throw new IllegalArgumentException("manifest.outputPreset is required.");
        }

        List<CompositionSceneRequest> scenes = manifest.scenes();
        if (scenes == null || scenes.isEmpty()) {
            throw new IllegalArgumentException("manifest.scenes must contain at least one scene.");
        }
        if (scenes.size() > MAX_SCENES) {
            throw new IllegalArgumentException("manifest.scenes supports at most " + MAX_SCENES + " scenes.");
        }

        Map<String, MultipartFile> safeAssets = assetsById == null
                ? Map.of()
                : new LinkedHashMap<>(assetsById);

        List<CompositionSceneRequest> normalizedScenes = new ArrayList<>();
        double totalDurationSeconds = 0.0;
        double previousSceneDuration = 0.0;

        for (int index = 0; index < scenes.size(); index++) {
            CompositionSceneRequest scene = scenes.get(index);
            if (scene == null) {
                throw new IllegalArgumentException("manifest.scenes[" + index + "] must not be null.");
            }

            String assetId = requireAssetId(index, scene.assetId());
            MultipartFile asset = requireAsset(index, assetId, safeAssets);
            SceneType sceneType = requireSceneType(index, scene.type());

            MotionType motion = scene.motion() == null ? MotionType.NONE : scene.motion();
            double clipStartSeconds = nonNegativeOrDefault(
                    scene.clipStartSec(),
                    0.0,
                    "manifest.scenes[" + index + "].clipStartSec");

            double sceneDurationSeconds = resolveSceneDurationSeconds(index, scene, sceneType, asset, clipStartSeconds);
            CompositionCaptionRequest caption = normalizeCaption(index, scene.caption(), sceneDurationSeconds);
            CompositionTransitionRequest transition = normalizeTransition(
                    index,
                    scene.transition(),
                    previousSceneDuration,
                    sceneDurationSeconds);
            CompositionVisualEditRequest visualEdit = normalizeVisualEdit(index, scene.visualEdit());

            totalDurationSeconds += sceneDurationSeconds;
            if (transition.type() == TransitionType.CROSSFADE) {
                totalDurationSeconds -= transition.transitionDurationSec();
            }

            if (sceneType == SceneType.IMAGE) {
                normalizedScenes.add(new CompositionSceneRequest(
                        assetId,
                        sceneType,
                        sceneDurationSeconds,
                        0.0,
                        null,
                        motion,
                        caption,
                        transition,
                        visualEdit));
            } else {
                normalizedScenes.add(new CompositionSceneRequest(
                        assetId,
                        sceneType,
                        null,
                        clipStartSeconds,
                        sceneDurationSeconds,
                        motion,
                        caption,
                        transition,
                        visualEdit));
            }

            previousSceneDuration = sceneDurationSeconds;
        }

        if (totalDurationSeconds > MAX_TOTAL_DURATION_SECONDS + EPSILON) {
            throw new IllegalArgumentException("Total timeline duration must be less than or equal to 36000 seconds.");
        }

        return new CompositionManifestRequest(manifest.outputPreset(), normalizedScenes);
    }

    private String requireAssetId(int index, String assetIdInput) {
        if (assetIdInput == null || assetIdInput.isBlank()) {
            throw new IllegalArgumentException("manifest.scenes[" + index + "].assetId is required.");
        }
        return assetIdInput.trim();
    }

    private MultipartFile requireAsset(int index, String assetId, Map<String, MultipartFile> assetsById) {
        MultipartFile asset = assetsById.get(assetId);
        if (asset == null || asset.isEmpty()) {
            throw new IllegalArgumentException("Missing required file part asset." + assetId + " for scene index " + index + ".");
        }
        return asset;
    }

    private SceneType requireSceneType(int index, SceneType sceneType) {
        if (sceneType == null) {
            throw new IllegalArgumentException("manifest.scenes[" + index + "].type is required.");
        }
        return sceneType;
    }

    private double resolveSceneDurationSeconds(
            int index,
            CompositionSceneRequest scene,
            SceneType sceneType,
            MultipartFile asset,
            double clipStartSeconds) {
        if (sceneType == SceneType.IMAGE) {
            validateContentType(index, sceneType, asset.getContentType(), "image/");
            if (scene.clipDurationSec() != null) {
                throw new IllegalArgumentException("manifest.scenes[" + index + "].clipDurationSec is not supported for IMAGE scenes.");
            }
            if (scene.clipStartSec() != null && scene.clipStartSec() > EPSILON) {
                throw new IllegalArgumentException("manifest.scenes[" + index + "].clipStartSec must be 0 for IMAGE scenes.");
            }

            double durationSeconds = requireFinite(
                    scene.durationSec(),
                    "manifest.scenes[" + index + "].durationSec");
            if (durationSeconds < MIN_IMAGE_DURATION_SECONDS || durationSeconds > MAX_IMAGE_DURATION_SECONDS) {
                throw new IllegalArgumentException(
                        "manifest.scenes[" + index + "].durationSec must be between "
                                + MIN_IMAGE_DURATION_SECONDS
                                + " and "
                                + MAX_IMAGE_DURATION_SECONDS
                                + " seconds.");
            }
            return durationSeconds;
        }

        validateContentType(index, sceneType, asset.getContentType(), "video/");
        if (scene.durationSec() != null) {
            throw new IllegalArgumentException("manifest.scenes[" + index + "].durationSec is only supported for IMAGE scenes.");
        }

        if (scene.clipDurationSec() != null) {
            double clipDuration = requireFinite(
                    scene.clipDurationSec(),
                    "manifest.scenes[" + index + "].clipDurationSec");
            if (clipDuration <= EPSILON) {
                throw new IllegalArgumentException("manifest.scenes[" + index + "].clipDurationSec must be greater than 0.");
            }
            return clipDuration;
        }

        double sourceDuration = probeVideoDurationSeconds(asset, index);
        double remainingDuration = sourceDuration - clipStartSeconds;
        if (remainingDuration <= EPSILON) {
            throw new IllegalArgumentException(
                    "manifest.scenes[" + index + "].clipStartSec exceeds the source video duration.");
        }
        return remainingDuration;
    }

    private CompositionCaptionRequest normalizeCaption(
            int index,
            CompositionCaptionRequest caption,
            double sceneDurationSeconds) {
        if (caption == null) {
            return null;
        }

        if (caption.text() == null || caption.text().isBlank()) {
            throw new IllegalArgumentException("manifest.scenes[" + index + "].caption.text must not be blank.");
        }

        double startOffset = nonNegativeOrDefault(
                caption.startOffsetSec(),
                0.0,
                "manifest.scenes[" + index + "].caption.startOffsetSec");
        double endOffset = caption.endOffsetSec() == null
                ? sceneDurationSeconds
                : requireFinite(caption.endOffsetSec(), "manifest.scenes[" + index + "].caption.endOffsetSec");

        if (endOffset <= startOffset + EPSILON) {
            throw new IllegalArgumentException(
                    "manifest.scenes[" + index + "].caption.endOffsetSec must be greater than caption.startOffsetSec.");
        }
        if (endOffset > sceneDurationSeconds + EPSILON) {
            throw new IllegalArgumentException(
                    "manifest.scenes[" + index + "].caption.endOffsetSec must not exceed the scene duration.");
        }

        CaptionPosition position = caption.position() == null
                ? CaptionPosition.BOTTOM
                : caption.position();

        return new CompositionCaptionRequest(caption.text().trim(), startOffset, endOffset, position);
    }

    private CompositionTransitionRequest normalizeTransition(
            int index,
            CompositionTransitionRequest transition,
            double previousSceneDuration,
            double sceneDuration) {
        if (index == 0) {
            if (transition != null && transition.type() != null && transition.type() != TransitionType.CUT) {
                throw new IllegalArgumentException("The first scene transition must be CUT.");
            }
            return new CompositionTransitionRequest(TransitionType.CUT, null);
        }

        TransitionType transitionType = transition == null || transition.type() == null
                ? TransitionType.CUT
                : transition.type();

        if (transitionType == TransitionType.CUT) {
            return new CompositionTransitionRequest(TransitionType.CUT, null);
        }

        double transitionDuration = requireFinite(
                transition == null ? null : transition.transitionDurationSec(),
                "manifest.scenes[" + index + "].transition.transitionDurationSec");
        if (transitionDuration < MIN_CROSSFADE_DURATION_SECONDS
                || transitionDuration > MAX_CROSSFADE_DURATION_SECONDS) {
            throw new IllegalArgumentException(
                    "manifest.scenes[" + index + "].transition.transitionDurationSec must be between "
                            + MIN_CROSSFADE_DURATION_SECONDS
                            + " and "
                            + MAX_CROSSFADE_DURATION_SECONDS
                            + " seconds.");
        }

        if (transitionDuration >= previousSceneDuration - EPSILON
                || transitionDuration >= sceneDuration - EPSILON) {
            throw new IllegalArgumentException(
                    "manifest.scenes[" + index + "].transition.transitionDurationSec must be smaller than adjacent scene durations.");
        }

        return new CompositionTransitionRequest(TransitionType.CROSSFADE, transitionDuration);
    }

    private CompositionVisualEditRequest normalizeVisualEdit(
            int index,
            CompositionVisualEditRequest visualEdit) {
        VisualFilterType filter = visualEdit == null || visualEdit.filter() == null
                ? VisualFilterType.NONE
                : visualEdit.filter();
        CompositionColorGradeRequest colorGrade = normalizeColorGrade(
                index,
                visualEdit == null ? null : visualEdit.colorGrade());
        CompositionOverlayRequest overlay = normalizeOverlay(
                index,
                visualEdit == null ? null : visualEdit.overlay());

        return new CompositionVisualEditRequest(filter, colorGrade, overlay);
    }

    private CompositionColorGradeRequest normalizeColorGrade(
            int index,
            CompositionColorGradeRequest colorGrade) {
        String prefix = "manifest.scenes[" + index + "].visualEdit.colorGrade.";
        double brightness = valueInRangeOrDefault(
                colorGrade == null ? null : colorGrade.brightness(),
                DEFAULT_BRIGHTNESS,
                MIN_BRIGHTNESS,
                MAX_BRIGHTNESS,
                prefix + "brightness");
        double contrast = valueInRangeOrDefault(
                colorGrade == null ? null : colorGrade.contrast(),
                DEFAULT_CONTRAST,
                MIN_CONTRAST,
                MAX_CONTRAST,
                prefix + "contrast");
        double saturation = valueInRangeOrDefault(
                colorGrade == null ? null : colorGrade.saturation(),
                DEFAULT_SATURATION,
                MIN_SATURATION,
                MAX_SATURATION,
                prefix + "saturation");

        return new CompositionColorGradeRequest(brightness, contrast, saturation);
    }

    private CompositionOverlayRequest normalizeOverlay(
            int index,
            CompositionOverlayRequest overlay) {
        if (overlay == null) {
            return null;
        }

        String colorField = "manifest.scenes[" + index + "].visualEdit.overlay.hexColor";
        if (overlay.hexColor() == null || overlay.hexColor().isBlank()) {
            throw new IllegalArgumentException(colorField + " is required when overlay is provided.");
        }

        String normalizedHexColor = overlay.hexColor().trim();
        if (!HEX_COLOR_PATTERN.matcher(normalizedHexColor).matches()) {
            throw new IllegalArgumentException(colorField + " must match #RRGGBB.");
        }

        String opacityField = "manifest.scenes[" + index + "].visualEdit.overlay.opacity";
        double opacity = valueInRangeOrDefault(
                overlay.opacity(),
                DEFAULT_OVERLAY_OPACITY,
                MIN_OVERLAY_OPACITY,
                MAX_OVERLAY_OPACITY,
                opacityField);

        if (opacity <= EPSILON) {
            return null;
        }

        return new CompositionOverlayRequest(normalizedHexColor.toUpperCase(Locale.ROOT), opacity);
    }

    private void validateContentType(int index, SceneType type, String contentType, String prefix) {
        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!normalizedContentType.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "asset for manifest.scenes[" + index + "] with type "
                            + type
                            + " must have content type "
                            + prefix
                            + "*.");
        }
    }

    private double nonNegativeOrDefault(Double value, double defaultValue, String fieldName) {
        if (value == null) {
            return defaultValue;
        }
        double normalized = requireFinite(value, fieldName);
        if (normalized < 0.0) {
            throw new IllegalArgumentException(fieldName + " must be greater than or equal to 0.");
        }
        return normalized;
    }

    private double requireFinite(Double value, String fieldName) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException(fieldName + " must be a finite number.");
        }
        return value;
    }

    private double valueInRangeOrDefault(
            Double value,
            double defaultValue,
            double minValue,
            double maxValue,
            String fieldName) {
        if (value == null) {
            return defaultValue;
        }

        double normalized = requireFinite(value, fieldName);
        if (normalized < minValue || normalized > maxValue) {
            throw new IllegalArgumentException(
                    fieldName
                            + " must be between "
                            + minValue
                            + " and "
                            + maxValue
                            + ".");
        }
        return normalized;
    }

    private double probeVideoDurationSeconds(MultipartFile asset, int sceneIndex) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("media-factory-probe-", resolveProbeSuffix(asset));
            asset.transferTo(tempFile);

            List<String> command = List.of(resolveFfmpegBinary(), "-i", tempFile.toString());
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalArgumentException(
                        "Timed out while probing duration for a video asset in manifest.scenes[" + sceneIndex + "].");
            }
            Matcher matcher = DURATION_PATTERN.matcher(output.toString());
            if (!matcher.find()) {
                throw new IllegalArgumentException(
                        "Unable to determine duration for video asset in manifest.scenes[" + sceneIndex + "].");
            }

            long hours = Long.parseLong(matcher.group(1));
            long minutes = Long.parseLong(matcher.group(2));
            double seconds = Double.parseDouble(matcher.group(3));
            Duration base = Duration.ofHours(hours).plusMinutes(minutes);
            return base.toSeconds() + seconds;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalArgumentException(
                    "Unable to probe duration for a video asset in manifest.scenes[" + sceneIndex + "].",
                    e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Cleanup failures are non-fatal.
                }
            }
        }
    }

    private String resolveProbeSuffix(MultipartFile asset) {
        String originalFilename = asset.getOriginalFilename();
        if (originalFilename != null) {
            int extensionIndex = originalFilename.lastIndexOf('.');
            if (extensionIndex >= 0 && extensionIndex < originalFilename.length() - 1) {
                return originalFilename.substring(extensionIndex);
            }
        }

        String contentType = asset.getContentType();
        if (contentType != null) {
            if (contentType.toLowerCase(Locale.ROOT).contains("webm")) {
                return ".webm";
            }
            if (contentType.toLowerCase(Locale.ROOT).contains("quicktime")) {
                return ".mov";
            }
            if (contentType.toLowerCase(Locale.ROOT).contains("ogg")) {
                return ".ogv";
            }
        }

        return ".mp4";
    }

    private String resolveFfmpegBinary() {
        String configuredPath = System.getenv(FFMPEG_PATH_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return configuredPath;
        }
        return DEFAULT_FFMPEG_BINARY;
    }
}
