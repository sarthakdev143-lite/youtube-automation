package github.sarthakdev143.media_factory.dto;

import github.sarthakdev143.media_factory.model.VideoJobState;

public record VideoJobSubmissionResponse(
        String jobId,
        VideoJobState state,
        String message) {
}
