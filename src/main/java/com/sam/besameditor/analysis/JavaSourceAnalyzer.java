package com.sam.besameditor.analysis;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Component
public class JavaSourceAnalyzer {

    public JavaFileAnalysisResult analyze(String sourcePath, String sourceCode) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is not available in the current runtime");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavaFileObject sourceFile = new InMemoryJavaSource(sourcePath, sourceCode);
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-proc:none", "-Xlint:none"),
                    null,
                    List.of(sourceFile));

            CompilationUnitTree compilationUnit = task.parse().iterator().next();
            assertNoSyntaxErrors(diagnostics);

            Trees trees = Trees.instance(task);
            SourcePositions sourcePositions = trees.getSourcePositions();
            List<FunctionAnalysisDraft> functions = extractFunctions(compilationUnit, sourcePositions, sourceCode);
            return new JavaFileAnalysisResult(sourcePath, functions);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to close Java compiler resources", ex);
        }
    }

    private void assertNoSyntaxErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
        diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .findFirst()
                .ifPresent(diagnostic -> {
                    long line = Math.max(diagnostic.getLineNumber(), 1L);
                    long column = Math.max(diagnostic.getColumnNumber(), 1L);
                    String message = diagnostic.getMessage(Locale.ENGLISH);
                    throw new IllegalArgumentException(
                            "Syntax error at line " + line + ", column " + column + ": " + message);
                });
    }

    private List<FunctionAnalysisDraft> extractFunctions(
            CompilationUnitTree compilationUnit,
            SourcePositions sourcePositions,
            String sourceCode) {
        List<MethodTree> methods = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                if (node.getBody() != null && node.getReturnType() != null) {
                    methods.add(node);
                }
                return super.visitMethod(node, unused);
            }
        }.scan(compilationUnit, null);

        List<FunctionAnalysisDraft> functions = new ArrayList<>();
        for (MethodTree method : methods) {
            int startLine = lineOf(compilationUnit, sourcePositions.getStartPosition(compilationUnit, method));
            int endLine = endLineOf(compilationUnit, sourcePositions, method);
            ControlFlowGraph graph = new ControlFlowGraphBuilder(compilationUnit, sourcePositions, sourceCode).build(method);
            int cyclomaticComplexity = cyclomaticComplexityOf(graph);
            functions.add(new FunctionAnalysisDraft(
                    method.getName().toString(),
                    buildSignature(method),
                    startLine,
                    endLine,
                    cyclomaticComplexity,
                    graph.nodes(),
                    graph.edges(),
                    graph.entryNodeId(),
                    graph.exitNodeIds()));
        }

        return functions;
    }

    private int cyclomaticComplexityOf(ControlFlowGraph graph) {
        int complexity = graph.edges().size() - graph.nodes().size() + 2;
        return Math.max(1, complexity);
    }

    private String buildSignature(MethodTree method) {
        String returnType = method.getReturnType() == null ? "void" : method.getReturnType().toString();
        String parameters = method.getParameters().stream()
                .map(this::formatParameter)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return returnType + " " + method.getName() + "(" + parameters + ")";
    }

    private String formatParameter(VariableTree parameter) {
        return parameter.getType() + " " + parameter.getName();
    }

    private int lineOf(CompilationUnitTree compilationUnit, long position) {
        if (position == Diagnostic.NOPOS || position < 0) {
            return 1;
        }
        return (int) compilationUnit.getLineMap().getLineNumber(position);
    }

    private int endLineOf(CompilationUnitTree compilationUnit, SourcePositions sourcePositions, Tree tree) {
        long endPosition = sourcePositions.getEndPosition(compilationUnit, tree);
        if (endPosition == Diagnostic.NOPOS || endPosition <= 0) {
            return lineOf(compilationUnit, sourcePositions.getStartPosition(compilationUnit, tree));
        }
        return lineOf(compilationUnit, endPosition - 1);
    }

    private record ControlFlowGraph(
            List<GraphNodeDraft> nodes,
            List<GraphEdgeDraft> edges,
            String entryNodeId,
            List<String> exitNodeIds) {
    }

    private static boolean isDefaultCase(CaseTree caseTree) {
        return caseTree.getExpressions() == null || caseTree.getExpressions().isEmpty();
    }

    private static final class ControlFlowGraphBuilder {
        private final CompilationUnitTree compilationUnit;
        private final SourcePositions sourcePositions;
        private final String sourceCode;
        private final List<GraphNodeDraft> nodes = new ArrayList<>();
        private final List<GraphEdgeDraft> edges = new ArrayList<>();
        private int nodeCounter = 0;
        private int edgeCounter = 0;

        private ControlFlowGraphBuilder(
                CompilationUnitTree compilationUnit,
                SourcePositions sourcePositions,
                String sourceCode) {
            this.compilationUnit = compilationUnit;
            this.sourcePositions = sourcePositions;
            this.sourceCode = sourceCode;
        }

        private ControlFlowGraph build(MethodTree method) {
            String entryNodeId = addNode("ENTRY", method.getName().toString(), method);
            String exitNodeId = addNode("EXIT", "exit", method);
            BuildResult body = buildStatement(method.getBody(), new ControlContext(null, null), exitNodeId);
            addEdge(entryNodeId, body.entryNodeId(), null);
            for (String openExitNodeId : body.openExitNodeIds()) {
                addEdge(openExitNodeId, exitNodeId, null);
            }
            return new ControlFlowGraph(
                    Collections.unmodifiableList(nodes),
                    Collections.unmodifiableList(edges),
                    entryNodeId,
                    List.of(exitNodeId));
        }

        private BuildResult buildStatement(StatementTree statement, ControlContext context, String exitNodeId) {
            if (statement == null) {
                String nodeId = addNode("NOOP", "empty", null);
                return new BuildResult(nodeId, List.of(nodeId));
            }

            return switch (statement.getKind()) {
                case BLOCK -> buildSequence(((BlockTree) statement).getStatements(), context, exitNodeId);
                case IF -> buildIf((IfTree) statement, context, exitNodeId);
                case WHILE_LOOP -> buildWhile((WhileLoopTree) statement, context, exitNodeId);
                case FOR_LOOP -> buildForLoop((ForLoopTree) statement, context, exitNodeId);
                case ENHANCED_FOR_LOOP -> buildEnhancedForLoop((EnhancedForLoopTree) statement, context, exitNodeId);
                case DO_WHILE_LOOP -> buildDoWhileLoop((DoWhileLoopTree) statement, context, exitNodeId);
                case SWITCH -> buildSwitch((SwitchTree) statement, context, exitNodeId);
                case RETURN -> buildTerminal("RETURN", statement, exitNodeId, "return");
                case THROW -> buildTerminal("THROW", (ThrowTree) statement, exitNodeId, "throw");
                case BREAK -> buildBreak(statement, context);
                case CONTINUE -> buildContinue(statement, context);
                case TRY -> buildTry((TryTree) statement, context, exitNodeId);
                case LABELED_STATEMENT -> buildStatement(((com.sun.source.tree.LabeledStatementTree) statement).getStatement(), context, exitNodeId);
                case EMPTY_STATEMENT -> {
                    String nodeId = addNode("NOOP", "empty", statement);
                    yield new BuildResult(nodeId, List.of(nodeId));
                }
                default -> buildLinear("STATEMENT", renderSnippet(statement, "statement"), statement);
            };
        }

        private BuildResult buildSequence(
                List<? extends StatementTree> statements,
                ControlContext context,
                String exitNodeId) {
            if (statements == null || statements.isEmpty()) {
                String nodeId = addNode("NOOP", "empty", null);
                return new BuildResult(nodeId, List.of(nodeId));
            }

            String entryNodeId = null;
            List<String> pendingExitNodeIds = new ArrayList<>();
            for (StatementTree statement : statements) {
                BuildResult result = buildStatement(statement, context, exitNodeId);
                if (entryNodeId == null) {
                    entryNodeId = result.entryNodeId();
                }
                for (String pendingExitNodeId : pendingExitNodeIds) {
                    addEdge(pendingExitNodeId, result.entryNodeId(), null);
                }
                pendingExitNodeIds = new ArrayList<>(result.openExitNodeIds());
                if (pendingExitNodeIds.isEmpty()) {
                    break;
                }
            }

            return new BuildResult(entryNodeId, pendingExitNodeIds);
        }

        private BuildResult buildIf(IfTree ifTree, ControlContext context, String exitNodeId) {
            String conditionNodeId = addNode("CONDITION", renderSnippet(ifTree.getCondition(), "if"), ifTree.getCondition());
            BuildResult thenBranch = buildStatement(ifTree.getThenStatement(), context, exitNodeId);
            BuildResult elseBranch = ifTree.getElseStatement() != null
                    ? buildStatement(ifTree.getElseStatement(), context, exitNodeId)
                    : buildLinear("NOOP", "else", ifTree);

            addEdge(conditionNodeId, thenBranch.entryNodeId(), "true");
            addEdge(conditionNodeId, elseBranch.entryNodeId(), "false");

            List<String> openExitNodeIds = new ArrayList<>(thenBranch.openExitNodeIds());
            openExitNodeIds.addAll(elseBranch.openExitNodeIds());
            return new BuildResult(conditionNodeId, openExitNodeIds);
        }

        private BuildResult buildWhile(WhileLoopTree loopTree, ControlContext context, String exitNodeId) {
            String conditionNodeId = addNode("LOOP_CONDITION", renderSnippet(loopTree.getCondition(), "while"), loopTree.getCondition());
            String afterLoopNodeId = addNode("JOIN", "after while", loopTree);
            ControlContext loopContext = new ControlContext(afterLoopNodeId, conditionNodeId);
            BuildResult body = buildStatement(loopTree.getStatement(), loopContext, exitNodeId);
            addEdge(conditionNodeId, body.entryNodeId(), "true");
            for (String openExitNodeId : body.openExitNodeIds()) {
                addEdge(openExitNodeId, conditionNodeId, "loop");
            }
            addEdge(conditionNodeId, afterLoopNodeId, "false");
            return new BuildResult(conditionNodeId, List.of(afterLoopNodeId));
        }

        private BuildResult buildForLoop(ForLoopTree loopTree, ControlContext context, String exitNodeId) {
            String conditionNodeId = addNode("LOOP_CONDITION", renderSnippet(loopTree, "for"), loopTree);
            String afterLoopNodeId = addNode("JOIN", "after for", loopTree);
            ControlContext loopContext = new ControlContext(afterLoopNodeId, conditionNodeId);
            BuildResult body = buildStatement(loopTree.getStatement(), loopContext, exitNodeId);
            addEdge(conditionNodeId, body.entryNodeId(), "true");
            for (String openExitNodeId : body.openExitNodeIds()) {
                addEdge(openExitNodeId, conditionNodeId, "loop");
            }
            addEdge(conditionNodeId, afterLoopNodeId, "false");
            return new BuildResult(conditionNodeId, List.of(afterLoopNodeId));
        }

        private BuildResult buildEnhancedForLoop(EnhancedForLoopTree loopTree, ControlContext context, String exitNodeId) {
            String conditionNodeId = addNode("LOOP_CONDITION", renderSnippet(loopTree, "for-each"), loopTree);
            String afterLoopNodeId = addNode("JOIN", "after for-each", loopTree);
            ControlContext loopContext = new ControlContext(afterLoopNodeId, conditionNodeId);
            BuildResult body = buildStatement(loopTree.getStatement(), loopContext, exitNodeId);
            addEdge(conditionNodeId, body.entryNodeId(), "true");
            for (String openExitNodeId : body.openExitNodeIds()) {
                addEdge(openExitNodeId, conditionNodeId, "loop");
            }
            addEdge(conditionNodeId, afterLoopNodeId, "false");
            return new BuildResult(conditionNodeId, List.of(afterLoopNodeId));
        }

        private BuildResult buildDoWhileLoop(DoWhileLoopTree loopTree, ControlContext context, String exitNodeId) {
            String conditionNodeId = addNode("LOOP_CONDITION", renderSnippet(loopTree.getCondition(), "do-while"), loopTree.getCondition());
            String afterLoopNodeId = addNode("JOIN", "after do-while", loopTree);
            ControlContext loopContext = new ControlContext(afterLoopNodeId, conditionNodeId);
            BuildResult body = buildStatement(loopTree.getStatement(), loopContext, exitNodeId);
            for (String openExitNodeId : body.openExitNodeIds()) {
                addEdge(openExitNodeId, conditionNodeId, "loop");
            }
            addEdge(conditionNodeId, body.entryNodeId(), "true");
            addEdge(conditionNodeId, afterLoopNodeId, "false");
            return new BuildResult(body.entryNodeId(), List.of(afterLoopNodeId));
        }

        private BuildResult buildSwitch(SwitchTree switchTree, ControlContext context, String exitNodeId) {
            String switchNodeId = addNode("SWITCH", renderSnippet(switchTree.getExpression(), "switch"), switchTree.getExpression());
            String afterSwitchNodeId = addNode("JOIN", "after switch", switchTree);
            ControlContext switchContext = new ControlContext(afterSwitchNodeId, context.continueTargetNodeId());

            List<? extends CaseTree> cases = switchTree.getCases();
            if (cases == null || cases.isEmpty()) {
                addEdge(switchNodeId, afterSwitchNodeId, null);
                return new BuildResult(switchNodeId, List.of(afterSwitchNodeId));
            }

            boolean hasDefaultCase = false;
            for (CaseTree caseTree : cases) {
                String caseNodeId = addNode("CASE", buildCaseLabel(caseTree), caseTree);
                addEdge(switchNodeId, caseNodeId, buildCaseLabel(caseTree));
                BuildResult caseBody = buildCaseBody(caseTree, switchContext, exitNodeId);
                addEdge(caseNodeId, caseBody.entryNodeId(), null);
                for (String openExitNodeId : caseBody.openExitNodeIds()) {
                    addEdge(openExitNodeId, afterSwitchNodeId, null);
                }
                hasDefaultCase = hasDefaultCase || isDefaultCase(caseTree);
            }

            if (!hasDefaultCase) {
                addEdge(switchNodeId, afterSwitchNodeId, "default");
            }

            return new BuildResult(switchNodeId, List.of(afterSwitchNodeId));
        }

        private BuildResult buildCaseBody(CaseTree caseTree, ControlContext context, String exitNodeId) {
            if (caseTree.getStatements() != null && !caseTree.getStatements().isEmpty()) {
                return buildSequence(caseTree.getStatements(), context, exitNodeId);
            }
            if (caseTree.getBody() instanceof StatementTree statementTree) {
                return buildStatement(statementTree, context, exitNodeId);
            }
            return buildLinear("NOOP", "empty case", caseTree);
        }

        private String buildCaseLabel(CaseTree caseTree) {
            if (isDefaultCase(caseTree)) {
                return "default";
            }
            String labels = String.join(", ", caseTree.getExpressions().stream().map(Object::toString).toList());
            return labels.isBlank() ? "case" : labels;
        }

        private BuildResult buildTry(TryTree tryTree, ControlContext context, String exitNodeId) {
            String tryNodeId = addNode("TRY", "try", tryTree);
            List<String> branchExitNodeIds = new ArrayList<>();

            BuildResult tryBody = buildStatement(tryTree.getBlock(), context, exitNodeId);
            addEdge(tryNodeId, tryBody.entryNodeId(), "try");
            branchExitNodeIds.addAll(tryBody.openExitNodeIds());

            for (CatchTree catchTree : tryTree.getCatches()) {
                String catchNodeId = addNode("CATCH", renderCatchLabel(catchTree), catchTree);
                BuildResult catchBody = buildStatement(catchTree.getBlock(), context, exitNodeId);
                addEdge(tryNodeId, catchNodeId, "catch");
                addEdge(catchNodeId, catchBody.entryNodeId(), null);
                branchExitNodeIds.addAll(catchBody.openExitNodeIds());
            }

            if (tryTree.getFinallyBlock() != null) {
                BuildResult finallyBody = buildStatement(tryTree.getFinallyBlock(), context, exitNodeId);
                for (String branchExitNodeId : branchExitNodeIds) {
                    addEdge(branchExitNodeId, finallyBody.entryNodeId(), null);
                }
                return new BuildResult(tryNodeId, finallyBody.openExitNodeIds());
            }

            return new BuildResult(tryNodeId, branchExitNodeIds);
        }

        private String renderCatchLabel(CatchTree catchTree) {
            return "catch (" + catchTree.getParameter() + ")";
        }

        private BuildResult buildTerminal(String type, Tree tree, String exitNodeId, String edgeLabel) {
            String nodeId = addNode(type, renderSnippet(tree, edgeLabel), tree);
            addEdge(nodeId, exitNodeId, edgeLabel);
            return new BuildResult(nodeId, List.of());
        }

        private BuildResult buildBreak(Tree tree, ControlContext context) {
            String nodeId = addNode("BREAK", "break", tree);
            if (context.breakTargetNodeId() != null) {
                addEdge(nodeId, context.breakTargetNodeId(), "break");
            }
            return new BuildResult(nodeId, List.of());
        }

        private BuildResult buildContinue(Tree tree, ControlContext context) {
            String nodeId = addNode("CONTINUE", "continue", tree);
            if (context.continueTargetNodeId() != null) {
                addEdge(nodeId, context.continueTargetNodeId(), "continue");
            }
            return new BuildResult(nodeId, List.of());
        }

        private BuildResult buildLinear(String type, String label, Tree tree) {
            String nodeId = addNode(type, label, tree);
            return new BuildResult(nodeId, List.of(nodeId));
        }

        private String addNode(String type, String label, Tree tree) {
            String nodeId = "n" + (++nodeCounter);
            Integer startLine = tree == null ? null : lineOf(sourcePositions.getStartPosition(compilationUnit, tree));
            Integer endLine = tree == null ? null : endLineOf(tree);
            nodes.add(new GraphNodeDraft(nodeId, type, label, startLine, endLine));
            return nodeId;
        }

        private void addEdge(String source, String target, String label) {
            String edgeId = "e" + (++edgeCounter);
            edges.add(new GraphEdgeDraft(edgeId, source, target, label));
        }

        private int lineOf(long position) {
            if (position == Diagnostic.NOPOS || position < 0) {
                return 1;
            }
            return (int) compilationUnit.getLineMap().getLineNumber(position);
        }

        private int endLineOf(Tree tree) {
            long endPosition = sourcePositions.getEndPosition(compilationUnit, tree);
            if (endPosition == Diagnostic.NOPOS || endPosition <= 0) {
                return lineOf(sourcePositions.getStartPosition(compilationUnit, tree));
            }
            return lineOf(endPosition - 1);
        }

        private String renderSnippet(Tree tree, String fallback) {
            if (tree == null) {
                return fallback;
            }
            long startPosition = sourcePositions.getStartPosition(compilationUnit, tree);
            long endPosition = sourcePositions.getEndPosition(compilationUnit, tree);
            if (startPosition == Diagnostic.NOPOS
                    || endPosition == Diagnostic.NOPOS
                    || startPosition < 0
                    || endPosition <= startPosition
                    || endPosition > sourceCode.length()) {
                return fallback;
            }
            String snippet = sourceCode.substring((int) startPosition, (int) endPosition)
                    .replaceAll("\\s+", " ")
                    .trim();
            if (snippet.isBlank()) {
                return fallback;
            }
            return snippet.length() > 96 ? snippet.substring(0, 93) + "..." : snippet;
        }

        private record BuildResult(String entryNodeId, List<String> openExitNodeIds) {
        }

        private record ControlContext(String breakTargetNodeId, String continueTargetNodeId) {
        }
    }

    private static final class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String sourceCode;

        private InMemoryJavaSource(String sourcePath, String sourceCode) {
            super(URI.create("string:///" + sanitizePath(sourcePath)), JavaFileObject.Kind.SOURCE);
            this.sourceCode = sourceCode;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return sourceCode;
        }

        private static String sanitizePath(String sourcePath) {
            String normalized = sourcePath == null || sourcePath.isBlank() ? "AnalysisTarget.java" : sourcePath;
            return normalized.replace('\\', '/');
        }
    }
}
