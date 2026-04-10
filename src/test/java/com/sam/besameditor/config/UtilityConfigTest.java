package com.sam.besameditor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilityConfigTest {

    @Test
    void jacksonConfig_ShouldCreateObjectMapperWithRegisteredModules() throws Exception {
        JacksonConfig jacksonConfig = new JacksonConfig();

        ObjectMapper objectMapper = jacksonConfig.objectMapper();
        String json = objectMapper.writeValueAsString(Map.of("createdAt", LocalDateTime.of(2026, 4, 10, 8, 30)));

        assertNotNull(objectMapper);
        assertTrue(json.contains("createdAt"));
    }

    @Test
    void webClientConfig_ShouldCreateBuilder() {
        WebClientConfig webClientConfig = new WebClientConfig();

        WebClient.Builder builder = webClientConfig.webClientBuilder();

        assertNotNull(builder);
        assertInstanceOf(WebClient.Builder.class, builder);
    }
}
