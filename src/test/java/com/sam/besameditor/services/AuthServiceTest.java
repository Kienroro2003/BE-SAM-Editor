package com.sam.besameditor.services;

import com.sam.besameditor.dto.AuthResponse;
import com.sam.besameditor.dto.LoginRequest;
import com.sam.besameditor.dto.RegisterRequest;
import com.sam.besameditor.dto.VerifyOtpRequest;
import com.sam.besameditor.models.AuthProvider;
import com.sam.besameditor.models.EmailVerificationCode;
import com.sam.besameditor.models.RefreshToken;
import com.sam.besameditor.models.User;
import com.sam.besameditor.repositories.EmailVerificationCodeRepository;
import com.sam.besameditor.repositories.UserRepository;
import com.sam.besameditor.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private EmailVerificationCodeRepository otpRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "otpExpiryMinutes", 5);

        RefreshToken defaultRefreshToken = new RefreshToken();
        defaultRefreshToken.setToken("refresh-token-123");
        lenient().when(refreshTokenService.createRefreshToken(nullable(Long.class))).thenReturn(defaultRefreshToken);
    }

    @Test
    void register_ShouldSaveUserAndSendOtp() {
        // Arrange
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@test.com");
        request.setFullName("Test User");
        request.setPassword("password123");

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password123")).thenReturn("encoded_pass");

        // Act
        Map<String, String> response = authService.register(request);

        // Assert
        assertNotNull(response);
        assertEquals("Registration successful. Please check your email for the OTP code.", response.get("message"));

        verify(userRepository, times(1)).findByEmail("test@test.com");
        verify(passwordEncoder, times(1)).encode("password123");
        verify(userRepository, times(1)).save(any(User.class));
        verify(otpRepository, times(1)).save(any(EmailVerificationCode.class));
        verify(emailService, times(1)).sendOtpEmail(eq("test@test.com"), anyString());
    }

    @Test
    void register_ShouldThrowException_WhenEmailExists() {
        // Arrange
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@test.com");
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(new User()));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> authService.register(request));
        assertEquals("Email already registered", exception.getMessage());

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void verifyOtp_ShouldVerifyCorrectly_AndReturnJwt() {
        // Arrange
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("test@test.com");
        request.setOtpCode("123456");

        User user = new User();
        user.setEmail("test@test.com");
        
        EmailVerificationCode otp = new EmailVerificationCode();
        otp.setOtpCode("123456");
        otp.setUser(user);
        otp.setExpiresAt(LocalDateTime.now().plusMinutes(5));

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(otpRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user)).thenReturn(Optional.of(otp));
        when(jwtService.generateToken(any(UserDetails.class))).thenReturn("jwt-token-123");

        // Act
        AuthResponse response = authService.verifyOtp(request);

        // Assert
        assertNotNull(response);
        assertEquals("jwt-token-123", response.getAccessToken());
        assertEquals("refresh-token-123", response.getRefreshToken());
        assertTrue(user.getIsEmailVerified());

        verify(otpRepository, times(1)).markAllUsedByUser(user);
        verify(userRepository, times(1)).save(user);
    }

    @Test
    void verifyOtp_ShouldThrowException_WhenOtpExpired() {
        // Arrange
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("test@test.com");
        request.setOtpCode("123456");

        User user = new User();
        user.setEmail("test@test.com");

        EmailVerificationCode otp = new EmailVerificationCode();
        otp.setOtpCode("123456");
        otp.setExpiresAt(LocalDateTime.now().minusMinutes(1)); // Expired

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(otpRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user)).thenReturn(Optional.of(otp));

        // Act & Assert
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, 
            () -> authService.verifyOtp(request));
        assertEquals("OTP has expired. Please request a new one.", exception.getMessage());
    }

    @Test
    void verifyOtp_ShouldThrowException_WhenUserNotFound() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("missing@test.com");
        request.setOtpCode("123456");

        when(userRepository.findByEmail("missing@test.com")).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> authService.verifyOtp(request));
        assertEquals("User not found", exception.getMessage());
    }

    @Test
    void verifyOtp_ShouldThrowException_WhenNoPendingOtp() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("test@test.com");
        request.setOtpCode("123456");

        User user = new User();
        user.setEmail("test@test.com");
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(otpRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user)).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> authService.verifyOtp(request));
        assertEquals("No pending OTP found. Please register again.", exception.getMessage());
    }

    @Test
    void verifyOtp_ShouldThrowBadCredentialsList_WhenOtpInvalid() {
        // Arrange
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("test@test.com");
        request.setOtpCode("invalid");

        User user = new User();
        user.setEmail("test@test.com");

        EmailVerificationCode otp = new EmailVerificationCode();
        otp.setOtpCode("123456");
        otp.setExpiresAt(LocalDateTime.now().plusMinutes(5));

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(otpRepository.findTopByUserAndUsedFalseOrderByCreatedAtDesc(user)).thenReturn(Optional.of(otp));

        // Act & Assert
        BadCredentialsException exception = assertThrows(BadCredentialsException.class, 
            () -> authService.verifyOtp(request));
        assertEquals("Invalid OTP code.", exception.getMessage());
    }

    @Test
    void login_ShouldReturnJwt_WhenCredentialsValidAndVerified() {
        // Arrange
        LoginRequest request = new LoginRequest();
        request.setEmail("test@test.com");
        request.setPassword("password123");

        User user = new User();
        user.setEmail("test@test.com");
        user.setPassword("encoded_pass");
        user.setIsEmailVerified(true);
        user.setFullName("Test User");

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "encoded_pass")).thenReturn(true);
        when(jwtService.generateToken(any(UserDetails.class))).thenReturn("jwt-token-123");

        // Act
        AuthResponse response = authService.login(request);

        // Assert
        assertNotNull(response);
        assertEquals("jwt-token-123", response.getAccessToken());
        assertEquals("refresh-token-123", response.getRefreshToken());
        assertEquals("Test User", response.getFullName());
    }

    @Test
    void login_ShouldThrowException_WhenNotVerified() {
        // Arrange
        LoginRequest request = new LoginRequest();
        request.setEmail("test@test.com");

        User user = new User();
        user.setEmail("test@test.com");
        user.setIsEmailVerified(false);

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        // Act & Assert
        IllegalStateException exception = assertThrows(IllegalStateException.class, 
            () -> authService.login(request));
        assertEquals("Email not verified. Please verify your email first.", exception.getMessage());
    }

    @Test
    void login_ShouldThrowException_WhenUserNotFound() {
        LoginRequest request = new LoginRequest();
        request.setEmail("missing@test.com");
        request.setPassword("password123");

        when(userRepository.findByEmail("missing@test.com")).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> authService.login(request));
        assertEquals("User not found", exception.getMessage());
    }

    @Test
    void login_ShouldThrowBadCredentials_WhenPasswordInvalid() {
        LoginRequest request = new LoginRequest();
        request.setEmail("test@test.com");
        request.setPassword("wrong");

        User user = new User();
        user.setEmail("test@test.com");
        user.setPassword("encoded_pass");
        user.setIsEmailVerified(true);
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded_pass")).thenReturn(false);

        assertThrows(BadCredentialsException.class, () -> authService.login(request));
    }

    @Test
    void resendOtp_ShouldSendOtp_WhenUnverified() {
        // Arrange
        User user = new User();
        user.setEmail("test@test.com");
        user.setIsEmailVerified(false);

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        // Act
        Map<String, String> response = authService.resendOtp("test@test.com");

        // Assert
        assertNotNull(response);
        assertEquals("A new OTP has been sent to your email.", response.get("message"));

        verify(otpRepository, times(1)).save(any(EmailVerificationCode.class));
        verify(emailService, times(1)).sendOtpEmail(eq("test@test.com"), anyString());
    }

    @Test
    void resendOtp_ShouldThrowException_WhenUserNotFound() {
        when(userRepository.findByEmail("missing@test.com")).thenReturn(Optional.empty());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> authService.resendOtp("missing@test.com"));
        assertEquals("User not found", exception.getMessage());
    }

    @Test
    void resendOtp_ShouldThrowException_WhenAlreadyVerified() {
        User user = new User();
        user.setEmail("test@test.com");
        user.setIsEmailVerified(true);
        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> authService.resendOtp("test@test.com"));
        assertEquals("Email is already verified.", exception.getMessage());
    }

    @Test
    void refreshToken_ShouldRotateAndReturnNewTokens_WhenRefreshTokenValid() {
        User user = new User();
        user.setId(1L);
        user.setEmail("test@test.com");
        user.setPassword("encoded");
        user.setFullName("Test User");

        RefreshToken currentRefreshToken = new RefreshToken();
        currentRefreshToken.setToken("old-refresh");
        currentRefreshToken.setUser(user);
        currentRefreshToken.setExpiresAt(LocalDateTime.now().plusDays(1));

        when(refreshTokenService.findByTokenOrThrow("old-refresh")).thenReturn(currentRefreshToken);
        when(refreshTokenService.verifyExpiration(currentRefreshToken)).thenReturn(currentRefreshToken);
        when(jwtService.generateToken(any(UserDetails.class))).thenReturn("new-access");

        RefreshToken rotatedRefreshToken = new RefreshToken();
        rotatedRefreshToken.setToken("new-refresh");
        when(refreshTokenService.createRefreshToken(1L)).thenReturn(rotatedRefreshToken);

        AuthResponse response = authService.refreshToken("old-refresh");

        assertEquals("new-access", response.getAccessToken());
        assertEquals("new-refresh", response.getRefreshToken());
        verify(refreshTokenService).deleteByToken("old-refresh");
    }

    @Test
    void logout_ShouldDeleteTokenAndReturnMessage() {
        Map<String, String> response = authService.logout("refresh-123");

        assertEquals("Logged out successfully.", response.get("message"));
        verify(refreshTokenService).deleteByToken("refresh-123");
    }

    @Test
    void logoutAllByEmail_ShouldDeleteUserTokensAndReturnMessage() {
        User user = new User();
        user.setId(99L);
        user.setEmail("test@test.com");

        when(userRepository.findByEmail("test@test.com")).thenReturn(Optional.of(user));

        Map<String, String> response = authService.logoutAllByEmail("test@test.com");

        assertEquals("Logged out from all devices successfully.", response.get("message"));
        verify(refreshTokenService).deleteByUserId(99L);
    }

    @Test
    void processGithubLogin_ShouldCreateUserAndReturnJwt_WhenNewUser() {
        // Arrange
        OAuth2User oauth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                Map.of(
                        "id", 123456,
                        "email", "github@test.com",
                        "login", "githubuser",
                        "name", "Github User",
                        "avatar_url", "https://avatar.url"
                ),
                "id"
        );

        when(userRepository.findByEmail("github@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded_github_pass");
        
        // Simulating the save returning the exact user
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArguments()[0]);
        when(jwtService.generateToken(any())).thenReturn("github-jwt");

        // Act
        AuthResponse response = authService.processGithubLogin(oauth2User);

        // Assert
        assertEquals("github-jwt", response.getAccessToken());
        assertEquals("refresh-token-123", response.getRefreshToken());
        verify(userRepository, times(1)).save(argThat(user -> 
            user.getEmail().equals("github@test.com") &&
            user.getProvider() == AuthProvider.GITHUB &&
            user.getIsEmailVerified()
        ));
    }

    @Test
    void processGithubLogin_ShouldReturnJwt_WhenExistingUser() {
        OAuth2User oauth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                Map.of(
                        "id", 123456,
                        "email", "github@test.com",
                        "login", "githubuser",
                        "name", "Github User"
                ),
                "id"
        );

        User existing = new User();
        existing.setEmail("github@test.com");
        existing.setPassword("encoded");
        when(userRepository.findByEmail("github@test.com")).thenReturn(Optional.of(existing));
        when(jwtService.generateToken(any())).thenReturn("existing-user-jwt");

        AuthResponse response = authService.processGithubLogin(oauth2User);

        assertEquals("existing-user-jwt", response.getAccessToken());
        assertEquals("refresh-token-123", response.getRefreshToken());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void processGithubLogin_ShouldFallbackValues_WhenEmailAndNameMissing() {
        OAuth2User oauth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                Map.of(
                        "id", 123456,
                        "login", "githubuser"
                ),
                "id"
        );

        when(userRepository.findByEmail("githubuser@users.noreply.github.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded_github_pass");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArguments()[0]);
        when(jwtService.generateToken(any())).thenReturn("github-jwt");

        AuthResponse response = authService.processGithubLogin(oauth2User);

        assertEquals("github-jwt", response.getAccessToken());
        assertEquals("refresh-token-123", response.getRefreshToken());
        verify(userRepository).save(argThat(user ->
                user.getEmail().equals("githubuser@users.noreply.github.com")
                        && user.getFullName().equals("githubuser")
                        && user.getProvider() == AuthProvider.GITHUB
        ));
    }

    @Test
    void processGithubLogin_ShouldFallbackEmail_WhenEmailIsBlank() {
        OAuth2User oauth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                Map.of(
                        "id", 123456,
                        "email", "   ",
                        "login", "githubuser",
                        "name", "Github User"
                ),
                "id"
        );

        when(userRepository.findByEmail("githubuser@users.noreply.github.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded_github_pass");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArguments()[0]);
        when(jwtService.generateToken(any())).thenReturn("github-jwt");

        AuthResponse response = authService.processGithubLogin(oauth2User);

        assertEquals("github-jwt", response.getAccessToken());
        assertEquals("refresh-token-123", response.getRefreshToken());
        verify(userRepository).save(argThat(user ->
                user.getEmail().equals("githubuser@users.noreply.github.com")
        ));
    }

    @Test
    void processGithubLogin_ShouldFallbackName_WhenNameIsBlankOrNull() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", 123456);
        attributes.put("email", "github@test.com");
        attributes.put("name", "   ");
        attributes.put("login", null);

        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "id");

        when(userRepository.findByEmail("github@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded_github_pass");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArguments()[0]);
        when(jwtService.generateToken(any())).thenReturn("github-jwt");

        AuthResponse response = authService.processGithubLogin(oauth2User);

        assertEquals("github-jwt", response.getAccessToken());
        assertEquals("refresh-token-123", response.getRefreshToken());
        verify(userRepository).save(argThat(user ->
                user.getEmail().equals("github@test.com")
                        && user.getFullName().equals("GitHub User")
                        && user.getProvider() == AuthProvider.GITHUB
        ));
    }

    @Test
    void processGithubLogin_ShouldFallbackName_WhenNameIsExplicitlyNull() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", 123456);
        attributes.put("email", "github@test.com");
        attributes.put("name", null);
        attributes.put("login", "githubuser");

        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "id");

        when(userRepository.findByEmail("github@test.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded_github_pass");
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArguments()[0]);
        when(jwtService.generateToken(any())).thenReturn("github-jwt");

        AuthResponse response = authService.processGithubLogin(oauth2User);

        assertEquals("github-jwt", response.getAccessToken());
        assertEquals("refresh-token-123", response.getRefreshToken());
        verify(userRepository).save(argThat(user ->
                user.getEmail().equals("github@test.com")
                        && user.getFullName().equals("GitHub User")
                        && user.getProvider() == AuthProvider.GITHUB
        ));
    }
}
