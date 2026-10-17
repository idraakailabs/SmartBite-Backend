package com.idraak.smartbite.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a));
        return ResponseEntity.badRequest().body(Map.of("error", "Validation failed", "details", errors));
    }

    // Bodies carry fixed text only: provider and parser messages are logged, never returned (BE-BUG-007).
    // The status codes and the "error" / "message" keys are unchanged; the app reads only the status code.

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<Map<String, String>> handleAiService(AiServiceException ex) {
        log.warn("v1 AI service error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "AI service error", "message", "Suggestions are unavailable right now. Please try again."));
    }

    /** A body that isn't valid JSON for the request was 500 with the parser message; now 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid request body", "message", "The request body is not valid JSON for this endpoint"));
    }

    /** Unknown paths (e.g. unexposed Actuator endpoints) were 500 because of the catch-all below; now 404. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "Not found", "message", "No such endpoint"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
        log.error("v1 request failed: {}", ex.getClass().getName());
        return ResponseEntity.internalServerError()
                .body(Map.of("error", "Internal server error", "message", "Something went wrong. Please try again."));
    }
}
