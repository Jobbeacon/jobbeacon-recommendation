package com.jobbeacon.recommendation.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** An error with an i18n message key as its code, rendered by {@link ExceptionHandlerAdvice}. */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code) {
        return new ApiException(HttpStatus.BAD_REQUEST, code);
    }

    public static ApiException notFound(String code) {
        return new ApiException(HttpStatus.NOT_FOUND, code);
    }
}
