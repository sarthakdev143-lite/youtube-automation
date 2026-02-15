package github.sarthakdev143.media_factory.service;

import github.sarthakdev143.media_factory.model.VideoJobStatus;

public class ActiveJobConflictException extends RuntimeException {

    private final VideoJobStatus activeJob;

    public ActiveJobConflictException(VideoJobStatus activeJob) {
        super("A job is already processing: " + (activeJob == null ? "unknown" : activeJob.jobId()));
        this.activeJob = activeJob;
    }

    public VideoJobStatus getActiveJob() {
        return activeJob;
    }
}
