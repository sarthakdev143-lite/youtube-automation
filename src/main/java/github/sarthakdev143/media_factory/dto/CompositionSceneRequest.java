package github.sarthakdev143.media_factory.dto;

import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.SceneType;

public record CompositionSceneRequest(
        String assetId,
        SceneType type,
        Double durationSec,
        Double clipStartSec,
        Double clipDurationSec,
        MotionType motion,
        CompositionCaptionRequest caption,
        CompositionTransitionRequest transition) {
}
