package com.idraak.smartbite.v2.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Error handling for v2 controllers only. v1 keeps its own {@code GlobalExceptionHandler} and error bodies.
 * Bodies never include exception messages or provider responses.
 */
@RestControllerAdvice(basePackages = "com.idraak.smartbite.v2")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class V2ExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(V2ExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Object> api(ApiException e) {
        if (e.code().status() >= 500) {
            log.warn("v2 request failed code={} cause={}", e.code(), e.getCause() == null ? "-" : e.getCause().getClass().getSimpleName());
        }
        return Problems.response(e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> invalidBody(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.add(error(Problems.snakeCasePath(fe.getField()), codeFor(fe.getCode()), fe.getDefaultMessage()));
        }
        e.getBindingResult().getGlobalErrors().forEach(ge ->
                errors.add(error(Problems.snakeCasePath(ge.getObjectName()), codeFor(ge.getCode()), ge.getDefaultMessage())));
        return Problems.validation(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Object> invalidParameters(HandlerMethodValidationException e) {
        List<Map<String, String>> errors = e.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(err -> error(
                        Problems.snakeCasePath(r.getMethodParameter().getParameterName() == null ? "parameter"
                                : r.getMethodParameter().getParameterName()),
                        codeFor(err.getCodes() == null || err.getCodes().length == 0 ? null : lastCode(err)),
                        err.getDefaultMessage())))
                .collect(Collectors.toList());
        return Problems.validation(errors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> missingParam(MissingServletRequestParameterException e) {
        return Problems.validation(List.of(error(Problems.snakeCasePath(e.getParameterName()), "required", "is required")));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Object> missingHeader(MissingRequestHeaderException e) {
        return Problems.validation(List.of(error(e.getHeaderName(), "required", "is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> typeMismatch(MethodArgumentTypeMismatchException e) {
        return Problems.validation(List.of(error(Problems.snakeCasePath(e.getName()), "invalid_value", "has an invalid value")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadable(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        if (cause instanceof InvalidFormatException ife && ife.getTargetType() != null && ife.getTargetType().isEnum()) {
            return Problems.validation(List.of(error(path(ife), "invalid_value", "is not an allowed value")));
        }
        return Problems.response(ErrorCode.MALFORMED_REQUEST, "The request body could not be read.", Map.of(), 0);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Object> mediaType(HttpMediaTypeNotSupportedException e) {
        return Problems.response(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, Map.of(), 0);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Object> method(HttpRequestMethodNotSupportedException e) {
        return Problems.response(ErrorCode.METHOD_NOT_ALLOWED, null, Map.of(), 0);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e) {
        log.error("v2 unexpected error type={}", e.getClass().getName(), e);
        return Problems.response(ErrorCode.INTERNAL_ERROR, null, Map.of(), 0);
    }

    private static Map<String, String> error(String field, String code, String message) {
        return Map.of("field", field, "code", code, "message", message == null ? "is invalid" : message);
    }

    private static String lastCode(org.springframework.context.MessageSourceResolvable err) {
        String[] codes = err.getCodes();
        return codes[codes.length - 1];
    }

    private static String path(DatabindException e) {
        StringBuilder sb = new StringBuilder();
        for (var ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!sb.isEmpty()) sb.append('.');
                sb.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.isEmpty() ? "body" : sb.toString();
    }

    static String codeFor(String constraint) {
        if (constraint == null) return "invalid_value";
        return switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> "required";
            case "Size", "Length" -> "invalid_size";
            case "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Range" -> "out_of_range";
            case "IanaTimezone" -> "invalid_timezone";
            default -> "invalid_value";
        };
    }
}
