package com.jobbeacon.recommendation.web;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;
import java.util.Objects;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class ExceptionHandlerAdvice {

    static final String BAD_REQUEST = "bad_request";
    static final String UNAUTHORIZED = "unauthorized";
    static final String BACKEND_UNAVAILABLE = "backend_unavailable";
    static final String INTERNAL_SERVER_ERROR = "internal_server_error";

    private final MessageSource messageSource;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ResponseGeneral<ErrorBody>> handleApiException(ApiException ex, Locale locale) {
        return error(ex.getStatus(), ex.getCode(), locale);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseGeneral<ErrorBody>> handleValidation(MethodArgumentNotValidException ex, Locale locale) {
        String code = ex.getBindingResult().getFieldErrors().stream()
            .map(DefaultMessageSourceResolvable::getDefaultMessage)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(BAD_REQUEST);
        return error(HttpStatus.BAD_REQUEST, code, locale);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ResponseGeneral<ErrorBody>> handleMissingHeader(MissingRequestHeaderException ex, Locale locale) {
        if (ex.getHeaderName().startsWith("X-User-")) {
            return error(HttpStatus.UNAUTHORIZED, UNAUTHORIZED, locale);
        }
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST, locale);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ResponseGeneral<ErrorBody>> handleUnreadable(HttpMessageNotReadableException ex, Locale locale) {
        return error(HttpStatus.BAD_REQUEST, BAD_REQUEST, locale);
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ResponseGeneral<ErrorBody>> handleBackend(FeignException ex, Locale locale) {
        log.error("Backend call failed with status {}: {}", ex.status(), ex.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, BACKEND_UNAVAILABLE, locale);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseGeneral<ErrorBody>> handleAll(Exception ex, Locale locale) {
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_SERVER_ERROR, locale);
    }

    private ResponseEntity<ResponseGeneral<ErrorBody>> error(HttpStatus status, String code, Locale locale) {
        String detail = messageSource.getMessage(code, null, code, locale);
        return ResponseEntity.status(status)
            .body(ResponseGeneral.of(status.value(), status.getReasonPhrase(), new ErrorBody(code, detail)));
    }
}
