package github.sarthakdev143.media_factory.service.impl;

import github.sarthakdev143.media_factory.dto.CompositionColorGradeRequest;
import github.sarthakdev143.media_factory.dto.CompositionManifestRequest;
import github.sarthakdev143.media_factory.dto.CompositionOverlayRequest;
import github.sarthakdev143.media_factory.dto.CompositionSceneRequest;
import github.sarthakdev143.media_factory.dto.CompositionTransitionRequest;
import github.sarthakdev143.media_factory.dto.CompositionVisualEditRequest;
import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.OutputPreset;
import github.sarthakdev143.media_factory.model.SceneType;
import github.sarthakdev143.media_factory.model.TransitionType;
import github.sarthakdev143.media_factory.model.VisualFilterType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositionManifestValidatorTest {

    private CompositionManifestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CompositionManifestValidator();
    }

    @Test
    void normalizeAndValidateAppliesDefaults() {
        CompositionManifestRequest manifest = new CompositionManifestRequest(
                OutputPreset.LANDSCAPE_16_9,
                List.of(
                        new CompositionSceneRequest(
                                "scene-image",
                                SceneType.IMAGE,
                                3.0,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null),
                        new CompositionSceneRequest(
                                "scene-video",
                                SceneType.VIDEO,
                                null,
                                null,
                                5.0,
                                MotionType.PAN_LEFT,
                                null,
                                new CompositionTransitionRequest(TransitionType.CROSSFADE, 0.7),
                                null)));

        CompositionManifestRequest normalized = validator.normalizeAndValidate(manifest, validAssets());

        assertThat(normalized.outputPreset()).isEqualTo(OutputPreset.LANDSCAPE_16_9);
        assertThat(normalized.scenes()).hasSize(2);

        CompositionSceneRequest firstScene = normalized.scenes().get(0);
        assertThat(firstScene.motion()).isEqualTo(MotionType.NONE);
        assertThat(firstScene.transition().type()).isEqualTo(TransitionType.CUT);
        assertThat(firstScene.durationSec()).isEqualTo(3.0);

        CompositionSceneRequest secondScene = normalized.scenes().get(1);
        assertThat(secondScene.clipStartSec()).isEqualTo(0.0);
        assertThat(secondScene.clipDurationSec()).isEqualTo(5.0);
        assertThat(secondScene.transition().type()).isEqualTo(TransitionType.CROSSFADE);
        assertThat(secondScene.transition().transitionDurationSec()).isEqualTo(0.7);
    }

    @Test
    void normalizeAndValidateRejectsMissingAsset() {
        CompositionManifestRequest manifest = new CompositionManifestRequest(
                OutputPreset.SQUARE_1_1,
                List.of(new CompositionSceneRequest(
                        "missing-scene",
                        SceneType.IMAGE,
                        2.0,
                        null,
                        null,
                        MotionType.NONE,
                        null,
                        null,
                        null)));

        assertThatThrownBy(() -> validator.normalizeAndValidate(manifest, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required file part asset.missing-scene");
    }

    @Test
    void normalizeAndValidateRejectsInvalidFirstTransition() {
        CompositionManifestRequest manifest = new CompositionManifestRequest(
                OutputPreset.PORTRAIT_9_16,
                List.of(new CompositionSceneRequest(
                        "scene-image",
                        SceneType.IMAGE,
                        2.0,
                        null,
                        null,
                        MotionType.NONE,
                        null,
                        new CompositionTransitionRequest(TransitionType.CROSSFADE, 0.3),
                        null)));

        assertThatThrownBy(() -> validator.normalizeAndValidate(manifest, validAssets()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("first scene transition must be CUT");
    }

    @Test
    void normalizeAndValidateRejectsTimelineLongerThanMax() {
        CompositionManifestRequest manifest = new CompositionManifestRequest(
                OutputPreset.LANDSCAPE_16_9,
                List.of(
                        new CompositionSceneRequest(
                                "scene-video",
                                SceneType.VIDEO,
                                null,
                                0.0,
                                20001.0,
                                MotionType.NONE,
                                null,
                                null,
                                null),
                        new CompositionSceneRequest(
                                "scene-video-2",
                                SceneType.VIDEO,
                                null,
                                0.0,
                                20001.0,
                                MotionType.NONE,
                                null,
                                new CompositionTransitionRequest(TransitionType.CUT, null),
                                null)));

        assertThatThrownBy(() -> validator.normalizeAndValidate(manifest, validLongVideoAssets()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Total timeline duration must be less than or equal to 36000 seconds");
    }

    @Test
    void normalizeAndValidateAppliesVisualEditDefaultsWhenOmitted() {
        CompositionManifestRequest manifest = new CompositionManifestRequest(
                OutputPreset.LANDSCAPE_16_9,
                List.of(new CompositionSceneRequest(
                        "scene-image",
                        SceneType.IMAGE,
                        3.0,
                        null,
                        null,
                        MotionType.NONE,
                        null,
                        null,
                        null)));

        CompositionManifestRequest normalized = validator.normalizeAndValidate(manifest, validAssets());
        CompositionVisualEditRequest visualEdit = normalized.scenes().get(0).visualEdit();

        assertThat(visualEdit).isNotNull();
        assertThat(visualEdit.filter()).isEqualTo(VisualFilterType.NONE);
        assertThat(visualEdit.colorGrade().brightness()).isEqualTo(0.0);
        assertThat(visualEdit.colorGrade().contrast()).isEqualTo(1.0);
        assertThat(visualEdit.colorGrade().saturation()).isEqualTo(1.0);
        assertThat(visualEdit.overlay()).isNull();
    }

    @Test
    void normalizeAndValidateRejectsInvalidOverlayColor() {
        CompositionManifestRequest manifest = new CompositionManifestRequest(
                OutputPreset.LANDSCAPE_16_9,
                List.of(new CompositionSceneRequest(
                        "scene-image",
                        SceneType.IMAGE,
                        3.0,
                        null,
                        null,
                        MotionType.NONE,
                        null,
                        null,
                        new CompositionVisualEditRequest(
                                VisualFilterType.SEPIA,
                                new CompositionColorGradeRequest(0.1, 1.2, 1.1),
                                new CompositionOverlayRequest("blue", 0.3)))));

        assertThatThrownBy(() -> validator.normalizeAndValidate(manifest, validAssets()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("visualEdit.overlay.hexColor must match #RRGGBB");
    }

    private Map<String, MultipartFile> validAssets() {
        Map<String, MultipartFile> assets = new LinkedHashMap<>();
        assets.put("scene-image", new MockMultipartFile(
                "asset.scene-image",
                "scene.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3}));
        assets.put("scene-video", new MockMultipartFile(
                "asset.scene-video",
                "scene.mp4",
                "video/mp4",
                new byte[]{4, 5, 6}));
        return assets;
    }

    private Map<String, MultipartFile> validLongVideoAssets() {
        Map<String, MultipartFile> assets = new LinkedHashMap<>();
        assets.put("scene-video", new MockMultipartFile(
                "asset.scene-video",
                "scene.mp4",
                "video/mp4",
                new byte[]{4, 5, 6}));
        assets.put("scene-video-2", new MockMultipartFile(
                "asset.scene-video-2",
                "scene2.mp4",
                "video/mp4",
                new byte[]{7, 8, 9}));
        return assets;
    }
}
