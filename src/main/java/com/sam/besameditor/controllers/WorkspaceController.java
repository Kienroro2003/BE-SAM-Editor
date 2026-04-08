package com.sam.besameditor.controllers;

import com.sam.besameditor.dto.ImportGithubWorkspaceRequest;
import com.sam.besameditor.dto.ImportGithubWorkspaceResponse;
import com.sam.besameditor.dto.WorkspaceSummaryResponse;
import com.sam.besameditor.dto.WorkspaceTreeResponse;
import com.sam.besameditor.services.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @PostMapping("/import/github")
    public ResponseEntity<ImportGithubWorkspaceResponse> importGithubWorkspace(
            @Valid @RequestBody ImportGithubWorkspaceRequest request,
            Authentication authentication) {
        ImportGithubWorkspaceResponse response =
                workspaceService.importFromGithub(request.getRepoUrl(), authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<WorkspaceSummaryResponse>> getMyWorkspaces(Authentication authentication) {
        return ResponseEntity.ok(workspaceService.getUserWorkspaces(authentication.getName()));
    }

    @GetMapping("/{projectId}/tree")
    public ResponseEntity<WorkspaceTreeResponse> getWorkspaceTree(
            @PathVariable Long projectId,
            Authentication authentication) {
        return ResponseEntity.ok(workspaceService.getWorkspaceTree(projectId, authentication.getName()));
    }
}
