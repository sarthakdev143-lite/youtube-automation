package github.sarthakdev143.media_factory.model;

public record UploadResult(String videoId, String warningMessage) {

    public UploadResult(String videoId) {
        this(videoId, null);
    }
}
