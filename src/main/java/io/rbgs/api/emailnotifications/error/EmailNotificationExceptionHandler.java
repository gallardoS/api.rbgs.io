package io.rbgs.api.emailnotifications.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "io.rbgs.api.emailnotifications")
public class EmailNotificationExceptionHandler {
    @ExceptionHandler(EmailNotificationException.class)
    public ProblemDetail handle(EmailNotificationException error) {
        HttpStatus status = switch (error.reason()) {
            case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
            case UNTRUSTED -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
        return ProblemDetail.forStatusAndDetail(status, error.getMessage());
    }
}
