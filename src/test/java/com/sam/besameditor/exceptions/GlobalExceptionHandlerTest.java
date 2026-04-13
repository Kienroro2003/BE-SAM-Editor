package com.sam.besameditor.exceptions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleIllegalArgument_ShouldReturnBadRequest() {
        ResponseEntity<?> response =
                exceptionHandler.handleIllegalArgument(new IllegalArgumentException("invalid"), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid", bodyMessage(response));
    }

    @Test
    void handleIllegalArgument_ShouldUseFallbackMessage_WhenMessageIsNull() {
        ResponseEntity<?> response =
                exceptionHandler.handleIllegalArgument(new IllegalArgumentException(), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid request", bodyMessage(response));
    }

    @Test
    void handleIllegalArgument_ShouldUseFallbackMessage_WhenMessageIsBlank() {
        ResponseEntity<?> response =
                exceptionHandler.handleIllegalArgument(new IllegalArgumentException("   "), null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid request", bodyMessage(response));
    }

    @Test
    void handleBadCredentials_ShouldReturnUnauthorized() {
        ResponseEntity<?> response =
                exceptionHandler.handleBadCredentials(new BadCredentialsException("wrong"), null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("wrong", bodyMessage(response));
    }

    @Test
    void handleBadCredentials_ShouldUseFallbackMessage_WhenMessageIsBlank() {
        ResponseEntity<?> response =
                exceptionHandler.handleBadCredentials(new BadCredentialsException("   "), null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Invalid credentials", bodyMessage(response));
    }

    @Test
    void handleConflict_ShouldReturnConflict() {
        ResponseEntity<?> response =
                exceptionHandler.handleConflict(new ConflictException("Email not verified. Please verify your email first."), null);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Email not verified. Please verify your email first.", bodyMessage(response));
    }

    @Test
    void handleNotFound_ShouldReturnNotFound() {
        ResponseEntity<?> response =
                exceptionHandler.handleNotFound(new NotFoundException("Workspace not found"), null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Workspace not found", bodyMessage(response));
    }

    @Test
    void handlePayloadTooLarge_ShouldReturn413() {
        ResponseEntity<?> response =
                exceptionHandler.handlePayloadTooLarge(new WorkspacePayloadTooLargeException("too large"), null);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("too large", bodyMessage(response));
    }

    @Test
    void handleMaxUploadSize_ShouldReturn413() {
        ResponseEntity<?> response =
                exceptionHandler.handleMaxUploadSize(new MaxUploadSizeExceededException(1024), null);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("Uploaded file exceeds allowed size", bodyMessage(response));
    }

    @Test
    void handleWorkspaceStorage_ShouldReturnInternalServerError() {
        ResponseEntity<?> response =
                exceptionHandler.handleWorkspaceStorage(new WorkspaceStorageException("storage failed", null), null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Failed to store repository source code on server", bodyMessage(response));
    }

    @Test
    void handleUpstreamService_ShouldReturnConfiguredStatus() {
        ResponseEntity<?> response =
                exceptionHandler.handleUpstreamService(
                        new UpstreamServiceException(HttpStatus.BAD_GATEWAY, "github failed"),
                        null);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("github failed", bodyMessage(response));
    }

    @Test
    void handleDataIntegrityViolation_ShouldReturnConflict() {
        ResponseEntity<?> response =
                exceptionHandler.handleDataIntegrityViolation(new DataIntegrityViolationException("duplicate"), null);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Database constraint violation", bodyMessage(response));
    }

    @Test
    void handleValidation_ShouldReturnFirstValidationMessage() throws Exception {
        Method method = DummyController.class.getMethod("dummy", Object.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "obj");
        bindingResult.addError(new ObjectError("obj", "Email is required"));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<?> response = exceptionHandler.handleValidation(exception, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Email is required", bodyMessage(response));
    }

    @Test
    void handleValidation_ShouldReturnDefaultMessage_WhenNoValidationErrors() throws Exception {
        Method method = DummyController.class.getMethod("dummy", Object.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "obj");
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<?> response = exceptionHandler.handleValidation(exception, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Validation error", bodyMessage(response));
    }

    @Test
    void handleValidation_ShouldReturnDefaultMessage_WhenFirstErrorMessageIsNull() throws Exception {
        Method method = DummyController.class.getMethod("dummy", Object.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "obj");
        bindingResult.addError(new ObjectError("obj", null));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<?> response = exceptionHandler.handleValidation(exception, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Validation error", bodyMessage(response));
    }

    @Test
    void handleHttpMessageNotReadable_ShouldReturnBadRequest() {
        ResponseEntity<?> response =
                exceptionHandler.handleHttpMessageNotReadable(
                        new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0])),
                        null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Invalid JSON request body", bodyMessage(response));
    }

    @Test
    void handleUnexpected_ShouldReturnInternalServerError() {
        ResponseEntity<?> response =
                exceptionHandler.handleUnexpected(new RuntimeException("boom"), null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Internal server error", bodyMessage(response));
    }

    @SuppressWarnings("unused")
    static class DummyController {
        public void dummy(@RequestBody Object body) {
        }
    }

    @SuppressWarnings("unchecked")
    private String bodyMessage(ResponseEntity<?> response) {
        return ((Map<String, String>) response.getBody()).get("message");
    }
}
