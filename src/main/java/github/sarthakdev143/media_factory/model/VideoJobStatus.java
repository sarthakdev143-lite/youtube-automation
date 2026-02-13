package github.sarthakdev143.media_factory.model;

import java.time.Instant;
import java.util.List;

public record VideoJobStatus(
        String jobId,
        VideoJobState state,
        String message,
        Instant createdAt,
        Instant updatedAt,
        PrivacyStatus privacyStatus,
        List<String> tags,
        String categoryId,
        Instant publishAt,
        String youtubeVideoId,
        String youtubeVideoUrl,
        String warningMessage) {

    public VideoJobStatus {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
