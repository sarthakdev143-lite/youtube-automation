package github.sarthakdev143.media_factory.integration.recovery;

import github.sarthakdev143.media_factory.config.StartupRecoveryRunner;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.VideoJobStage;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.persistence.VideoJob;
import github.sarthakdev143.media_factory.persistence.VideoJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "media-factory.preflight.enabled=false",
        "media-factory.worker.enabled=false",
        "spring.datasource.url=jdbc:sqlite:file:startup_recovery_test?mode=memory&cache=shared",
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class StartupRecoveryRunnerTest {

    @Autowired
    private VideoJobRepository videoJobRepository;

    @Autowired
    private StartupRecoveryRunner startupRecoveryRunner;

    @BeforeEach
    void setUp() {
        videoJobRepository.deleteAll();
    }

    @Test
    void startupRecoveryMarksProcessingJobsAsFailed() throws Exception {
        VideoJob processingJob = buildJob(VideoJobState.PROCESSING);
        VideoJob queuedJob = buildJob(VideoJobState.QUEUED);
        videoJobRepository.save(processingJob);
        videoJobRepository.save(queuedJob);

        startupRecoveryRunner.run(new DefaultApplicationArguments(new String[]{}));

        VideoJob recovered = videoJobRepository.findById(processingJob.getId()).orElseThrow();
        VideoJob stillQueued = videoJobRepository.findById(queuedJob.getId()).orElseThrow();

        assertThat(recovered.getState()).isEqualTo(VideoJobState.FAILED);
        assertThat(recovered.getJobStage()).isEqualTo(VideoJobStage.FAILED);
        assertThat(recovered.getStageDetail()).isEqualTo("Application restarted during processing");
        assertThat(recovered.getErrorMessage()).isEqualTo("Application restarted during processing");
        assertThat(stillQueued.getState()).isEqualTo(VideoJobState.QUEUED);
    }

    private VideoJob buildJob(VideoJobState state) {
        UUID jobId = UUID.randomUUID();
        VideoJob videoJob = new VideoJob();
        videoJob.setId(jobId);
        videoJob.setState(state);
        videoJob.setTitle("Test");
        videoJob.setDescription("Description");
        videoJob.setPrivacyStatus(PrivacyStatus.PRIVATE);
        videoJob.setTags("");
        videoJob.setCategoryId(null);
        videoJob.setPublishAt(null);
        videoJob.setInputImagePath("media-factory-work/inputs/" + jobId + "-image.jpg");
        videoJob.setInputAudioPath("media-factory-work/inputs/" + jobId + "-audio.mp3");
        videoJob.setInputThumbnailPath(null);
        videoJob.setOutputPath(null);
        videoJob.setYoutubeVideoId(null);
        videoJob.setJobStage(state == VideoJobState.PROCESSING ? VideoJobStage.GENERATING : VideoJobStage.QUEUED);
        videoJob.setStageDetail(state == VideoJobState.PROCESSING ? "Generating video with FFmpeg." : "Queued.");
        videoJob.setProgressPercent(0);
        videoJob.setGenerationProgressPercent(0);
        videoJob.setUploadProgressPercent(0);
        videoJob.setUploadState(null);
        videoJob.setErrorMessage(null);
        videoJob.setWarningMessage(null);
        videoJob.setDurationSeconds(120);
        return videoJob;
    }
}
