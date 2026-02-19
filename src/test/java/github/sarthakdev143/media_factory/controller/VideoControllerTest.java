package github.sarthakdev143.media_factory.controller;

import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.VideoJobProgressReport;
import github.sarthakdev143.media_factory.model.VideoJobStage;
import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
import github.sarthakdev143.media_factory.service.VideoProcessingService;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VideoController.class)
class VideoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VideoProcessingService videoProcessingService;

    @Test
    void generateReturnsAcceptedForValidRequest() throws Exception {
        when(videoProcessingService.submitJob(any(), any(), anyInt(), anyInt(), anyString(), anyString(), any(PublishOptions.class), any()))
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

        ArgumentCaptor<PublishOptions> publishOptionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(videoProcessingService).submitJob(
                any(),
                any(),
                eq(60),
                eq(40),
                eq("My title"),
                eq("My description"),
                publishOptionsCaptor.capture(),
                any());
        assertThat(publishOptionsCaptor.getValue().categoryId()).isEqualTo("10");
    }

    @Test
    void generateReturnsAcceptedForValidPublishingControls() throws Exception {
        when(videoProcessingService.submitJob(any(), any(), anyInt(), anyInt(), anyString(), anyString(), any(PublishOptions.class), any()))
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
                        .param("vignetteStrength", "70")
                        .param("tags", "music", "MUSIC", "chill")
                        .param("categoryId", "22")
                        .param("publishAt", publishAt))
                .andExpect(status().isAccepted());

        ArgumentCaptor<PublishOptions> publishOptionsCaptor = ArgumentCaptor.forClass(PublishOptions.class);
        verify(videoProcessingService).submitJob(
                any(),
                any(),
                eq(60),
                eq(70),
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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("Duration must be between")))
                .andExpect(jsonPath("$.field").value(nullValue()));

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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("image must have a image/* content type.")));

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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("privacyStatus must be one of")));

        verifyNoInteractions(videoProcessingService);
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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("publishAt must be an ISO-8601 UTC instant ending with Z.")));

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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("at least 5 minutes in the future")));

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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("publishAt can only be used with privacyStatus=PRIVATE")));

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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("categoryId must match")));

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
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("thumbnail must have content type image/jpeg or image/png.")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void generateReturnsBadRequestForInvalidVignetteStrength() throws Exception {
        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .param("duration", "60")
                        .param("vignetteStrength", "101")
                        .param("title", "My title")
                        .param("description", "My description"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("vignetteStrength must be between")));

        verifyNoInteractions(videoProcessingService);
    }

    @Test
    void generateReturnsBadRequestForOversizedThumbnail() throws Exception {
        MockMultipartFile oversizedThumbnail = new MockMultipartFile(
                "thumbnail",
                "thumbnail.jpg",
                "image/jpeg",
                new byte[2_097_153]);

        mockMvc.perform(multipart("/api/video/generate")
                        .file(validImage())
                        .file(validAudio())
                        .file(oversizedThumbnail)
                        .param("duration", "60")
                        .param("title", "My title")
                        .param("description", "My description"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message", containsString("thumbnail must be <= 2MB")));

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
                new VideoJobProgressReport(
                        VideoJobStage.UPLOADING_VIDEO,
                        "Uploading generated video to YouTube.",
                        87,
                        100,
                        37,
                        "MEDIA_IN_PROGRESS"),
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
                .andExpect(jsonPath("$.progressReport.stage").value("UPLOADING_VIDEO"))
                .andExpect(jsonPath("$.progressReport.uploadPercent").value(37))
                .andExpect(jsonPath("$.youtubeVideoUrl").value("https://www.youtube.com/watch?v=video-123"))
                .andExpect(jsonPath("$.warningMessage").value("Thumbnail upload failed"));
    }

    @Test
    void getStatusReturnsNotFoundForUnknownJob() throws Exception {
        when(videoProcessingService.getJobStatus("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/video/status/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"))
                .andExpect(jsonPath("$.field").value("jobId"))
                .andExpect(jsonPath("$.message", containsString("Job not found")));
    }

    @Test
    void cancelReturnsUpdatedJobStatus() throws Exception {
        VideoJobStatus cancelledStatus = new VideoJobStatus(
                "job-123",
                VideoJobState.FAILED,
                "Cancelled by user.",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:05Z"),
                PrivacyStatus.PRIVATE,
                List.of("music"),
                "10",
                null,
                null,
                null,
                null);
        when(videoProcessingService.cancelJob("job-123")).thenReturn(cancelledStatus);

        mockMvc.perform(post("/api/video/status/job-123/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.message").value("Cancelled by user."));
    }

    @Test
    void retryReturnsAcceptedWithNewJobId() throws Exception {
        when(videoProcessingService.retryJob("job-123")).thenReturn("job-456");

        mockMvc.perform(post("/api/video/status/job-123/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("job-456"))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.message", containsString("Retry job accepted")));
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
                "thumb.png",
                "image/png",
                new byte[]{9, 8, 7});
    }
}
