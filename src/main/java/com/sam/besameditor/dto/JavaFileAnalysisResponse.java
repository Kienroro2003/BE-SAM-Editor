package com.sam.besameditor.dto;

import java.util.List;

public class JavaFileAnalysisResponse {

    private final Long projectId;
    private final String path;
    private final String language;
    private final boolean cached;
    private final List<FunctionAnalysisSummaryResponse> functions;

    public JavaFileAnalysisResponse(
            Long projectId,
            String path,
            String language,
            boolean cached,
            List<FunctionAnalysisSummaryResponse> functions) {
        this.projectId = projectId;
        this.path = path;
        this.language = language;
        this.cached = cached;
        this.functions = functions;
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

    public boolean isCached() {
        return cached;
    }

    public List<FunctionAnalysisSummaryResponse> getFunctions() {
        return functions;
    }
}
