package github.sarthakdev143.media_factory.integration.video;

import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FfmpegProgressParser {

    private static final Pattern TIME_TOKEN_PATTERN = Pattern.compile("time=(\\d{2,}:\\d{2}:\\d{2}(?:\\.\\d+)?)");

    public OptionalDouble extractEncodedSeconds(String ffmpegLine) {
        if (ffmpegLine == null || ffmpegLine.isBlank()) {
            return OptionalDouble.empty();
        }

        Matcher matcher = TIME_TOKEN_PATTERN.matcher(ffmpegLine);
        if (!matcher.find()) {
            return OptionalDouble.empty();
        }

        String token = matcher.group(1);
        String[] parts = token.split(":");
        if (parts.length != 3) {
            return OptionalDouble.empty();
        }

        double hours = Double.parseDouble(parts[0]);
        double minutes = Double.parseDouble(parts[1]);
        double seconds = Double.parseDouble(parts[2]);

        return OptionalDouble.of(hours * 3600 + minutes * 60 + seconds);
    }
}
