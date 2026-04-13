package com.sam.besameditor.security;

import com.sam.besameditor.dto.AuthResponse;
import com.sam.besameditor.exceptions.ConflictException;
import com.sam.besameditor.services.AuthService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {
    private static final Logger log = LoggerFactory.getLogger(OAuth2LoginSuccessHandler.class);

    private final AuthService authService;

    @Value("${app.oauth2.redirect-uri:http://localhost:3000/oauth2/success}")
    private String redirectUri;

    public OAuth2LoginSuccessHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        try {
            log.info("GitHub OAuth2 callback received: path={}, remote={}",
                    request.getRequestURI(),
                    request.getRemoteAddr());
            OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();
            AuthResponse authResponse = authService.processGithubLogin(oauth2User);

            String accessToken = authResponse.getAccessToken();
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("Missing access token after GitHub login");
            }

            StringBuilder redirect = new StringBuilder(redirectUri)
                    .append("?token=")
                    .append(URLEncoder.encode(accessToken, StandardCharsets.UTF_8));

            String refreshToken = authResponse.getRefreshToken();
            if (refreshToken != null && !refreshToken.isBlank()) {
                redirect.append("&refreshToken=")
                        .append(URLEncoder.encode(refreshToken, StandardCharsets.UTF_8));
            }

            log.info("GitHub OAuth2 login success for email={}", authResponse.getEmail());
            response.sendRedirect(redirect.toString());
        } catch (Exception ex) {
            log.error("GitHub OAuth2 login handler failed: path={}, remote={}",
                    request.getRequestURI(),
                    request.getRemoteAddr(),
                    ex);
            String encodedError = URLEncoder.encode(resolveSafeErrorMessage(ex), StandardCharsets.UTF_8);
            response.sendRedirect(redirectUri + "?error=" + encodedError);
        }
    }

    private String resolveSafeErrorMessage(Exception ex) {
        if (ex instanceof BadCredentialsException || ex instanceof IllegalArgumentException || ex instanceof ConflictException) {
            String message = ex.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
        }
        return "GitHub login failed";
    }
}
