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
        String warningMessage,
        Integer progressPercent) {

    public VideoJobStatus {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public VideoJobStatus(
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
        this(
                jobId,
                state,
                message,
                createdAt,
                updatedAt,
                privacyStatus,
                tags,
                categoryId,
                publishAt,
                youtubeVideoId,
                youtubeVideoUrl,
                warningMessage,
                null);
    }
}
