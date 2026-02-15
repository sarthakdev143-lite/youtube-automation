package github.sarthakdev143.media_factory.integration.video;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

@Component
public class FfmpegProcessRunner {

    private static final Logger logger = LoggerFactory.getLogger(FfmpegProcessRunner.class);

    private static final int MAX_ERROR_OUTPUT_CHARS = 20_000;
    private static final long PROGRESS_UPDATE_INTERVAL_MILLIS = 2_000L;

    private final FfmpegProgressParser progressParser = new FfmpegProgressParser();
    private final AtomicReference<ActiveProcess> activeProcess = new AtomicReference<>();

    public void runCommand(
            String jobId,
            String stage,
            List<String> command,
            double expectedDurationSeconds,
            Path outputPath,
            IntConsumer progressConsumer) throws IOException, InterruptedException {
        logger.info("[FFMPEG START] jobId={} stage={} command={}", jobId, stage, String.join(" ", command));

        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        ActiveProcess processInfo = new ActiveProcess(jobId, stage, outputPath, process);

        if (!activeProcess.compareAndSet(null, processInfo)) {
            process.destroyForcibly();
            throw new IOException("Another FFmpeg process is already registered as active.");
        }

        StringBuilder errorOutput = new StringBuilder();
        try (BufferedReader stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            long lastProgressUpdateAt = 0L;
            int lastReportedProgress = -1;

            String line;
            while ((line = stderrReader.readLine()) != null) {
                appendErrorLine(errorOutput, line);
                if (progressConsumer == null || expectedDurationSeconds <= 0.0) {
                    continue;
                }

                var encodedSeconds = progressParser.extractEncodedSeconds(line);
                if (encodedSeconds.isEmpty()) {
                    continue;
                }

                int progress = toProgressPercent(encodedSeconds.getAsDouble(), expectedDurationSeconds);
                long now = System.currentTimeMillis();
                boolean intervalElapsed = now - lastProgressUpdateAt >= PROGRESS_UPDATE_INTERVAL_MILLIS;
                boolean advanced = progress > lastReportedProgress;
                if (intervalElapsed && advanced) {
                    progressConsumer.accept(progress);
                    lastReportedProgress = progress;
                    lastProgressUpdateAt = now;
                }
            }
        } finally {
            activeProcess.compareAndSet(processInfo, null);
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("FFmpeg stage " + stage + " failed with exit code " + exitCode + ". " + errorOutput);
        }

        if (progressConsumer != null) {
            progressConsumer.accept(100);
        }
        logger.info("[FFMPEG COMPLETE] jobId={} stage={} exitCode={}", jobId, stage, exitCode);
    }

    public Optional<ActiveProcessDetails> stopActiveProcess() {
        ActiveProcess processInfo = activeProcess.getAndSet(null);
        if (processInfo == null) {
            return Optional.empty();
        }

        Process process = processInfo.process();
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        logger.warn("[FFMPEG STOPPED] jobId={} stage={}", processInfo.jobId(), processInfo.stage());
        return Optional.of(new ActiveProcessDetails(
                processInfo.jobId(),
                processInfo.stage(),
                processInfo.outputPath()));
    }

    public boolean isNvencFailure(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null) {
            return false;
        }

        String message = throwable.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("h264_nvenc")
                || message.contains("unknown encoder")
                || message.contains("no nvenc capable devices found")
                || message.contains("cannot load nvcuda")
                || message.contains("driver does not support");
    }

    private int toProgressPercent(double encodedSeconds, double expectedDurationSeconds) {
        double raw = (encodedSeconds / expectedDurationSeconds) * 100.0;
        int progress = (int) Math.floor(raw);
        if (progress < 0) {
            return 0;
        }
        return Math.min(progress, 99);
    }

    private void appendErrorLine(StringBuilder errorOutput, String line) {
        if (errorOutput.length() > MAX_ERROR_OUTPUT_CHARS) {
            int trimTo = errorOutput.length() - MAX_ERROR_OUTPUT_CHARS / 2;
            errorOutput.delete(0, trimTo);
        }
        errorOutput.append(line).append(System.lineSeparator());
    }

    private record ActiveProcess(String jobId, String stage, Path outputPath, Process process) {
    }

    public record ActiveProcessDetails(String jobId, String stage, Path outputPath) {
    }
}
