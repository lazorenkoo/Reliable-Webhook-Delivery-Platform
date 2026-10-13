package com.lazorenkoo.relayforge.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException exception, ServletWebRequest request) {
        int status = exception.getStatusCode().value();
        String message = exception.getReason() == null ? "Request failed" : exception.getReason();
        if (status >= 500) {
            message = status == 500 ? "Internal server error" : "Webhook delivery failed";
        }
        return ResponseEntity.status(status).headers(exception.getHeaders())
                .body(new ApiError(status, message, request.getRequest().getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, ServletWebRequest request) {
        return ResponseEntity.status(500).body(new ApiError(500, "Internal server error",
                request.getRequest().getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        HttpStatus known = HttpStatus.resolve(status.value());
        String message = known == null ? "Request failed" : known.getReasonPhrase();
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        return new ResponseEntity<>(new ApiError(status.value(), message, path), headers, status);
    }
}
