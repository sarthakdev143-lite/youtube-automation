package github.sarthakdev143.media_factory.dto;

public record ApiErrorResponse(
        String code,
        String message,
        String field) {
}
