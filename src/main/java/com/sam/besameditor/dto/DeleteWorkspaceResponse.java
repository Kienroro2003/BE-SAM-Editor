package com.sam.besameditor.dto;

public class DeleteWorkspaceResponse {

    private final Long projectId;
    private final int deletedFiles;
    private final String message;

    public DeleteWorkspaceResponse(Long projectId, int deletedFiles, String message) {
        this.projectId = projectId;
        this.deletedFiles = deletedFiles;
        this.message = message;
    }

    public Long getProjectId() {
        return projectId;
    }

    public int getDeletedFiles() {
        return deletedFiles;
    }

    public String getMessage() {
        return message;
    }
}
