package github.sarthakdev143.media_factory.dto;

import github.sarthakdev143.media_factory.model.TransitionType;

public record CompositionTransitionRequest(
        TransitionType type,
        Double transitionDurationSec) {
}
