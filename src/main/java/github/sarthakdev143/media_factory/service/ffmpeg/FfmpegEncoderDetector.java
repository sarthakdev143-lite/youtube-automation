package github.sarthakdev143.media_factory.service.ffmpeg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class FfmpegEncoderDetector implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(FfmpegEncoderDetector.class);
    private static final String FFMPEG_PATH_ENV = "FFMPEG_PATH";
    private static final String DEFAULT_FFMPEG_BINARY = "ffmpeg";
    private static final Duration DETECTION_TIMEOUT = Duration.ofSeconds(20);

    public enum EncoderType {
        NVENC,
        QSV,
        LIBX264
    }

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private volatile boolean hasNvenc;
    private volatile boolean hasQsv;

    @Override
    public void run(ApplicationArguments args) {
        detectCapabilitiesIfNeeded();
    }

    public void detectCapabilitiesIfNeeded() {
        if (initialized.compareAndSet(false, true)) {
            detectCapabilities();
        }
    }

    public boolean hasNvenc() {
        detectCapabilitiesIfNeeded();
        return hasNvenc;
    }

    public boolean hasQsv() {
        detectCapabilitiesIfNeeded();
        return hasQsv;
    }

    public EncoderType selectPreferredEncoder() {
        detectCapabilitiesIfNeeded();
        if (hasNvenc) {
            return EncoderType.NVENC;
        }
        if (hasQsv) {
            return EncoderType.QSV;
        }
        return EncoderType.LIBX264;
    }

    private void detectCapabilities() {
        String ffmpegBinary = resolveFfmpegBinary();
        List<String> outputLines = new ArrayList<>();

        try {
            Process process = new ProcessBuilder(ffmpegBinary, "-encoders")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLines.add(line.toLowerCase(Locale.ROOT));
                }
            }

            boolean finished = process.waitFor(DETECTION_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                logger.warn("FFmpeg encoder detection failed, falling back to libx264.");
                hasNvenc = false;
                hasQsv = false;
                return;
            }

            hasNvenc = outputLines.stream().anyMatch(line -> line.contains("h264_nvenc"));
            hasQsv = outputLines.stream().anyMatch(line -> line.contains("h264_qsv"));
            logger.info(
                    "[ENCODER_DETECTED hasNvenc={} hasQsv={} preferred={}]",
                    hasNvenc,
                    hasQsv,
                    selectPreferredEncoder());
        } catch (Exception e) {
            hasNvenc = false;
            hasQsv = false;
            logger.warn("Unable to detect FFmpeg encoders; libx264 fallback will be used.", e);
        }
    }

    private String resolveFfmpegBinary() {
        String configuredPath = System.getenv(FFMPEG_PATH_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return configuredPath;
        }
        return DEFAULT_FFMPEG_BINARY;
    }
}
