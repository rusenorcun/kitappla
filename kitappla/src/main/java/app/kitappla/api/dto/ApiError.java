package app.kitappla.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String error,
        String message,
        String code,
        String path,
        String timestamp,
        Map<String, String> details
) {
    public ApiError(String error) {
        this(error, error, null, null, null, null);
    }

    public ApiError(String error, String code) {
        this(error, error, code, null, null, null);
    }

    public ApiError(String error, String code, String path) {
        this(error, error, code, path, Instant.now().toString(), null);
    }

    public ApiError(String error, String code, String path, Map<String, String> details) {
        this(error, error, code, path, Instant.now().toString(), details);
    }
}
