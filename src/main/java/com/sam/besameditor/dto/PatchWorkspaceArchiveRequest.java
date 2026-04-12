package com.sam.besameditor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PatchWorkspaceArchiveRequest {

    @NotBlank(message = "path is required")
    private String path;

    @NotNull(message = "content is required")
    private String content;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
