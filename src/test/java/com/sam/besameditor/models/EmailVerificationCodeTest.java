package com.sam.besameditor.models;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailVerificationCodeTest {

    @Test
    void isExpired_ShouldReturnTrue_WhenPastExpiry() {
        EmailVerificationCode code = new EmailVerificationCode();
        code.setExpiresAt(LocalDateTime.now().minusMinutes(1));

        assertTrue(code.isExpired());
    }

    @Test
    void isExpired_ShouldReturnFalse_WhenFutureExpiry() {
        EmailVerificationCode code = new EmailVerificationCode();
        code.setExpiresAt(LocalDateTime.now().plusMinutes(1));

        assertFalse(code.isExpired());
    }
}
