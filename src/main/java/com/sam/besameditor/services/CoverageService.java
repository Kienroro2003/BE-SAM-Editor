package com.sam.besameditor.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.besameditor.analysis.GraphEdgeDraft;
import com.sam.besameditor.analysis.GraphNodeDraft;
import com.sam.besameditor.coverage.CoverageLineStat;
import com.sam.besameditor.coverage.CoverageReportParser;
import com.sam.besameditor.coverage.CoverageSandboxRunner;
import com.sam.besameditor.coverage.CoverageSandboxRunnerRegistry;
import com.sam.besameditor.coverage.CoverageStatus;
import com.sam.besameditor.coverage.SandboxCoverageExecutionResult;
import com.sam.besameditor.dto.AnalysisGraphEdgeResponse;
import com.sam.besameditor.dto.AnalysisGraphNodeResponse;
import com.sam.besameditor.dto.CoverageFunctionSummaryResponse;
import com.sam.besameditor.dto.FunctionCfgResponse;
import com.sam.besameditor.dto.JavaFileCoverageResponse;
import com.sam.besameditor.exceptions.NotFoundException;
import com.sam.besameditor.models.AnalyzedFunction;
import com.sam.besameditor.models.CloudinaryDeliveryType;
import com.sam.besameditor.models.CoverageRun;
import com.sam.besameditor.models.CoverageRunStatus;
import com.sam.besameditor.models.FlowGraphData;
import com.sam.besameditor.models.Project;
import com.sam.besameditor.models.SourceFile;
import com.sam.besameditor.models.User;
import com.sam.besameditor.repositories.AnalyzedFunctionRepository;
import com.sam.besameditor.repositories.CoverageRunRepository;
import com.sam.besameditor.repositories.FlowGraphDataRepository;
import com.sam.besameditor.repositories.ProjectRepository;
import com.sam.besameditor.repositories.SourceFileRepository;
import com.sam.besameditor.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class CoverageService {

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);
    private static final TypeReference<List<CoverageLineStat>> LINE_COVERAGE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<GraphNodeDraft>> GRAPH_NODE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<GraphEdgeDraft>> GRAPH_EDGE_LIST_TYPE = new TypeReference<>() {
    };
    private static final Set<String> TECHNICAL_NODE_TYPES = Set.of("ENTRY", "EXIT", "NOOP", "JOIN");
    private static final Set<String> BRANCHING_NODE_TYPES = Set.of("CONDITION", "LOOP_CONDITION", "SWITCH");
    private static final Set<String> JAVA_LANGUAGES = Set.of("JAVA");

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final AnalyzedFunctionRepository analyzedFunctionRepository;
    private final CoverageRunRepository coverageRunRepository;
    private final FlowGraphDataRepository flowGraphDataRepository;
    private final CodeAnalysisService codeAnalysisService;
    private final CloudinaryWorkspaceStorageService cloudinaryWorkspaceStorageService;
    private final CoverageSandboxRunnerRegistry runnerRegistry;
    private final ObjectMapper objectMapper;

    public CoverageService(
            UserRepository userRepository,
            ProjectRepository projectRepository,
            SourceFileRepository sourceFileRepository,
            AnalyzedFunctionRepository analyzedFunctionRepository,
            CoverageRunRepository coverageRunRepository,
            FlowGraphDataRepository flowGraphDataRepository,
            CodeAnalysisService codeAnalysisService,
            CloudinaryWorkspaceStorageService cloudinaryWorkspaceStorageService,
            CoverageSandboxRunnerRegistry runnerRegistry,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.analyzedFunctionRepository = analyzedFunctionRepository;
        this.coverageRunRepository = coverageRunRepository;
        this.flowGraphDataRepository = flowGraphDataRepository;
        this.codeAnalysisService = codeAnalysisService;
        this.cloudinaryWorkspaceStorageService = cloudinaryWorkspaceStorageService;
        this.runnerRegistry = runnerRegistry;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public JavaFileCoverageResponse runCoverage(Long projectId, String rawPath, String userEmail) {
        SourceContext sourceContext = resolveSourceContext(projectId, rawPath, userEmail);
        String language = sourceContext.sourceFile().getLanguage();

        if (isJavaLanguage(language)) {
            codeAnalysisService.analyzeJavaFile(projectId, rawPath, userEmail);
            sourceContext = resolveSourceContext(projectId, rawPath, userEmail);
        } else {
            codeAnalysisService.analyzeFile(projectId, rawPath, userEmail, null);
            sourceContext = resolveSourceContext(projectId, rawPath, userEmail);
        }

        CoverageSandboxRunner runner = runnerRegistry.getRunner(language);
        CoverageReportParser parser = runnerRegistry.getParser(language);

        CoverageRun coverageRun = new CoverageRun();
        coverageRun.setProjectId(projectId);
        coverageRun.setSourceFilePath(sourceContext.normalizedPath());
        coverageRun.setLanguage(language);
        coverageRun.setSourceHash(sourceContext.sourceFile().getAnalysisHash() != null
                ? sourceContext.sourceFile().getAnalysisHash()
                : "no-analysis");
        coverageRun.setStartedAt(LocalDateTime.now());
        coverageRun.setCommand("pending");
        coverageRun.setStatus(CoverageRunStatus.FAILED);

        List<CoverageLineStat> lineStats = List.of();
        Path temporaryWorkspaceRoot = null;
        try {
            Path workspaceRoot;
            if (hasCloudinaryArchive(sourceContext.project())) {
                temporaryWorkspaceRoot = materializeWorkspaceRoot(sourceContext.project());
                workspaceRoot = temporaryWorkspaceRoot;
            } else {
                workspaceRoot = resolveWorkspaceRoot(sourceContext.project());
            }

            SandboxCoverageExecutionResult executionResult = runner.run(
                    workspaceRoot,
                    sourceContext.normalizedPath());
            coverageRun.setStatus(executionResult.status());
            coverageRun.setCommand(executionResult.command());
            coverageRun.setExitCode(executionResult.exitCode());
            coverageRun.setStdoutText(executionResult.stdout());
            coverageRun.setStderrText(executionResult.stderr());
            coverageRun.setCompletedAt(LocalDateTime.now());
            logSandboxExecution(sourceContext, executionResult);

            if (executionResult.status() == CoverageRunStatus.SUCCEEDED && executionResult.reportPath() != null) {
                try {
                    Map<String, List<CoverageLineStat>> coverageBySourceFile = parser.parse(executionResult.reportPath());
                    lineStats = resolveLineStats(coverageBySourceFile, sourceContext.normalizedPath(), language);
                    coverageRun.setLineCoverageJson(writeJson(lineStats));
                } finally {
                    Files.deleteIfExists(executionResult.reportPath());
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to clean up coverage report", ex);
        } catch (RuntimeException ex) {
            coverageRun.setStatus(CoverageRunStatus.FAILED);
            coverageRun.setCommand("sandbox coverage run");
            coverageRun.setStderrText(ex.getMessage());
            coverageRun.setCompletedAt(LocalDateTime.now());
        } finally {
            deleteDirectoryIfExistsQuietly(temporaryWorkspaceRoot);
        }

        CoverageRun savedRun = coverageRunRepository.save(coverageRun);

        boolean overlayAvailable = savedRun.getStatus() == CoverageRunStatus.SUCCEEDED;
        List<AnalyzedFunction> functions = analyzedFunctionRepository
                .findBySourceFile_IdOrderByStartLineAsc(sourceContext.sourceFile().getId());
        Map<Long, FlowGraphData> flowGraphsByFunctionId = loadFlowGraphsForMissingCoverage(savedRun.getStatus(), functions);
        List<CoverageLineStat> effectiveLineStats = lineStats;
        List<CoverageFunctionSummaryResponse> summaries = functions.stream()
                .map(function -> buildCoverageFunctionSummary(
                        function,
                        effectiveLineStats,
                        savedRun.getStatus(),
                        overlayAvailable,
                        flowGraphsByFunctionId.get(function.getId())))
                .toList();
        CoverageSnapshot coverageSnapshot = buildCoverageSnapshot(savedRun.getStatus(), effectiveLineStats, summaries);

        return new JavaFileCoverageResponse(
                savedRun.getId(),
                savedRun.getProjectId(),
                savedRun.getSourceFilePath(),
                savedRun.getLanguage(),
                savedRun.getStatus().name(),
                savedRun.getExitCode(),
                overlayAvailable,
                savedRun.getCommand(),
                savedRun.getStdoutText(),
                savedRun.getStderrText(),
                savedRun.getStartedAt(),
                savedRun.getCompletedAt(),
                summaries,
                coverageSnapshot.coveredLines(),
                coverageSnapshot.uncoveredLines(),
                coverageSnapshot.coveredBranches(),
                coverageSnapshot.uncoveredBranches());
    }

    private void logSandboxExecution(SourceContext sourceContext, SandboxCoverageExecutionResult executionResult) {
        log.info(
                "Coverage sandbox completed: projectId={}, path={}, language={}, status={}, exitCode={}, command={}",
                sourceContext.project().getId(),
                sourceContext.normalizedPath(),
                sourceContext.sourceFile().getLanguage(),
                executionResult.status(),
                executionResult.exitCode(),
                executionResult.command());

        if (executionResult.stdout() != null && !executionResult.stdout().isBlank()) {
            log.info(
                    "Coverage sandbox stdout: projectId={}, path={}\n{}",
                    sourceContext.project().getId(),
                    sourceContext.normalizedPath(),
                    executionResult.stdout());
        }

        if (executionResult.stderr() != null && !executionResult.stderr().isBlank()) {
            if (executionResult.status() == CoverageRunStatus.SUCCEEDED) {
                log.info(
                        "Coverage sandbox stderr: projectId={}, path={}\n{}",
                        sourceContext.project().getId(),
                        sourceContext.normalizedPath(),
                        executionResult.stderr());
            } else {
                log.warn(
                        "Coverage sandbox stderr: projectId={}, path={}\n{}",
                        sourceContext.project().getId(),
                        sourceContext.normalizedPath(),
                        executionResult.stderr());
            }
        }
    }

    @Transactional(readOnly = true)
    public FunctionCfgResponse getFunctionCfgWithCoverage(Long projectId, Long functionId, Long coverageRunId, String userEmail) {
        FunctionCfgResponse baseResponse = codeAnalysisService.getFunctionCfg(projectId, functionId, userEmail);
        AnalyzedFunction analyzedFunction = analyzedFunctionRepository.findByIdAndSourceFile_Project_Id(functionId, projectId)
                .orElseThrow(() -> new NotFoundException("Function analysis not found"));
        CoverageRun coverageRun = coverageRunRepository.findByIdAndProjectId(coverageRunId, projectId)
                .orElseThrow(() -> new NotFoundException("Coverage run not found"));

        if (!coverageRun.getSourceFilePath().equals(analyzedFunction.getSourceFile().getFilePath())) {
            throw new NotFoundException("Coverage run does not match requested file");
        }
        if (!coverageRun.getSourceHash().equals(analyzedFunction.getSourceFile().getAnalysisHash())) {
            throw new NotFoundException("Coverage cache is stale. Re-run coverage.");
        }

        if (coverageRun.getStatus() == CoverageRunStatus.SUCCEEDED && coverageRun.getLineCoverageJson() != null) {
            List<CoverageLineStat> lineStats = deserializeLineCoverage(coverageRun.getLineCoverageJson());
            CoverageAggregate functionCoverage = summarizeCoverage(lineStats, baseResponse.getStartLine(), baseResponse.getEndLine(), true, false);
            List<AnalysisGraphNodeResponse> nodes = baseResponse.getNodes().stream()
                    .map(node -> applyCoverage(node, lineStats))
                    .toList();
            return buildFunctionCfgResponse(baseResponse, coverageRun, functionCoverage, nodes);
        }

        if (coverageRun.getStatus() == CoverageRunStatus.NO_TESTS_FOUND) {
            Map<String, Long> outgoingCounts = buildOutgoingEdgeCounts(baseResponse.getEdges());
            CoverageAggregate functionCoverage = estimateMissingCoverage(
                    baseResponse.getNodes(),
                    outgoingCounts,
                    baseResponse.getStartLine(),
                    baseResponse.getEndLine(),
                    baseResponse.getCyclomaticComplexity());
            List<AnalysisGraphNodeResponse> nodes = baseResponse.getNodes().stream()
                    .map(node -> applySyntheticMissingCoverage(node, outgoingCounts))
                    .toList();
            return buildFunctionCfgResponse(baseResponse, coverageRun, functionCoverage, nodes);
        }

        throw new NotFoundException("Coverage overlay is not available for this run");
    }

    private FunctionCfgResponse buildFunctionCfgResponse(
            FunctionCfgResponse baseResponse,
            CoverageRun coverageRun,
            CoverageAggregate functionCoverage,
            List<AnalysisGraphNodeResponse> nodes) {
        return new FunctionCfgResponse(
                baseResponse.getFunctionId(),
                baseResponse.getFunctionName(),
                baseResponse.getSignature(),
                baseResponse.getStartLine(),
                baseResponse.getEndLine(),
                baseResponse.getCyclomaticComplexity(),
                baseResponse.getEntryNodeId(),
                baseResponse.getExitNodeIds(),
                nodes,
                baseResponse.getEdges(),
                coverageRun.getId(),
                functionCoverage.status().name(),
                functionCoverage.coveredLineCount(),
                functionCoverage.missedLineCount(),
                functionCoverage.coveredBranchCount(),
                functionCoverage.missedBranchCount());
    }

    private boolean isJavaLanguage(String language) {
        return language != null && JAVA_LANGUAGES.contains(language.trim().toUpperCase());
    }

    private CoverageFunctionSummaryResponse buildCoverageFunctionSummary(
            AnalyzedFunction function,
            List<CoverageLineStat> lineStats,
            CoverageRunStatus runStatus,
            boolean overlayAvailable,
            FlowGraphData flowGraphData) {
        CoverageAggregate coverage;
        if (runStatus == CoverageRunStatus.NO_TESTS_FOUND) {
            coverage = estimateMissingCoverage(function, flowGraphData);
        } else {
            coverage = summarizeCoverage(lineStats, function.getStartLine(), function.getEndLine(), overlayAvailable, true);
        }
        return new CoverageFunctionSummaryResponse(
                function.getId(),
                function.getFunctionName(),
                function.getSignature(),
                function.getStartLine(),
                function.getEndLine(),
                function.getCyclomaticComplexity(),
                coverage.status().name(),
                coverage.coveredLineCount(),
                coverage.missedLineCount(),
                coverage.coveredBranchCount(),
                coverage.missedBranchCount());
    }

    private Map<Long, FlowGraphData> loadFlowGraphsForMissingCoverage(
            CoverageRunStatus runStatus,
            List<AnalyzedFunction> functions) {
        if (runStatus != CoverageRunStatus.NO_TESTS_FOUND || functions.isEmpty()) {
            return Map.of();
        }

        List<Long> functionIds = functions.stream()
                .map(AnalyzedFunction::getId)
                .toList();
        return flowGraphDataRepository.findByAnalyzedFunction_IdIn(functionIds).stream()
                .collect(Collectors.toMap(flowGraph -> flowGraph.getAnalyzedFunction().getId(), Function.identity()));
    }

    private CoverageAggregate estimateMissingCoverage(AnalyzedFunction function, FlowGraphData flowGraphData) {
        int missedLineCount = estimateExecutableLineCount(function, flowGraphData);
        int missedBranchCount = estimateBranchCount(function, flowGraphData);
        return new CoverageAggregate(CoverageStatus.MISSED, 0, missedLineCount, 0, missedBranchCount);
    }

    private CoverageAggregate estimateMissingCoverage(
            List<AnalysisGraphNodeResponse> nodes,
            Map<String, Long> outgoingCounts,
            int startLine,
            int endLine,
            int cyclomaticComplexity) {
        int missedLineCount = estimateExecutableLineCount(nodes, startLine, endLine);
        int missedBranchCount = estimateBranchCount(nodes, outgoingCounts, cyclomaticComplexity);
        return new CoverageAggregate(CoverageStatus.MISSED, 0, missedLineCount, 0, missedBranchCount);
    }

    private int estimateExecutableLineCount(AnalyzedFunction function, FlowGraphData flowGraphData) {
        List<GraphNodeDraft> nodes = deserializeGraphNodes(flowGraphData);
        if (!nodes.isEmpty()) {
            return (int) nodes.stream()
                    .filter(this::isExecutableGraphNode)
                    .flatMapToInt(node -> IntStream.rangeClosed(node.startLine(), Math.max(node.startLine(), node.endLine())))
                    .distinct()
                    .count();
        }
        return Math.max(0, function.getEndLine() - function.getStartLine() + 1);
    }

    private int estimateExecutableLineCount(
            List<AnalysisGraphNodeResponse> nodes,
            int startLine,
            int endLine) {
        long executableLineCount = nodes.stream()
                .filter(this::isExecutableGraphNode)
                .flatMapToInt(this::toLineRange)
                .distinct()
                .count();
        if (executableLineCount > 0) {
            return (int) executableLineCount;
        }
        return Math.max(0, endLine - startLine + 1);
    }

    private int estimateBranchCount(AnalyzedFunction function, FlowGraphData flowGraphData) {
        List<GraphNodeDraft> nodes = deserializeGraphNodes(flowGraphData);
        List<GraphEdgeDraft> edges = deserializeGraphEdges(flowGraphData);
        if (!nodes.isEmpty() && !edges.isEmpty()) {
            Map<String, Long> outgoingCounts = edges.stream()
                    .collect(Collectors.groupingBy(GraphEdgeDraft::source, Collectors.counting()));
            int branchCount = nodes.stream()
                    .filter(node -> BRANCHING_NODE_TYPES.contains(normalizeNodeType(node.type())))
                    .mapToInt(node -> Math.toIntExact(outgoingCounts.getOrDefault(node.id(), 0L)))
                    .sum();
            if (branchCount > 0) {
                return branchCount;
            }
        }
        return function.getCyclomaticComplexity() > 1 ? function.getCyclomaticComplexity() : 0;
    }

    private int estimateBranchCount(
            List<AnalysisGraphNodeResponse> nodes,
            Map<String, Long> outgoingCounts,
            int cyclomaticComplexity) {
        int branchCount = nodes.stream()
                .filter(this::isBranchingGraphNode)
                .mapToInt(node -> Math.toIntExact(outgoingCounts.getOrDefault(node.getId(), 0L)))
                .sum();
        if (branchCount > 0) {
            return branchCount;
        }
        return cyclomaticComplexity > 1 ? cyclomaticComplexity : 0;
    }

    private boolean isExecutableGraphNode(GraphNodeDraft node) {
        return node.startLine() != null
                && node.endLine() != null
                && !TECHNICAL_NODE_TYPES.contains(normalizeNodeType(node.type()));
    }

    private boolean isExecutableGraphNode(AnalysisGraphNodeResponse node) {
        return node.getStartLine() != null
                && node.getEndLine() != null
                && !TECHNICAL_NODE_TYPES.contains(normalizeNodeType(node.getType()));
    }

    private boolean isBranchingGraphNode(AnalysisGraphNodeResponse node) {
        return BRANCHING_NODE_TYPES.contains(normalizeNodeType(node.getType()));
    }

    private IntStream toLineRange(AnalysisGraphNodeResponse node) {
        if (node.getStartLine() == null || node.getEndLine() == null) {
            return IntStream.empty();
        }
        return IntStream.rangeClosed(node.getStartLine(), Math.max(node.getStartLine(), node.getEndLine()));
    }

    private String normalizeNodeType(String type) {
        return type == null ? "" : type.trim().toUpperCase();
    }

    private Map<String, Long> buildOutgoingEdgeCounts(List<AnalysisGraphEdgeResponse> edges) {
        return edges.stream()
                .collect(Collectors.groupingBy(AnalysisGraphEdgeResponse::getSource, Collectors.counting()));
    }

    private AnalysisGraphNodeResponse applyCoverage(AnalysisGraphNodeResponse node, List<CoverageLineStat> lineStats) {
        if (node.getStartLine() == null || node.getEndLine() == null || TECHNICAL_NODE_TYPES.contains(node.getType())) {
            return new AnalysisGraphNodeResponse(
                    node.getId(),
                    node.getType(),
                    node.getLabel(),
                    node.getStartLine(),
                    node.getEndLine(),
                    CoverageStatus.NEUTRAL.name(),
                    0,
                    0,
                    0,
                    0);
        }

        CoverageAggregate coverage = summarizeCoverage(lineStats, node.getStartLine(), node.getEndLine(), true, false);
        return new AnalysisGraphNodeResponse(
                node.getId(),
                node.getType(),
                node.getLabel(),
                node.getStartLine(),
                node.getEndLine(),
                coverage.status().name(),
                coverage.coveredLineCount(),
                coverage.missedLineCount(),
                coverage.coveredBranchCount(),
                coverage.missedBranchCount());
    }

    private AnalysisGraphNodeResponse applySyntheticMissingCoverage(
            AnalysisGraphNodeResponse node,
            Map<String, Long> outgoingCounts) {
        if (!isExecutableGraphNode(node)) {
            return new AnalysisGraphNodeResponse(
                    node.getId(),
                    node.getType(),
                    node.getLabel(),
                    node.getStartLine(),
                    node.getEndLine(),
                    CoverageStatus.NEUTRAL.name(),
                    0,
                    0,
                    0,
                    0);
        }

        int missedLineCount = (int) toLineRange(node).count();
        int missedBranchCount = isBranchingGraphNode(node)
                ? Math.toIntExact(outgoingCounts.getOrDefault(node.getId(), 0L))
                : 0;

        return new AnalysisGraphNodeResponse(
                node.getId(),
                node.getType(),
                node.getLabel(),
                node.getStartLine(),
                node.getEndLine(),
                CoverageStatus.MISSED.name(),
                0,
                missedLineCount,
                0,
                missedBranchCount);
    }

    private CoverageAggregate summarizeCoverage(
            List<CoverageLineStat> lineStats,
            int startLine,
            int endLine,
            boolean markMissedWhenAbsent,
            boolean neutralWhenOverlayMissing) {
        int normalizedEndLine = Math.max(startLine, endLine);
        List<CoverageLineStat> relevantLines = lineStats.stream()
                .filter(CoverageLineStat::isExecutable)
                .filter(line -> line.lineNumber() >= startLine && line.lineNumber() <= normalizedEndLine)
                .sorted(Comparator.comparingInt(CoverageLineStat::lineNumber))
                .toList();

        int coveredLineCount = (int) relevantLines.stream().filter(CoverageLineStat::isCovered).count();
        int missedLineCount = (int) relevantLines.stream().filter(line -> !line.isCovered()).count();
        int coveredBranchCount = relevantLines.stream().mapToInt(CoverageLineStat::coveredBranches).sum();
        int missedBranchCount = relevantLines.stream().mapToInt(CoverageLineStat::missedBranches).sum();

        CoverageStatus status;
        if (!relevantLines.isEmpty()) {
            status = coveredLineCount > 0 ? CoverageStatus.COVERED : CoverageStatus.MISSED;
        } else if (!markMissedWhenAbsent && neutralWhenOverlayMissing) {
            status = CoverageStatus.NEUTRAL;
        } else {
            status = markMissedWhenAbsent ? CoverageStatus.MISSED : CoverageStatus.NEUTRAL;
        }

        return new CoverageAggregate(status, coveredLineCount, missedLineCount, coveredBranchCount, missedBranchCount);
    }

    private List<CoverageLineStat> resolveLineStats(Map<String, List<CoverageLineStat>> coverageBySourceFile, String normalizedPath, String language) {
        if (isJavaLanguage(language)) {
            String expectedKey = toJacocoSourceKey(normalizedPath);
            if (coverageBySourceFile.containsKey(expectedKey)) {
                return coverageBySourceFile.get(expectedKey);
            }
        } else {
            if (coverageBySourceFile.containsKey(normalizedPath)) {
                return coverageBySourceFile.get(normalizedPath);
            }
        }

        String fileName = Path.of(normalizedPath).getFileName().toString();
        List<List<CoverageLineStat>> matches = coverageBySourceFile.entrySet().stream()
                .filter(entry -> entry.getKey().equals(fileName) || entry.getKey().endsWith("/" + fileName))
                .map(Map.Entry::getValue)
                .toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        return List.of();
    }

    private CoverageSnapshot buildCoverageSnapshot(
            CoverageRunStatus runStatus,
            List<CoverageLineStat> lineStats,
            List<CoverageFunctionSummaryResponse> summaries) {
        if (runStatus == CoverageRunStatus.SUCCEEDED && lineStats != null && !lineStats.isEmpty()) {
            List<Integer> coveredLines = lineStats.stream()
                    .filter(CoverageLineStat::isExecutable)
                    .filter(CoverageLineStat::isCovered)
                    .map(CoverageLineStat::lineNumber)
                    .distinct()
                    .sorted()
                    .toList();
            List<Integer> uncoveredLines = lineStats.stream()
                    .filter(CoverageLineStat::isExecutable)
                    .filter(line -> !line.isCovered())
                    .map(CoverageLineStat::lineNumber)
                    .distinct()
                    .sorted()
                    .toList();

            return new CoverageSnapshot(
                    coveredLines,
                    uncoveredLines,
                    expandBranchIds(lineStats, true),
                    expandBranchIds(lineStats, false));
        }

        if (runStatus == CoverageRunStatus.NO_TESTS_FOUND && summaries != null && !summaries.isEmpty()) {
            List<Integer> uncoveredLines = summaries.stream()
                    .flatMapToInt(summary -> IntStream.rangeClosed(
                            summary.getStartLine(),
                            Math.max(summary.getStartLine(), summary.getEndLine())))
                    .distinct()
                    .sorted()
                    .boxed()
                    .toList();
            List<String> uncoveredBranches = summaries.stream()
                    .flatMap(summary -> IntStream.rangeClosed(1, Math.max(summary.getMissedBranchCount(), 0))
                            .mapToObj(index -> "F" + summary.getFunctionId() + "#M" + index))
                    .toList();

            return new CoverageSnapshot(List.of(), uncoveredLines, List.of(), uncoveredBranches);
        }

        return CoverageSnapshot.empty();
    }

    private List<String> expandBranchIds(List<CoverageLineStat> lineStats, boolean covered) {
        String marker = covered ? "C" : "M";
        return lineStats.stream()
                .filter(CoverageLineStat::isExecutable)
                .flatMap(line -> {
                    int branchCount = covered ? line.coveredBranches() : line.missedBranches();
                    return IntStream.rangeClosed(1, Math.max(branchCount, 0))
                            .mapToObj(index -> "L" + line.lineNumber() + "#" + marker + index);
                })
                .toList();
    }

    private String toJacocoSourceKey(String normalizedPath) {
        String unixPath = normalizedPath.replace('\\', '/');
        for (String sourcePrefix : List.of("src/main/java/", "src/test/java/")) {
            int index = unixPath.indexOf(sourcePrefix);
            if (index >= 0) {
                return unixPath.substring(index + sourcePrefix.length());
            }
        }
        return Path.of(unixPath).getFileName().toString();
    }

    private List<CoverageLineStat> deserializeLineCoverage(String json) {
        try {
            return objectMapper.readValue(json, LINE_COVERAGE_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize line coverage data", ex);
        }
    }

    private List<GraphNodeDraft> deserializeGraphNodes(FlowGraphData flowGraphData) {
        if (flowGraphData == null || flowGraphData.getNodesJson() == null || flowGraphData.getNodesJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(flowGraphData.getNodesJson(), GRAPH_NODE_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("Unable to deserialize flow-graph nodes for analyzedFunctionId={}", flowGraphData.getAnalyzedFunction().getId(), ex);
            return List.of();
        }
    }

    private List<GraphEdgeDraft> deserializeGraphEdges(FlowGraphData flowGraphData) {
        if (flowGraphData == null || flowGraphData.getEdgesJson() == null || flowGraphData.getEdgesJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(flowGraphData.getEdgesJson(), GRAPH_EDGE_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("Unable to deserialize flow-graph edges for analyzedFunctionId={}", flowGraphData.getAnalyzedFunction().getId(), ex);
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize coverage data", ex);
        }
    }

    private SourceContext resolveSourceContext(Long projectId, String rawPath, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
            .or(() -> userRepository.findByGithubId(userEmail))
                .orElseThrow(() -> new NotFoundException("User not found"));
        Project project = projectRepository.findByIdAndUser_Id(projectId, user.getId())
                .orElseThrow(() -> new NotFoundException("Workspace not found"));
        String normalizedPath = normalizePath(rawPath);
        SourceFile sourceFile = sourceFileRepository.findByProject_IdAndFilePath(projectId, normalizedPath)
                .orElseThrow(() -> new NotFoundException("File not found in workspace"));

        if (!runnerRegistry.isSupported(sourceFile.getLanguage())) {
            throw new IllegalArgumentException("Coverage is not supported for language: " + sourceFile.getLanguage());
        }
        return new SourceContext(project, sourceFile, normalizedPath);
    }

    private Path resolveWorkspaceRoot(Project project) {
        String rawStoragePath = project.getStoragePath();
        if (rawStoragePath == null || rawStoragePath.isBlank()) {
            throw new NotFoundException("Workspace source not found on server");
        }

        try {
            Path workspaceRoot = Path.of(rawStoragePath).toAbsolutePath().normalize();
            if (!Files.exists(workspaceRoot) || !Files.isDirectory(workspaceRoot)) {
                throw new NotFoundException("Workspace source not found on server");
            }
            return workspaceRoot;
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Workspace source path is invalid", ex);
        }
    }

    private Path materializeWorkspaceRoot(Project project) {
        Path tempWorkspaceRoot;
        try {
            tempWorkspaceRoot = Files.createTempDirectory("workspace-coverage-");
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to prepare workspace for coverage", ex);
        }

        try {
            cloudinaryWorkspaceStorageService.restoreWorkspaceArchive(
                    tempWorkspaceRoot,
                    project.getCloudinaryPublicId(),
                    project.getCloudinaryUrl(),
                    resolveCloudinaryDeliveryType(project));
            return tempWorkspaceRoot;
        } catch (RuntimeException ex) {
            deleteDirectoryIfExistsQuietly(tempWorkspaceRoot);
            throw ex;
        }
    }

    private boolean hasCloudinaryArchive(Project project) {
        return (project.getCloudinaryPublicId() != null && !project.getCloudinaryPublicId().isBlank())
                || (project.getCloudinaryUrl() != null && !project.getCloudinaryUrl().isBlank());
    }

    private CloudinaryDeliveryType resolveCloudinaryDeliveryType(Project project) {
        return CloudinaryDeliveryType.resolve(project.getCloudinaryDeliveryType());
    }

    private void deleteDirectoryIfExistsQuietly(Path workspaceRoot) {
        if (workspaceRoot == null || !Files.exists(workspaceRoot)) {
            return;
        }
        try (var walk = Files.walk(workspaceRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }

        String normalized = rawPath.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Invalid file path");
        }
        if (normalized.contains("../") || normalized.contains("..\\") || normalized.equals("..")) {
            throw new IllegalArgumentException("Invalid file path");
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    private record SourceContext(Project project, SourceFile sourceFile, String normalizedPath) {
    }

    private record CoverageAggregate(
            CoverageStatus status,
            int coveredLineCount,
            int missedLineCount,
            int coveredBranchCount,
            int missedBranchCount) {
    }

    private record CoverageSnapshot(
            List<Integer> coveredLines,
            List<Integer> uncoveredLines,
            List<String> coveredBranches,
            List<String> uncoveredBranches) {

        private static CoverageSnapshot empty() {
            return new CoverageSnapshot(List.of(), List.of(), List.of(), List.of());
        }
    }
}
