package com.sam.besameditor.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DtoTest {

    @Test
    void registerRequest_GetterSetter_ShouldWork() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@test.com");
        request.setFullName("Test User");
        request.setPassword("password123");

        assertEquals("test@test.com", request.getEmail());
        assertEquals("Test User", request.getFullName());
        assertEquals("password123", request.getPassword());
    }

    @Test
    void loginRequest_GetterSetter_ShouldWork() {
        LoginRequest request = new LoginRequest();
        request.setEmail("test@test.com");
        request.setPassword("password123");

        assertEquals("test@test.com", request.getEmail());
        assertEquals("password123", request.getPassword());
    }

    @Test
    void verifyOtpRequest_GetterSetter_ShouldWork() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("test@test.com");
        request.setOtpCode("123456");

        assertEquals("test@test.com", request.getEmail());
        assertEquals("123456", request.getOtpCode());
    }

    @Test
    void authResponse_ConstructorAndGetters_ShouldWork() {
        AuthResponse response = new AuthResponse("token", "test@test.com", "Test User");

        assertEquals("token", response.getAccessToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("test@test.com", response.getEmail());
        assertEquals("Test User", response.getFullName());
    }
}
