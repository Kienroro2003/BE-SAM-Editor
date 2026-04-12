package com.sam.besameditor.dto;

public class CoverageFunctionSummaryResponse {

    private final Long functionId;
    private final String functionName;
    private final String signature;
    private final int startLine;
    private final int endLine;
    private final int cyclomaticComplexity;
    private final String coverageStatus;
    private final int coveredLineCount;
    private final int missedLineCount;
    private final int coveredBranchCount;
    private final int missedBranchCount;

    public CoverageFunctionSummaryResponse(
            Long functionId,
            String functionName,
            String signature,
            int startLine,
            int endLine,
            int cyclomaticComplexity,
            String coverageStatus,
            int coveredLineCount,
            int missedLineCount,
            int coveredBranchCount,
            int missedBranchCount) {
        this.functionId = functionId;
        this.functionName = functionName;
        this.signature = signature;
        this.startLine = startLine;
        this.endLine = endLine;
        this.cyclomaticComplexity = cyclomaticComplexity;
        this.coverageStatus = coverageStatus;
        this.coveredLineCount = coveredLineCount;
        this.missedLineCount = missedLineCount;
        this.coveredBranchCount = coveredBranchCount;
        this.missedBranchCount = missedBranchCount;
    }

    public Long getFunctionId() {
        return functionId;
    }

    public String getFunctionName() {
        return functionName;
    }

    public String getSignature() {
        return signature;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public int getCyclomaticComplexity() {
        return cyclomaticComplexity;
    }

    public String getCoverageStatus() {
        return coverageStatus;
    }

    public int getCoveredLineCount() {
        return coveredLineCount;
    }

    public int getMissedLineCount() {
        return missedLineCount;
    }

    public int getCoveredBranchCount() {
        return coveredBranchCount;
    }

    public int getMissedBranchCount() {
        return missedBranchCount;
    }
}
