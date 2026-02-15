package github.sarthakdev143.media_factory.model;

public enum VideoJobStage {
    QUEUED,
    PREPARING,
    GENERATING,
    UPLOADING_VIDEO,
    UPLOADING_THUMBNAIL,
    FINALIZING,
    COMPLETED,
    FAILED
}
