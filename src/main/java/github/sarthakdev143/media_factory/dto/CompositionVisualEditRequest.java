package github.sarthakdev143.media_factory.dto;

import github.sarthakdev143.media_factory.model.VisualFilterType;

public record CompositionVisualEditRequest(
        VisualFilterType filter,
        CompositionColorGradeRequest colorGrade,
        CompositionOverlayRequest overlay) {
}
