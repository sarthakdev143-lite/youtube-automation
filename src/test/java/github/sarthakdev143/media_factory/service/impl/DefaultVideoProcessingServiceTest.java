package github.sarthakdev143.media_factory.service.impl;

import com.google.api.services.youtube.YouTube;
import github.sarthakdev143.media_factory.factory.VideoGeneratorUploaderFactory;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;
import github.sarthakdev143.media_factory.integration.youtube.YouTubeServiceProvider;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultVideoProcessingServiceTest {

    @Mock
    private YouTubeServiceProvider youTubeServiceProvider;

    @Mock
    private VideoGeneratorUploaderFactory uploaderFactory;

    @Mock
    private VideoGeneratorUploader uploader;

    @Mock
    private YouTube youTubeService;

    private DefaultVideoProcessingService service;

    @BeforeEach
    void setUp() {
        TaskExecutor directExecutor = Runnable::run;
        service = new DefaultVideoProcessingService(youTubeServiceProvider, uploaderFactory, directExecutor);
    }

    @Test
    void submitJobCompletesSuccessfully() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);

        String jobId = service.submitJob(validImage(), validAudio(), 60, "Title", "Description");
        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();

        assertThat(status.state()).isEqualTo(VideoJobState.COMPLETED);
        verify(uploader).generateVideo(anyString(), anyString(), eq(60), anyString());
        verify(uploader).uploadToYouTube(anyString(), eq("Title"), eq("Description"));
    }

    @Test
    void submitJobMarksFailedWhenFfmpegGenerationFails() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);
        doThrow(new RuntimeException("ffmpeg failed"))
                .when(uploader)
                .generateVideo(anyString(), anyString(), anyInt(), anyString());

        String jobId = service.submitJob(validImage(), validAudio(), 60, "Title", "Description");
        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();

        assertThat(status.state()).isEqualTo(VideoJobState.FAILED);
    }

    @Test
    void submitJobMarksFailedWhenOAuthInitFails() throws Exception {
        when(youTubeServiceProvider.getService()).thenThrow(new IOException("oauth failed"));

        String jobId = service.submitJob(validImage(), validAudio(), 60, "Title", "Description");
        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();

        assertThat(status.state()).isEqualTo(VideoJobState.FAILED);
        verifyNoInteractions(uploaderFactory);
    }

    private MockMultipartFile validImage() {
        return new MockMultipartFile(
                "image",
                "image.jpg",
                "image/jpeg",
                new byte[]{1, 2, 3});
    }

    private MockMultipartFile validAudio() {
        return new MockMultipartFile(
                "audio",
                "audio.mp3",
                "audio/mpeg",
                new byte[]{4, 5, 6});
    }
}
