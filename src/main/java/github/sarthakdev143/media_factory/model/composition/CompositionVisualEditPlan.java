package github.sarthakdev143.media_factory.model.composition;

import github.sarthakdev143.media_factory.model.VisualFilterType;

public record CompositionVisualEditPlan(
        VisualFilterType filter,
        CompositionColorGradePlan colorGrade,
        CompositionOverlayPlan overlay) {
}
