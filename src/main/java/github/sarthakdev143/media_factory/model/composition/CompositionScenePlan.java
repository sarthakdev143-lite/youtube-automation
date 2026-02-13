package github.sarthakdev143.media_factory.model.composition;

import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.SceneType;

public record CompositionScenePlan(
        String assetId,
        SceneType type,
        double durationSec,
        double clipStartSec,
        MotionType motion,
        CompositionCaptionPlan caption,
        CompositionTransitionPlan transition,
        CompositionVisualEditPlan visualEdit) {
}
