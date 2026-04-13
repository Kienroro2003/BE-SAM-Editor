package com.sam.besameditor.exceptions;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, resolveMessage(ex, "Invalid request"), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<?> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, resolveMessage(ex, "Invalid credentials"), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<?> handleConflict(ConflictException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, resolveMessage(ex, "Request conflict"), request);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<?> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(WorkspacePayloadTooLargeException.class)
    public ResponseEntity<?> handlePayloadTooLarge(WorkspacePayloadTooLargeException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage(), request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.PAYLOAD_TOO_LARGE, "Uploaded file exceeds allowed size", request);
    }

    @ExceptionHandler(WorkspaceStorageException.class)
    public ResponseEntity<?> handleWorkspaceStorage(WorkspaceStorageException ex, HttpServletRequest request) {
        log.error("Workspace storage failed", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store repository source code on server", request);
    }

    @ExceptionHandler(UpstreamServiceException.class)
    public ResponseEntity<?> handleUpstreamService(UpstreamServiceException ex, HttpServletRequest request) {
        return buildErrorResponse(ex.getStatus(), ex.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "Database constraint violation", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage() != null ? err.getDefaultMessage() : "Validation error")
                .orElse("Validation error");
        return buildErrorResponse(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Invalid JSON request body", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<?> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        String message = ex.getReason();
        if (message == null || message.isBlank()) {
            message = "Request failed";
        }
        return buildErrorResponse(HttpStatus.valueOf(ex.getStatusCode().value()), message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    private String resolveMessage(Exception ex, String fallback) {
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? fallback : message;
    }

    private ResponseEntity<?> buildErrorResponse(HttpStatus status, String message, HttpServletRequest request) {
        if (acceptsEventStream(request)) {
            return ResponseEntity.status(status)
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(toSseErrorBody(message));
        }
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    private boolean acceptsEventStream(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String acceptHeader = request.getHeader(HttpHeaders.ACCEPT);
        return acceptHeader != null && acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }

    private String toSseErrorBody(String message) {
        String resolvedMessage = (message == null || message.isBlank()) ? "Request failed" : message;
        String[] lines = resolvedMessage.split("\\R", -1);
        StringBuilder builder = new StringBuilder("event: error\n");
        for (String line : lines) {
            builder.append("data: ").append(line).append('\n');
        }
        builder.append('\n');
        return builder.toString();
    }
}
