package com.sam.besameditor.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaFileCoverageResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serialization_ShouldHideStdoutAndStderr() throws Exception {
        JavaFileCoverageResponse response = new JavaFileCoverageResponse(
                55L,
                10L,
                "src/App.java",
                "JAVA",
                "FAILED",
                1,
                false,
                "./gradlew test",
                "stdout text",
                "stderr text",
                null,
                null,
                List.of());

        String json = objectMapper.writeValueAsString(response);

        assertTrue(json.contains("\"status\":\"FAILED\""));
        assertFalse(json.contains("\"stdout\""));
        assertFalse(json.contains("\"stderr\""));
    }
}
