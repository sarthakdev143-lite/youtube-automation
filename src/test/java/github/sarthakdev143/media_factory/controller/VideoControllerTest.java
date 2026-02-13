package github.sarthakdev143.media_factory.controller;

import github.sarthakdev143.media_factory.dto.CompositionCaptionRequest;
import github.sarthakdev143.media_factory.dto.CompositionManifestRequest;
import github.sarthakdev143.media_factory.dto.CompositionSceneRequest;
import github.sarthakdev143.media_factory.dto.CompositionTransitionRequest;
import github.sarthakdev143.media_factory.model.MotionType;
import github.sarthakdev143.media_factory.model.OutputPreset;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.SceneType;
import github.sarthakdev143.media_factory.model.TransitionType;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
import github.sarthakdev143.media_factory.service.VideoProcessingService;
import github.sarthakdev143.media_factory.service.impl.CompositionManifestValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoController.class)
class VideoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VideoProcessingService videoProcessingService;

    @MockitoBean
    private CompositionManifestValidator compositionManifestValidator;

    @Test
    void generateReturnsAcceptedForValidRequest() throws Exception {
        when(videoProcessingService.submitJob(any(), any(), anyInt(), anyString(), anyString(), any(PublishOptions.class), any()))
                .thenReturn("job-123");

        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.state").value("QUEUED"));
    }

    @Test
    void generateReturnsAcceptedForValidPublishingControls() throws Exception {
        when(videoProcessingService.submitJob(any(), any(), anyInt(), anyString(), anyString(), any(PublishOptions.class), any()))
                .thenReturn("job-123");

        String publishAt = Instant.now().plusSeconds(600).toString();
        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .file(validThumbnail())
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description")
                        .param("privacyStatus", "private")
                        .param("tags", "music", "MUSIC", "chill")
                        .param("categoryId", "22")
                        .param("publishAt", publishAt))
                .andExpect(status().isAccepted());

        ArgumentCaptor<PublishOptions> publishOptionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(videoProcessingService).submitJob(
                any(),
                any(),
                eq(60),
                eq("My title"),
                eq("My description"),
                publishOptionsCaptor.capture(),
                any());

        PublishOptions publishOptions = publishOptionsCaptor.getValue();
        assertThat(publishOptions.privacyStatus()).isEqualTo(PrivacyStatus.PRIVATE);
        assertThat(publishOptions.tags()).containsExactly("music", "chill");
        assertThat(publishOptions.categoryId()).isEqualTo("22");
        assertThat(publishOptions.publishAt()).isEqualTo(Instant.parse(publishAt));
    }

    @Test
    void generateReturnsBadRequestForInvalidDuration() throws Exception {
        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .param("duration", "0")
                        .param("title", "My title")
                        .param("description", "My description"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Duration must be between")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void generateReturnsBadRequestForInvalidMimeType() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "image.txt",
                "text/plain",
                "not-an-image".getBytes());

        mockMvc.perform(multipart("/api/video/generate")
                        .file(image)
                        .file(validAudio())
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("image must have a image/* content type.")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void generateReturnsBadRequestForInvalidPrivacyStatus() throws Exception {
        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description")
                        .param("privacyStatus", "friends-only"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("privacyStatus must be one of")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void compositionReturnsAcceptedForValidRequest() throws Exception {
        CompositionManifestRequest normalizedManifest = validCompositionManifest();
        when(compositionManifestValidator.normalizeAndValidate(any(CompositionManifestRequest.class), anyMap()))
                .thenReturn(normalizedManifest);
        when(videoProcessingService.submitCompositionJob(anyMap(), any(), any(), anyString(), anyString(), any(), any()))
                .thenReturn("job-comp-123");

        mockMvc.perform(multipart("/api/video/compositions")
                        .file(validAudio())
                        .file(validCompositionAsset())
                        .param("manifest", validManifestJson())
                        .param("title", "Composition title")
                        .param("description", "Composition description"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-comp-123"))
                .andExpect(jsonPath("$.state").value("QUEUED"));

        ArgumentCaptor<Map<String, org.springframework.web.multipart.MultipartFile>> assetCaptor = ArgumentCaptor.forClass(Map.class);
        verify(videoProcessingService).submitCompositionJob(
                assetCaptor.capture(),
                any(),
                eq(normalizedManifest),
                eq("Composition title"),
                eq("Composition description"),
                any(PublishOptions.class),
                any());
        assertThat(assetCaptor.getValue()).containsKey("scene-1");
    }

    @Test
    void compositionReturnsBadRequestForMalformedManifestJson() throws Exception {
        mockMvc.perform(multipart("/api/video/compositions")
                        .file(validAudio())
                        .file(validCompositionAsset())
                        .param("manifest", "{not-json")
                        .param("title", "Composition title")
                        .param("description", "Composition description"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("manifest must be valid JSON")));

        verifyNoInteractions(videoProcessingService, compositionManifestValidator);
    }

    @Test
    void compositionReturnsBadRequestForMissingAssetReference() throws Exception {
        when(compositionManifestValidator.normalizeAndValidate(any(CompositionManifestRequest.class), anyMap()))
                .thenThrow(new IllegalArgumentException("Missing required file part asset.scene-1"));

        mockMvc.perform(multipart("/api/video/compositions")
                        .file(validAudio())
                        .param("manifest", validManifestJson())
                        .param("title", "Composition title")
                        .param("description", "Composition description"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("Missing required file part")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void compositionReturnsBadRequestForInvalidTransitionConfig() throws Exception {
        when(compositionManifestValidator.normalizeAndValidate(any(CompositionManifestRequest.class), anyMap()))
                .thenThrow(new IllegalArgumentException("The first scene transition must be CUT."));

        mockMvc.perform(multipart("/api/video/compositions")
                        .file(validAudio())
                        .file(validCompositionAsset())
                        .param("manifest", validManifestJson())
                        .param("title", "Composition title")
                        .param("description", "Composition description"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("first scene transition")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void compositionReturnsBadRequestWhenPublishAtWithNonPrivatePrivacy() throws Exception {
        mockMvc.perform(multipart("/api/video/compositions")
                        .file(validAudio())
                        .file(validCompositionAsset())
                        .param("manifest", validManifestJson())
                        .param("title", "Composition title")
                        .param("description", "Composition description")
                        .param("privacyStatus", "PUBLIC")
                        .param("publishAt", Instant.now().plusSeconds(600).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("publishAt can only be used with privacyStatus=PRIVATE")));

        verifyNoInteractions(videoProcessingService, compositionManifestValidator);
    }

    @Test
    void generateReturnsBadRequestForNonUtcPublishAt() throws Exception {
        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description")
                        .param("publishAt", "2026-02-20T18:30:00+05:30"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("publishAt must be an ISO-8601 UTC instant ending with Z.")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void generateReturnsBadRequestForPublishAtTooSoon() throws Exception {
        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description")
                        .param("publishAt", Instant.now().plusSeconds(30).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("at least 5 minutes in the future")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void generateReturnsBadRequestWhenPublishAtWithNonPrivatePrivacy() throws Exception {
        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description")
                        .param("privacyStatus", "PUBLIC")
                        .param("publishAt", Instant.now().plusSeconds(600).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("publishAt can only be used with privacyStatus=PRIVATE")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void generateReturnsBadRequestForMalformedCategoryId() throws Exception {
        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description")
                        .param("categoryId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("categoryId must match")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void generateReturnsBadRequestForInvalidThumbnailMimeType() throws Exception {
        MockMultipartFile thumbnail = new MockMultipartFile(
                "thumbnail",
                "thumbnail.gif",
                "image/gif",
                new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .file(thumbnail)
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("thumbnail must have content type image/jpeg or image/png.")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void getStatusReturnsCurrentJobState() throws Exception {
        VideoJobStatus jobStatus = new VideoJobStatus(
                "job-123",
                VideoJobState.PROCESSING,
                "Generating video and uploading to YouTube.",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:05Z"),
                PrivacyStatus.PRIVATE,
                List.of("music"),
                "10",
                Instant.parse("2026-01-01T01:00:00Z"),
                "video-123",
                "https://www.youtube.com/watch?v=video-123",
                "Thumbnail upload failed");
        when(videoProcessingService.getJobStatus("job-123")).thenReturn(Optional.of(jobStatus));

        mockMvc.perform(get("/api/video/status/job-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.state").value("PROCESSING"))
                .andExpect(jsonPath("$.youtubeVideoUrl").value("https://www.youtube.com/watch?v=video-123"))
                .andExpect(jsonPath("$.warningMessage").value("Thumbnail upload failed"));
    }

    @Test
    void getStatusReturnsNotFoundForUnknownJob() throws Exception {
        when(videoProcessingService.getJobStatus("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/video/status/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Job not found")));
    }

    private CompositionManifestRequest validCompositionManifest() {
        return new CompositionManifestRequest(
                OutputPreset.PORTRAIT_9_16,
                List.of(new CompositionSceneRequest(
                        "scene-1",
                        SceneType.IMAGE,
                        2.0,
                        0.0,
                        null,
                        MotionType.ZOOM_IN,
                        new CompositionCaptionRequest("hello", 0.0, 1.5, github.sarthakdev143.media_factory.model.CaptionPosition.BOTTOM),
                        new CompositionTransitionRequest(TransitionType.CUT, null))));
    }

    private String validManifestJson() {
        return "{" +
                "\"outputPreset\":\"PORTRAIT_9_16\"," +
                "\"scenes\":[{" +
                "\"assetId\":\"scene-1\"," +
                "\"type\":\"IMAGE\"," +
                "\"durationSec\":2.0," +
                "\"motion\":\"ZOOM_IN\"," +
                "\"transition\":{\"type\":\"CUT\"}" +
                "}]}";
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

    private MockMultipartFile validCompositionAsset() {
        return new MockMultipartFile(
                "asset.scene-1",
                "scene.jpg",
                "image/jpeg",
                new byte[]{8, 8, 8});
    }

    private MockMultipartFile validThumbnail() {
        return new MockMultipartFile(
                "thumbnail",
                "thumb.png",
                "image/png",
                new byte[]{9, 8, 7});
    }
}
