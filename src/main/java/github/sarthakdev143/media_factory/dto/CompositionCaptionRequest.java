package github.sarthakdev143.media_factory.dto;

import github.sarthakdev143.media_factory.model.CaptionPosition;

public record CompositionCaptionRequest(
        String text,
        Double startOffsetSec,
        Double endOffsetSec,
        CaptionPosition position) {
}
