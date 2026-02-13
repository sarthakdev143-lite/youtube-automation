package github.sarthakdev143.media_factory.model.composition;

import github.sarthakdev143.media_factory.model.TransitionType;

public record CompositionTransitionPlan(
        TransitionType type,
        double durationSec) {
}
