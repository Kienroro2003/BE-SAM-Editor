package com.sam.besameditor.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.besameditor.analysis.JavaSourceAnalyzer;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeAnalysisServiceCoverageTest {

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

    @TempDir
    Path tempDir;

    private CodeAnalysisService codeAnalysisService;

    @BeforeEach
    void setUp() {
        codeAnalysisService = new CodeAnalysisService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                flowGraphDataRepository,
                new JavaSourceAnalyzer(),
                new ObjectMapper(),
                1_048_576L
        );
    }

    @Test
    void analyzeJavaFile_ShouldReturnCachedResult_WhenHashMatchesAndGraphCacheComplete() throws IOException {
        String relativePath = "src/Sample.java";
        String content = """
                class Sample {
                    void run() {
                    }
                }
                """;
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-cached"));
        writeJavaFile(workspaceRoot, relativePath, content);

        User user = createUser(1L, "user@test.com");
        Project project = createProject(11L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(21L, project, relativePath, "JAVA");
        sourceFile.setAnalysisHash(hashContent(content));

        AnalyzedFunction function = createFunction(101L, sourceFile, "run", "void run()");
        FlowGraphData graph = new FlowGraphData();
        graph.setAnalyzedFunction(function);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(11L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(11L, relativePath)).thenReturn(Optional.of(sourceFile));
        when(analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(21L)).thenReturn(List.of(function));
        when(flowGraphDataRepository.findByAnalyzedFunction_IdIn(List.of(101L))).thenReturn(List.of(graph));

        JavaFileAnalysisResponse response = codeAnalysisService.analyzeJavaFile(11L, relativePath, "user@test.com");

        assertTrue(response.isCached());
        assertEquals(1, response.getFunctions().size());
        verify(analyzedFunctionRepository, never()).saveAll(any());
    }

    @Test
    void analyzeJavaFile_ShouldRebuild_WhenGraphCacheIncomplete() throws IOException {
        String relativePath = "src/Sample.java";
        String content = """
                class Sample {
                    int sum(int a, int b) {
                        return a + b;
                    }
                }
                """;
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-rebuild"));
        writeJavaFile(workspaceRoot, relativePath, content);

        User user = createUser(1L, "user@test.com");
        Project project = createProject(12L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(22L, project, relativePath, "JAVA");
        sourceFile.setAnalysisHash(hashContent(content));

        AnalyzedFunction cachedFunction = createFunction(102L, sourceFile, "sum", "int sum(int a, int b)");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(12L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(12L, relativePath)).thenReturn(Optional.of(sourceFile));
        when(analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(22L)).thenReturn(List.of(cachedFunction), List.of(cachedFunction));
        when(flowGraphDataRepository.findByAnalyzedFunction_IdIn(List.of(102L))).thenReturn(List.of());
        when(sourceFileRepository.save(any(SourceFile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(analyzedFunctionRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        JavaFileAnalysisResponse response = codeAnalysisService.analyzeJavaFile(12L, relativePath, "user@test.com");

        assertFalse(response.isCached());
        verify(analyzedFunctionRepository).saveAll(anyList());
        verify(flowGraphDataRepository).save(any(FlowGraphData.class));
    }

    @Test
    void getFunctionSummaries_ShouldThrow_WhenGraphCacheIncomplete() throws IOException {
        String relativePath = "src/Sample.java";
        String content = "class Sample { void run() {} }";
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-incomplete"));
        writeJavaFile(workspaceRoot, relativePath, content);

        User user = createUser(1L, "user@test.com");
        Project project = createProject(13L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(23L, project, relativePath, "JAVA");
        sourceFile.setAnalysisHash(hashContent(content));
        AnalyzedFunction function = createFunction(103L, sourceFile, "run", "void run()");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(13L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(13L, relativePath)).thenReturn(Optional.of(sourceFile));
        when(analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(23L)).thenReturn(List.of(function));
        when(flowGraphDataRepository.findByAnalyzedFunction_IdIn(List.of(103L))).thenReturn(List.of());

        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> codeAnalysisService.getFunctionSummaries(13L, relativePath, "user@test.com")
        );

        assertEquals("Analysis graph cache is incomplete. Re-run analysis.", exception.getMessage());
    }

    @Test
    void getFunctionCfg_ShouldThrow_WhenFunctionMissing() {
        User user = createUser(1L, "user@test.com");
        Project project = new Project();
        project.setId(14L);
        project.setUser(user);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(14L, 1L)).thenReturn(Optional.of(project));
        when(analyzedFunctionRepository.findByIdAndSourceFile_Project_Id(999L, 14L)).thenReturn(Optional.empty());

        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> codeAnalysisService.getFunctionCfg(14L, 999L, "user@test.com")
        );

        assertEquals("Function analysis not found", exception.getMessage());
    }

    @Test
    void getFunctionCfg_ShouldThrow_WhenFlowGraphMissing() throws IOException {
        String relativePath = "src/Sample.java";
        String content = "class Sample { void run() {} }";
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-cfg-missing"));
        writeJavaFile(workspaceRoot, relativePath, content);

        User user = createUser(1L, "user@test.com");
        Project project = createProject(15L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(24L, project, relativePath, "JAVA");
        sourceFile.setAnalysisHash(hashContent(content));
        AnalyzedFunction function = createFunction(104L, sourceFile, "run", "void run()");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(15L, 1L)).thenReturn(Optional.of(project));
        when(analyzedFunctionRepository.findByIdAndSourceFile_Project_Id(104L, 15L)).thenReturn(Optional.of(function));
        when(flowGraphDataRepository.findByAnalyzedFunction_Id(104L)).thenReturn(Optional.empty());

        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> codeAnalysisService.getFunctionCfg(15L, 104L, "user@test.com")
        );

        assertEquals("Function CFG not found", exception.getMessage());
    }

    @Test
    void getFunctionCfg_ShouldThrow_WhenNoAnalysisHashExists() throws IOException {
        String relativePath = "src/Sample.java";
        String content = "class Sample { void run() {} }";
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-no-hash"));
        writeJavaFile(workspaceRoot, relativePath, content);

        User user = createUser(1L, "user@test.com");
        Project project = createProject(16L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(25L, project, relativePath, "JAVA");
        AnalyzedFunction function = createFunction(105L, sourceFile, "run", "void run()");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(16L, 1L)).thenReturn(Optional.of(project));
        when(analyzedFunctionRepository.findByIdAndSourceFile_Project_Id(105L, 16L)).thenReturn(Optional.of(function));

        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> codeAnalysisService.getFunctionCfg(16L, 105L, "user@test.com")
        );

        assertEquals("No analysis found for file", exception.getMessage());
    }

    @Test
    void analyzeJavaFile_ShouldThrow_WhenLanguageIsNotJava() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-lang"));
        writeJavaFile(workspaceRoot, "src/Sample.txt", "text");

        User user = createUser(1L, "user@test.com");
        Project project = createProject(17L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(26L, project, "src/Sample.txt", "TEXT");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(17L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(17L, "src/Sample.txt")).thenReturn(Optional.of(sourceFile));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> codeAnalysisService.analyzeJavaFile(17L, "src/Sample.txt", "user@test.com")
        );

        assertEquals("Only JAVA files are supported for analysis", exception.getMessage());
    }

    @Test
    void analyzeJavaFile_ShouldThrow_WhenPathTraversalRequested() {
        User user = createUser(1L, "user@test.com");
        Project project = new Project();
        project.setId(18L);
        project.setUser(user);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(18L, 1L)).thenReturn(Optional.of(project));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> codeAnalysisService.analyzeJavaFile(18L, "../etc/passwd", "user@test.com")
        );

        assertEquals("Invalid file path", exception.getMessage());
    }

    @Test
    void analyzeJavaFile_ShouldThrow_WhenFileTooLarge() throws IOException {
        codeAnalysisService = new CodeAnalysisService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                flowGraphDataRepository,
                new JavaSourceAnalyzer(),
                new ObjectMapper(),
                4L
        );
        String relativePath = "src/Sample.java";
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-large"));
        writeJavaFile(workspaceRoot, relativePath, "class Sample {}");

        User user = createUser(1L, "user@test.com");
        Project project = createProject(19L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(27L, project, relativePath, "JAVA");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(19L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(19L, relativePath)).thenReturn(Optional.of(sourceFile));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> codeAnalysisService.analyzeJavaFile(19L, relativePath, "user@test.com")
        );

        assertEquals("File is too large to analyze", exception.getMessage());
    }

    @Test
    void analyzeJavaFile_ShouldThrow_WhenFileIsNotUtf8() throws IOException {
        String relativePath = "src/Sample.java";
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-binary"));
        Path targetFile = workspaceRoot.resolve(relativePath);
        Files.createDirectories(targetFile.getParent());
        Files.write(targetFile, new byte[]{(byte) 0xC3, (byte) 0x28});

        User user = createUser(1L, "user@test.com");
        Project project = createProject(20L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(28L, project, relativePath, "JAVA");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(20L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(20L, relativePath)).thenReturn(Optional.of(sourceFile));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> codeAnalysisService.analyzeJavaFile(20L, relativePath, "user@test.com")
        );

        assertEquals("Only UTF-8 text files are supported for analysis", exception.getMessage());
    }

    @Test
    void analyzeJavaFile_ShouldPersistEmptyResult_WhenNoMethodsFound() throws IOException {
        String relativePath = "src/Empty.java";
        String content = "class Empty {}";
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-empty"));
        writeJavaFile(workspaceRoot, relativePath, content);

        User user = createUser(1L, "user@test.com");
        Project project = createProject(21L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(29L, project, relativePath, "JAVA");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(21L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(21L, relativePath)).thenReturn(Optional.of(sourceFile));
        when(analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(29L)).thenReturn(List.of());
        when(sourceFileRepository.save(any(SourceFile.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JavaFileAnalysisResponse response = codeAnalysisService.analyzeJavaFile(21L, relativePath, "user@test.com");

        assertFalse(response.isCached());
        assertTrue(response.getFunctions().isEmpty());
        verify(analyzedFunctionRepository, never()).saveAll(anyList());
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

    private AnalyzedFunction createFunction(Long id, SourceFile sourceFile, String name, String signature) {
        AnalyzedFunction function = new AnalyzedFunction();
        function.setId(id);
        function.setSourceFile(sourceFile);
        function.setFunctionName(name);
        function.setSignature(signature);
        function.setStartLine(2);
        function.setEndLine(4);
        function.setCyclomaticComplexity(1);
        return function;
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
}
