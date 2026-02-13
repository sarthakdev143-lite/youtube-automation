package github.sarthakdev143.media_factory.controller;

import github.sarthakdev143.media_factory.model.VideoJobState;
import github.sarthakdev143.media_factory.model.VideoJobStatus;
import github.sarthakdev143.media_factory.service.VideoProcessingService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Test
    void generateReturnsAcceptedForValidRequest() throws Exception {
        when(videoProcessingService.submitJob(any(), any(), anyInt(), anyString(), anyString()))
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
    void getStatusReturnsCurrentJobState() throws Exception {
        VideoJobStatus jobStatus = new VideoJobStatus(
                "job-123",
                VideoJobState.PROCESSING,
                "Generating video and uploading to YouTube.",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:05Z"));
        when(videoProcessingService.getJobStatus("job-123")).thenReturn(Optional.of(jobStatus));

        mockMvc.perform(get("/api/video/status/job-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-123"))
                .andExpect(jsonPath("$.state").value("PROCESSING"));
    }

    @Test
    void getStatusReturnsNotFoundForUnknownJob() throws Exception {
        when(videoProcessingService.getJobStatus("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/video/status/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Job not found")));
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
