package com.sam.besameditor.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.besameditor.analysis.GraphEdgeDraft;
import com.sam.besameditor.analysis.GraphNodeDraft;
import com.sam.besameditor.analysis.JavaSourceAnalyzer;
import com.sam.besameditor.dto.FunctionCfgResponse;
import com.sam.besameditor.dto.JavaFileAnalysisResponse;
import com.sam.besameditor.exceptions.NotFoundException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CodeAnalysisServiceTest {

    private static final String ANALYSIS_CACHE_VERSION = "analysis-v2";

    @Mock
    private UserRepository userRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SourceFileRepository sourceFileRepository;
    @Mock
    private AnalyzedFunctionRepository analyzedFunctionRepository;
    @Mock
    private FlowGraphDataRepository flowGraphDataRepository;
    @Mock
    private CloudinaryWorkspaceStorageService cloudinaryWorkspaceStorageService;

    @TempDir
    Path tempDir;

    private CodeAnalysisService codeAnalysisService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        codeAnalysisService = new CodeAnalysisService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                flowGraphDataRepository,
                new JavaSourceAnalyzer(),
                cloudinaryWorkspaceStorageService,
                objectMapper,
                1_048_576L);
    }

    @Test
    void analyzeJavaFile_ShouldPersistFunctionsAndGraph() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-12"));
        String relativePath = "src/Calculator.java";
        writeJavaFile(workspaceRoot, relativePath, """
                class Calculator {
                    int divide(int a, int b) {
                        if (b == 0) {
                            return 0;
                        }
                        return a / b;
                    }
                }
                """);

        User user = createUser(5L, "user@test.com");
        Project project = createProject(12L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(20L, project, relativePath, "JAVA");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(12L, 5L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(12L, relativePath)).thenReturn(Optional.of(sourceFile));
        when(analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(20L)).thenReturn(List.of());
        when(sourceFileRepository.save(any(SourceFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(analyzedFunctionRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<AnalyzedFunction> functions = invocation.getArgument(0);
            long nextId = 100L;
            for (AnalyzedFunction function : functions) {
                function.setId(nextId++);
            }
            return functions;
        });

        JavaFileAnalysisResponse response = codeAnalysisService.analyzeJavaFile(12L, relativePath, "user@test.com");

        assertFalse(response.isCached());
        assertEquals(1, response.getFunctions().size());
        assertEquals("divide", response.getFunctions().get(0).getFunctionName());
        verify(flowGraphDataRepository).save(any(FlowGraphData.class));

        ArgumentCaptor<SourceFile> sourceFileCaptor = ArgumentCaptor.forClass(SourceFile.class);
        verify(sourceFileRepository, atLeastOnce()).save(sourceFileCaptor.capture());
        assertTrue(sourceFileCaptor.getAllValues().stream().anyMatch(saved -> saved.getAnalysisHash() != null));
    }

    @Test
    void getFunctionSummaries_ShouldReturnCachedResult_WhenHashMatches() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-15"));
        String relativePath = "src/Sample.java";
        String content = """
                class Sample {
                    void run() {
                        System.out.println("ok");
                    }
                }
                """;
        writeJavaFile(workspaceRoot, relativePath, content);

        User user = createUser(6L, "user@test.com");
        Project project = createProject(15L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(21L, project, relativePath, "JAVA");
        sourceFile.setAnalysisHash(hashContent(content));

        AnalyzedFunction analyzedFunction = new AnalyzedFunction();
        analyzedFunction.setId(101L);
        analyzedFunction.setSourceFile(sourceFile);
        analyzedFunction.setFunctionName("run");
        analyzedFunction.setSignature("void run()");
        analyzedFunction.setStartLine(2);
        analyzedFunction.setEndLine(4);
        analyzedFunction.setCyclomaticComplexity(1);

        FlowGraphData flowGraphData = new FlowGraphData();
        flowGraphData.setAnalyzedFunction(analyzedFunction);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(15L, 6L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(15L, relativePath)).thenReturn(Optional.of(sourceFile));
        when(analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(21L)).thenReturn(List.of(analyzedFunction));
        when(flowGraphDataRepository.findByAnalyzedFunction_IdIn(List.of(101L))).thenReturn(List.of(flowGraphData));

        JavaFileAnalysisResponse response = codeAnalysisService.getFunctionSummaries(15L, relativePath, "user@test.com");

        assertTrue(response.isCached());
        assertEquals(1, response.getFunctions().size());
        assertEquals("run", response.getFunctions().get(0).getFunctionName());
        verify(analyzedFunctionRepository, never()).saveAll(anyList());
    }

    @Test
    void getFunctionCfg_ShouldReturnStoredGraph_WhenCacheFresh() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-18"));
        String relativePath = "src/Sample.java";
        String content = """
                class Sample {
                    void run() {
                        System.out.println("ok");
                    }
                }
                """;
        writeJavaFile(workspaceRoot, relativePath, content);

        User user = createUser(7L, "user@test.com");
        Project project = createProject(18L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(22L, project, relativePath, "JAVA");
        sourceFile.setAnalysisHash(hashContent(content));

        AnalyzedFunction analyzedFunction = new AnalyzedFunction();
        analyzedFunction.setId(102L);
        analyzedFunction.setSourceFile(sourceFile);
        analyzedFunction.setFunctionName("run");
        analyzedFunction.setSignature("void run()");
        analyzedFunction.setStartLine(2);
        analyzedFunction.setEndLine(4);
        analyzedFunction.setCyclomaticComplexity(1);

        FlowGraphData flowGraphData = new FlowGraphData();
        flowGraphData.setAnalyzedFunction(analyzedFunction);
        flowGraphData.setEntryNodeId("n1");
        flowGraphData.setExitNodeIdsJson(objectMapper.writeValueAsString(List.of("n2")));
        flowGraphData.setNodesJson(objectMapper.writeValueAsString(List.of(
                new GraphNodeDraft("n1", "ENTRY", "run", 2, 2),
                new GraphNodeDraft("n2", "EXIT", "exit", 4, 4))));
        flowGraphData.setEdgesJson(objectMapper.writeValueAsString(List.of(
                new GraphEdgeDraft("e1", "n1", "n2", null))));

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(18L, 7L)).thenReturn(Optional.of(project));
        when(analyzedFunctionRepository.findByIdAndSourceFile_Project_Id(102L, 18L)).thenReturn(Optional.of(analyzedFunction));
        when(flowGraphDataRepository.findByAnalyzedFunction_Id(102L)).thenReturn(Optional.of(flowGraphData));

        FunctionCfgResponse response = codeAnalysisService.getFunctionCfg(18L, 102L, "user@test.com");

        assertEquals(102L, response.getFunctionId());
        assertEquals("n1", response.getEntryNodeId());
        assertEquals(2, response.getNodes().size());
        assertEquals(1, response.getEdges().size());
    }

    @Test
    void getFunctionSummaries_ShouldThrow_WhenCacheIsStale() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-19"));
        String relativePath = "src/Sample.java";
        writeJavaFile(workspaceRoot, relativePath, "class Sample { void run() {} }");

        User user = createUser(8L, "user@test.com");
        Project project = createProject(19L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(23L, project, relativePath, "JAVA");
        sourceFile.setAnalysisHash("outdated-hash");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(19L, 8L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(19L, relativePath)).thenReturn(Optional.of(sourceFile));

        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> codeAnalysisService.getFunctionSummaries(19L, relativePath, "user@test.com"));

        assertEquals("Analysis cache is stale. Re-run analysis.", exception.getMessage());
    }

    @Test
    void getFunctionSummaries_ShouldThrow_WhenCacheUsesLegacyAnalyzerHash() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-20"));
        String relativePath = "src/Sample.java";
        String content = """
                class Sample {
                    int average(int[] value, int minimum, int maximum) {
                        return 0;
                    }
                }
                """;
        writeJavaFile(workspaceRoot, relativePath, content);

        User user = createUser(9L, "user@test.com");
        Project project = createProject(20L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(24L, project, relativePath, "JAVA");
        sourceFile.setAnalysisHash(legacyHashContent(content));

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(20L, 9L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(20L, relativePath)).thenReturn(Optional.of(sourceFile));

        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> codeAnalysisService.getFunctionSummaries(20L, relativePath, "user@test.com"));

        assertEquals("Analysis cache is stale. Re-run analysis.", exception.getMessage());
    }

    private User createUser(Long id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private Project createProject(Long id, User user, Path workspaceRoot) {
        Project project = new Project();
        project.setId(id);
        project.setUser(user);
        project.setName("workspace");
        project.setStoragePath(workspaceRoot.toString());
        return project;
    }

    private SourceFile createSourceFile(Long id, Project project, String filePath, String language) {
        SourceFile sourceFile = new SourceFile();
        sourceFile.setId(id);
        sourceFile.setProject(project);
        sourceFile.setFilePath(filePath);
        sourceFile.setLanguage(language);
        return sourceFile;
    }

    private void writeJavaFile(Path workspaceRoot, String relativePath, String content) throws IOException {
        Path targetFile = workspaceRoot.resolve(relativePath);
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, content, StandardCharsets.UTF_8);
    }

    private String hashContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ANALYSIS_CACHE_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String legacyHashContent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
