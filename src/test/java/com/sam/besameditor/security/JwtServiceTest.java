package com.sam.besameditor.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    private JwtService jwtService;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        // Use a base64 encoded string of 32+ bytes for HMAC-SHA256
        String secret = "dGhpc2lzYXZlcnlzZWN1cmVzZWNyZXRrZXlmb3Jqd3R0ZXN0aW5nMTIzNDU2Nzg5MA=="; 
        ReflectionTestUtils.setField(jwtService, "jwtSecret", secret);
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", 3600000L); // 1 hour

        userDetails = new User("test@test.com", "password", Collections.emptyList());
    }

    @Test
    void generateToken_ShouldReturnValidJwtString() {
        // Act
        String token = jwtService.generateToken(userDetails);

        // Assert
        assertNotNull(token);
        assertFalse(token.isEmpty());
        // JWTs have 3 parts separated by dots
        assertEquals(3, token.split("\\.").length);
    }

    @Test
    void extractUsername_ShouldReturnCorrectSubject() {
        // Arrange
        String token = jwtService.generateToken(userDetails);

        // Act
        String username = jwtService.extractUsername(token);

        // Assert
        assertEquals("test@test.com", username);
    }

    @Test
    void isTokenValid_ShouldReturnTrue_WhenTokenIsValidAndMatchesUser() {
        // Arrange
        String token = jwtService.generateToken(userDetails);

        // Act
        boolean isValid = jwtService.isTokenValid(token, userDetails);

        // Assert
        assertTrue(isValid);
    }

    @Test
    void isTokenValid_ShouldReturnFalse_WhenUserDoesNotMatch() {
        // Arrange
        String token = jwtService.generateToken(userDetails);
        UserDetails differentUser = new User("other@test.com", "password", Collections.emptyList());

        // Act
        boolean isValid = jwtService.isTokenValid(token, differentUser);

        // Assert
        assertFalse(isValid);
    }

    @Test
    void extractUsername_ShouldThrowException_WhenSignatureIsInvalid() {
        // Arrange
        String token = jwtService.generateToken(userDetails);
        // Tamper with the token signature
        String tamperedToken = token + "invalid";

        // Act & Assert
        assertThrows(SignatureException.class, () -> jwtService.extractUsername(tamperedToken));
    }

    @Test
    void extractUsername_ShouldThrowException_WhenTokenIsExpired() {
        // Arrange - set expiration to 1 ms
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", 1L);
        String token = jwtService.generateToken(userDetails);

        // Wait to ensure the token expires
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Act & Assert
        assertThrows(ExpiredJwtException.class, () -> jwtService.extractUsername(token));
    }

    @Test
    void extractUsername_ShouldThrowException_WhenTokenIsMalformed() {
        // Arrange
        String malformedToken = "this.is.notajwt";

        // Act & Assert
        assertThrows(MalformedJwtException.class, () -> jwtService.extractUsername(malformedToken));
    }

    @Test
    void isTokenValid_ShouldReturnFalse_WhenTokenExpired() throws InterruptedException {
        ReflectionTestUtils.setField(jwtService, "jwtExpirationMs", 1L);
        String token = jwtService.generateToken(userDetails);
        Thread.sleep(10);

        assertFalse(jwtService.isTokenValid(token, userDetails));
    }

    @Test
    void isTokenValid_ShouldReturnFalse_WhenTokenMalformed() {
        assertFalse(jwtService.isTokenValid("bad.token.value", userDetails));
    }

    @Test
    void isTokenExpired_PrivateMethod_ShouldReturnFalse_ForFreshToken() {
        String token = jwtService.generateToken(userDetails);

        Boolean expired = ReflectionTestUtils.invokeMethod(jwtService, "isTokenExpired", token);

        assertNotNull(expired);
        assertFalse(expired);
    }
}
