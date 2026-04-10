package com.sam.besameditor.analysis;

import java.util.List;

public record JavaFileAnalysisResult(
        String path,
        List<FunctionAnalysisDraft> functions) {
}
