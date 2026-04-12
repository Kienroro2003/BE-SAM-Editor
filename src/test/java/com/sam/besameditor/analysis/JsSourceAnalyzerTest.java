package com.sam.besameditor.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsSourceAnalyzerTest {

    private final JsSourceAnalyzer analyzer = new JsSourceAnalyzer();

    // -------------------------------------------------------------------------
    // Basic function declarations
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldExtractSimpleFunctionDeclaration() {
        String source = """
                function add(a, b) {
                    return a + b;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("utils.js", source, "JAVASCRIPT");

        assertEquals(1, result.functions().size());
        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals("add", fn.functionName());
        assertTrue(fn.signature().contains("add"));
        assertTrue(fn.signature().contains("(a, b)"));
        assertEquals(1, fn.cyclomaticComplexity());
        assertNotNull(fn.entryNodeId());
        assertEquals(1, fn.exitNodeIds().size());
        assertFalse(fn.nodes().isEmpty());
        assertFalse(fn.edges().isEmpty());
        assertTrue(fn.nodes().stream().anyMatch(n -> "ENTRY".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "EXIT".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "RETURN".equals(n.type())));
    }

    @Test
    void analyze_ShouldExtractArrowFunction() {
        String source = """
                const multiply = (x, y) => {
                    return x * y;
                };
                """;

        JavaFileAnalysisResult result = analyzer.analyze("math.js", source, "JAVASCRIPT");

        assertEquals(1, result.functions().size());
        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals("multiply", fn.functionName());
        assertTrue(fn.signature().contains("multiply"));
        assertEquals(1, fn.cyclomaticComplexity());
    }

    @Test
    void analyze_ShouldExtractClassMethod() {
        String source = """
                class Calculator {
                    add(a, b) {
                        return a + b;
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("calc.js", source, "JAVASCRIPT");

        assertEquals(1, result.functions().size());
        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals("Calculator.add", fn.functionName());
        assertTrue(fn.signature().contains("Calculator.add"));
    }

    // -------------------------------------------------------------------------
    // If / else
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldBuildCfgForIfElse() {
        String source = """
                function check(value) {
                    if (value > 0) {
                        return "positive";
                    } else {
                        return "non-positive";
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("check.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(2, fn.cyclomaticComplexity());
        assertEquals(fn.edges().size() - fn.nodes().size() + 2, fn.cyclomaticComplexity());
        assertTrue(fn.nodes().stream().anyMatch(n -> "CONDITION".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "RETURN".equals(n.type())));
    }

    @Test
    void analyze_ShouldHandleIfWithoutElse() {
        String source = """
                function maybeLog(x) {
                    if (x > 10) {
                        console.log(x);
                    }
                    return x;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("log.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(2, fn.cyclomaticComplexity());
    }

    @Test
    void analyze_ShouldHandleNestedIfElse() {
        String source = """
                function classify(x) {
                    if (x > 0) {
                        if (x > 100) {
                            return "big";
                        } else {
                            return "small positive";
                        }
                    } else {
                        return "non-positive";
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("classify.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(3, fn.cyclomaticComplexity());
    }

    // -------------------------------------------------------------------------
    // Loops
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldBuildCfgForWhileLoop() {
        String source = """
                function countdown(n) {
                    while (n > 0) {
                        n--;
                    }
                    return n;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("loop.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(2, fn.cyclomaticComplexity());
        assertTrue(fn.nodes().stream().anyMatch(n -> "LOOP_CONDITION".equals(n.type())));
    }

    @Test
    void analyze_ShouldBuildCfgForForLoop() {
        String source = """
                function sum(arr) {
                    let total = 0;
                    for (let i = 0; i < arr.length; i++) {
                        total += arr[i];
                    }
                    return total;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("sum.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(2, fn.cyclomaticComplexity());
        assertTrue(fn.nodes().stream().anyMatch(n -> "LOOP_CONDITION".equals(n.type())));
    }

    @Test
    void analyze_ShouldBuildCfgForForOfLoop() {
        String source = """
                function sumArray(arr) {
                    let total = 0;
                    for (const item of arr) {
                        total += item;
                    }
                    return total;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("forof.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(2, fn.cyclomaticComplexity());
        assertTrue(fn.nodes().stream().anyMatch(n -> "LOOP_CONDITION".equals(n.type())));
    }

    @Test
    void analyze_ShouldBuildCfgForForInLoop() {
        String source = """
                function listKeys(obj) {
                    const keys = [];
                    for (const key in obj) {
                        keys.push(key);
                    }
                    return keys;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("forin.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(2, fn.cyclomaticComplexity());
    }

    @Test
    void analyze_ShouldBuildCfgForDoWhileLoop() {
        String source = """
                function atLeastOnce(n) {
                    let result = 0;
                    do {
                        result += n;
                        n--;
                    } while (n > 0);
                    return result;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("dowhile.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(2, fn.cyclomaticComplexity());
    }

    // -------------------------------------------------------------------------
    // Break / Continue
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldHandleBreakAndContinue() {
        String source = """
                function findFirst(arr) {
                    let result = -1;
                    for (let i = 0; i < arr.length; i++) {
                        if (arr[i] === null) {
                            continue;
                        }
                        if (arr[i] > 0) {
                            result = arr[i];
                            break;
                        }
                    }
                    return result;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("find.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertTrue(fn.cyclomaticComplexity() >= 3);
        assertTrue(fn.nodes().stream().anyMatch(n -> "BREAK".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "CONTINUE".equals(n.type())));
    }

    // -------------------------------------------------------------------------
    // Switch / case
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldBuildCfgForSwitch() {
        String source = """
                function toText(n) {
                    switch (n) {
                        case 1:
                            return "one";
                        case 2:
                            return "two";
                        case 3:
                            return "three";
                        default:
                            return "unknown";
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("switch.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertTrue(fn.cyclomaticComplexity() >= 4);
        assertTrue(fn.nodes().stream().anyMatch(n -> "SWITCH".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "CASE".equals(n.type())));
    }

    // -------------------------------------------------------------------------
    // Try / catch / finally
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldBuildCfgForTryCatch() {
        String source = """
                function safeDivide(a, b) {
                    try {
                        return a / b;
                    } catch (e) {
                        console.error(e);
                        return 0;
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("safe.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertTrue(fn.cyclomaticComplexity() >= 2);
        assertTrue(fn.nodes().stream().anyMatch(n -> "TRY".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "CATCH".equals(n.type())));
    }

    @Test
    void analyze_ShouldBuildCfgForTryCatchFinally() {
        String source = """
                function withCleanup(resource) {
                    try {
                        resource.use();
                    } catch (e) {
                        console.error(e);
                    } finally {
                        resource.close();
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("cleanup.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertTrue(fn.cyclomaticComplexity() >= 2);
    }

    // -------------------------------------------------------------------------
    // Throw
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldBuildCfgForThrow() {
        String source = """
                function validate(x) {
                    if (x < 0) {
                        throw new Error("Negative");
                    }
                    return x;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("validate.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(2, fn.cyclomaticComplexity());
        assertTrue(fn.nodes().stream().anyMatch(n -> "THROW".equals(n.type())));
    }

    // -------------------------------------------------------------------------
    // Multiple functions
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldExtractMultipleFunctions() {
        String source = """
                function foo(x) {
                    return x + 1;
                }

                function bar(y) {
                    if (y > 0) {
                        return y;
                    }
                    return 0;
                }

                const baz = (z) => {
                    while (z > 0) {
                        z--;
                    }
                    return z;
                };
                """;

        JavaFileAnalysisResult result = analyzer.analyze("multi.js", source, "JAVASCRIPT");

        assertEquals(3, result.functions().size());
        assertEquals("foo", result.functions().get(0).functionName());
        assertEquals("bar", result.functions().get(1).functionName());
        assertEquals("baz", result.functions().get(2).functionName());

        assertEquals(1, result.functions().get(0).cyclomaticComplexity());
        assertEquals(2, result.functions().get(1).cyclomaticComplexity());
        assertEquals(2, result.functions().get(2).cyclomaticComplexity());
    }

    // -------------------------------------------------------------------------
    // TypeScript support
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldWorkWithTypeScript() {
        String source = """
                function greet(name: string): string {
                    if (name.length === 0) {
                        return "Hello, stranger!";
                    }
                    return "Hello, " + name + "!";
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("greet.ts", source, "TYPESCRIPT");

        assertEquals(1, result.functions().size());
        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals("greet", fn.functionName());
        assertEquals(2, fn.cyclomaticComplexity());
    }

    // -------------------------------------------------------------------------
    // Complex real-world scenario
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldHandleComplexFunction_WithConsistentCcAndCfg() {
        String source = """
                function processItems(items) {
                    let result = [];
                    let errorCount = 0;

                    for (const item of items) {
                        if (item === null) {
                            continue;
                        }

                        try {
                            if (item.type === 'special') {
                                result.push(item.value * 2);
                            } else {
                                result.push(item.value);
                            }
                        } catch (e) {
                            errorCount++;
                            if (errorCount > 3) {
                                throw new Error("Too many errors");
                            }
                        }
                    }

                    return result;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("process.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        // CC = E - N + 2, and it should be consistent
        assertEquals(fn.edges().size() - fn.nodes().size() + 2, fn.cyclomaticComplexity());
        assertTrue(fn.cyclomaticComplexity() >= 5);
        assertTrue(fn.nodes().stream().anyMatch(n -> "LOOP_CONDITION".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "CONDITION".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "TRY".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "CONTINUE".equals(n.type())));
        assertTrue(fn.nodes().stream().anyMatch(n -> "THROW".equals(n.type())));
    }

    @Test
    void analyze_CcShouldAlwaysMatchEdgesMinusNodesPlusTwo() {
        String source = """
                function average(values, min, max) {
                    let i = 0;
                    let inputNumber = 0;
                    let validNumber = 0;
                    let sum = 0;
                    let average;

                    while (values[i] !== -999 && inputNumber < 100) {
                        inputNumber++;
                        if (values[i] >= min && values[i] <= max) {
                            validNumber++;
                            sum = sum + values[i];
                        } else {
                            break;
                        }
                        i++;
                    }

                    if (validNumber > 0) {
                        average = sum / validNumber;
                    } else {
                        average = -999;
                    }

                    return average;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("average.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        int expectedCc = fn.edges().size() - fn.nodes().size() + 2;
        assertEquals(expectedCc, fn.cyclomaticComplexity(),
                "CC should always equal E - N + 2. Nodes=" + fn.nodes().size()
                        + " Edges=" + fn.edges().size()
                        + " CC=" + fn.cyclomaticComplexity());
    }

    // -------------------------------------------------------------------------
    // Syntax errors
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldThrowOnSyntaxError() {
        String source = """
                function broken( {
                    return;
                }
                """;

        assertThrows(IllegalArgumentException.class,
                () -> analyzer.analyze("broken.js", source, "JAVASCRIPT"));
    }

    // -------------------------------------------------------------------------
    // Empty function
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldHandleEmptyFunction() {
        String source = """
                function noop() {
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("noop.js", source, "JAVASCRIPT");

        assertEquals(1, result.functions().size());
        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals("noop", fn.functionName());
        assertEquals(1, fn.cyclomaticComplexity());
    }

    // -------------------------------------------------------------------------
    // Arrow function with expression body should be skipped
    // (no statement_block -> no CFG to build)
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldSkipArrowFunctionWithExpressionBody() {
        String source = """
                const double = (x) => x * 2;
                """;

        JavaFileAnalysisResult result = analyzer.analyze("arrow.js", source, "JAVASCRIPT");

        // Expression-body arrows are not analyzed (no block to build CFG from)
        assertEquals(0, result.functions().size());
    }

    // -------------------------------------------------------------------------
    // Line numbers
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldReportCorrectLineNumbers() {
        String source = """
                // comment line 1
                // comment line 2
                function foo() {
                    return 42;
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("lines.js", source, "JAVASCRIPT");

        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals(3, fn.startLine());
        assertEquals(5, fn.endLine());
    }

    // -------------------------------------------------------------------------
    // Generator function
    // -------------------------------------------------------------------------

    @Test
    void analyze_ShouldHandleGeneratorFunction() {
        String source = """
                function* range(start, end) {
                    for (let i = start; i < end; i++) {
                        yield i;
                    }
                }
                """;

        JavaFileAnalysisResult result = analyzer.analyze("gen.js", source, "JAVASCRIPT");

        // tree-sitter parses generator_function_declaration — we handle yield as a regular statement
        assertEquals(1, result.functions().size());
        FunctionAnalysisDraft fn = result.functions().get(0);
        assertEquals("range", fn.functionName());
        assertTrue(fn.signature().contains("function*"));
        assertEquals(2, fn.cyclomaticComplexity());
    }
}
