package github.sarthakdev143.media_factory.controller;

import github.sarthakdev143.media_factory.dto.ApiErrorResponse;
import github.sarthakdev143.media_factory.service.ActiveJobConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new ApiErrorResponse(
                        exception.getCode(),
                        exception.getMessage(),
                        exception.getField()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgumentException(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(
                        "INVALID_REQUEST",
                        exception.getMessage(),
                        null));
    }

    @ExceptionHandler(ActiveJobConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleActiveJobConflict(ActiveJobConflictException exception) {
        String activeJobId = exception.getActiveJob() == null ? null : exception.getActiveJob().jobId();
        String message = activeJobId == null
                ? "A job is already processing."
                : "A job is already processing: " + activeJobId;
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "ACTIVE_JOB_CONFLICT",
                        message,
                        null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception exception) {
        logger.error("Unhandled API exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(
                        "INTERNAL_ERROR",
                        "Unexpected server error. Please try again.",
                        null));
    }
}
