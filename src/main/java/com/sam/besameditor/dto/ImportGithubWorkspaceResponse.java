package com.sam.besameditor.dto;

public class ImportGithubWorkspaceResponse {

    private final Long projectId;
    private final String name;
    private final String sourceUrl;
    private final int totalFiles;
    private final long totalSizeBytes;
    private final String cloudinaryUrl;

    public ImportGithubWorkspaceResponse(
            Long projectId,
            String name,
            String sourceUrl,
            int totalFiles,
            long totalSizeBytes,
            String cloudinaryUrl) {
        this.projectId = projectId;
        this.name = name;
        this.sourceUrl = sourceUrl;
        this.totalFiles = totalFiles;
        this.totalSizeBytes = totalSizeBytes;
        this.cloudinaryUrl = cloudinaryUrl;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public int getTotalFiles() {
        return totalFiles;
    }

    public long getTotalSizeBytes() {
        return totalSizeBytes;
    }

    public String getCloudinaryUrl() {
        return cloudinaryUrl;
    }
}
