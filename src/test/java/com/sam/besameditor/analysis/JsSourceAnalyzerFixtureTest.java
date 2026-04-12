package com.sam.besameditor.analysis;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsSourceAnalyzerFixtureTest {

    private final JsSourceAnalyzer analyzer = new JsSourceAnalyzer();

    @Test
    void analyze_ShouldHandleUserAccessFixture() throws IOException {
        FunctionAnalysisDraft function = analyzeFixture("fixtures/js/user-access.js");

        assertEquals("resolveUserAccess", function.functionName());
        assertTrue(function.cyclomaticComplexity() >= 4);
        assertTrue(function.nodes().stream().anyMatch(node -> "CONDITION".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "RETURN".equals(node.type())));
    }

    @Test
    void analyze_ShouldHandleOrderBatchFixture() throws IOException {
        FunctionAnalysisDraft function = analyzeFixture("fixtures/js/order-batch.js");

        assertEquals("processOrderBatch", function.functionName());
        assertTrue(function.cyclomaticComplexity() >= 4);
        assertTrue(function.nodes().stream().anyMatch(node -> "LOOP_CONDITION".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "CONTINUE".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "BREAK".equals(node.type())));
    }

    @Test
    void analyze_ShouldHandlePaymentStateFixture() throws IOException {
        FunctionAnalysisDraft function = analyzeFixture("fixtures/js/payment-state.js");

        assertEquals("mapPaymentState", function.functionName());
        assertTrue(function.cyclomaticComplexity() >= 4);
        assertTrue(function.nodes().stream().anyMatch(node -> "SWITCH".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "CASE".equals(node.type())));
    }

    @Test
    void analyze_ShouldHandleReportExportFixture() throws IOException {
        FunctionAnalysisDraft function = analyzeFixture("fixtures/js/report-export.js");

        assertEquals("exportReport", function.functionName());
        assertTrue(function.cyclomaticComplexity() >= 4);
        assertTrue(function.nodes().stream().anyMatch(node -> "TRY".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "CATCH".equals(node.type())));
        assertTrue(function.nodes().stream().anyMatch(node -> "LOOP_CONDITION".equals(node.type())));
    }

    private FunctionAnalysisDraft analyzeFixture(String resourcePath) throws IOException {
        String source = readResource(resourcePath);
        JavaFileAnalysisResult result = analyzer.analyze(resourcePath, source, "JAVASCRIPT");

        assertEquals(1, result.functions().size());
        FunctionAnalysisDraft function = result.functions().get(0);
        assertNotNull(function.entryNodeId());
        assertEquals(function.edges().size() - function.nodes().size() + 2, function.cyclomaticComplexity());
        return function;
    }

    private String readResource(String resourcePath) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(inputStream, "Missing test resource: " + resourcePath);
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
