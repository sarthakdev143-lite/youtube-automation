package github.sarthakdev143.media_factory.model.composition;

import github.sarthakdev143.media_factory.model.CaptionPosition;

public record CompositionCaptionPlan(
        String text,
        double startOffsetSec,
        double endOffsetSec,
        CaptionPosition position) {
}
