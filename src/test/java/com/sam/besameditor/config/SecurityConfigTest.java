package com.sam.besameditor.config;

import com.sam.besameditor.security.CustomUserDetailsService;
import com.sam.besameditor.security.JwtAuthenticationFilter;
import com.sam.besameditor.security.OAuth2LoginSuccessHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityConfigTest {

    @Mock
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Mock
    private CustomUserDetailsService customUserDetailsService;

    @Mock
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ObjectProvider<ClientRegistrationRepository> clientRegistrationRepositoryProvider;

    @Mock
    private AuthenticationConfiguration authenticationConfiguration;

    @Mock
    private AuthenticationManager authenticationManager;

    @Test
    void authenticationProvider_ShouldAuthenticateUsingConfiguredServices() {
        SecurityConfig securityConfig = new SecurityConfig(
                jwtAuthenticationFilter,
                customUserDetailsService,
                oAuth2LoginSuccessHandler,
                passwordEncoder,
                clientRegistrationRepositoryProvider
        );

        UserDetails userDetails = new User("test@test.com", "encoded", Collections.emptyList());
        when(customUserDetailsService.loadUserByUsername("test@test.com")).thenReturn(userDetails);
        when(passwordEncoder.matches("raw", "encoded")).thenReturn(true);

        DaoAuthenticationProvider provider = securityConfig.authenticationProvider();
        Authentication auth = provider.authenticate(
                new UsernamePasswordAuthenticationToken("test@test.com", "raw"));

        assertNotNull(auth);
        assertEquals("test@test.com", auth.getName());
    }

    @Test
    void authenticationManager_ShouldReturnManagerFromConfiguration() throws Exception {
        SecurityConfig securityConfig = new SecurityConfig(
                jwtAuthenticationFilter,
                customUserDetailsService,
                oAuth2LoginSuccessHandler,
                passwordEncoder,
                clientRegistrationRepositoryProvider
        );

        when(authenticationConfiguration.getAuthenticationManager()).thenReturn(authenticationManager);

        AuthenticationManager result = securityConfig.authenticationManager(authenticationConfiguration);

        assertEquals(authenticationManager, result);
    }
}
