package com.sam.besameditor.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.besameditor.dto.AiSuggestTestsRequest;
import com.sam.besameditor.exceptions.UpstreamServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Service
public class AiRecommendationService {

    private static final String SYSTEM_PROMPT = """
            You are an expert software testing assistant. Your ONLY job is to suggest additional test cases to improve code coverage.
            RULES:

            NEVER rewrite or modify existing test code
            ONLY suggest new test functions/cases that cover the uncovered parts
            Match the exact testing framework and style used in the existing test code
            Be minimal and optimal — one test per uncovered branch/function if possible
            For each suggestion, add a comment explaining which uncovered item it targets
            If coverage is already 100%, respond only with: \"Coverage is already at 100%. No additional tests needed.\"

            OUTPUT FORMAT:
            For each suggestion, provide:

            A brief explanation (1-2 lines) of what is uncovered and why
            The suggested test code snippet
            """;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String groqModel;

    public AiRecommendationService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${app.ai.groq.base-url:https://api.groq.com/openai/v1}") String groqBaseUrl,
            @Value("${app.ai.groq.model:llama-3.3-70b-versatile}") String groqModel) {
        this.webClient = webClientBuilder.baseUrl(groqBaseUrl).build();
        this.objectMapper = objectMapper;
        this.groqModel = groqModel;
    }

    public Flux<ServerSentEvent<String>> suggestTests(AiSuggestTestsRequest request, String apiKey) {
        if (request.getCoverageResult().isFullyCovered()) {
            return Flux.just(ServerSentEvent.builder("Coverage is already at 100%. No additional tests needed.").event("message").build());
        }

        String userPrompt = buildUserPrompt(request);

        Map<String, Object> payload = Map.of(
                "model", groqModel,
                "stream", true,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(payload)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("Groq API error")
                        .map(body -> {
                            HttpStatus status = HttpStatus.resolve(response.statusCode().value());
                            if (status == null) {
                                status = HttpStatus.BAD_GATEWAY;
                            }
                            return new UpstreamServiceException(status, body);
                        }))
                .bodyToFlux(String.class)
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .flatMap(this::parseSseChunk)
                .map(token -> ServerSentEvent.<String>builder(token).event("token").build())
                .onErrorMap(ex -> {
                    if (ex instanceof UpstreamServiceException) {
                        return ex;
                    }
                    return new UpstreamServiceException(HttpStatus.BAD_GATEWAY, "Failed to stream AI suggestions from Groq");
                });
    }

    private Flux<String> parseSseChunk(String rawChunk) {
        String[] lines = rawChunk.split("\\r?\\n");
        return Flux.fromArray(lines)
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim())
                .filter(data -> !data.isBlank() && !"[DONE]".equals(data))
                .flatMap(this::extractContentToken);
    }

    private Flux<String> extractContentToken(String jsonLine) {
        try {
            Map<String, Object> data = objectMapper.readValue(jsonLine, Map.class);
            Object choicesObj = data.get("choices");
            if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
                return Flux.empty();
            }
            Object firstChoiceObj = choices.get(0);
            if (!(firstChoiceObj instanceof Map<?, ?> firstChoice)) {
                return Flux.empty();
            }
            Object deltaObj = firstChoice.get("delta");
            if (!(deltaObj instanceof Map<?, ?> delta)) {
                return Flux.empty();
            }
            Object contentObj = delta.get("content");
            if (!(contentObj instanceof String content) || content.isBlank()) {
                return Flux.empty();
            }
            return Flux.just(content);
        } catch (JsonProcessingException ex) {
            return Flux.empty();
        }
    }

    private String buildUserPrompt(AiSuggestTestsRequest request) {
        String coverageJson = toJson(request.getCoverageResult().toMap());
        return """
                Analyze the following code coverage gap and suggest ONLY additional tests.

                [LANGUAGE]
                %s

                [SOURCE_CODE]
                %s

                [EXISTING_TEST_CODE]
                %s

                [COVERAGE_RESULT]
                %s

                Focus only on uncovered lines, branches, and functions. Suggest minimal and non-overlapping new tests.
                """.formatted(
                request.getLanguage(),
                request.getSourceCode(),
                request.getTestCode(),
                coverageJson
        );
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize coverageResult");
        }
    }
}
