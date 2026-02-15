package github.sarthakdev143.media_factory.model;

import java.time.Instant;
import java.util.List;

public record VideoJobStatus(
        String jobId,
        VideoJobState state,
        String message,
        Instant createdAt,
        Instant updatedAt,
        int progressPercent,
        VideoJobProgressReport progressReport,
        PrivacyStatus privacyStatus,
        List<String> tags,
        String categoryId,
        Instant publishAt,
        String youtubeVideoId,
        String youtubeVideoUrl,
        String warningMessage) {

    public VideoJobStatus {
        tags = tags == null ? List.of() : List.copyOf(tags);
        progressReport = progressReport == null
                ? new VideoJobProgressReport(defaultStageFor(state), null, progressPercent, 0, 0, null)
                : progressReport;
    }

    public VideoJobStatus(
            String jobId,
            VideoJobState state,
            String message,
            Instant createdAt,
            Instant updatedAt,
            VideoJobProgressReport progressReport,
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
                0,
                progressReport,
                privacyStatus,
                tags,
                categoryId,
                publishAt,
                youtubeVideoId,
                youtubeVideoUrl,
                warningMessage);
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
                0,
                null,
                privacyStatus,
                tags,
                categoryId,
                publishAt,
                youtubeVideoId,
                youtubeVideoUrl,
                warningMessage);
    }

    private static VideoJobStage defaultStageFor(VideoJobState state) {
        if (state == null) {
            return VideoJobStage.QUEUED;
        }
        return switch (state) {
            case QUEUED -> VideoJobStage.QUEUED;
            case PROCESSING -> VideoJobStage.PREPARING;
            case COMPLETED -> VideoJobStage.COMPLETED;
            case FAILED -> VideoJobStage.FAILED;
        };
    }
}
