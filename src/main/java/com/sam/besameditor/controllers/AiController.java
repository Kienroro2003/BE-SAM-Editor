package com.sam.besameditor.controllers;

import com.sam.besameditor.dto.AiSuggestTestsRequest;
import com.sam.besameditor.services.AiRecommendationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiRecommendationService aiRecommendationService;
    private final String groqApiKey;

    public AiController(
            AiRecommendationService aiRecommendationService,
            @Value("${GROQ_API_KEY:}") String groqApiKey) {
        this.aiRecommendationService = aiRecommendationService;
        this.groqApiKey = groqApiKey;
    }

    @PostMapping(value = "/suggest-tests", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> suggestTests(
            @Valid @RequestBody AiSuggestTestsRequest request,
            Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalArgumentException("Unauthorized request");
        }

        if (groqApiKey == null || groqApiKey.isBlank()) {
            throw new IllegalArgumentException("GROQ_API_KEY is not configured");
        }

        return aiRecommendationService.suggestTests(request, groqApiKey);
    }
}
