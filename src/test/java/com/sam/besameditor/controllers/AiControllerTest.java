package com.sam.besameditor.controllers;

import com.sam.besameditor.dto.AiSuggestTestsRequest;
import com.sam.besameditor.services.AiRecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiControllerTest {

    @Mock
    private AiRecommendationService aiRecommendationService;

    @Test
    void suggestTests_ShouldThrow_WhenAuthenticationIsMissing() {
        AiController aiController = new AiController(aiRecommendationService, "groq-key");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> aiController.suggestTests(createRequest(), null));

        assertEquals("Unauthorized request", exception.getMessage());
    }

    @Test
    void suggestTests_ShouldThrow_WhenGroqApiKeyIsMissing() {
        AiController aiController = new AiController(aiRecommendationService, "");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> aiController.suggestTests(createRequest(), authentication));

        assertEquals("GROQ_API_KEY is not configured", exception.getMessage());
    }

    @Test
    void suggestTests_ShouldDelegateToService_WhenRequestIsValid() {
        AiController aiController = new AiController(aiRecommendationService, "groq-key");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        AiSuggestTestsRequest request = createRequest();
        Flux<ServerSentEvent<String>> expected = Flux.just(
                ServerSentEvent.<String>builder("test chunk").event("token").build());

        when(aiRecommendationService.suggestTests(request, "groq-key")).thenReturn(expected);

        List<String> tokens = aiController.suggestTests(request, authentication)
                .map(ServerSentEvent::data)
                .collectList()
                .block();

        assertEquals(List.of("test chunk"), tokens);
        verify(aiRecommendationService).suggestTests(request, "groq-key");
    }

    private AiSuggestTestsRequest createRequest() {
        AiSuggestTestsRequest request = new AiSuggestTestsRequest();
        request.setSourceCode("public class App { int sum(int a, int b) { return a + b; } }");
        request.setTestCode("class AppTest { }");
        request.setLanguage("java");

        AiSuggestTestsRequest.CoverageResult coverageResult = new AiSuggestTestsRequest.CoverageResult();
        coverageResult.setCoveredLines(List.of(10));
        coverageResult.setUncoveredLines(List.of(11, 12));
        coverageResult.setCoveredBranches(List.of());
        coverageResult.setUncoveredBranches(List.of("L11#M1"));
        coverageResult.setCoveredFunctions(List.of("sum(int,int)"));
        coverageResult.setUncoveredFunctions(List.of("edgeCase(int,int)"));
        coverageResult.setCoveragePercentage(50.0);

        request.setCoverageResult(coverageResult);
        return request;
    }
}
