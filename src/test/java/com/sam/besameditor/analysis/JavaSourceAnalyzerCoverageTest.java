package com.sam.besameditor.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceAnalyzerCoverageTest {

    private final JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();

    @Test
    void analyze_ShouldSupportEnhancedForContinueTryCatchFinallyThrowDoWhileAndEmptySwitch() {
        String source = """
                class FlowCases {
                    int run(int[] values, int target) {
                        int total = 0;
                        for (int value : values) {
                            if (value < 0) {
                                continue;
                            }
                            try {
                                if (value == target) {
                                    throw new IllegalStateException();
                                }
                                total += value;
                            } catch (IllegalStateException ex) {
                                break;
                            } finally {
                                total++;
                            }
                        }
                        do {
                            total--;
                        } while (total > 10);
                        switch (target) {
                        }
                        return total;
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("FlowCases.java", source);
        FunctionAnalysisDraft function = result.functions().get(0);

        assertTrue(function.nodes().stream().anyMatch(node -> "CONTINUE".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "TRY".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "CATCH".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "THROW".equals(node.type())));
        assertTrue(function.nodes().stream().filter(node -> "LOOP_CONDITION".equals(node.type())).count() >= 2);
        assertTrue(function.nodes().stream().anyMatch(node -> "SWITCH".equals(node.type())));
        assertTrue(function.edges().stream().anyMatch(edge -> "continue".equals(edge.label())));
        assertTrue(function.cyclomaticComplexity() >= 5);
    }

    @Test
    void analyze_ShouldHandleNullPathEmptyStatementsAndImplicitElse() {
        String source = """
                class EmptyFlow {
                    void run() {
                        ;
                        if (true) {
                        }
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze(null, source);
        FunctionAnalysisDraft function = result.functions().get(0);

        assertEquals("run", function.functionName());
        assertTrue(function.nodes().stream().anyMatch(node -> "NOOP".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "CONDITION".equals(node.type())));
        assertTrue(function.cyclomaticComplexity() >= 2);
    }

    @Test
    void analyze_ShouldHandleSwitchCasesWithoutStatementsAndLabeledLoops() {
        String source = """
                class MixedFlow {
                    int run(int value) {
                        outer:
                        while (value > 0) {
                            switch (value) {
                                case 1:
                                default:
                            }
                            break outer;
                        }
                        return value;
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("MixedFlow.java", source);
        FunctionAnalysisDraft function = result.functions().get(0);

        assertTrue(function.nodes().stream().anyMatch(node -> "CASE".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "BREAK".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "JOIN".equals(node.type())));
        assertTrue(function.edges().stream().anyMatch(edge -> "break".equals(edge.label())));
    }
}
