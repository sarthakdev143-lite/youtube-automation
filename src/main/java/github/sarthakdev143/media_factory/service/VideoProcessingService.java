package github.sarthakdev143.media_factory.service;

import github.sarthakdev143.media_factory.dto.CompositionManifestRequest;
import github.sarthakdev143.media_factory.model.PublishOptions;
import github.sarthakdev143.media_factory.model.VideoJobStatus;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

public interface VideoProcessingService {

    String submitJob(
            MultipartFile image,
            MultipartFile audio,
            int durationSeconds,
            String title,
            String description,
            PublishOptions publishOptions,
            MultipartFile thumbnail) throws IOException;

    String submitCompositionJob(
            Map<String, MultipartFile> assets,
            MultipartFile audio,
            CompositionManifestRequest manifest,
            String title,
            String description,
            PublishOptions publishOptions,
            MultipartFile thumbnail) throws IOException;

    Optional<VideoJobStatus> getJobStatus(String jobId);
}
