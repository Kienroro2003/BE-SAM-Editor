package com.sam.besameditor.controllers;

import com.sam.besameditor.dto.AuthResponse;
import com.sam.besameditor.dto.LoginRequest;
import com.sam.besameditor.dto.RefreshTokenRequest;
import com.sam.besameditor.dto.RegisterRequest;
import com.sam.besameditor.dto.VerifyOtpRequest;
import com.sam.besameditor.services.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** Step 1: Register → send OTP (account not yet active). */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /** Step 2: Verify OTP → activate account, return JWT. */
    @PostMapping("/verify-otp")
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        return ResponseEntity.ok(authService.verifyOtp(request));
    }

    /** Resend OTP for unverified users. */
    @PostMapping("/resend-otp")
    public ResponseEntity<Map<String, String>> resendOtp(@RequestParam String email) {
        return ResponseEntity.ok(authService.resendOtp(email));
    }

    /** Login with verified credentials. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** Rotate access + refresh token. */
    @PostMapping("/refresh-token")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request.getRefreshToken()));
    }

    /** Logout current device by refresh token. */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.logout(request.getRefreshToken()));
    }

    /** Logout all devices for authenticated user. */
    @PostMapping("/logout-all")
    public ResponseEntity<Map<String, String>> logoutAll(Authentication authentication) {
        return ResponseEntity.ok(authService.logoutAllByEmail(authentication.getName()));
    }

    /** Get current authenticated user info. */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        return ResponseEntity.ok(Map.of(
                "email", authentication.getName(),
                "authorities", authentication.getAuthorities()));
    }

    /** GitHub OAuth2 login redirect hint. */
    @GetMapping("/github")
    public ResponseEntity<Map<String, String>> githubLoginHint() {
        return ResponseEntity.ok(Map.of("loginUrl", "/oauth2/authorization/github"));
    }
}
