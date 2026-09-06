package com.example.agentservice.procurement.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Converts expected workflow conflicts into stable API responses instead of servlet 500 pages. */
@RestControllerAdvice(basePackages = "com.example.agentservice.procurement.controller")
public class ProcurementApiExceptionHandler {

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleWorkflowConflict(
            IllegalStateException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleInvalidArgument(
            IllegalArgumentException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request.getRequestURI());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, String path) {
        String detail = message == null || message.isBlank() ? status.getReasonPhrase() : message;
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), detail, path));
    }

    public record ApiError(Instant timestamp, int status, String error, String message, String path) {
    }
}
