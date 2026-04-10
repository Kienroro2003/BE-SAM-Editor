package com.sam.besameditor.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JavaSourceAnalyzerTest {

    private final JavaSourceAnalyzer analyzer = new JavaSourceAnalyzer();

    @Test
    void analyze_ShouldExtractMethodsCcAndCfg() {
        String source = """
                package sample;

                class Calculator {
                    int divide(int a, int b) {
                        if (b == 0 || a < 0) {
                            return 0;
                        }
                        return a / b;
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("src/Calculator.java", source);

        assertEquals(1, result.functions().size());
        FunctionAnalysisDraft function = result.functions().get(0);
        assertEquals("divide", function.functionName());
        assertEquals("int divide(int a, int b)", function.signature());
        assertEquals(3, function.cyclomaticComplexity());
        assertFalse(function.nodes().isEmpty());
        assertFalse(function.edges().isEmpty());
        assertNotNull(function.entryNodeId());
        assertEquals(1, function.exitNodeIds().size());
        assertTrue(function.nodes().stream().anyMatch(node -> "CONDITION".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "RETURN".equals(node.type())));
    }

    @Test
    void analyze_ShouldHandleLoopAndSwitchWithoutCrashing() {
        String source = """
                class FlowSample {
                    int sample(int value) {
                        for (int i = 0; i < value; i++) {
                            if (i == 2) {
                                break;
                            }
                        }
                        switch (value) {
                            case 1:
                                return 1;
                            default:
                                return 0;
                        }
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("FlowSample.java", source);

        FunctionAnalysisDraft function = result.functions().get(0);
        assertTrue(function.cyclomaticComplexity() >= 4);
        assertTrue(function.nodes().stream().anyMatch(node -> "LOOP_CONDITION".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "SWITCH".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "BREAK".equals(node.type())));
    }

    @Test
    void analyze_ShouldThrowReadableError_WhenSyntaxInvalid() {
        String source = """
                class Broken {
                    void nope() {
                        if (true) {
                            System.out.println("broken");
                    }
                }
                """;

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> analyzer.analyze("Broken.java", source));

        assertTrue(exception.getMessage().startsWith("Syntax error at line"));
    }
}
