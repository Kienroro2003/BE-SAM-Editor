package com.sam.besameditor.dto;

public class DeleteWorkspaceFolderResponse {

    private final Long projectId;
    private final String path;
    private final int deletedFiles;
    private final String message;

    public DeleteWorkspaceFolderResponse(Long projectId, String path, int deletedFiles, String message) {
        this.projectId = projectId;
        this.path = path;
        this.deletedFiles = deletedFiles;
        this.message = message;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getPath() {
        return path;
    }

    public int getDeletedFiles() {
        return deletedFiles;
    }

    public String getMessage() {
        return message;
    }
}
