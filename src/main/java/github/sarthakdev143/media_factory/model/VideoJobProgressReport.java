package github.sarthakdev143.media_factory.model;

public record VideoJobProgressReport(
        VideoJobStage stage,
        String detail,
        int overallPercent,
        int generationPercent,
        int uploadPercent,
        String uploadState) {

    public VideoJobProgressReport {
        stage = stage == null ? VideoJobStage.QUEUED : stage;
        detail = detail == null || detail.isBlank() ? null : detail;
        overallPercent = clampPercent(overallPercent);
        generationPercent = clampPercent(generationPercent);
        uploadPercent = clampPercent(uploadPercent);
        uploadState = uploadState == null || uploadState.isBlank() ? null : uploadState;
    }

    private static int clampPercent(int value) {
        if (value < 0) {
            return 0;
        }
        return Math.min(value, 100);
    }
}
