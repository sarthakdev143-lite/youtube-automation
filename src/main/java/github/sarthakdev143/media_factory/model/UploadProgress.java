package github.sarthakdev143.media_factory.model;

public record UploadProgress(String state, double progressFraction) {

    public UploadProgress {
        state = state == null || state.isBlank() ? "UNKNOWN" : state;
        if (Double.isNaN(progressFraction) || Double.isInfinite(progressFraction)) {
            progressFraction = 0.0d;
        }
        if (progressFraction < 0.0d) {
            progressFraction = 0.0d;
        }
        if (progressFraction > 1.0d) {
            progressFraction = 1.0d;
        }
    }
}
