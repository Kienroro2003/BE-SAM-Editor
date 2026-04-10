package com.sam.besameditor.analysis;

import java.util.List;

public record FunctionAnalysisDraft(
        String functionName,
        String signature,
        int startLine,
        int endLine,
        int cyclomaticComplexity,
        List<GraphNodeDraft> nodes,
        List<GraphEdgeDraft> edges,
        String entryNodeId,
        List<String> exitNodeIds) {
}
