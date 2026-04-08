package com.sam.besameditor.services;

import com.sam.besameditor.dto.AuthResponse;
import com.sam.besameditor.dto.LoginRequest;
import com.sam.besameditor.dto.RegisterRequest;
import com.sam.besameditor.dto.VerifyOtpRequest;
import com.sam.besameditor.models.AuthProvider;
import com.sam.besameditor.models.EmailVerificationCode;
import com.sam.besameditor.models.User;
import com.sam.besameditor.repositories.EmailVerificationCodeRepository;
import com.sam.besameditor.repositories.UserRepository;
import com.sam.besameditor.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailVerificationCodeRepository otpRepository;
    private final EmailService emailService;

    @Value("${app.otp.expiry-minutes:5}")
    private int otpExpiryMinutes;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            EmailVerificationCodeRepository otpRepository,
            EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.otpRepository = otpRepository;
        this.emailService = emailService;
    }

    /**
     * Step 1/2: Save unverified user and send OTP email.
     */
    @Transactional
    public Map<String, String> register(RegisterRequest request) {
        userRepository.findByEmail(request.getEmail()).ifPresent(u -> {
            throw new IllegalArgumentException("Email already registered");
        });

        User user = new User();
        user.setEmail(request.getEmail());
        user.setFullName(request.getFullName());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setProvider(AuthProvider.LOCAL);
        user.setIsEmailVerified(false);
        userRepository.save(user);

        sendOtp(user);

        return Map.of("message", "Registration successful. Please check your email for the OTP code.");
    }

    /**
     * Step 2/2: Verify OTP, activate account, return JWT.
     */
    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        EmailVerificationCode otp = otpRepository
                .findTopByUserAndUsedFalseOrderByCreatedAtDesc(user)
                .orElseThrow(() -> new IllegalArgumentException("No pending OTP found. Please register again."));

        if (otp.isExpired()) {
            throw new IllegalArgumentException("OTP has expired. Please request a new one.");
        }

        if (!otp.getOtpCode().equals(request.getOtpCode())) {
            throw new BadCredentialsException("Invalid OTP code.");
        }

        otpRepository.markAllUsedByUser(user);
        user.setIsEmailVerified(true);
        userRepository.save(user);

        String token = jwtService.generateToken(buildUserDetails(user));
        return new AuthResponse(token, user.getEmail(), user.getFullName());
    }

    /**
     * Resend OTP for existing unverified user.
     */
    @Transactional
    public Map<String, String> resendOtp(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getIsEmailVerified()) {
            throw new IllegalStateException("Email is already verified.");
        }

        sendOtp(user);
        return Map.of("message", "A new OTP has been sent to your email.");
    }

    /**
     * Login — only verified accounts pass.
     */
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (!user.getIsEmailVerified()) {
            throw new IllegalStateException("Email not verified. Please verify your email first.");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String token = jwtService.generateToken(buildUserDetails(user));
        return new AuthResponse(token, user.getEmail(), user.getFullName());
    }

    /**
     * GitHub OAuth2 — auto-verified on first login.
     */
    public String processGithubLogin(OAuth2User oauth2User) {
        Map<String, Object> attributes = oauth2User.getAttributes();

        String email = (String) attributes.get("email");
        if (email == null || email.isBlank()) {
            String login = (String) attributes.get("login");
            email = login + "@users.noreply.github.com";
        }

        String name = (String) attributes.getOrDefault("name", attributes.get("login"));
        if (name == null || name.isBlank()) name = "GitHub User";

        String githubId = String.valueOf(attributes.get("id"));
        String avatarUrl = (String) attributes.get("avatar_url");

        String finalEmail = email;
        String finalName = name;
        String finalGithubId = githubId;

        User user = userRepository.findByEmail(finalEmail).orElseGet(() -> {
            User newUser = new User();
            newUser.setEmail(finalEmail);
            newUser.setFullName(finalName);
            newUser.setPassword(passwordEncoder.encode("oauth2-github-" + finalGithubId));
            newUser.setProvider(AuthProvider.GITHUB);
            newUser.setGithubId(finalGithubId);
            newUser.setAvatarUrl(avatarUrl);
            newUser.setIsEmailVerified(true);
            return userRepository.save(newUser);
        });

        return jwtService.generateToken(buildUserDetails(user));
    }

    // ── Private Helpers ──────────────────────────────────────────────

    private void sendOtp(User user) {
        String code = generateOtpCode();
        EmailVerificationCode otp = new EmailVerificationCode();
        otp.setUser(user);
        otp.setOtpCode(code);
        otp.setExpiresAt(LocalDateTime.now().plusMinutes(otpExpiryMinutes));
        otpRepository.save(otp);
        emailService.sendOtpEmail(user.getEmail(), code);
    }

    private String generateOtpCode() {
        return String.valueOf(100000 + new SecureRandom().nextInt(900000));
    }

    private UserDetails buildUserDetails(User user) {
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .roles("USER")
                .build();
    }
}
