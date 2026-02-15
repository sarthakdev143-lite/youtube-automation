package github.sarthakdev143.media_factory.service.impl;

import github.sarthakdev143.media_factory.model.VideoJobStatus;
import github.sarthakdev143.media_factory.persistence.entity.JobEntity;

import java.util.Arrays;
import java.util.List;

final class JobStatusMapper {

    private JobStatusMapper() {
    }

    static VideoJobStatus toStatus(JobEntity entity) {
        return new VideoJobStatus(
                entity.getId().toString(),
                entity.getState(),
                entity.getMessage(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getPrivacyStatus(),
                parseTags(entity.getTagsCsv()),
                entity.getCategoryId(),
                entity.getScheduledPublishTime(),
                entity.getYoutubeVideoId(),
                entity.getYoutubeVideoUrl(),
                entity.getWarningMessage(),
                entity.getProgressPercent());
    }

    static String toTagsCsv(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return String.join(",", tags);
    }

    private static List<String> parseTags(String tagsCsv) {
        if (tagsCsv == null || tagsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tagsCsv.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isEmpty())
                .toList();
    }
}
