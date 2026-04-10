package com.sam.besameditor.dto;

public class AnalysisGraphEdgeResponse {

    private final String id;
    private final String source;
    private final String target;
    private final String label;

    public AnalysisGraphEdgeResponse(String id, String source, String target, String label) {
        this.id = id;
        this.source = source;
        this.target = target;
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getTarget() {
        return target;
    }

    public String getLabel() {
        return label;
    }
}
