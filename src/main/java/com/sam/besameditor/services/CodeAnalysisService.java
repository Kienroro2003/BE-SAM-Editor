package com.sam.besameditor.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.besameditor.analysis.FunctionAnalysisDraft;
import com.sam.besameditor.analysis.GraphEdgeDraft;
import com.sam.besameditor.analysis.GraphNodeDraft;
import com.sam.besameditor.analysis.JavaFileAnalysisResult;
import com.sam.besameditor.analysis.JavaSourceAnalyzer;
import com.sam.besameditor.dto.AnalysisGraphEdgeResponse;
import com.sam.besameditor.dto.AnalysisGraphNodeResponse;
import com.sam.besameditor.dto.FunctionAnalysisSummaryResponse;
import com.sam.besameditor.dto.FunctionCfgResponse;
import com.sam.besameditor.dto.JavaFileAnalysisResponse;
import com.sam.besameditor.exceptions.NotFoundException;
import com.sam.besameditor.models.CloudinaryDeliveryType;
import com.sam.besameditor.models.AnalyzedFunction;
import com.sam.besameditor.models.FlowGraphData;
import com.sam.besameditor.models.Project;
import com.sam.besameditor.models.SourceFile;
import com.sam.besameditor.models.User;
import com.sam.besameditor.repositories.AnalyzedFunctionRepository;
import com.sam.besameditor.repositories.FlowGraphDataRepository;
import com.sam.besameditor.repositories.ProjectRepository;
import com.sam.besameditor.repositories.SourceFileRepository;
import com.sam.besameditor.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class CodeAnalysisService {

    private static final String ANALYSIS_CACHE_VERSION = "analysis-v2";
    private static final TypeReference<List<GraphNodeDraft>> GRAPH_NODE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<GraphEdgeDraft>> GRAPH_EDGE_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final AnalyzedFunctionRepository analyzedFunctionRepository;
    private final FlowGraphDataRepository flowGraphDataRepository;
    private final JavaSourceAnalyzer javaSourceAnalyzer;
    private final CloudinaryWorkspaceStorageService cloudinaryWorkspaceStorageService;
    private final ObjectMapper objectMapper;
    private final long fileContentMaxBytes;

    public CodeAnalysisService(
            UserRepository userRepository,
            ProjectRepository projectRepository,
            SourceFileRepository sourceFileRepository,
            AnalyzedFunctionRepository analyzedFunctionRepository,
            FlowGraphDataRepository flowGraphDataRepository,
            JavaSourceAnalyzer javaSourceAnalyzer,
            CloudinaryWorkspaceStorageService cloudinaryWorkspaceStorageService,
            ObjectMapper objectMapper,
            @Value("${app.workspace.file-content-max-bytes:1048576}") long fileContentMaxBytes) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.analyzedFunctionRepository = analyzedFunctionRepository;
        this.flowGraphDataRepository = flowGraphDataRepository;
        this.javaSourceAnalyzer = javaSourceAnalyzer;
        this.cloudinaryWorkspaceStorageService = cloudinaryWorkspaceStorageService;
        this.objectMapper = objectMapper;
        this.fileContentMaxBytes = fileContentMaxBytes;
    }

    @Transactional
    public JavaFileAnalysisResponse analyzeJavaFile(Long projectId, String rawPath, String userEmail) {
        SourceContext sourceContext = resolveSourceContext(projectId, rawPath, userEmail);
        String currentHash = hashContent(sourceContext.content());
        List<AnalyzedFunction> cachedFunctions = analyzedFunctionRepository
                .findBySourceFile_IdOrderByStartLineAsc(sourceContext.sourceFile().getId());

        if (currentHash.equals(sourceContext.sourceFile().getAnalysisHash())
                && (cachedFunctions.isEmpty() || hasCompleteGraphCache(cachedFunctions))) {
            return buildAnalysisResponse(
                    sourceContext.project().getId(),
                    sourceContext.normalizedPath(),
                    sourceContext.sourceFile().getLanguage(),
                    true,
                    cachedFunctions);
        }

        JavaFileAnalysisResult analysisResult = javaSourceAnalyzer.analyze(sourceContext.normalizedPath(), sourceContext.content());
        List<AnalyzedFunction> savedFunctions = persistAnalysis(sourceContext.sourceFile(), analysisResult, currentHash);

        return buildAnalysisResponse(
                sourceContext.project().getId(),
                sourceContext.normalizedPath(),
                sourceContext.sourceFile().getLanguage(),
                false,
                savedFunctions);
    }

    @Transactional(readOnly = true)
    public JavaFileAnalysisResponse getFunctionSummaries(Long projectId, String rawPath, String userEmail) {
        SourceContext sourceContext = resolveSourceContext(projectId, rawPath, userEmail);
        ensureFreshAnalysisCache(sourceContext.sourceFile(), sourceContext.content());

        List<AnalyzedFunction> cachedFunctions = analyzedFunctionRepository
                .findBySourceFile_IdOrderByStartLineAsc(sourceContext.sourceFile().getId());
        if (!cachedFunctions.isEmpty() && !hasCompleteGraphCache(cachedFunctions)) {
            throw new NotFoundException("Analysis graph cache is incomplete. Re-run analysis.");
        }

        return buildAnalysisResponse(
                sourceContext.project().getId(),
                sourceContext.normalizedPath(),
                sourceContext.sourceFile().getLanguage(),
                true,
                cachedFunctions);
    }

    @Transactional(readOnly = true)
    public FunctionCfgResponse getFunctionCfg(Long projectId, Long functionId, String userEmail) {
        User user = findUserByEmail(userEmail);
        Project project = projectRepository.findByIdAndUser_Id(projectId, user.getId())
                .orElseThrow(() -> new NotFoundException("Workspace not found"));

        AnalyzedFunction analyzedFunction = analyzedFunctionRepository.findByIdAndSourceFile_Project_Id(functionId, projectId)
                .orElseThrow(() -> new NotFoundException("Function analysis not found"));

        String currentContent = readSourceContent(project, analyzedFunction.getSourceFile().getFilePath());
        ensureFreshAnalysisCache(analyzedFunction.getSourceFile(), currentContent);

        FlowGraphData flowGraphData = flowGraphDataRepository.findByAnalyzedFunction_Id(functionId)
                .orElseThrow(() -> new NotFoundException("Function CFG not found"));

        List<AnalysisGraphNodeResponse> nodes = deserializeNodes(flowGraphData.getNodesJson()).stream()
                .map(node -> new AnalysisGraphNodeResponse(node.id(), node.type(), node.label(), node.startLine(), node.endLine()))
                .toList();
        List<AnalysisGraphEdgeResponse> edges = deserializeEdges(flowGraphData.getEdgesJson()).stream()
                .map(edge -> new AnalysisGraphEdgeResponse(edge.id(), edge.source(), edge.target(), edge.label()))
                .toList();

        return new FunctionCfgResponse(
                analyzedFunction.getId(),
                analyzedFunction.getFunctionName(),
                analyzedFunction.getSignature(),
                analyzedFunction.getStartLine(),
                analyzedFunction.getEndLine(),
                analyzedFunction.getCyclomaticComplexity(),
                flowGraphData.getEntryNodeId(),
                deserializeExitNodeIds(flowGraphData.getExitNodeIdsJson()),
                nodes,
                edges);
    }

    private List<AnalyzedFunction> persistAnalysis(SourceFile sourceFile, JavaFileAnalysisResult analysisResult, String contentHash) {
        clearSourceFileCache(sourceFile.getId());

        List<FunctionAnalysisDraft> drafts = analysisResult.functions();
        if (drafts.isEmpty()) {
            sourceFile.setAnalysisHash(contentHash);
            sourceFile.setAnalysisUpdatedAt(LocalDateTime.now());
            sourceFileRepository.save(sourceFile);
            return List.of();
        }

        List<AnalyzedFunction> functionsToSave = drafts.stream()
                .map(draft -> {
                    AnalyzedFunction function = new AnalyzedFunction();
                    function.setSourceFile(sourceFile);
                    function.setFunctionName(draft.functionName());
                    function.setSignature(draft.signature());
                    function.setStartLine(draft.startLine());
                    function.setEndLine(draft.endLine());
                    function.setCyclomaticComplexity(draft.cyclomaticComplexity());
                    return function;
                })
                .toList();

        List<AnalyzedFunction> savedFunctions = analyzedFunctionRepository.saveAll(functionsToSave);
        for (int index = 0; index < drafts.size(); index++) {
            FunctionAnalysisDraft draft = drafts.get(index);
            AnalyzedFunction analyzedFunction = savedFunctions.get(index);
            FlowGraphData flowGraphData = new FlowGraphData();
            flowGraphData.setAnalyzedFunction(analyzedFunction);
            flowGraphData.setNodesJson(writeJson(draft.nodes()));
            flowGraphData.setEdgesJson(writeJson(draft.edges()));
            flowGraphData.setEntryNodeId(draft.entryNodeId());
            flowGraphData.setExitNodeIdsJson(writeJson(draft.exitNodeIds()));
            flowGraphDataRepository.save(flowGraphData);
        }

        sourceFile.setAnalysisHash(contentHash);
        sourceFile.setAnalysisUpdatedAt(LocalDateTime.now());
        sourceFileRepository.save(sourceFile);
        return savedFunctions;
    }

    private JavaFileAnalysisResponse buildAnalysisResponse(
            Long projectId,
            String normalizedPath,
            String language,
            boolean cached,
            List<AnalyzedFunction> functions) {
        List<FunctionAnalysisSummaryResponse> summaries = functions.stream()
                .map(function -> new FunctionAnalysisSummaryResponse(
                        function.getId(),
                        function.getFunctionName(),
                        function.getSignature(),
                        function.getStartLine(),
                        function.getEndLine(),
                        function.getCyclomaticComplexity()))
                .toList();
        return new JavaFileAnalysisResponse(projectId, normalizedPath, language, cached, summaries);
    }

    private boolean hasCompleteGraphCache(List<AnalyzedFunction> functions) {
        if (functions.isEmpty()) {
            return true;
        }

        List<Long> functionIds = functions.stream().map(AnalyzedFunction::getId).toList();
        List<FlowGraphData> flowGraphs = flowGraphDataRepository.findByAnalyzedFunction_IdIn(functionIds);
        Map<Long, FlowGraphData> flowGraphsByFunctionId = new HashMap<>();
        for (FlowGraphData flowGraph : flowGraphs) {
            flowGraphsByFunctionId.put(flowGraph.getAnalyzedFunction().getId(), flowGraph);
        }

        return functionIds.stream().allMatch(flowGraphsByFunctionId::containsKey);
    }

    private void ensureFreshAnalysisCache(SourceFile sourceFile, String currentContent) {
        if (sourceFile.getAnalysisHash() == null || sourceFile.getAnalysisHash().isBlank()) {
            throw new NotFoundException("No analysis found for file");
        }

        String currentHash = hashContent(currentContent);
        if (!currentHash.equals(sourceFile.getAnalysisHash())) {
            throw new NotFoundException("Analysis cache is stale. Re-run analysis.");
        }
    }

    private void clearSourceFileCache(Long sourceFileId) {
        analyzedFunctionRepository.deleteBySourceFile_Id(sourceFileId);
    }

    private SourceContext resolveSourceContext(Long projectId, String rawPath, String userEmail) {
        User user = findUserByEmail(userEmail);
        Project project = projectRepository.findByIdAndUser_Id(projectId, user.getId())
                .orElseThrow(() -> new NotFoundException("Workspace not found"));

        String normalizedPath = normalizePath(rawPath);
        SourceFile sourceFile = sourceFileRepository.findByProject_IdAndFilePath(projectId, normalizedPath)
                .orElseThrow(() -> new NotFoundException("File not found in workspace"));

        if (!"JAVA".equalsIgnoreCase(sourceFile.getLanguage())) {
            throw new IllegalArgumentException("Only JAVA files are supported for analysis");
        }

        String content = readSourceContent(project, normalizedPath);
        return new SourceContext(project, sourceFile, normalizedPath, content);
    }

    private String readSourceContent(Project project, String normalizedPath) {
        if (hasCloudinaryArchive(project)) {
            Path tempWorkspaceRoot = materializeWorkspaceRoot(project);
            try {
                return readSourceContent(tempWorkspaceRoot, normalizedPath);
            } finally {
                deleteDirectoryIfExistsQuietly(tempWorkspaceRoot);
            }
        }

        Path workspaceRoot = resolveWorkspaceRoot(project);
        return readSourceContent(workspaceRoot, normalizedPath);
    }

    private String readSourceContent(Path workspaceRoot, String normalizedPath) {
        Path targetFile = workspaceRoot.resolve(normalizedPath).normalize();
        if (!targetFile.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
            throw new NotFoundException("File not found in workspace");
        }

        try {
            long sizeBytes = Files.size(targetFile);
            if (sizeBytes > fileContentMaxBytes) {
                throw new IllegalArgumentException("File is too large to analyze");
            }
            byte[] fileBytes = Files.readAllBytes(targetFile);
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(fileBytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Only UTF-8 text files are supported for analysis", ex);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read source file for analysis", ex);
        }
    }

    private Path materializeWorkspaceRoot(Project project) {
        Path tempWorkspaceRoot;
        try {
            tempWorkspaceRoot = Files.createTempDirectory("workspace-analysis-");
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read source file for analysis", ex);
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

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private String hashContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ANALYSIS_CACHE_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize analysis graph data", ex);
        }
    }

    private List<GraphNodeDraft> deserializeNodes(String json) {
        try {
            return objectMapper.readValue(json, GRAPH_NODE_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize graph nodes", ex);
        }
    }

    private List<GraphEdgeDraft> deserializeEdges(String json) {
        try {
            return objectMapper.readValue(json, GRAPH_EDGE_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize graph edges", ex);
        }
    }

    private List<String> deserializeExitNodeIds(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to deserialize exit node ids", ex);
        }
    }

    private record SourceContext(Project project, SourceFile sourceFile, String normalizedPath, String content) {
    }
}
