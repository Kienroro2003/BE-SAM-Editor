package com.sam.besameditor.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordConfigTest {

    @Test
    void passwordEncoder_ShouldReturnBCryptEncoder() {
        PasswordConfig passwordConfig = new PasswordConfig();

        PasswordEncoder passwordEncoder = passwordConfig.passwordEncoder();

        assertInstanceOf(BCryptPasswordEncoder.class, passwordEncoder);
        assertTrue(passwordEncoder.matches("secret", passwordEncoder.encode("secret")));
    }
}
