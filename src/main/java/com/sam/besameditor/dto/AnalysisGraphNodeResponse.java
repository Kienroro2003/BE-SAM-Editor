package com.sam.besameditor.dto;

public class AnalysisGraphNodeResponse {

    private final String id;
    private final String type;
    private final String label;
    private final Integer startLine;
    private final Integer endLine;
    private final String coverageStatus;
    private final Integer coveredLineCount;
    private final Integer missedLineCount;
    private final Integer coveredBranchCount;
    private final Integer missedBranchCount;

    public AnalysisGraphNodeResponse(String id, String type, String label, Integer startLine, Integer endLine) {
        this(id, type, label, startLine, endLine, null, null, null, null, null);
    }

    public AnalysisGraphNodeResponse(
            String id,
            String type,
            String label,
            Integer startLine,
            Integer endLine,
            String coverageStatus,
            Integer coveredLineCount,
            Integer missedLineCount,
            Integer coveredBranchCount,
            Integer missedBranchCount) {
        this.id = id;
        this.type = type;
        this.label = label;
        this.startLine = startLine;
        this.endLine = endLine;
        this.coverageStatus = coverageStatus;
        this.coveredLineCount = coveredLineCount;
        this.missedLineCount = missedLineCount;
        this.coveredBranchCount = coveredBranchCount;
        this.missedBranchCount = missedBranchCount;
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

    public String getCoverageStatus() {
        return coverageStatus;
    }

    public Integer getCoveredLineCount() {
        return coveredLineCount;
    }

    public Integer getMissedLineCount() {
        return missedLineCount;
    }

    public Integer getCoveredBranchCount() {
        return coveredBranchCount;
    }

    public Integer getMissedBranchCount() {
        return missedBranchCount;
    }
}
