package com.sam.besameditor.services;

import com.sam.besameditor.exceptions.UpstreamServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GithubContentsApiClientTest {

    @Test
    void listDirectory_ShouldReturnItems_AndEncodePath_AndApplyToken() {
        AtomicReference<ClientRequest> requestRef = new AtomicReference<>();
        GithubContentsApiClient client = new GithubContentsApiClient(
                WebClient.builder().exchangeFunction(request -> {
                    requestRef.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("""
                                    [
                                      {"path":"src/App.java","type":"file","size":12},
                                      {"path":"src/main","type":"dir","size":0}
                                    ]
                                    """)
                            .build());
                }),
                "https://api.github.com",
                "  secret-token  "
        );

        List<GithubRepositoryTreeClient.GithubContentItem> items =
                client.listDirectory("owner", "repo", "src main");

        assertEquals(2, items.size());
        assertEquals("src/App.java", items.get(0).path());
        assertEquals("file", items.get(0).type());
        assertEquals(12L, items.get(0).size());
        assertEquals("/repos/owner/repo/contents/src%20main", requestRef.get().url().getRawPath());
        assertEquals("Bearer secret-token", requestRef.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
        assertEquals(MediaType.APPLICATION_JSON_VALUE, requestRef.get().headers().getFirst(HttpHeaders.ACCEPT));
    }

    @Test
    void listDirectory_ShouldCallRootContentsEndpoint_WhenPathBlankAndNoTokenConfigured() {
        AtomicReference<ClientRequest> requestRef = new AtomicReference<>();
        GithubContentsApiClient client = new GithubContentsApiClient(
                WebClient.builder().exchangeFunction(request -> {
                    requestRef.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("[]")
                            .build());
                }),
                "https://api.github.com",
                ""
        );

        List<GithubRepositoryTreeClient.GithubContentItem> items =
                client.listDirectory("owner", "repo", "");

        assertEquals(List.of(), items);
        assertEquals("/repos/owner/repo/contents", requestRef.get().url().getRawPath());
        assertNull(requestRef.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void listDirectory_ShouldTranslateNotFoundError() {
        GithubContentsApiClient client = new GithubContentsApiClient(
                builderResponding(HttpStatus.NOT_FOUND, "{\"message\":\"Not Found\"}"),
                "https://api.github.com",
                ""
        );

        UpstreamServiceException exception = assertThrows(
                UpstreamServiceException.class,
                () -> client.listDirectory("owner", "repo", "src"));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("GitHub repository not found or inaccessible.", exception.getMessage());
    }

    @Test
    void listDirectory_ShouldTranslateRateLimitErrors() {
        GithubContentsApiClient forbiddenClient = new GithubContentsApiClient(
                builderResponding(HttpStatus.FORBIDDEN, "{\"message\":\"Forbidden\"}"),
                "https://api.github.com",
                ""
        );
        GithubContentsApiClient throttledClient = new GithubContentsApiClient(
                builderResponding(HttpStatus.TOO_MANY_REQUESTS, "{\"message\":\"Too Many Requests\"}"),
                "https://api.github.com",
                ""
        );

        UpstreamServiceException forbidden = assertThrows(
                UpstreamServiceException.class,
                () -> forbiddenClient.listDirectory("owner", "repo", "src"));
        UpstreamServiceException throttled = assertThrows(
                UpstreamServiceException.class,
                () -> throttledClient.listDirectory("owner", "repo", "src"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, forbidden.getStatus());
        assertEquals("GitHub API rate limit reached.", forbidden.getMessage());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, throttled.getStatus());
        assertEquals("GitHub API rate limit reached.", throttled.getMessage());
    }

    @Test
    void listDirectory_ShouldTranslateUnexpectedHttpError() {
        GithubContentsApiClient client = new GithubContentsApiClient(
                builderResponding(HttpStatus.INTERNAL_SERVER_ERROR, "{\"message\":\"boom\"}"),
                "https://api.github.com",
                ""
        );

        UpstreamServiceException exception = assertThrows(
                UpstreamServiceException.class,
                () -> client.listDirectory("owner", "repo", "src"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("GitHub API request failed.", exception.getMessage());
    }

    @Test
    void listDirectory_ShouldTranslateTransportFailure() {
        GithubContentsApiClient client = new GithubContentsApiClient(
                WebClient.builder().exchangeFunction(request -> Mono.error(new RuntimeException("boom"))),
                "https://api.github.com",
                ""
        );

        UpstreamServiceException exception = assertThrows(
                UpstreamServiceException.class,
                () -> client.listDirectory("owner", "repo", "src"));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatus());
        assertEquals("Failed to communicate with GitHub API.", exception.getMessage());
    }

    @Test
    void githubContentsItemResponse_GetterSetter_ShouldWork() {
        GithubContentsApiClient.GithubContentsItemResponse response = new GithubContentsApiClient.GithubContentsItemResponse();
        response.setPath("src/App.java");
        response.setType("file");
        response.setSize(42L);

        assertEquals("src/App.java", response.getPath());
        assertEquals("file", response.getType());
        assertEquals(42L, response.getSize());
    }

    private WebClient.Builder builderResponding(HttpStatus status, String body) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body)
                .build()));
    }
}
