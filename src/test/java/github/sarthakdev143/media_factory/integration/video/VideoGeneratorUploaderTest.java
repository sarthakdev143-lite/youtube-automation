package github.sarthakdev143.media_factory.integration.video;

import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.ThumbnailSetResponse;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;
import github.sarthakdev143.media_factory.model.PrivacyStatus;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.UploadResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoGeneratorUploaderTest {

    @Mock
    private YouTube youtubeService;

    @Mock
    private YouTube.Videos videos;

    @Mock
    private YouTube.Videos.Insert videosInsert;

    @Mock
    private YouTube.Thumbnails thumbnails;

    @Mock
    private YouTube.Thumbnails.Set thumbnailsSet;

    @Test
    void uploadToYouTubeMapsMetadataAndReturnsVideoId() throws Exception {
        VideoGeneratorUploader uploader = new VideoGeneratorUploader(youtubeService);

        Path videoFile = Files.createTempFile("media-factory-test-video-", ".mp4");
        Files.write(videoFile, new byte[]{1, 2, 3});

        PublishOptions options = new PublishOptions(
                PrivacyStatus.PRIVATE,
                List.of("music", "focus"),
                "22",
                Instant.parse("2026-02-20T18:30:00Z"));

        when(youtubeService.videos()).thenReturn(videos);
        when(videos.insert(anyList(), any(Video.class), any(FileContent.class))).thenReturn(videosInsert);
        Video apiResponse = new Video();
        apiResponse.setId("video-abc123");
        when(videosInsert.execute()).thenReturn(apiResponse);

        UploadResult result = uploader.uploadToYouTube(
                videoFile.toString(),
                "My Title",
                "My Description",
                options);

        assertThat(result.videoId()).isEqualTo("video-abc123");

        ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
        verify(videos).insert(eq(List.of("snippet", "status")), videoCaptor.capture(), any(FileContent.class));

        Video mappedVideo = videoCaptor.getValue();
        VideoSnippet snippet = mappedVideo.getSnippet();
        VideoStatus status = mappedVideo.getStatus();

        assertThat(snippet.getTitle()).isEqualTo("My Title");
        assertThat(snippet.getDescription()).isEqualTo("My Description");
        assertThat(snippet.getTags()).containsExactly("music", "focus");
        assertThat(snippet.getCategoryId()).isEqualTo("22");
        assertThat(status.getPrivacyStatus()).isEqualTo("private");
        assertThat(status.getPublishAt().getValue()).isEqualTo(options.publishAt().toEpochMilli());

        Files.deleteIfExists(videoFile);
    }

    @Test
    void uploadThumbnailInvokesYouTubeThumbnailApi() throws Exception {
        VideoGeneratorUploader uploader = new VideoGeneratorUploader(youtubeService);
        Path thumbnailPath = Files.createTempFile("media-factory-test-thumbnail-", ".png");
        Files.write(thumbnailPath, new byte[]{9, 8, 7});

        when(youtubeService.thumbnails()).thenReturn(thumbnails);
        when(thumbnails.set(eq("video-123"), any(FileContent.class))).thenReturn(thumbnailsSet);
        when(thumbnailsSet.execute()).thenReturn(new ThumbnailSetResponse());

        uploader.uploadThumbnail("video-123", thumbnailPath.toString(), "image/png");

        ArgumentCaptor<FileContent> fileContentCaptor = ArgumentCaptor.forClass(FileContent.class);
        verify(thumbnails).set(eq("video-123"), fileContentCaptor.capture());
        verify(thumbnailsSet).execute();
        assertThat(fileContentCaptor.getValue().getType()).isEqualTo("image/png");

        Files.deleteIfExists(thumbnailPath);
    }

    @Test
    void uploadToYouTubeRetriesWithoutCategoryWhenYouTubeRejectsCategory() throws Exception {
        VideoGeneratorUploader uploader = new VideoGeneratorUploader(youtubeService);
        Path videoFile = Files.createTempFile("media-factory-test-video-", ".mp4");
        Files.write(videoFile, new byte[]{1, 2, 3});

        PublishOptions options = new PublishOptions(
                PrivacyStatus.PRIVATE,
                List.of("music"),
                "999",
                null);

        when(youtubeService.videos()).thenReturn(videos);
        when(videos.insert(anyList(), any(Video.class), any(FileContent.class))).thenReturn(videosInsert);

        GoogleJsonError.ErrorInfo errorInfo = new GoogleJsonError.ErrorInfo();
        errorInfo.setReason("invalidCategoryId");
        errorInfo.setMessage("Invalid category");
        GoogleJsonError error = new GoogleJsonError();
        error.setCode(400);
        error.setMessage("Invalid category");
        error.setErrors(List.of(errorInfo));

        HttpResponseException.Builder responseBuilder = new HttpResponseException.Builder(
                400,
                "Bad Request",
                new HttpHeaders());
        GoogleJsonResponseException invalidCategoryException =
                new GoogleJsonResponseException(responseBuilder, error);

        Video fallbackResponse = new Video();
        fallbackResponse.setId("video-fallback-1");
        when(videosInsert.execute())
                .thenThrow(invalidCategoryException)
                .thenReturn(fallbackResponse);

        UploadResult result = uploader.uploadToYouTube(
                videoFile.toString(),
                "Title",
                "Description",
                options);

        assertThat(result.videoId()).isEqualTo("video-fallback-1");
        assertThat(result.warningMessage()).contains("Invalid categoryId was ignored");

        ArgumentCaptor<Video> requestCaptor = ArgumentCaptor.forClass(Video.class);
        verify(videos, times(2)).insert(eq(List.of("snippet", "status")), requestCaptor.capture(), any(FileContent.class));
        assertThat(requestCaptor.getAllValues().get(0).getSnippet().getCategoryId()).isEqualTo("999");
        assertThat(requestCaptor.getAllValues().get(1).getSnippet().getCategoryId()).isNull();

        Files.deleteIfExists(videoFile);
    }
}
