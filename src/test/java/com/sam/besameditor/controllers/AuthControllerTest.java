package com.sam.besameditor.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.besameditor.dto.AuthResponse;
import com.sam.besameditor.dto.LoginRequest;
import com.sam.besameditor.dto.RegisterRequest;
import com.sam.besameditor.dto.VerifyOtpRequest;
import com.sam.besameditor.services.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuthService authService;

    @InjectMocks
    private AuthController authController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(authController).build();
    }

    @Test
    void register_ShouldReturn201() throws Exception {
        // Arrange
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@test.com");
        request.setFullName("Test User");
        request.setPassword("password123");

        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(Map.of("message", "Registration successful. Please check your email for the OTP code."));

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Registration successful. Please check your email for the OTP code."));
    }

    @Test
    void register_ShouldReturn400_WhenInvalidRequest() throws Exception {
        // Arrange
        RegisterRequest request = new RegisterRequest();
        request.setEmail("invalid-email"); // Should fail @Email validation
        request.setFullName(""); // Should fail @NotBlank validation

        // Act & Assert
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verifyOtp_ShouldReturn200AndAuthResponse() throws Exception {
        // Arrange
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("test@test.com");
        request.setOtpCode("123456");

        AuthResponse authResponse = new AuthResponse("jwt-token", "test@test.com", "Test User");
        when(authService.verifyOtp(any(VerifyOtpRequest.class))).thenReturn(authResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.email").value("test@test.com"));
    }

    @Test
    void resendOtp_ShouldReturn200() throws Exception {
        // Arrange
        when(authService.resendOtp(anyString()))
                .thenReturn(Map.of("message", "A new OTP has been sent to your email."));

        // Act & Assert
        mockMvc.perform(post("/api/auth/resend-otp")
                        .param("email", "test@test.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("A new OTP has been sent to your email."));
    }

    @Test
    void login_ShouldReturn200AndAuthResponse() throws Exception {
        // Arrange
        LoginRequest request = new LoginRequest();
        request.setEmail("test@test.com");
        request.setPassword("password123");

        AuthResponse authResponse = new AuthResponse("jwt-token", "test@test.com", "Test User");
        when(authService.login(any(LoginRequest.class))).thenReturn(authResponse);

        // Act & Assert
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("jwt-token"))
                .andExpect(jsonPath("$.email").value("test@test.com"));
    }

    @Test
    void me_ShouldReturnCurrentUserInfo() throws Exception {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        Collection<? extends GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        when(authentication.getName()).thenReturn("me@test.com");
        doReturn(authorities).when(authentication).getAuthorities();

        Map<String, Object> body = authController.me(authentication).getBody();

        assertEquals("me@test.com", body.get("email"));
        assertEquals(authorities, body.get("authorities"));
    }

    @Test
    void githubLoginHint_ShouldReturnHintUrl() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/auth/github"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginUrl").value("/oauth2/authorization/github"));
    }
}
