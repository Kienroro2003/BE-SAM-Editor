package com.sam.besameditor.dto;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DtoTest {

    @Test
    void registerRequest_GetterSetter_ShouldWork() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@test.com");
        request.setFullName("Test User");
        request.setPassword("password123");

        assertEquals("test@test.com", request.getEmail());
        assertEquals("Test User", request.getFullName());
        assertEquals("password123", request.getPassword());
    }

    @Test
    void loginRequest_GetterSetter_ShouldWork() {
        LoginRequest request = new LoginRequest();
        request.setEmail("test@test.com");
        request.setPassword("password123");

        assertEquals("test@test.com", request.getEmail());
        assertEquals("password123", request.getPassword());
    }

    @Test
    void verifyOtpRequest_GetterSetter_ShouldWork() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("test@test.com");
        request.setOtpCode("123456");

        assertEquals("test@test.com", request.getEmail());
        assertEquals("123456", request.getOtpCode());
    }

    @Test
    void authResponse_ConstructorAndGetters_ShouldWork() {
        AuthResponse response = new AuthResponse("token", "refresh", "test@test.com", "Test User");

        assertEquals("token", response.getAccessToken());
        assertEquals("refresh", response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("test@test.com", response.getEmail());
        assertEquals("Test User", response.getFullName());
    }

    @Test
    void refreshTokenRequest_GetterSetter_ShouldWork() {
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken("refresh-token");

        assertEquals("refresh-token", request.getRefreshToken());
    }

    @Test
    void authResponse_ConstructorWithoutRefreshToken_ShouldDefaultRefreshTokenToNull() {
        AuthResponse response = new AuthResponse("token", "test@test.com", "Test User");

        assertEquals("token", response.getAccessToken());
        assertNull(response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());
        assertEquals("test@test.com", response.getEmail());
        assertEquals("Test User", response.getFullName());
    }

    @Test
    void importRequests_GetterSetter_ShouldWork() {
        ImportGithubWorkspaceRequest githubRequest = new ImportGithubWorkspaceRequest();
        githubRequest.setRepoUrl("https://github.com/openai/sample");
        assertEquals("https://github.com/openai/sample", githubRequest.getRepoUrl());

        ImportLocalFolderWorkspaceRequest localRequest = new ImportLocalFolderWorkspaceRequest();
        localRequest.setFolderPath("/tmp/workspace");
        localRequest.setWorkspaceName("Local Workspace");
        assertEquals("/tmp/workspace", localRequest.getFolderPath());
        assertEquals("Local Workspace", localRequest.getWorkspaceName());
    }

    @Test
    void graphAndAnalysisResponses_ConstructorAndGetters_ShouldWork() {
        AnalysisGraphNodeResponse node = new AnalysisGraphNodeResponse("n1", "ENTRY", "start", 1, 2);
        AnalysisGraphEdgeResponse edge = new AnalysisGraphEdgeResponse("e1", "n1", "n2", "true");
        FunctionAnalysisSummaryResponse summary =
                new FunctionAnalysisSummaryResponse(10L, "average", "int average()", 3, 28, 4);
        FunctionCfgResponse cfg = new FunctionCfgResponse(
                10L,
                "average",
                "int average()",
                3,
                28,
                4,
                "n1",
                List.of("n2"),
                List.of(node),
                List.of(edge)
        );
        JavaFileAnalysisResponse analysis = new JavaFileAnalysisResponse(
                7L,
                "src/AverageCalculator.java",
                "JAVA",
                true,
                List.of(summary)
        );

        assertEquals("n1", node.getId());
        assertEquals("ENTRY", node.getType());
        assertEquals("start", node.getLabel());
        assertEquals(1, node.getStartLine());
        assertEquals(2, node.getEndLine());

        assertEquals("e1", edge.getId());
        assertEquals("n1", edge.getSource());
        assertEquals("n2", edge.getTarget());
        assertEquals("true", edge.getLabel());

        assertEquals(10L, summary.getFunctionId());
        assertEquals("average", summary.getFunctionName());
        assertEquals("int average()", summary.getSignature());
        assertEquals(3, summary.getStartLine());
        assertEquals(28, summary.getEndLine());
        assertEquals(4, summary.getCyclomaticComplexity());

        assertEquals(10L, cfg.getFunctionId());
        assertEquals("average", cfg.getFunctionName());
        assertEquals("int average()", cfg.getSignature());
        assertEquals(3, cfg.getStartLine());
        assertEquals(28, cfg.getEndLine());
        assertEquals(4, cfg.getCyclomaticComplexity());
        assertEquals("n1", cfg.getEntryNodeId());
        assertEquals(List.of("n2"), cfg.getExitNodeIds());
        assertEquals(List.of(node), cfg.getNodes());
        assertEquals(List.of(edge), cfg.getEdges());

        assertEquals(7L, analysis.getProjectId());
        assertEquals("src/AverageCalculator.java", analysis.getPath());
        assertEquals("JAVA", analysis.getLanguage());
        assertEquals(List.of(summary), analysis.getFunctions());
    }

    @Test
    void workspaceResponses_ConstructorAndGetters_ShouldWork() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 10, 10, 0);
        LocalDateTime updatedAt = createdAt.plusHours(1);
        WorkspaceTreeNodeResponse child = new WorkspaceTreeNodeResponse("App.java", "src/App.java", "file", "JAVA", null);
        WorkspaceTreeNodeResponse folder =
                new WorkspaceTreeNodeResponse("src", "src", "folder", null, List.of(child));
        WorkspaceTreeResponse tree = new WorkspaceTreeResponse(11L, "repo", List.of(folder));
        WorkspaceSummaryResponse summary = new WorkspaceSummaryResponse(
                11L,
                "repo",
                com.sam.besameditor.models.ProjectSourceType.LOCAL_FOLDER,
                "file:///tmp/repo",
                createdAt,
                updatedAt
        );
        WorkspaceFileContentResponse content =
                new WorkspaceFileContentResponse(11L, "src/App.java", "JAVA", "class App {}", 12L);
        ImportGithubWorkspaceResponse importResponse =
                new ImportGithubWorkspaceResponse(11L, "repo", "https://github.com/openai/repo", 2, 32L);
        DeleteWorkspaceResponse deleteWorkspace = new DeleteWorkspaceResponse(11L, 2, "deleted");
        DeleteWorkspaceFolderResponse deleteFolder =
                new DeleteWorkspaceFolderResponse(11L, "src", 1, "deleted");

        assertEquals("src", folder.getName());
        assertEquals("src", folder.getPath());
        assertEquals("folder", folder.getType());
        assertNull(folder.getLanguage());
        assertEquals(List.of(child), folder.getChildren());

        assertEquals(11L, tree.getProjectId());
        assertEquals("repo", tree.getProjectName());
        assertEquals(List.of(folder), tree.getNodes());

        assertEquals(11L, summary.getProjectId());
        assertEquals("repo", summary.getName());
        assertEquals(com.sam.besameditor.models.ProjectSourceType.LOCAL_FOLDER, summary.getSourceType());
        assertEquals("file:///tmp/repo", summary.getSourceUrl());
        assertEquals(createdAt, summary.getCreatedAt());
        assertEquals(updatedAt, summary.getUpdatedAt());

        assertEquals(11L, content.getProjectId());
        assertEquals("src/App.java", content.getPath());
        assertEquals("JAVA", content.getLanguage());
        assertEquals("class App {}", content.getContent());
        assertEquals(12L, content.getSizeBytes());

        assertEquals(11L, importResponse.getProjectId());
        assertEquals("repo", importResponse.getName());
        assertEquals("https://github.com/openai/repo", importResponse.getSourceUrl());
        assertEquals(2, importResponse.getTotalFiles());
        assertEquals(32L, importResponse.getTotalSizeBytes());

        assertEquals(11L, deleteWorkspace.getProjectId());
        assertEquals(2, deleteWorkspace.getDeletedFiles());
        assertEquals("deleted", deleteWorkspace.getMessage());

        assertEquals(11L, deleteFolder.getProjectId());
        assertEquals("src", deleteFolder.getPath());
        assertEquals(1, deleteFolder.getDeletedFiles());
        assertEquals("deleted", deleteFolder.getMessage());
    }
}
