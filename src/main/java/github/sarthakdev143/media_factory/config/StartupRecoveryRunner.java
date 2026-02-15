package github.sarthakdev143.media_factory.config;

import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStage;
import github.sarthakdev143.media_factory.persistence.VideoJob;
import github.sarthakdev143.media_factory.persistence.VideoJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@Order(10)
public class StartupRecoveryRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(StartupRecoveryRunner.class);
    private static final String RECOVERY_ERROR = "Application restarted during processing";

    private final VideoJobRepository videoJobRepository;

    public StartupRecoveryRunner(VideoJobRepository videoJobRepository) {
        this.videoJobRepository = videoJobRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<VideoJob> interruptedJobs = videoJobRepository.findAllByState(VideoJobState.PROCESSING);
        for (VideoJob job : interruptedJobs) {
            job.setState(VideoJobState.FAILED);
            job.setJobStage(VideoJobStage.FAILED);
            job.setStageDetail(RECOVERY_ERROR);
            job.setErrorMessage(RECOVERY_ERROR);
        }
        if (!interruptedJobs.isEmpty()) {
            videoJobRepository.saveAll(interruptedJobs);
        }

        long queuedCount = videoJobRepository.countByState(VideoJobState.QUEUED);
        long processingCount = videoJobRepository.countByState(VideoJobState.PROCESSING);
        long completedCount = videoJobRepository.countByState(VideoJobState.COMPLETED);
        long failedCount = videoJobRepository.countByState(VideoJobState.FAILED);

        logger.info(
                "[STARTUP_RECOVERY recovered={} queued={} processing={} completed={} failed={}]",
                interruptedJobs.size(),
                queuedCount,
                processingCount,
                completedCount,
                failedCount);
    }
}
