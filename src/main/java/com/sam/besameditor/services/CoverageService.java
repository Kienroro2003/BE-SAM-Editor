package com.sam.besameditor.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.sam.besameditor.models.CoverageRun;
import com.sam.besameditor.models.CoverageRunStatus;
import com.sam.besameditor.models.Project;
import com.sam.besameditor.models.SourceFile;
import com.sam.besameditor.models.User;
import com.sam.besameditor.repositories.AnalyzedFunctionRepository;
import com.sam.besameditor.repositories.CoverageRunRepository;
import com.sam.besameditor.repositories.ProjectRepository;
import com.sam.besameditor.repositories.SourceFileRepository;
import com.sam.besameditor.repositories.UserRepository;
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

@Service
public class CoverageService {

    private static final TypeReference<List<CoverageLineStat>> LINE_COVERAGE_LIST_TYPE = new TypeReference<>() {
    };
    private static final Set<String> TECHNICAL_NODE_TYPES = Set.of("ENTRY", "EXIT", "NOOP", "JOIN");
    private static final Set<String> JAVA_LANGUAGES = Set.of("JAVA");

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final AnalyzedFunctionRepository analyzedFunctionRepository;
    private final CoverageRunRepository coverageRunRepository;
    private final CodeAnalysisService codeAnalysisService;
    private final CoverageSandboxRunnerRegistry runnerRegistry;
    private final ObjectMapper objectMapper;

    public CoverageService(
            UserRepository userRepository,
            ProjectRepository projectRepository,
            SourceFileRepository sourceFileRepository,
            AnalyzedFunctionRepository analyzedFunctionRepository,
            CoverageRunRepository coverageRunRepository,
            CodeAnalysisService codeAnalysisService,
            CoverageSandboxRunnerRegistry runnerRegistry,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.analyzedFunctionRepository = analyzedFunctionRepository;
        this.coverageRunRepository = coverageRunRepository;
        this.codeAnalysisService = codeAnalysisService;
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
        try {
            SandboxCoverageExecutionResult executionResult = runner.run(
                    resolveWorkspaceRoot(sourceContext.project()),
                    sourceContext.normalizedPath());
            coverageRun.setStatus(executionResult.status());
            coverageRun.setCommand(executionResult.command());
            coverageRun.setExitCode(executionResult.exitCode());
            coverageRun.setStdoutText(executionResult.stdout());
            coverageRun.setStderrText(executionResult.stderr());
            coverageRun.setCompletedAt(LocalDateTime.now());

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
        }

        CoverageRun savedRun = coverageRunRepository.save(coverageRun);

        boolean overlayAvailable = savedRun.getStatus() == CoverageRunStatus.SUCCEEDED;
        List<CoverageFunctionSummaryResponse> summaries;
        if (isJavaLanguage(language)) {
            List<AnalyzedFunction> functions = analyzedFunctionRepository
                    .findBySourceFile_IdOrderByStartLineAsc(sourceContext.sourceFile().getId());
            List<CoverageLineStat> effectiveLineStats = lineStats;
            summaries = functions.stream()
                    .map(function -> buildCoverageFunctionSummary(function, effectiveLineStats, overlayAvailable))
                    .toList();
        } else {
            summaries = List.of();
        }

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
                summaries);
    }

    @Transactional(readOnly = true)
    public FunctionCfgResponse getFunctionCfgWithCoverage(Long projectId, Long functionId, Long coverageRunId, String userEmail) {
        FunctionCfgResponse baseResponse = codeAnalysisService.getFunctionCfg(projectId, functionId, userEmail);
        AnalyzedFunction analyzedFunction = analyzedFunctionRepository.findByIdAndSourceFile_Project_Id(functionId, projectId)
                .orElseThrow(() -> new NotFoundException("Function analysis not found"));
        CoverageRun coverageRun = coverageRunRepository.findByIdAndProjectId(coverageRunId, projectId)
                .orElseThrow(() -> new NotFoundException("Coverage run not found"));

        if (coverageRun.getStatus() != CoverageRunStatus.SUCCEEDED || coverageRun.getLineCoverageJson() == null) {
            throw new NotFoundException("Coverage overlay is not available for this run");
        }
        if (!coverageRun.getSourceFilePath().equals(analyzedFunction.getSourceFile().getFilePath())) {
            throw new NotFoundException("Coverage run does not match requested file");
        }
        if (!coverageRun.getSourceHash().equals(analyzedFunction.getSourceFile().getAnalysisHash())) {
            throw new NotFoundException("Coverage cache is stale. Re-run coverage.");
        }

        List<CoverageLineStat> lineStats = deserializeLineCoverage(coverageRun.getLineCoverageJson());
        CoverageAggregate functionCoverage = summarizeCoverage(lineStats, baseResponse.getStartLine(), baseResponse.getEndLine(), true, false);
        List<AnalysisGraphNodeResponse> nodes = baseResponse.getNodes().stream()
                .map(node -> applyCoverage(node, lineStats))
                .toList();
        List<AnalysisGraphEdgeResponse> edges = baseResponse.getEdges();

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
                edges,
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
            boolean overlayAvailable) {
        CoverageAggregate coverage = summarizeCoverage(lineStats, function.getStartLine(), function.getEndLine(), overlayAvailable, true);
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize coverage data", ex);
        }
    }

    private SourceContext resolveSourceContext(Long projectId, String rawPath, String userEmail) {
        User user = userRepository.findByEmail(userEmail)
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
}
