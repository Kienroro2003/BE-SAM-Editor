package com.sam.besameditor.services;

import com.sam.besameditor.exceptions.UpstreamServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@Component
public class GithubContentsApiClient implements GithubRepositoryTreeClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private final WebClient webClient;

    public GithubContentsApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${app.github.api-base:https://api.github.com}") String githubApiBase) {
        this.webClient = webClientBuilder
                .baseUrl(githubApiBase)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "BE-SAM-Editor")
                .build();
    }

    @Override
    public List<GithubContentItem> listDirectory(String owner, String repo, String path) {
        String encodedPath = UriUtils.encodePath(path == null ? "" : path, StandardCharsets.UTF_8);
        String endpoint = encodedPath.isBlank()
                ? "/repos/" + owner + "/" + repo + "/contents"
                : "/repos/" + owner + "/" + repo + "/contents/" + encodedPath;

        try {
            List<GithubContentsItemResponse> items = webClient.get()
                    .uri(endpoint)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToFlux(GithubContentsItemResponse.class)
                    .collectList()
                    .timeout(REQUEST_TIMEOUT)
                    .block();

            if (items == null) {
                return List.of();
            }
            return items.stream()
                    .map(item -> new GithubContentItem(item.getPath(), item.getType(), item.getSize()))
                    .toList();
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new UpstreamServiceException(HttpStatus.NOT_FOUND, "GitHub repository not found or inaccessible.");
            }
            if (ex.getStatusCode() == HttpStatus.FORBIDDEN || ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                throw new UpstreamServiceException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub API rate limit reached.");
            }
            throw new UpstreamServiceException(HttpStatus.BAD_GATEWAY, "GitHub API request failed.");
        } catch (Exception ex) {
            throw new UpstreamServiceException(HttpStatus.BAD_GATEWAY, "Failed to communicate with GitHub API.");
        }
    }

    public static class GithubContentsItemResponse {
        private String path;
        private String type;
        private Long size;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Long getSize() {
            return size;
        }

        public void setSize(Long size) {
            this.size = size;
        }
    }
}
