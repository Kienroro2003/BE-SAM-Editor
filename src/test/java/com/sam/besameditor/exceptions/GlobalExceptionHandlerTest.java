package com.sam.besameditor.exceptions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @Test
    void handleIllegalArgument_ShouldReturnBadRequest() {
        ResponseEntity<Map<String, String>> response =
                exceptionHandler.handleIllegalArgument(new IllegalArgumentException("invalid"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid", response.getBody().get("message"));
    }

    @Test
    void handleBadCredentials_ShouldReturnUnauthorized() {
        ResponseEntity<Map<String, String>> response =
                exceptionHandler.handleBadCredentials(new BadCredentialsException("wrong"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("Invalid credentials", response.getBody().get("message"));
    }

    @Test
    void handleNotFound_ShouldReturnNotFound() {
        ResponseEntity<Map<String, String>> response =
                exceptionHandler.handleNotFound(new NotFoundException("Workspace not found"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Workspace not found", response.getBody().get("message"));
    }

    @Test
    void handlePayloadTooLarge_ShouldReturn413() {
        ResponseEntity<Map<String, String>> response =
                exceptionHandler.handlePayloadTooLarge(new WorkspacePayloadTooLargeException("too large"));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("too large", response.getBody().get("message"));
    }

    @Test
    void handleUpstreamService_ShouldReturnConfiguredStatus() {
        ResponseEntity<Map<String, String>> response =
                exceptionHandler.handleUpstreamService(
                        new UpstreamServiceException(HttpStatus.BAD_GATEWAY, "github failed"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("github failed", response.getBody().get("message"));
    }

    @Test
    void handleDataIntegrityViolation_ShouldReturnConflict() {
        ResponseEntity<Map<String, String>> response =
                exceptionHandler.handleDataIntegrityViolation(new DataIntegrityViolationException("duplicate"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("Database constraint violation", response.getBody().get("message"));
    }

    @Test
    void handleValidation_ShouldReturnFirstValidationMessage() throws Exception {
        Method method = DummyController.class.getMethod("dummy", Object.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "obj");
        bindingResult.addError(new ObjectError("obj", "Email is required"));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Map<String, String>> response = exceptionHandler.handleValidation(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Email is required", response.getBody().get("message"));
    }

    @Test
    void handleValidation_ShouldReturnDefaultMessage_WhenNoValidationErrors() throws Exception {
        Method method = DummyController.class.getMethod("dummy", Object.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "obj");
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Map<String, String>> response = exceptionHandler.handleValidation(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Validation error", response.getBody().get("message"));
    }

    @Test
    void handleValidation_ShouldReturnDefaultMessage_WhenFirstErrorMessageIsNull() throws Exception {
        Method method = DummyController.class.getMethod("dummy", Object.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "obj");
        bindingResult.addError(new ObjectError("obj", null));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Map<String, String>> response = exceptionHandler.handleValidation(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("Validation error", response.getBody().get("message"));
    }

    @SuppressWarnings("unused")
    static class DummyController {
        public void dummy(@RequestBody Object body) {
        }
    }
}
