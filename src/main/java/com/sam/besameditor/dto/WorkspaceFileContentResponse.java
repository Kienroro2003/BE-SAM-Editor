package com.sam.besameditor.dto;

public class WorkspaceFileContentResponse {

    private final Long projectId;
    private final String path;
    private final String language;
    private final String content;
    private final long sizeBytes;

    public WorkspaceFileContentResponse(
            Long projectId,
            String path,
            String language,
            String content,
            long sizeBytes) {
        this.projectId = projectId;
        this.path = path;
        this.language = language;
        this.content = content;
        this.sizeBytes = sizeBytes;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getPath() {
        return path;
    }

    public String getLanguage() {
        return language;
    }

    public String getContent() {
        return content;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }
}
