package com.sam.besameditor.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.besameditor.dto.AiSuggestTestsRequest;
import com.sam.besameditor.exceptions.UpstreamServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

    private static final Logger log = LoggerFactory.getLogger(AiRecommendationService.class);

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

            1) A short title in markdown bold, e.g. **Test for ...**
            2) A brief explanation (1-2 lines) in normal text
            3) A fenced code block with explicit language, e.g. ```java ... ```

            Leave exactly one blank line between title, explanation, and code block.
            Never collapse words together. Keep spaces and punctuation natural.
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

        Flux<String> completionFallback = Flux.defer(() -> requestSingleCompletion(userPrompt, apiKey));
        Flux<String> resolvedTokenStream = requestStreamedTokens(userPrompt, apiKey)
                .onErrorResume(ex -> {
                    // Keep upstream/provider errors as-is; fallback only for stream parsing/codec issues.
                    if (ex instanceof UpstreamServiceException) {
                        return Flux.error(ex);
                    }

                    log.warn("AI stream decoding failed, falling back to non-stream completion", ex);
                    return completionFallback;
                })
                .switchIfEmpty(completionFallback);

        return resolvedTokenStream
                .map(token -> ServerSentEvent.<String>builder(token).event("token").build())
                .onErrorMap(ex -> {
                    if (ex instanceof UpstreamServiceException) {
                        return ex;
                    }
                    return new UpstreamServiceException(HttpStatus.BAD_GATEWAY, "Failed to stream AI suggestions from Groq");
                });
    }

    private Flux<String> requestStreamedTokens(String userPrompt, String apiKey) {
        Map<String, Object> payload = buildPayload(userPrompt, true);
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(payload)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("Groq API error")
                        .map(body -> buildUpstreamException(response.statusCode().value(), body)))
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .map(ServerSentEvent::data)
                .filter(data -> data != null && !data.isBlank() && !"[DONE]".equals(data))
                .flatMap(this::extractContentToken);
    }

    private Flux<String> requestSingleCompletion(String userPrompt, String apiKey) {
        Map<String, Object> payload = buildPayload(userPrompt, false);
        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(payload)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("Groq API error")
                        .map(body -> buildUpstreamException(response.statusCode().value(), body)))
                .bodyToMono(String.class)
                .flatMapMany(this::extractCompletionContent);
    }

    private Map<String, Object> buildPayload(String userPrompt, boolean stream) {
        return Map.of(
                "model", groqModel,
                "stream", stream,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userPrompt)
                )
        );
    }

    private UpstreamServiceException buildUpstreamException(int rawStatusCode, String body) {
        HttpStatus status = HttpStatus.resolve(rawStatusCode);
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return new UpstreamServiceException(status, body);
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
            if (!(contentObj instanceof String content) || content.isEmpty()) {
                return Flux.empty();
            }
            return Flux.just(content);
        } catch (JsonProcessingException ex) {
            return Flux.empty();
        }
    }

    private Flux<String> extractCompletionContent(String rawJson) {
        try {
            Map<String, Object> data = objectMapper.readValue(rawJson, Map.class);
            Object choicesObj = data.get("choices");
            if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
                return Flux.error(new IllegalStateException("Groq completion response did not include choices"));
            }

            Object firstChoiceObj = choices.get(0);
            if (!(firstChoiceObj instanceof Map<?, ?> firstChoice)) {
                return Flux.error(new IllegalStateException("Groq completion choice is invalid"));
            }

            Object messageObj = firstChoice.get("message");
            if (!(messageObj instanceof Map<?, ?> message)) {
                return Flux.error(new IllegalStateException("Groq completion message is missing"));
            }

            Object contentObj = message.get("content");
            if (!(contentObj instanceof String content) || content.isBlank()) {
                return Flux.error(new IllegalStateException("Groq completion content is empty"));
            }

            return Flux.just(content);
        } catch (JsonProcessingException ex) {
            return Flux.error(new IllegalStateException("Unable to parse Groq completion response", ex));
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
