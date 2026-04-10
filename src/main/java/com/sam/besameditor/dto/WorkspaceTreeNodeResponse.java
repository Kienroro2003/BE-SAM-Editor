package com.sam.besameditor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkspaceTreeNodeResponse {

    private final String name;
    private final String path;
    private final String type;
    private final String language;
    private final List<WorkspaceTreeNodeResponse> children;

    public WorkspaceTreeNodeResponse(
            String name,
            String path,
            String type,
            String language,
            List<WorkspaceTreeNodeResponse> children) {
        this.name = name;
        this.path = path;
        this.type = type;
        this.language = language;
        this.children = children;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public String getType() {
        return type;
    }

    public String getLanguage() {
        return language;
    }

    public List<WorkspaceTreeNodeResponse> getChildren() {
        return children;
    }
}
