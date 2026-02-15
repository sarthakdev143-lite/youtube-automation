package github.sarthakdev143.media_factory.service.ffmpeg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FfmpegService {

    private static final Logger logger = LoggerFactory.getLogger(FfmpegService.class);
    private static final String FFMPEG_PATH_ENV = "FFMPEG_PATH";
    private static final String DEFAULT_FFMPEG_BINARY = "ffmpeg";
    private static final Pattern TIME_WITH_HOURS = Pattern.compile("time=(\\d+):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final Pattern TIME_WITH_MINUTES = Pattern.compile("time=(\\d+):(\\d{2}(?:\\.\\d+)?)");
    private static final int MAX_DURATION_SECONDS = 21_600;
    private static final int STDERR_BUFFER_LINES = 80;
    private static final int STDERR_SNIPPET_LINES = 8;
    private static final long COMMAND_TIMEOUT_MIN_SECONDS = 120L;
    private static final long COMMAND_TIMEOUT_MULTIPLIER = 4L;
    private static final long COMMAND_TIMEOUT_GRACE_SECONDS = 60L;

    private enum ImageInputRateMode {
        FRAMERATE_OPTION,
        INPUT_R_OPTION
    }

    private final FfmpegEncoderDetector encoderDetector;

    public record EncodingResult(FfmpegEncoderDetector.EncoderType encoderType, int durationSeconds) {
    }

    public FfmpegService(FfmpegEncoderDetector encoderDetector) {
        this.encoderDetector = encoderDetector;
    }

    public List<String> buildCommand(
            Path imagePath,
            Path audioPath,
            Path outputPath,
            int requestedDurationSeconds,
            int outputFrameRate,
            FfmpegEncoderDetector.EncoderType encoderType) {
        return buildCommand(
                imagePath,
                audioPath,
                outputPath,
                requestedDurationSeconds,
                outputFrameRate,
                encoderType,
                ImageInputRateMode.FRAMERATE_OPTION);
    }

    private List<String> buildCommand(
            Path imagePath,
            Path audioPath,
            Path outputPath,
            int requestedDurationSeconds,
            int outputFrameRate,
            FfmpegEncoderDetector.EncoderType encoderType,
            ImageInputRateMode imageInputRateMode) {

        int boundedDurationSeconds = Math.max(1, Math.min(MAX_DURATION_SECONDS, requestedDurationSeconds));
        int boundedFrameRate = Math.max(1, outputFrameRate);

        List<String> command = new ArrayList<>();
        command.add(resolveFfmpegBinary());
        command.add("-hide_banner");
        command.add("-y");
        command.add("-nostdin");
        command.add("-loop");
        command.add("1");
        if (imageInputRateMode == ImageInputRateMode.FRAMERATE_OPTION) {
            command.add("-framerate");
            command.add("1");
        } else {
            command.add("-r");
            command.add("1");
        }
        command.add("-i");
        command.add(imagePath.toAbsolutePath().toString());
        command.add("-stream_loop");
        command.add("-1");
        command.add("-i");
        command.add(audioPath.toAbsolutePath().toString());
        command.add("-t");
        command.add(String.valueOf(boundedDurationSeconds));
        command.add("-r");
        command.add(String.valueOf(boundedFrameRate));

        switch (encoderType) {
            case NVENC -> Collections.addAll(
                    command,
                    "-c:v", "h264_nvenc",
                    "-preset", "p4",
                    "-tune", "hq",
                    "-cq", "23",
                    "-b:v", "0",
                    "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart");
            case QSV -> Collections.addAll(
                    command,
                    "-c:v", "h264_qsv",
                    "-global_quality", "23",
                    "-look_ahead", "1",
                    "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart");
            case LIBX264 -> Collections.addAll(
                    command,
                    "-c:v", "libx264",
                    "-preset", "veryfast",
                    "-crf", "23",
                    "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart");
            default -> throw new IllegalArgumentException("Unsupported encoder type: " + encoderType);
        }

        Collections.addAll(command, "-c:a", "aac", "-shortest", outputPath.toAbsolutePath().toString());
        return command;
    }

    public EncodingResult runEncoding(
            Path imagePath,
            Path audioPath,
            Path outputPath,
            int requestedDurationSeconds,
            int outputFrameRate,
            Consumer<Integer> progressCallback,
            Consumer<Process> processCallback,
            Path ffmpegLogPath) throws IOException, InterruptedException {

        int boundedDurationSeconds = Math.max(1, Math.min(MAX_DURATION_SECONDS, requestedDurationSeconds));
        FfmpegEncoderDetector.EncoderType preferredEncoder = encoderDetector.selectPreferredEncoder();
        List<FfmpegEncoderDetector.EncoderType> encodersToTry = encoderFallbackOrder(preferredEncoder);
        boolean requiresInputRateCompatibility = false;

        IOException lastException = null;
        for (FfmpegEncoderDetector.EncoderType encoderType : encodersToTry) {
            try {
                List<String> command = buildCommand(
                        imagePath,
                        audioPath,
                        outputPath,
                        boundedDurationSeconds,
                        outputFrameRate,
                        encoderType,
                        requiresInputRateCompatibility ? ImageInputRateMode.INPUT_R_OPTION : ImageInputRateMode.FRAMERATE_OPTION);
                runCommand(command, boundedDurationSeconds, progressCallback, processCallback, ffmpegLogPath);
                return new EncodingResult(encoderType, boundedDurationSeconds);
            } catch (IOException ex) {
                if (!requiresInputRateCompatibility && isUnsupportedFramerateOption(ex)) {
                    requiresInputRateCompatibility = true;
                    logger.warn(
                            "FFmpeg does not accept -framerate for this image input. Retrying encoder {} with compatibility input rate flag.",
                            encoderType);
                    try {
                        List<String> compatibilityCommand = buildCommand(
                                imagePath,
                                audioPath,
                                outputPath,
                                boundedDurationSeconds,
                                outputFrameRate,
                                encoderType,
                                ImageInputRateMode.INPUT_R_OPTION);
                        runCommand(
                                compatibilityCommand,
                                boundedDurationSeconds,
                                progressCallback,
                                processCallback,
                                ffmpegLogPath);
                        return new EncodingResult(encoderType, boundedDurationSeconds);
                    } catch (IOException compatibilityException) {
                        lastException = compatibilityException;
                        logger.warn(
                                "FFmpeg encoding failed with encoder {}, trying fallback if available.",
                                encoderType,
                                compatibilityException);
                        continue;
                    }
                }
                lastException = ex;
                logger.warn("FFmpeg encoding failed with encoder {}, trying fallback if available.", encoderType, ex);
            }
        }

        throw new IOException("FFmpeg failed for all encoder options.", lastException);
    }

    public OptionalDouble parseTimeSeconds(String ffmpegLine) {
        if (ffmpegLine == null || ffmpegLine.isBlank()) {
            return OptionalDouble.empty();
        }

        Matcher hourMatcher = TIME_WITH_HOURS.matcher(ffmpegLine);
        if (hourMatcher.find()) {
            double hours = Double.parseDouble(hourMatcher.group(1));
            double minutes = Double.parseDouble(hourMatcher.group(2));
            double seconds = Double.parseDouble(hourMatcher.group(3));
            return OptionalDouble.of(hours * 3600 + minutes * 60 + seconds);
        }

        Matcher minuteMatcher = TIME_WITH_MINUTES.matcher(ffmpegLine);
        if (minuteMatcher.find()) {
            double minutes = Double.parseDouble(minuteMatcher.group(1));
            double seconds = Double.parseDouble(minuteMatcher.group(2));
            return OptionalDouble.of(minutes * 60 + seconds);
        }

        return OptionalDouble.empty();
    }

    private List<FfmpegEncoderDetector.EncoderType> encoderFallbackOrder(
            FfmpegEncoderDetector.EncoderType preferred) {
        List<FfmpegEncoderDetector.EncoderType> order = new ArrayList<>();
        order.add(preferred);
        if (!order.contains(FfmpegEncoderDetector.EncoderType.NVENC) && encoderDetector.hasNvenc()) {
            order.add(FfmpegEncoderDetector.EncoderType.NVENC);
        }
        if (!order.contains(FfmpegEncoderDetector.EncoderType.QSV) && encoderDetector.hasQsv()) {
            order.add(FfmpegEncoderDetector.EncoderType.QSV);
        }
        if (!order.contains(FfmpegEncoderDetector.EncoderType.LIBX264)) {
            order.add(FfmpegEncoderDetector.EncoderType.LIBX264);
        }
        return order;
    }

    private void runCommand(
            List<String> command,
            int durationSeconds,
            Consumer<Integer> progressCallback,
            Consumer<Process> processCallback,
            Path ffmpegLogPath) throws IOException, InterruptedException {

        Files.createDirectories(ffmpegLogPath.getParent());
        List<String> stderrLines = Collections.synchronizedList(new ArrayList<>());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(false);
        Process process = processBuilder.start();
        processCallback.accept(process);

        ExecutorService streamExecutor = Executors.newFixedThreadPool(2);
        Future<?> stdoutDrain = streamExecutor.submit(() -> drainStdout(process));
        Future<?> stderrParser = streamExecutor.submit(() -> parseStderr(
                process,
                durationSeconds,
                progressCallback,
                ffmpegLogPath,
                stderrLines));

        long timeoutSeconds = resolveCommandTimeoutSeconds(durationSeconds);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            waitForFuture(stdoutDrain);
            waitForFuture(stderrParser);
            streamExecutor.shutdown();
            streamExecutor.awaitTermination(5, TimeUnit.SECONDS);
            throw new IOException(
                    "FFmpeg timed out after "
                            + timeoutSeconds
                            + " seconds for command: "
                            + String.join(" ", command)
                            + ". stderr tail: "
                            + stderrTail(stderrLines));
        }

        int exitCode = process.exitValue();
        waitForFuture(stdoutDrain);
        waitForFuture(stderrParser);
        streamExecutor.shutdown();
        streamExecutor.awaitTermination(5, TimeUnit.SECONDS);

        if (exitCode != 0) {
            long unsignedExitCode = Integer.toUnsignedLong(exitCode);
            throw new IOException(
                    "FFmpeg failed with exit code "
                            + exitCode
                            + " (unsigned "
                            + unsignedExitCode
                            + ") for command: "
                            + String.join(" ", command)
                            + ". stderr tail: "
                            + stderrTail(stderrLines));
        }
    }

    private void parseStderr(
            Process process,
            int durationSeconds,
            Consumer<Integer> progressCallback,
            Path ffmpegLogPath,
            List<String> stderrLines) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
             BufferedWriter logWriter = Files.newBufferedWriter(
                     ffmpegLogPath,
                     StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.APPEND,
                     StandardOpenOption.WRITE)) {

            logWriter.write("[" + Instant.now() + "] FFmpeg stderr stream opened");
            logWriter.newLine();
            String line;
            while ((line = reader.readLine()) != null) {
                logWriter.write(line);
                logWriter.newLine();
                pushStderrLine(stderrLines, line);

                OptionalDouble timeSeconds = parseTimeSeconds(line);
                if (timeSeconds.isPresent()) {
                    int percent = toProgressPercent(timeSeconds.getAsDouble(), durationSeconds);
                    progressCallback.accept(percent);
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException("Unable to parse FFmpeg stderr output.", ex);
        }
    }

    private void drainStdout(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // FFmpeg writes progress on stderr; stdout is drained to avoid blocking.
            }
        } catch (Exception ex) {
            logger.debug("Ignoring FFmpeg stdout drain failure.", ex);
        }
    }

    private void waitForFuture(Future<?> future) throws IOException, InterruptedException {
        try {
            future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } catch (Exception ex) {
            throw new IOException("FFmpeg stream processing failed.", ex);
        }
    }

    private int toProgressPercent(double timeSeconds, int durationSeconds) {
        if (durationSeconds <= 0) {
            return 0;
        }
        int percent = (int) Math.round((timeSeconds / durationSeconds) * 100.0d);
        if (percent < 0) {
            return 0;
        }
        return Math.min(percent, 100);
    }

    private void pushStderrLine(List<String> lines, String line) {
        if (line == null) {
            return;
        }
        synchronized (lines) {
            if (lines.size() >= STDERR_BUFFER_LINES) {
                lines.remove(0);
            }
            lines.add(line);
        }
    }

    private boolean isUnsupportedFramerateOption(IOException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("option framerate not found");
    }

    private String stderrTail(List<String> stderrLines) {
        if (stderrLines == null || stderrLines.isEmpty()) {
            return "<empty>";
        }
        List<String> snapshot;
        synchronized (stderrLines) {
            snapshot = new ArrayList<>(stderrLines);
        }
        int fromIndex = Math.max(0, snapshot.size() - STDERR_SNIPPET_LINES);
        return String.join(" | ", snapshot.subList(fromIndex, snapshot.size()));
    }

    private long resolveCommandTimeoutSeconds(int durationSeconds) {
        long scaledDuration = Math.max(1, durationSeconds) * COMMAND_TIMEOUT_MULTIPLIER;
        return Math.max(COMMAND_TIMEOUT_MIN_SECONDS, scaledDuration + COMMAND_TIMEOUT_GRACE_SECONDS);
    }

    private String resolveFfmpegBinary() {
        String configuredPath = System.getenv(FFMPEG_PATH_ENV);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return configuredPath;
        }
        return DEFAULT_FFMPEG_BINARY;
    }
}
