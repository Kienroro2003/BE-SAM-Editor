package com.sam.besameditor.controllers;

import com.sam.besameditor.dto.ImportGithubWorkspaceRequest;
import com.sam.besameditor.dto.ImportGithubWorkspaceResponse;
import com.sam.besameditor.dto.WorkspaceSummaryResponse;
import com.sam.besameditor.dto.WorkspaceTreeResponse;
import com.sam.besameditor.models.ProjectSourceType;
import com.sam.besameditor.services.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceControllerTest {

    @Mock
    private WorkspaceService workspaceService;

    @InjectMocks
    private WorkspaceController workspaceController;

    @Test
    void importGithubWorkspace_ShouldReturnCreated() {
        ImportGithubWorkspaceRequest request = new ImportGithubWorkspaceRequest();
        request.setRepoUrl("https://github.com/owner/repo");

        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        ImportGithubWorkspaceResponse serviceResponse =
                new ImportGithubWorkspaceResponse(1L, "repo", "https://github.com/owner/repo", 2, 30L);
        when(workspaceService.importFromGithub("https://github.com/owner/repo", "user@test.com"))
                .thenReturn(serviceResponse);

        ImportGithubWorkspaceResponse response = workspaceController
                .importGithubWorkspace(request, authentication)
                .getBody();

        assertEquals(1L, response.getProjectId());
        assertEquals("repo", response.getName());
        assertEquals(2, response.getTotalFiles());
    }

    @Test
    void importFolderZipWorkspace_ShouldReturnCreated() {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");
        MockMultipartFile file =
                new MockMultipartFile("file", "sample-workspace.zip", "application/zip", new byte[]{1, 2, 3});

        ImportGithubWorkspaceResponse serviceResponse =
                new ImportGithubWorkspaceResponse(5L, "sample-workspace", "upload://sample-workspace.zip", 3, 120L);
        when(workspaceService.importFromZip(any(), any(), any()))
                .thenReturn(serviceResponse);

        ImportGithubWorkspaceResponse response = workspaceController
                .importFolderZipWorkspace(file, "sample-workspace", authentication)
                .getBody();

        assertEquals(5L, response.getProjectId());
        assertEquals("sample-workspace", response.getName());
        assertEquals(3, response.getTotalFiles());
    }

    @Test
    void getMyWorkspaces_ShouldReturnWorkspaceList() {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        WorkspaceSummaryResponse item = new WorkspaceSummaryResponse(
                3L,
                "repo",
                ProjectSourceType.GITHUB,
                "https://github.com/owner/repo",
                LocalDateTime.now(),
                LocalDateTime.now());

        when(workspaceService.getUserWorkspaces("user@test.com")).thenReturn(List.of(item));

        List<WorkspaceSummaryResponse> response = workspaceController
                .getMyWorkspaces(authentication)
                .getBody();

        assertEquals(1, response.size());
        assertEquals("repo", response.get(0).getName());
    }

    @Test
    void getWorkspaceTree_ShouldReturnTree() {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        WorkspaceTreeResponse treeResponse = new WorkspaceTreeResponse(10L, "repo", List.of());
        when(workspaceService.getWorkspaceTree(10L, "user@test.com")).thenReturn(treeResponse);

        WorkspaceTreeResponse response = workspaceController
                .getWorkspaceTree(10L, authentication)
                .getBody();

        assertEquals(10L, response.getProjectId());
        assertEquals("repo", response.getProjectName());
    }
}
