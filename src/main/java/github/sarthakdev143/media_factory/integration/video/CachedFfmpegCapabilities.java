package github.sarthakdev143.media_factory.integration.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CachedFfmpegCapabilities implements FfmpegCapabilities {

    private static final Logger logger = LoggerFactory.getLogger(CachedFfmpegCapabilities.class);

    private static final String FFMPEG_PATH_ENV = "FFMPEG_PATH";
    private static final String DEFAULT_FFMPEG_BINARY = "ffmpeg";
    private static final int DETECTION_TIMEOUT_SECONDS = 15;

    private final String ffmpegBinary;
    private final AtomicBoolean nvencAvailable = new AtomicBoolean(false);

    public CachedFfmpegCapabilities() {
        this.ffmpegBinary = resolveFfmpegBinary();
        this.nvencAvailable.set(detectNvencSupport(this.ffmpegBinary));
    }

    @Override
    public String ffmpegBinary() {
        return ffmpegBinary;
    }

    @Override
    public boolean nvencAvailable() {
        return nvencAvailable.get();
    }

    private boolean detectNvencSupport(String ffmpegExecutable) {
        Process process = null;
        try {
            process = new ProcessBuilder(ffmpegExecutable, "-encoders")
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            }

            boolean completed = process.waitFor(DETECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                logger.warn("[FFMPEG INIT] Encoder detection timed out. Falling back to CPU.");
                return false;
            }

            boolean available = output.toString().toLowerCase(Locale.ROOT).contains("h264_nvenc");
            logger.info(
                    "[FFMPEG INIT] binary={} nvencAvailable={}",
                    ffmpegExecutable,
                    available);
            return available;
        } catch (Exception exception) {
            logger.warn("[FFMPEG INIT] Failed to detect encoders. Falling back to CPU.", exception);
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
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
