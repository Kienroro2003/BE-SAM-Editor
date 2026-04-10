package com.sam.besameditor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ImportLocalFolderWorkspaceRequest {

    @NotBlank(message = "folderPath is required")
    private String folderPath;

    @Size(max = 255, message = "workspaceName must be at most 255 characters")
    private String workspaceName;

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public String getWorkspaceName() {
        return workspaceName;
    }

    public void setWorkspaceName(String workspaceName) {
        this.workspaceName = workspaceName;
    }
}
