package github.sarthakdev143.media_factory.config;

import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.persistence.entity.JobEntity;
import github.sarthakdev143.media_factory.persistence.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JobStartupRecovery implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(JobStartupRecovery.class);
    private static final String RESTART_FAILURE_MESSAGE = "Application restarted during processing.";

    private final JobRepository jobRepository;

    public JobStartupRecovery(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<JobEntity> interruptedJobs = jobRepository.findAllByState(VideoJobState.PROCESSING);
        if (interruptedJobs.isEmpty()) {
            return;
        }

        for (JobEntity interruptedJob : interruptedJobs) {
            interruptedJob.setState(VideoJobState.FAILED);
            interruptedJob.setMessage(RESTART_FAILURE_MESSAGE);
            interruptedJob.setErrorMessage(RESTART_FAILURE_MESSAGE);
        }
        jobRepository.saveAll(interruptedJobs);

        logger.warn("[RECOVERY] Marked {} job(s) as FAILED after restart.", interruptedJobs.size());
    }
}
