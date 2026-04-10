package com.sam.besameditor.dto;

public class AnalysisGraphNodeResponse {

    private final String id;
    private final String type;
    private final String label;
    private final Integer startLine;
    private final Integer endLine;

    public AnalysisGraphNodeResponse(String id, String type, String label, Integer startLine, Integer endLine) {
        this.id = id;
        this.type = type;
        this.label = label;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getLabel() {
        return label;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public Integer getEndLine() {
        return endLine;
    }
}
