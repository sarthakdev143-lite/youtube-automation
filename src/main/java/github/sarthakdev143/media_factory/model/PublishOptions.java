package github.sarthakdev143.media_factory.model;

import java.time.Instant;
import java.util.List;

public record PublishOptions(
        PrivacyStatus privacyStatus,
        List<String> tags,
        String categoryId,
        Instant publishAt) {

    public PublishOptions {
        privacyStatus = privacyStatus == null ? PrivacyStatus.PRIVATE : privacyStatus;
        tags = tags == null ? List.of() : List.copyOf(tags);
        categoryId = categoryId == null || categoryId.isBlank() ? null : categoryId;
    }

    public boolean isScheduled() {
        return publishAt != null;
    }
}
