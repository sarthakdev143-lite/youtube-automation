package github.sarthakdev143.media_factory.service.impl;

import com.google.api.services.youtube.YouTube;
import github.sarthakdev143.media_factory.dto.CompositionManifestRequest;
import github.sarthakdev143.media_factory.dto.CompositionSceneRequest;
import github.sarthakdev143.media_factory.dto.CompositionTransitionRequest;
import github.sarthakdev143.media_factory.factory.VideoGeneratorUploaderFactory;
import github.sarthakdev143.media_factory.integration.video.VideoGeneratorUploader;
import github.sarthakdev143.media_factory.integration.youtube.YouTubeServiceProvider;
import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.OutputPreset;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.SceneType;
import github.sarthakdev143.media_factory.model.TransitionType;
import github.sarthakdev143.media_factory.model.UploadResult;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
import github.sarthakdev143.media_factory.model.composition.CompositionRenderPlan;
import github.sarthakdev143.media_factory.service.CompositionRenderer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private CompositionRenderer compositionRenderer;

    @Mock
    private VideoGeneratorUploader uploader;

    @Mock
    private YouTube youTubeService;

    private DefaultVideoProcessingService service;

    @BeforeEach
    void setUp() {
        TaskExecutor directExecutor = Runnable::run;
        service = new DefaultVideoProcessingService(
                youTubeServiceProvider,
                uploaderFactory,
                compositionRenderer,
                directExecutor,
                new SimpleMeterRegistry());
    }

    @Test
    void submitJobCompletesSuccessfully() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);
        when(uploader.uploadToYouTube(anyString(), eq("Title"), eq("Description"), any(PublishOptions.class)))
                .thenReturn(new UploadResult("video-123"));

        String jobId = service.submitJob(
                validImage(),
                validAudio(),
                60,
                "Title",
                "Description",
                defaultOptions(),
                null);
        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();

        assertThat(status.state()).isEqualTo(VideoJobState.COMPLETED);
        assertThat(status.youtubeVideoId()).isEqualTo("video-123");
        assertThat(status.youtubeVideoUrl()).isEqualTo("https://www.youtube.com/watch?v=video-123");
        verify(uploader).generateVideo(anyString(), anyString(), eq(60), anyString());
        verify(uploader).uploadToYouTube(anyString(), eq("Title"), eq("Description"), any(PublishOptions.class));
        verifyNoInteractions(compositionRenderer);
    }

    @Test
    void submitJobPassesPublishOptionsToUploader() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);
        when(uploader.uploadToYouTube(anyString(), anyString(), anyString(), any(PublishOptions.class)))
                .thenReturn(new UploadResult("video-456"));

        PublishOptions options = new PublishOptions(
                PrivacyStatus.UNLISTED,
                List.of("music", "focus"),
                "22",
                null);

        String jobId = service.submitJob(validImage(), validAudio(), 120, "Title", "Description", options, null);

        ArgumentCaptor<PublishOptions> optionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(uploader).uploadToYouTube(anyString(), eq("Title"), eq("Description"), optionsCaptor.capture());
        assertThat(optionsCaptor.getValue()).isEqualTo(options);

        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();
        assertThat(status.privacyStatus()).isEqualTo(PrivacyStatus.UNLISTED);
        assertThat(status.tags()).containsExactly("music", "focus");
        assertThat(status.categoryId()).isEqualTo("22");
    }

    @Test
    void submitJobMarksCompletedWithWarningWhenThumbnailUploadFails() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);
        when(uploader.uploadToYouTube(anyString(), anyString(), anyString(), any(PublishOptions.class)))
                .thenReturn(new UploadResult("video-thumb-1"));
        doThrow(new IOException("thumbnail failed"))
                .when(uploader)
                .uploadThumbnail(eq("video-thumb-1"), anyString(), eq("image/png"));

        String jobId = service.submitJob(
                validImage(),
                validAudio(),
                60,
                "Title",
                "Description",
                new PublishOptions(
                        PrivacyStatus.PRIVATE,
                        List.of("tag"),
                        null,
                        Instant.now().plusSeconds(1200)),
                validThumbnail());

        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();
        assertThat(status.state()).isEqualTo(VideoJobState.COMPLETED);
        assertThat(status.warningMessage()).isEqualTo("Video uploaded, but thumbnail upload failed.");
        verify(uploader).uploadThumbnail(eq("video-thumb-1"), anyString(), eq("image/png"));
    }

    @Test
    void submitJobPreservesUploadWarningsFromUploader() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);
        when(uploader.uploadToYouTube(anyString(), anyString(), anyString(), any(PublishOptions.class)))
                .thenReturn(new UploadResult("video-cat-1", "Invalid categoryId was ignored. Video uploaded without category."));

        String jobId = service.submitJob(
                validImage(),
                validAudio(),
                60,
                "Title",
                "Description",
                defaultOptions(),
                null);

        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();
        assertThat(status.state()).isEqualTo(VideoJobState.COMPLETED);
        assertThat(status.warningMessage()).contains("Invalid categoryId was ignored");
    }

    @Test
    void submitJobMarksFailedWhenFfmpegGenerationFails() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);
        doThrow(new RuntimeException("ffmpeg failed"))
                .when(uploader)
                .generateVideo(anyString(), anyString(), anyInt(), anyString());

        String jobId = service.submitJob(
                validImage(),
                validAudio(),
                60,
                "Title",
                "Description",
                defaultOptions(),
                null);
        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();

        assertThat(status.state()).isEqualTo(VideoJobState.FAILED);
    }

    @Test
    void submitJobMarksFailedWhenOAuthInitFails() throws Exception {
        when(youTubeServiceProvider.getService()).thenThrow(new IOException("oauth failed"));

        String jobId = service.submitJob(
                validImage(),
                validAudio(),
                60,
                "Title",
                "Description",
                defaultOptions(),
                null);
        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();

        assertThat(status.state()).isEqualTo(VideoJobState.FAILED);
        verifyNoInteractions(uploaderFactory);
    }

    @Test
    void submitCompositionJobCompletesSuccessfully() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);
        when(uploader.uploadToYouTube(anyString(), eq("Composition title"), eq("Composition description"), any(PublishOptions.class)))
                .thenReturn(new UploadResult("video-comp-123"));

        String jobId = service.submitCompositionJob(
                validAssets(),
                validAudio(),
                validCompositionManifest(),
                "Composition title",
                "Composition description",
                defaultOptions(),
                null);

        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();
        assertThat(status.state()).isEqualTo(VideoJobState.COMPLETED);
        assertThat(status.youtubeVideoId()).isEqualTo("video-comp-123");

        ArgumentCaptor<CompositionRenderPlan> renderPlanCaptor = ArgumentCaptor.forClass(CompositionRenderPlan.class);
        verify(compositionRenderer).renderComposition(renderPlanCaptor.capture(), any(Path.class));
        CompositionRenderPlan renderPlan = renderPlanCaptor.getValue();
        assertThat(renderPlan.outputPreset()).isEqualTo(OutputPreset.PORTRAIT_9_16);
        assertThat(renderPlan.scenes()).hasSize(1);
        assertThat(renderPlan.assetPaths()).containsKey("scene-1");
    }

    @Test
    void submitCompositionJobMarksFailedWhenRenderFails() throws Exception {
        doThrow(new IOException("render failed"))
                .when(compositionRenderer)
                .renderComposition(any(CompositionRenderPlan.class), any(Path.class));

        String jobId = service.submitCompositionJob(
                validAssets(),
                validAudio(),
                validCompositionManifest(),
                "Composition title",
                "Composition description",
                defaultOptions(),
                null);

        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();
        assertThat(status.state()).isEqualTo(VideoJobState.FAILED);
        verifyNoInteractions(uploaderFactory);
    }

    @Test
    void submitCompositionJobMarksCompletedWithWarningWhenThumbnailUploadFails() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);
        when(uploader.uploadToYouTube(anyString(), anyString(), anyString(), any(PublishOptions.class)))
                .thenReturn(new UploadResult("video-thumb-2"));
        doThrow(new IOException("thumbnail failed"))
                .when(uploader)
                .uploadThumbnail(eq("video-thumb-2"), anyString(), eq("image/png"));

        String jobId = service.submitCompositionJob(
                validAssets(),
                validAudio(),
                validCompositionManifest(),
                "Composition title",
                "Composition description",
                defaultOptions(),
                validThumbnail());

        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();
        assertThat(status.state()).isEqualTo(VideoJobState.COMPLETED);
        assertThat(status.warningMessage()).isEqualTo("Video uploaded, but thumbnail upload failed.");
    }

    @Test
    void submitCompositionJobMarksFailedWhenUploadFails() throws Exception {
        when(youTubeServiceProvider.getService()).thenReturn(youTubeService);
        when(uploaderFactory.create(youTubeService)).thenReturn(uploader);
        doThrow(new RuntimeException("upload failed"))
                .when(uploader)
                .uploadToYouTube(anyString(), anyString(), anyString(), any(PublishOptions.class));

        String jobId = service.submitCompositionJob(
                validAssets(),
                validAudio(),
                validCompositionManifest(),
                "Composition title",
                "Composition description",
                defaultOptions(),
                null);

        VideoJobStatus status = service.getJobStatus(jobId).orElseThrow();
        assertThat(status.state()).isEqualTo(VideoJobState.FAILED);
    }

    private CompositionManifestRequest validCompositionManifest() {
        return new CompositionManifestRequest(
                OutputPreset.PORTRAIT_9_16,
                List.of(new CompositionSceneRequest(
                        "scene-1",
                        SceneType.IMAGE,
                        2.5,
                        0.0,
                        null,
                        MotionType.ZOOM_IN,
                        null,
                        new CompositionTransitionRequest(TransitionType.CUT, null))));
    }

    private Map<String, MultipartFile> validAssets() {
        Map<String, MultipartFile> assets = new LinkedHashMap<>();
        assets.put("scene-1", new MockMultipartFile(
                "asset.scene-1",
                "scene.jpg",
                "image/jpeg",
                new byte[]{10, 11, 12}));
        return assets;
    }

    private PublishOptions defaultOptions() {
        return new PublishOptions(PrivacyStatus.PRIVATE, List.of(), null, null);
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

    private MockMultipartFile validThumbnail() {
        return new MockMultipartFile(
                "thumbnail",
                "thumbnail.png",
                "image/png",
                new byte[]{7, 8, 9});
    }
}
