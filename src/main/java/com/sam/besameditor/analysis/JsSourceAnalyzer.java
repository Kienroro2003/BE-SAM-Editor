package com.sam.besameditor.analysis;

import org.springframework.stereotype.Component;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterTypescript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Analyzes JavaScript and TypeScript source files to extract function-level
 * control flow graphs (CFGs) and compute cyclomatic complexity.
 *
 * <p>Uses tree-sitter for AST parsing, producing the same {@link FunctionAnalysisDraft}
 * output format as {@link JavaSourceAnalyzer} so results can be persisted identically.
 */
@Component
public class JsSourceAnalyzer {

    /**
     * Analyze a JavaScript or TypeScript source file.
     *
     * @param sourcePath relative file path (used only for the result metadata)
     * @param sourceCode full source text
     * @param language   "JAVASCRIPT" or "TYPESCRIPT"
     * @return analysis result containing per-function CFG drafts
     */
    public JavaFileAnalysisResult analyze(String sourcePath, String sourceCode, String language) {
        try (TSParser parser = new TSParser()) {
            boolean isTypescript = "TYPESCRIPT".equalsIgnoreCase(language)
                    || "TS".equalsIgnoreCase(language)
                    || "TSX".equalsIgnoreCase(language);
            if (isTypescript) {
                parser.setLanguage(new TreeSitterTypescript());
            } else {
                parser.setLanguage(new TreeSitterJavascript());
            }

            try (TSTree tree = parser.parseString(null, sourceCode)) {
                TSNode root = tree.getRootNode();
                if (root.isNull() || root.hasError()) {
                    throw new IllegalArgumentException("Syntax error: unable to parse source file");
                }

                List<FunctionEntry> functions = new ArrayList<>();
                collectFunctions(root, sourceCode, null, functions);

                List<FunctionAnalysisDraft> drafts = new ArrayList<>();
                for (FunctionEntry entry : functions) {
                    ControlFlowGraph graph = new ControlFlowGraphBuilder(sourceCode).build(entry);
                    int cyclomaticComplexity = Math.max(1, graph.edges().size() - graph.nodes().size() + 2);
                    drafts.add(new FunctionAnalysisDraft(
                            entry.name(),
                            entry.signature(),
                            entry.startLine(),
                            entry.endLine(),
                            cyclomaticComplexity,
                            graph.nodes(),
                            graph.edges(),
                            graph.entryNodeId(),
                            graph.exitNodeIds()));
                }

                return new JavaFileAnalysisResult(sourcePath, drafts);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Function discovery
    // -------------------------------------------------------------------------

    /**
     * Recursively walk the AST and collect all function-like nodes:
     * function_declaration, arrow_function (with statement_block body),
     * method_definition, generator_function_declaration.
     */
    private void collectFunctions(TSNode node, String source, String className, List<FunctionEntry> out) {
        if (node.isNull()) return;

        String type = node.getType();
        switch (type) {
            case "function_declaration", "generator_function_declaration" -> {
                TSNode nameNode = node.getChildByFieldName("name");
                TSNode paramsNode = node.getChildByFieldName("parameters");
                TSNode bodyNode = node.getChildByFieldName("body");
                if (!bodyNode.isNull() && "statement_block".equals(bodyNode.getType())) {
                    String name = nodeText(nameNode, source, "anonymous");
                    String params = nodeText(paramsNode, source, "()");
                    boolean isGenerator = "generator_function_declaration".equals(type);
                    String signature = (isGenerator ? "function* " : "function ") + name + params;
                    out.add(new FunctionEntry(name, signature, bodyNode, lineOf(node), endLineOf(node)));
                }
            }
            case "arrow_function" -> {
                TSNode bodyNode = node.getChildByFieldName("body");
                if (!bodyNode.isNull() && "statement_block".equals(bodyNode.getType())) {
                    String name = resolveArrowFunctionName(node, source);
                    TSNode paramsNode = node.getChildByFieldName("parameters");
                    // parameters might be a single identifier (no parens) or formal_parameters
                    String params = paramsNode.isNull() ? "()" : nodeText(paramsNode, source, "()");
                    if (!params.startsWith("(")) {
                        params = "(" + params + ")";
                    }
                    String signature = name + params + " => {...}";
                    out.add(new FunctionEntry(name, signature, bodyNode, lineOf(node), endLineOf(node)));
                }
            }
            case "method_definition" -> {
                TSNode nameNode = node.getChildByFieldName("name");
                TSNode paramsNode = node.getChildByFieldName("parameters");
                TSNode bodyNode = node.getChildByFieldName("body");
                if (!bodyNode.isNull() && "statement_block".equals(bodyNode.getType())) {
                    String rawName = nodeText(nameNode, source, "anonymous");
                    String name = className != null ? className + "." + rawName : rawName;
                    String params = nodeText(paramsNode, source, "()");
                    String signature = name + params;
                    out.add(new FunctionEntry(name, signature, bodyNode, lineOf(node), endLineOf(node)));
                }
            }
            default -> {
                // not a function node — continue walking
            }
        }

        // Determine className for class bodies
        String currentClassName = className;
        if ("class_declaration".equals(type) || "class".equals(type)) {
            TSNode nameNode = node.getChildByFieldName("name");
            if (!nameNode.isNull()) {
                currentClassName = nodeText(nameNode, source, null);
            }
        }

        // Recurse into children (but NOT into the body of functions we already collected,
        // because we don't analyze nested functions as separate top-level entries here;
        // we still walk into them to find nested function declarations)
        for (int i = 0; i < node.getChildCount(); i++) {
            collectFunctions(node.getChild(i), source, currentClassName, out);
        }
    }

    /**
     * For arrow functions assigned to a variable (e.g. {@code const foo = (x) => {...}}),
     * resolve the variable name.
     */
    private String resolveArrowFunctionName(TSNode arrowNode, String source) {
        TSNode parent = arrowNode.getParent();
        if (!parent.isNull() && "variable_declarator".equals(parent.getType())) {
            TSNode nameNode = parent.getChildByFieldName("name");
            if (!nameNode.isNull()) {
                return nodeText(nameNode, source, "anonymous");
            }
        }
        // Could be a property in an object literal: { foo: (x) => {...} }
        if (!parent.isNull() && "pair".equals(parent.getType())) {
            TSNode keyNode = parent.getChildByFieldName("key");
            if (!keyNode.isNull()) {
                return nodeText(keyNode, source, "anonymous");
            }
        }
        return "anonymous";
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static int lineOf(TSNode node) {
        return node.getStartPoint().getRow() + 1; // tree-sitter rows are 0-based
    }

    private static int endLineOf(TSNode node) {
        int endRow = node.getEndPoint().getRow() + 1;
        // If the end column is 0, the node ends at the previous line
        if (node.getEndPoint().getColumn() == 0 && endRow > 1) {
            return endRow - 1;
        }
        return endRow;
    }

    private static String nodeText(TSNode node, String source, String fallback) {
        if (node.isNull()) return fallback;
        int startByte = node.getStartByte();
        int endByte = node.getEndByte();
        if (startByte < 0 || endByte <= startByte || endByte > source.length()) {
            return fallback;
        }
        return source.substring(startByte, endByte);
    }

    // -------------------------------------------------------------------------
    // Internal records
    // -------------------------------------------------------------------------

    record FunctionEntry(String name, String signature, TSNode bodyNode, int startLine, int endLine) {
    }

    private record ControlFlowGraph(
            List<GraphNodeDraft> nodes,
            List<GraphEdgeDraft> edges,
            String entryNodeId,
            List<String> exitNodeIds) {
    }

    // -------------------------------------------------------------------------
    // Control Flow Graph Builder
    // -------------------------------------------------------------------------

    private static final class ControlFlowGraphBuilder {
        private final String sourceCode;
        private final List<GraphNodeDraft> nodes = new ArrayList<>();
        private final List<GraphEdgeDraft> edges = new ArrayList<>();
        private int nodeCounter = 0;
        private int edgeCounter = 0;

        ControlFlowGraphBuilder(String sourceCode) {
            this.sourceCode = sourceCode;
        }

        ControlFlowGraph build(FunctionEntry function) {
            String entryNodeId = addNode("ENTRY", function.name(), function.bodyNode());
            String exitNodeId = addNode("EXIT", "exit", function.bodyNode());
            BuildResult body = buildBlock(function.bodyNode(), new ControlContext(null, null), exitNodeId);
            addEdge(entryNodeId, body.entryNodeId(), null);
            for (String openExit : body.openExitNodeIds()) {
                addEdge(openExit, exitNodeId, null);
            }
            return new ControlFlowGraph(
                    Collections.unmodifiableList(nodes),
                    Collections.unmodifiableList(edges),
                    entryNodeId,
                    List.of(exitNodeId));
        }

        // ---- Statement dispatch ----

        private BuildResult buildStatement(TSNode node, ControlContext ctx, String exitNodeId) {
            if (node.isNull()) {
                return buildNoop("empty", null);
            }

            String type = node.getType();
            return switch (type) {
                case "statement_block" -> buildBlock(node, ctx, exitNodeId);
                case "if_statement" -> buildIf(node, ctx, exitNodeId);
                case "while_statement" -> buildWhile(node, ctx, exitNodeId);
                case "for_statement" -> buildFor(node, ctx, exitNodeId);
                case "for_in_statement" -> buildForIn(node, ctx, exitNodeId);
                case "do_statement" -> buildDoWhile(node, ctx, exitNodeId);
                case "switch_statement" -> buildSwitch(node, ctx, exitNodeId);
                case "try_statement" -> buildTry(node, ctx, exitNodeId);
                case "return_statement" -> buildTerminal("RETURN", node, exitNodeId, "return");
                case "throw_statement" -> buildTerminal("THROW", node, exitNodeId, "throw");
                case "break_statement" -> buildBreak(node, ctx);
                case "continue_statement" -> buildContinue(node, ctx);
                case "labeled_statement" -> buildLabeled(node, ctx, exitNodeId);
                case "empty_statement" -> buildNoop("empty", node);
                default -> {
                    // expression_statement, lexical_declaration, variable_declaration, etc.
                    // Check if the expression contains a ternary — treat it as a condition
                    if (containsTernary(node)) {
                        yield buildTernaryStatement(node, ctx, exitNodeId);
                    }
                    yield buildLinear("STATEMENT", renderSnippet(node, "statement"), node);
                }
            };
        }

        // ---- Block (sequence of statements) ----

        private BuildResult buildBlock(TSNode blockNode, ControlContext ctx, String exitNodeId) {
            List<TSNode> statements = collectBlockStatements(blockNode);
            if (statements.isEmpty()) {
                return buildNoop("empty", blockNode);
            }
            return buildSequence(statements, ctx, exitNodeId);
        }

        private BuildResult buildSequence(List<TSNode> statements, ControlContext ctx, String exitNodeId) {
            if (statements.isEmpty()) {
                return buildNoop("empty", null);
            }

            String entryNodeId = null;
            List<String> pendingExits = new ArrayList<>();
            for (TSNode stmt : statements) {
                BuildResult result = buildStatement(stmt, ctx, exitNodeId);
                if (entryNodeId == null) {
                    entryNodeId = result.entryNodeId();
                }
                for (String pendingExit : pendingExits) {
                    addEdge(pendingExit, result.entryNodeId(), null);
                }
                pendingExits = new ArrayList<>(result.openExitNodeIds());
                if (pendingExits.isEmpty()) {
                    break; // unreachable code after return/throw/break/continue
                }
            }
            return new BuildResult(entryNodeId, pendingExits);
        }

        // ---- if/else ----

        private BuildResult buildIf(TSNode ifNode, ControlContext ctx, String exitNodeId) {
            TSNode conditionNode = ifNode.getChildByFieldName("condition");
            TSNode consequenceNode = ifNode.getChildByFieldName("consequence");
            TSNode alternativeNode = ifNode.getChildByFieldName("alternative");

            String condNodeId = addNode("CONDITION", renderSnippet(conditionNode, "if"), conditionNode);
            BuildResult thenBranch = buildStatement(consequenceNode, ctx, exitNodeId);

            BuildResult elseBranch;
            if (!alternativeNode.isNull()) {
                // else_clause has a child that is the actual statement
                TSNode elseBody = getElseBody(alternativeNode);
                elseBranch = buildStatement(elseBody, ctx, exitNodeId);
            } else {
                elseBranch = buildNoop("else", ifNode);
            }

            addEdge(condNodeId, thenBranch.entryNodeId(), "true");
            addEdge(condNodeId, elseBranch.entryNodeId(), "false");

            List<String> openExits = new ArrayList<>(thenBranch.openExitNodeIds());
            openExits.addAll(elseBranch.openExitNodeIds());
            return new BuildResult(condNodeId, openExits);
        }

        private TSNode getElseBody(TSNode elseClause) {
            // else_clause children: "else" keyword + the actual statement
            for (int i = 0; i < elseClause.getChildCount(); i++) {
                TSNode child = elseClause.getChild(i);
                String childType = child.getType();
                if (!"else".equals(childType)) {
                    return child;
                }
            }
            return elseClause;
        }

        // ---- while ----

        private BuildResult buildWhile(TSNode whileNode, ControlContext ctx, String exitNodeId) {
            TSNode conditionNode = whileNode.getChildByFieldName("condition");
            TSNode bodyNode = whileNode.getChildByFieldName("body");

            String condNodeId = addNode("LOOP_CONDITION", renderSnippet(conditionNode, "while"), conditionNode);
            String afterLoopId = addNode("JOIN", "after while", whileNode);
            ControlContext loopCtx = new ControlContext(afterLoopId, condNodeId);
            BuildResult body = buildStatement(bodyNode, loopCtx, exitNodeId);

            addEdge(condNodeId, body.entryNodeId(), "true");
            for (String openExit : body.openExitNodeIds()) {
                addEdge(openExit, condNodeId, "loop");
            }
            addEdge(condNodeId, afterLoopId, "false");
            return new BuildResult(condNodeId, List.of(afterLoopId));
        }

        // ---- for ----

        private BuildResult buildFor(TSNode forNode, ControlContext ctx, String exitNodeId) {
            String condNodeId = addNode("LOOP_CONDITION", renderSnippet(forNode, "for"), forNode);
            String afterLoopId = addNode("JOIN", "after for", forNode);
            ControlContext loopCtx = new ControlContext(afterLoopId, condNodeId);
            TSNode bodyNode = forNode.getChildByFieldName("body");
            BuildResult body = buildStatement(bodyNode, loopCtx, exitNodeId);

            addEdge(condNodeId, body.entryNodeId(), "true");
            for (String openExit : body.openExitNodeIds()) {
                addEdge(openExit, condNodeId, "loop");
            }
            addEdge(condNodeId, afterLoopId, "false");
            return new BuildResult(condNodeId, List.of(afterLoopId));
        }

        // ---- for-in / for-of ----

        private BuildResult buildForIn(TSNode forInNode, ControlContext ctx, String exitNodeId) {
            TSNode operatorNode = forInNode.getChildByFieldName("operator");
            String loopKind = operatorNode.isNull() ? "for-in" : nodeText(operatorNode, sourceCode, "for-in");
            String label = "for-" + loopKind;

            String condNodeId = addNode("LOOP_CONDITION", renderSnippet(forInNode, label), forInNode);
            String afterLoopId = addNode("JOIN", "after " + label, forInNode);
            ControlContext loopCtx = new ControlContext(afterLoopId, condNodeId);
            TSNode bodyNode = forInNode.getChildByFieldName("body");
            BuildResult body = buildStatement(bodyNode, loopCtx, exitNodeId);

            addEdge(condNodeId, body.entryNodeId(), "true");
            for (String openExit : body.openExitNodeIds()) {
                addEdge(openExit, condNodeId, "loop");
            }
            addEdge(condNodeId, afterLoopId, "false");
            return new BuildResult(condNodeId, List.of(afterLoopId));
        }

        // ---- do-while ----

        private BuildResult buildDoWhile(TSNode doNode, ControlContext ctx, String exitNodeId) {
            TSNode conditionNode = doNode.getChildByFieldName("condition");
            TSNode bodyNode = doNode.getChildByFieldName("body");

            String condNodeId = addNode("LOOP_CONDITION", renderSnippet(conditionNode, "do-while"), conditionNode);
            String afterLoopId = addNode("JOIN", "after do-while", doNode);
            ControlContext loopCtx = new ControlContext(afterLoopId, condNodeId);
            BuildResult body = buildStatement(bodyNode, loopCtx, exitNodeId);

            for (String openExit : body.openExitNodeIds()) {
                addEdge(openExit, condNodeId, "loop");
            }
            addEdge(condNodeId, body.entryNodeId(), "true");
            addEdge(condNodeId, afterLoopId, "false");
            return new BuildResult(body.entryNodeId(), List.of(afterLoopId));
        }

        // ---- switch ----

        private BuildResult buildSwitch(TSNode switchNode, ControlContext ctx, String exitNodeId) {
            TSNode valueNode = switchNode.getChildByFieldName("value");
            TSNode switchBody = switchNode.getChildByFieldName("body");

            String switchNodeId = addNode("SWITCH", renderSnippet(valueNode, "switch"), valueNode);
            String afterSwitchId = addNode("JOIN", "after switch", switchNode);
            ControlContext switchCtx = new ControlContext(afterSwitchId, ctx.continueTargetNodeId());

            if (switchBody.isNull()) {
                addEdge(switchNodeId, afterSwitchId, null);
                return new BuildResult(switchNodeId, List.of(afterSwitchId));
            }

            boolean hasDefault = false;
            for (int i = 0; i < switchBody.getNamedChildCount(); i++) {
                TSNode caseNode = switchBody.getNamedChild(i);
                String caseType = caseNode.getType();

                String caseLabel;
                if ("switch_default".equals(caseType)) {
                    caseLabel = "default";
                    hasDefault = true;
                } else {
                    TSNode caseValueNode = caseNode.getChildByFieldName("value");
                    caseLabel = caseValueNode.isNull() ? "case" : nodeText(caseValueNode, sourceCode, "case");
                }

                String caseNodeId = addNode("CASE", caseLabel, caseNode);
                addEdge(switchNodeId, caseNodeId, caseLabel);

                List<TSNode> caseStatements = collectCaseBodyStatements(caseNode);
                BuildResult caseBody;
                if (caseStatements.isEmpty()) {
                    caseBody = buildNoop("empty case", caseNode);
                } else {
                    caseBody = buildSequence(caseStatements, switchCtx, exitNodeId);
                }
                addEdge(caseNodeId, caseBody.entryNodeId(), null);
                for (String openExit : caseBody.openExitNodeIds()) {
                    addEdge(openExit, afterSwitchId, null);
                }
            }

            if (!hasDefault) {
                addEdge(switchNodeId, afterSwitchId, "default");
            }

            return new BuildResult(switchNodeId, List.of(afterSwitchId));
        }

        private List<TSNode> collectCaseBodyStatements(TSNode caseNode) {
            List<TSNode> stmts = new ArrayList<>();
            for (int i = 0; i < caseNode.getChildCount(); i++) {
                String fieldName = caseNode.getFieldNameForChild(i);
                if ("body".equals(fieldName)) {
                    stmts.add(caseNode.getChild(i));
                }
            }
            return stmts;
        }

        // ---- try/catch/finally ----

        private BuildResult buildTry(TSNode tryNode, ControlContext ctx, String exitNodeId) {
            TSNode bodyNode = tryNode.getChildByFieldName("body");
            TSNode handlerNode = tryNode.getChildByFieldName("handler");
            TSNode finalizerNode = tryNode.getChildByFieldName("finalizer");

            String tryNodeId = addNode("TRY", "try", tryNode);
            List<String> branchExits = new ArrayList<>();

            BuildResult tryBody = buildStatement(bodyNode, ctx, exitNodeId);
            addEdge(tryNodeId, tryBody.entryNodeId(), "try");
            branchExits.addAll(tryBody.openExitNodeIds());

            if (!handlerNode.isNull()) {
                TSNode catchParam = handlerNode.getChildByFieldName("parameter");
                String catchLabel = catchParam.isNull()
                        ? "catch"
                        : "catch (" + nodeText(catchParam, sourceCode, "e") + ")";
                String catchNodeId = addNode("CATCH", catchLabel, handlerNode);
                TSNode catchBody = handlerNode.getChildByFieldName("body");
                BuildResult catchResult = buildStatement(catchBody, ctx, exitNodeId);
                addEdge(tryNodeId, catchNodeId, "catch");
                addEdge(catchNodeId, catchResult.entryNodeId(), null);
                branchExits.addAll(catchResult.openExitNodeIds());
            }

            if (!finalizerNode.isNull()) {
                TSNode finallyBody = finalizerNode.getChildByFieldName("body");
                BuildResult finallyResult = buildStatement(finallyBody, ctx, exitNodeId);
                for (String branchExit : branchExits) {
                    addEdge(branchExit, finallyResult.entryNodeId(), null);
                }
                return new BuildResult(tryNodeId, finallyResult.openExitNodeIds());
            }

            return new BuildResult(tryNodeId, branchExits);
        }

        // ---- Terminals ----

        private BuildResult buildTerminal(String nodeType, TSNode node, String exitNodeId, String edgeLabel) {
            String nodeId = addNode(nodeType, renderSnippet(node, edgeLabel), node);
            addEdge(nodeId, exitNodeId, edgeLabel);
            return new BuildResult(nodeId, List.of());
        }

        private BuildResult buildBreak(TSNode node, ControlContext ctx) {
            String nodeId = addNode("BREAK", "break", node);
            if (ctx.breakTargetNodeId() != null) {
                addEdge(nodeId, ctx.breakTargetNodeId(), "break");
            }
            return new BuildResult(nodeId, List.of());
        }

        private BuildResult buildContinue(TSNode node, ControlContext ctx) {
            String nodeId = addNode("CONTINUE", "continue", node);
            if (ctx.continueTargetNodeId() != null) {
                addEdge(nodeId, ctx.continueTargetNodeId(), "continue");
            }
            return new BuildResult(nodeId, List.of());
        }

        // ---- Labeled statement ----

        private BuildResult buildLabeled(TSNode labeledNode, ControlContext ctx, String exitNodeId) {
            // labeled_statement has a "label" field and a "body" field
            TSNode bodyNode = labeledNode.getChildByFieldName("body");
            return buildStatement(bodyNode, ctx, exitNodeId);
        }

        // ---- Ternary expression within a statement ----

        private boolean containsTernary(TSNode node) {
            if (node.isNull()) return false;
            if ("ternary_expression".equals(node.getType())) return true;
            for (int i = 0; i < node.getChildCount(); i++) {
                if (containsTernary(node.getChild(i))) return true;
            }
            return false;
        }

        private BuildResult buildTernaryStatement(TSNode node, ControlContext ctx, String exitNodeId) {
            // Find the outermost ternary expression
            TSNode ternary = findFirstTernary(node);
            if (ternary == null) {
                return buildLinear("STATEMENT", renderSnippet(node, "statement"), node);
            }

            // ternary_expression children: condition ? consequence : alternative
            TSNode condition = ternary.getChildByFieldName("condition");
            if (condition.isNull()) {
                // Fallback: child 0 is condition
                condition = ternary.getChild(0);
            }
            TSNode consequence = ternary.getChildByFieldName("consequence");
            if (consequence.isNull()) {
                consequence = ternary.getChild(2);
            }
            TSNode alternative = ternary.getChildByFieldName("alternative");
            if (alternative.isNull()) {
                alternative = ternary.getChild(4);
            }

            String condNodeId = addNode("CONDITION", renderSnippet(condition, "ternary"), condition);
            String thenNodeId = addNode("STATEMENT", renderSnippet(consequence, "then"), consequence);
            String elseNodeId = addNode("STATEMENT", renderSnippet(alternative, "else"), alternative);

            addEdge(condNodeId, thenNodeId, "true");
            addEdge(condNodeId, elseNodeId, "false");
            return new BuildResult(condNodeId, List.of(thenNodeId, elseNodeId));
        }

        private TSNode findFirstTernary(TSNode node) {
            if (node.isNull()) return null;
            if ("ternary_expression".equals(node.getType())) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                TSNode found = findFirstTernary(node.getChild(i));
                if (found != null) return found;
            }
            return null;
        }

        // ---- Linear and NOOP helpers ----

        private BuildResult buildLinear(String nodeType, String label, TSNode node) {
            String nodeId = addNode(nodeType, label, node);
            return new BuildResult(nodeId, List.of(nodeId));
        }

        private BuildResult buildNoop(String label, TSNode node) {
            String nodeId = addNode("NOOP", label, node);
            return new BuildResult(nodeId, List.of(nodeId));
        }

        // ---- Node / Edge creation ----

        private String addNode(String type, String label, TSNode node) {
            String nodeId = "n" + (++nodeCounter);
            Integer startLine = node == null || node.isNull() ? null : lineOf(node);
            Integer endLine = node == null || node.isNull() ? null : endLineOf(node);
            nodes.add(new GraphNodeDraft(nodeId, type, label, startLine, endLine));
            return nodeId;
        }

        private void addEdge(String source, String target, String label) {
            String edgeId = "e" + (++edgeCounter);
            edges.add(new GraphEdgeDraft(edgeId, source, target, label));
        }

        // ---- Helpers ----

        private List<TSNode> collectBlockStatements(TSNode blockNode) {
            List<TSNode> statements = new ArrayList<>();
            for (int i = 0; i < blockNode.getNamedChildCount(); i++) {
                TSNode child = blockNode.getNamedChild(i);
                if (!child.isNull()) {
                    statements.add(child);
                }
            }
            return statements;
        }

        private String renderSnippet(TSNode node, String fallback) {
            if (node == null || node.isNull()) return fallback;
            int startByte = node.getStartByte();
            int endByte = node.getEndByte();
            if (startByte < 0 || endByte <= startByte || endByte > sourceCode.length()) {
                return fallback;
            }
            String snippet = sourceCode.substring(startByte, endByte)
                    .replaceAll("\\s+", " ")
                    .trim();
            if (snippet.isBlank()) return fallback;
            return snippet.length() > 96 ? snippet.substring(0, 93) + "..." : snippet;
        }

        // ---- Inner records ----

        private record BuildResult(String entryNodeId, List<String> openExitNodeIds) {
        }

        private record ControlContext(String breakTargetNodeId, String continueTargetNodeId) {
        }
    }
}
