package com.sam.besameditor.dto;

public class FunctionAnalysisSummaryResponse {

    private final Long functionId;
    private final String functionName;
    private final String signature;
    private final int startLine;
    private final int endLine;
    private final int cyclomaticComplexity;

    public FunctionAnalysisSummaryResponse(
            Long functionId,
            String functionName,
            String signature,
            int startLine,
            int endLine,
            int cyclomaticComplexity) {
        this.functionId = functionId;
        this.functionName = functionName;
        this.signature = signature;
        this.startLine = startLine;
        this.endLine = endLine;
        this.cyclomaticComplexity = cyclomaticComplexity;
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
}
