package com.sam.besameditor.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.security.oauth2.client.registration.github.client-id=test-client-id",
        "spring.security.oauth2.client.registration.github.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.github.scope=read:user,user:email"
})
class SecurityConfigOAuth2IntegrationTest {

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    private SecurityFilterChain securityFilterChain;

    @Test
    void context_ShouldProvideOauth2Registration_AndSecurityFilterChain() {
        assertNotNull(clientRegistrationRepository);
        assertNotNull(securityFilterChain);
    }
}
