package com.sam.besameditor.dto;

import java.util.List;

public class FunctionCfgResponse {

    private final Long functionId;
    private final String functionName;
    private final String signature;
    private final int startLine;
    private final int endLine;
    private final int cyclomaticComplexity;
    private final String entryNodeId;
    private final List<String> exitNodeIds;
    private final List<AnalysisGraphNodeResponse> nodes;
    private final List<AnalysisGraphEdgeResponse> edges;
    private final Long coverageRunId;
    private final String coverageStatus;
    private final Integer coveredLineCount;
    private final Integer missedLineCount;
    private final Integer coveredBranchCount;
    private final Integer missedBranchCount;

    public FunctionCfgResponse(
            Long functionId,
            String functionName,
            String signature,
            int startLine,
            int endLine,
            int cyclomaticComplexity,
            String entryNodeId,
            List<String> exitNodeIds,
            List<AnalysisGraphNodeResponse> nodes,
            List<AnalysisGraphEdgeResponse> edges) {
        this(
                functionId,
                functionName,
                signature,
                startLine,
                endLine,
                cyclomaticComplexity,
                entryNodeId,
                exitNodeIds,
                nodes,
                edges,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public FunctionCfgResponse(
            Long functionId,
            String functionName,
            String signature,
            int startLine,
            int endLine,
            int cyclomaticComplexity,
            String entryNodeId,
            List<String> exitNodeIds,
            List<AnalysisGraphNodeResponse> nodes,
            List<AnalysisGraphEdgeResponse> edges,
            Long coverageRunId,
            String coverageStatus,
            Integer coveredLineCount,
            Integer missedLineCount,
            Integer coveredBranchCount,
            Integer missedBranchCount) {
        this.functionId = functionId;
        this.functionName = functionName;
        this.signature = signature;
        this.startLine = startLine;
        this.endLine = endLine;
        this.cyclomaticComplexity = cyclomaticComplexity;
        this.entryNodeId = entryNodeId;
        this.exitNodeIds = exitNodeIds;
        this.nodes = nodes;
        this.edges = edges;
        this.coverageRunId = coverageRunId;
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

    public String getEntryNodeId() {
        return entryNodeId;
    }

    public List<String> getExitNodeIds() {
        return exitNodeIds;
    }

    public List<AnalysisGraphNodeResponse> getNodes() {
        return nodes;
    }

    public List<AnalysisGraphEdgeResponse> getEdges() {
        return edges;
    }

    public Long getCoverageRunId() {
        return coverageRunId;
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
