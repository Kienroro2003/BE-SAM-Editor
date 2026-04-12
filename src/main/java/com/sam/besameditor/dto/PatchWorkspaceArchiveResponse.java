package com.sam.besameditor.dto;

public class PatchWorkspaceArchiveResponse {

    private final Long projectId;
    private final String path;
    private final String cloudinaryUrl;
    private final int contentBytes;
    private final String message;

    public PatchWorkspaceArchiveResponse(Long projectId, String path, String cloudinaryUrl, int contentBytes, String message) {
        this.projectId = projectId;
        this.path = path;
        this.cloudinaryUrl = cloudinaryUrl;
        this.contentBytes = contentBytes;
        this.message = message;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getPath() {
        return path;
    }

    public String getCloudinaryUrl() {
        return cloudinaryUrl;
    }

    public int getContentBytes() {
        return contentBytes;
    }

    public String getMessage() {
        return message;
    }
}
