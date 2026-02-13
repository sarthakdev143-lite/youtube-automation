package github.sarthakdev143.media_factory.model;

import java.util.Locale;

public enum PrivacyStatus {
    PRIVATE,
    UNLISTED,
    PUBLIC;

    public static PrivacyStatus fromInput(String input) {
        if (input == null || input.isBlank()) {
            return PRIVATE;
        }

        try {
            return PrivacyStatus.valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("privacyStatus must be one of PRIVATE, UNLISTED, PUBLIC.");
        }
    }

    public String toApiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
