package com.sam.besameditor.dto;

import java.util.List;

public class WorkspaceTreeResponse {

    private final Long projectId;
    private final String projectName;
    private final List<WorkspaceTreeNodeResponse> nodes;

    public WorkspaceTreeResponse(Long projectId, String projectName, List<WorkspaceTreeNodeResponse> nodes) {
        this.projectId = projectId;
        this.projectName = projectName;
        this.nodes = nodes;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getProjectName() {
        return projectName;
    }

    public List<WorkspaceTreeNodeResponse> getNodes() {
        return nodes;
    }
}
