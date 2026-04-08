package com.sam.besameditor.security;

import com.sam.besameditor.services.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2LoginSuccessHandlerTest {

    @Mock
    private AuthService authService;

    @Mock
    private Authentication authentication;

    private OAuth2LoginSuccessHandler successHandler;

    @BeforeEach
    void setUp() {
        successHandler = new OAuth2LoginSuccessHandler(authService);
        ReflectionTestUtils.setField(successHandler, "redirectUri", "http://localhost:3000/oauth2/success");
    }

    @Test
    void onAuthenticationSuccess_ShouldRedirectWithUrlEncodedToken() throws Exception {
        OAuth2User oauth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                Map.of("id", 1, "login", "githubuser"),
                "id"
        );

        when(authentication.getPrincipal()).thenReturn(oauth2User);
        when(authService.processGithubLogin(oauth2User)).thenReturn("abc+/=");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, authentication);

        assertEquals("http://localhost:3000/oauth2/success?token=abc%2B%2F%3D", response.getRedirectedUrl());
    }
}
