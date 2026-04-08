package com.sam.besameditor.dto;

import jakarta.validation.constraints.NotBlank;

public class ImportGithubWorkspaceRequest {

    @NotBlank(message = "repoUrl is required")
    private String repoUrl;

    public String getRepoUrl() {
        return repoUrl;
    }

    public void setRepoUrl(String repoUrl) {
        this.repoUrl = repoUrl;
    }
}
