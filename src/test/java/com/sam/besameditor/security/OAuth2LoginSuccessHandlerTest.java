package com.sam.besameditor.security;

import com.sam.besameditor.dto.AuthResponse;
import com.sam.besameditor.exceptions.ConflictException;
import com.sam.besameditor.services.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OAuth2LoginSuccessHandlerTest {

    private TestAuthService authService;
    private OAuth2LoginSuccessHandler successHandler;

    @BeforeEach
    void setUp() {
        authService = new TestAuthService();
        successHandler = new OAuth2LoginSuccessHandler(authService);
        ReflectionTestUtils.setField(successHandler, "redirectUri", "http://localhost:3000/oauth2/success");
    }

    @Test
    void onAuthenticationSuccess_ShouldRedirectWithUrlEncodedToken() throws Exception {
        OAuth2User oauth2User = oauth2User();
        authService.nextResponse = new AuthResponse("abc+/=", "refresh+/=", "github@user.com", "GitHub User");

        MockHttpServletResponse response = performSuccess(oauth2User);

        assertEquals("http://localhost:3000/oauth2/success?token=abc%2B%2F%3D&refreshToken=refresh%2B%2F%3D",
                response.getRedirectedUrl());
    }

    @Test
    void onAuthenticationSuccess_ShouldRedirectWithoutRefreshToken_WhenMissingRefreshToken() throws Exception {
        OAuth2User oauth2User = oauth2User();
        authService.nextResponse = new AuthResponse("abc+/=", null, "github@user.com", "GitHub User");

        MockHttpServletResponse response = performSuccess(oauth2User);

        assertEquals("http://localhost:3000/oauth2/success?token=abc%2B%2F%3D",
                response.getRedirectedUrl());
    }

    @Test
    void onAuthenticationSuccess_ShouldRedirectWithoutRefreshToken_WhenRefreshTokenBlank() throws Exception {
        OAuth2User oauth2User = oauth2User();
        authService.nextResponse = new AuthResponse("token-value", "   ", "github@user.com", "GitHub User");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/login/oauth2/code/github");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken(oauth2User, null));

        assertEquals("http://localhost:3000/oauth2/success?token=token-value",
                response.getRedirectedUrl());
    }

    @Test
    void onAuthenticationSuccess_ShouldRedirectWithGenericError_WhenAccessTokenMissing() throws Exception {
        OAuth2User oauth2User = oauth2User();
        authService.nextResponse = new AuthResponse("   ", "refresh", "github@user.com", "GitHub User");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/login/oauth2/code/github");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken(oauth2User, null));

        assertEquals("http://localhost:3000/oauth2/success?error=GitHub+login+failed",
                response.getRedirectedUrl());
    }

    @Test
    void onAuthenticationSuccess_ShouldRedirectWithBusinessError_WhenAuthErrorOccurs() throws Exception {
        OAuth2User oauth2User = oauth2User();
        authService.nextException = new ConflictException("Email is already verified.");

        MockHttpServletResponse response = performSuccess(oauth2User);

        assertEquals("http://localhost:3000/oauth2/success?error=Email+is+already+verified.",
                response.getRedirectedUrl());
    }

    @Test
    void onAuthenticationSuccess_ShouldRedirectWithBusinessError_WhenCredentialErrorOccurs() throws Exception {
        OAuth2User oauth2User = oauth2User();
        authService.nextException = new BadCredentialsException("Invalid refresh token");

        MockHttpServletResponse response = performSuccess(oauth2User);

        assertEquals("http://localhost:3000/oauth2/success?error=Invalid+refresh+token",
                response.getRedirectedUrl());
    }

    @Test
    void onAuthenticationSuccess_ShouldRedirectWithGenericError_WhenUnexpectedExceptionOccurs() throws Exception {
        OAuth2User oauth2User = oauth2User();
        authService.nextException = new RuntimeException("boom");

        MockHttpServletResponse response = performSuccess(oauth2User);

        assertEquals("http://localhost:3000/oauth2/success?error=GitHub+login+failed",
                response.getRedirectedUrl());
    }

    private MockHttpServletResponse performSuccess(OAuth2User oauth2User) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, new TestingAuthenticationToken(oauth2User, null));

        return response;
    }

    private OAuth2User oauth2User() {
        return new DefaultOAuth2User(
                Collections.emptyList(),
                Map.of("id", 1, "login", "githubuser"),
                "id"
        );
    }

    private static final class TestAuthService extends AuthService {
        private AuthResponse nextResponse;
        private RuntimeException nextException;

        private TestAuthService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public AuthResponse processGithubLogin(OAuth2User oauth2User) {
            if (nextException != null) {
                throw nextException;
            }
            return nextResponse;
        }
    }
}
