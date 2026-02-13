package github.sarthakdev143.media_factory.model;

import java.time.Instant;

public record VideoJobStatus(
        String jobId,
        VideoJobState state,
        String message,
        Instant createdAt,
        Instant updatedAt) {
}
