package com.sam.besameditor.services;

import com.sam.besameditor.models.RefreshToken;
import com.sam.besameditor.models.User;
import com.sam.besameditor.repositories.RefreshTokenRepository;
import com.sam.besameditor.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshExpirationDays", 7);
    }

    @Test
    void createRefreshToken_ShouldDeleteOldTokenAndCreateNewToken() {
        User user = new User();
        user.setId(1L);
        user.setEmail("test@test.com");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RefreshToken token = refreshTokenService.createRefreshToken(1L);

        assertNotNull(token.getToken());
        assertEquals(user, token.getUser());
        assertTrue(token.getExpiresAt().isAfter(LocalDateTime.now().plusDays(6)));
        verify(refreshTokenRepository).deleteByUser_Id(1L);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void findByTokenOrThrow_ShouldThrow_WhenTokenNotFound() {
        when(refreshTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        BadCredentialsException exception = assertThrows(BadCredentialsException.class,
                () -> refreshTokenService.findByTokenOrThrow("missing"));

        assertEquals("Invalid refresh token", exception.getMessage());
    }

    @Test
    void verifyExpiration_ShouldThrowAndDelete_WhenTokenExpired() {
        RefreshToken token = new RefreshToken();
        token.setToken("expired-token");
        token.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        BadCredentialsException exception = assertThrows(BadCredentialsException.class,
                () -> refreshTokenService.verifyExpiration(token));

        assertEquals("Refresh token expired", exception.getMessage());
        verify(refreshTokenRepository).deleteByToken("expired-token");
    }

    @Test
    void verifyExpiration_ShouldReturnToken_WhenTokenValid() {
        RefreshToken token = new RefreshToken();
        token.setToken("valid-token");
        token.setExpiresAt(LocalDateTime.now().plusDays(1));

        RefreshToken result = refreshTokenService.verifyExpiration(token);

        assertEquals(token, result);
        verify(refreshTokenRepository, never()).deleteByToken(any());
    }

    @Test
    void deleteByUserId_ShouldDelegateToRepository() {
        refreshTokenService.deleteByUserId(7L);
        verify(refreshTokenRepository).deleteByUser_Id(7L);
    }

    @Test
    void deleteByToken_ShouldDelegateToRepository() {
        refreshTokenService.deleteByToken("token-1");
        verify(refreshTokenRepository).deleteByToken("token-1");
    }
}
