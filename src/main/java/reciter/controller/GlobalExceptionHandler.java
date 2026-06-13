package reciter.controller;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler producing clean JSON error responses without leaking stack traces
 * or internal exception detail to clients. Stack traces are logged server-side only.
 *
 * <p>The threshold-exceeded case (thrown as an {@link IOException} by
 * {@code PubMedArticleRetrievalService.retrieve} when the matching article count exceeds the
 * retrieval threshold) is mapped to {@code 502 Bad Gateway} with a small {@code {error, message}}
 * body so callers can distinguish it from a generic failure. All other uncaught exceptions are
 * mapped to a generic {@code 500 Internal Server Error} body with no internal detail.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Marker substring identifying the threshold-exceeded IOException message. */
    private static final String THRESHOLD_EXCEEDED_MARKER = "exceeded the threshold level";

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, Object>> handleIOException(IOException ex) {
        String message = ex.getMessage();
        if (message != null && message.contains(THRESHOLD_EXCEEDED_MARKER)) {
            log.warn("PubMed retrieval threshold exceeded: {}", message);
            return errorResponse(HttpStatus.BAD_GATEWAY, "threshold_exceeded",
                    "The query matched too many PubMed articles to retrieve. Please refine the query.");
        }
        log.error("Upstream PubMed retrieval error.", ex);
        return errorResponse(HttpStatus.BAD_GATEWAY, "upstream_error",
                "Unable to retrieve results from PubMed at this time.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("Unhandled exception while processing request.", ex);
        return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred while processing the request.");
    }

    private static ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String error, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
