package com.sam.besameditor.dto;

import com.sam.besameditor.models.ProjectSourceType;

import java.time.LocalDateTime;

public class WorkspaceSummaryResponse {

    private final Long projectId;
    private final String name;
    private final ProjectSourceType sourceType;
    private final String sourceUrl;
    private final String cloudinaryUrl;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public WorkspaceSummaryResponse(
            Long projectId,
            String name,
            ProjectSourceType sourceType,
            String sourceUrl,
            String cloudinaryUrl,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        this.projectId = projectId;
        this.name = name;
        this.sourceType = sourceType;
        this.sourceUrl = sourceUrl;
        this.cloudinaryUrl = cloudinaryUrl;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public ProjectSourceType getSourceType() {
        return sourceType;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getCloudinaryUrl() {
        return cloudinaryUrl;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
