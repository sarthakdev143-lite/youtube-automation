package github.sarthakdev143.media_factory.service;

import github.sarthakdev143.media_factory.model.VideoJobState;

public class JobInProgressException extends RuntimeException {

    private final String activeJobId;
    private final VideoJobState activeJobState;

    public JobInProgressException(String activeJobId, VideoJobState activeJobState) {
        super("Another job is currently active with id: " + activeJobId);
        this.activeJobId = activeJobId;
        this.activeJobState = activeJobState;
    }

    public String activeJobId() {
        return activeJobId;
    }

    public VideoJobState activeJobState() {
        return activeJobState;
    }
}
