package com.sam.besameditor.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.besameditor.analysis.JavaSourceAnalyzer;
import com.sam.besameditor.analysis.JsSourceAnalyzer;
import com.sam.besameditor.analysis.JsSourceAnalyzer;
import com.sam.besameditor.dto.JavaFileAnalysisResponse;
import com.sam.besameditor.exceptions.NotFoundException;
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
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeAnalysisServicePrivateCoverageTest {

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
                new JsSourceAnalyzer(),
                new ObjectMapper(),
                32L
        );
    }

    @Test
    void publicMethods_ShouldCoverCachedEmptyAndMissingEntityBranches() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"));
        Path targetFile = workspaceRoot.resolve("src/Empty.java");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "class Empty {}", StandardCharsets.UTF_8);

        User user = createUser(1L, "user@test.com");
        Project project = createProject(10L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(20L, project, "src/Empty.java", "JAVA");
        sourceFile.setAnalysisHash((String) invoke(codeAnalysisService, "hashContent", new Class[]{String.class}, "class Empty {}"));

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(10L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(10L, "src/Empty.java")).thenReturn(Optional.of(sourceFile));
        when(analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(20L)).thenReturn(List.of());

        JavaFileAnalysisResponse analyzeResponse = codeAnalysisService.analyzeJavaFile(10L, "src/Empty.java", "user@test.com");
        assertTrue(analyzeResponse.isCached());
        assertTrue(analyzeResponse.getFunctions().isEmpty());

        JavaFileAnalysisResponse summariesResponse = codeAnalysisService.getFunctionSummaries(10L, "src/Empty.java", "user@test.com");
        assertTrue(summariesResponse.isCached());
        assertTrue(summariesResponse.getFunctions().isEmpty());
        verifyNoInteractions(flowGraphDataRepository);

        when(userRepository.findByEmail("missing@test.com")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> codeAnalysisService.analyzeJavaFile(10L, "src/Empty.java", "missing@test.com"));

        when(projectRepository.findByIdAndUser_Id(999L, 1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> codeAnalysisService.analyzeJavaFile(999L, "src/Empty.java", "user@test.com"));

        when(projectRepository.findByIdAndUser_Id(11L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(11L, "src/Missing.java")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> codeAnalysisService.analyzeJavaFile(11L, "src/Missing.java", "user@test.com"));

        when(projectRepository.findByIdAndUser_Id(12L, 1L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> codeAnalysisService.getFunctionCfg(12L, 99L, "user@test.com"));
    }

    @Test
    void privateHelpers_ShouldCoverWorkspaceValidationAndReadFailures() throws IOException {
        Project blankStorage = new Project();
        assertThrows(NotFoundException.class,
                () -> invoke(codeAnalysisService, "resolveWorkspaceRoot", new Class[]{Project.class}, blankStorage));

        Project invalidStorage = new Project();
        invalidStorage.setStoragePath("\0");
        assertThrows(IllegalArgumentException.class,
                () -> invoke(codeAnalysisService, "resolveWorkspaceRoot", new Class[]{Project.class}, invalidStorage));

        Project missingStorage = new Project();
        missingStorage.setStoragePath(tempDir.resolve("missing").toString());
        assertThrows(NotFoundException.class,
                () -> invoke(codeAnalysisService, "resolveWorkspaceRoot", new Class[]{Project.class}, missingStorage));

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));
        Project validProject = createProject(30L, createUser(1L, "user@test.com"), workspaceRoot);
        assertEquals(workspaceRoot.toAbsolutePath().normalize(),
                invoke(codeAnalysisService, "resolveWorkspaceRoot", new Class[]{Project.class}, validProject));

        assertThrows(IllegalArgumentException.class,
                () -> invoke(codeAnalysisService, "normalizePath", new Class[]{String.class}, new Object[]{null}));
        assertThrows(IllegalArgumentException.class,
                () -> invoke(codeAnalysisService, "normalizePath", new Class[]{String.class}, "/etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> invoke(codeAnalysisService, "normalizePath", new Class[]{String.class}, "../secret"));
        assertEquals("src/App.java",
                invoke(codeAnalysisService, "normalizePath", new Class[]{String.class}, "src//App.java"));

        Path targetFile = workspaceRoot.resolve("src/Sample.java");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "class Sample {}", StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class,
                () -> invoke(codeAnalysisService, "readSourceContent", new Class[]{Project.class, String.class}, validProject, "../secret.java"));
        assertThrows(NotFoundException.class,
                () -> invoke(codeAnalysisService, "readSourceContent", new Class[]{Project.class, String.class}, validProject, "src/Missing.java"));

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readAllBytes(targetFile)).thenThrow(new IOException("boom"));

            IllegalArgumentException readFailure = assertThrows(
                    IllegalArgumentException.class,
                    () -> invoke(codeAnalysisService, "readSourceContent", new Class[]{Project.class, String.class}, validProject, "src/Sample.java")
            );

            assertEquals("Unable to read source file for analysis", readFailure.getMessage());
        }
    }

    @Test
    void privateHelpers_ShouldCoverSerializationAndHashFailureBranches() {
        ObjectMapper mapper = mock(ObjectMapper.class);
        CodeAnalysisService failingService = new CodeAnalysisService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                flowGraphDataRepository,
                new JavaSourceAnalyzer(),
                new JsSourceAnalyzer(),
                mapper,
                32L
        );

        JsonProcessingException jsonFailure = new JsonProcessingException("boom") {
        };
        try {
            when(mapper.writeValueAsString(any())).thenThrow(jsonFailure);
            doThrow(jsonFailure).when(mapper).readValue(eq("bad"), any(TypeReference.class));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }

        IllegalStateException writeFailure = assertThrows(
                IllegalStateException.class,
                () -> invoke(failingService, "writeJson", new Class[]{Object.class}, List.of("x"))
        );
        assertEquals("Unable to serialize analysis graph data", writeFailure.getMessage());

        IllegalStateException nodeFailure = assertThrows(
                IllegalStateException.class,
                () -> invoke(failingService, "deserializeNodes", new Class[]{String.class}, "bad")
        );
        assertEquals("Unable to deserialize graph nodes", nodeFailure.getMessage());

        IllegalStateException edgeFailure = assertThrows(
                IllegalStateException.class,
                () -> invoke(failingService, "deserializeEdges", new Class[]{String.class}, "bad")
        );
        assertEquals("Unable to deserialize graph edges", edgeFailure.getMessage());

        IllegalStateException exitFailure = assertThrows(
                IllegalStateException.class,
                () -> invoke(failingService, "deserializeExitNodeIds", new Class[]{String.class}, "bad")
        );
        assertEquals("Unable to deserialize exit node ids", exitFailure.getMessage());

        try (MockedStatic<MessageDigest> messageDigest = mockStatic(MessageDigest.class, CALLS_REAL_METHODS)) {
            messageDigest.when(() -> MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new NoSuchAlgorithmException("missing"));

            IllegalStateException hashFailure = assertThrows(
                    IllegalStateException.class,
                    () -> invoke(codeAnalysisService, "hashContent", new Class[]{String.class}, "content")
            );

            assertEquals("SHA-256 is not available", hashFailure.getMessage());
        }
    }

    @Test
    void publicMethods_ShouldCoverStaleAndCacheCompletenessBranches() throws IOException {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("stale-workspace"));
        Path targetFile = workspaceRoot.resolve("src/Sample.java");
        Files.createDirectories(targetFile.getParent());
        Files.writeString(targetFile, "class Sample {}", StandardCharsets.UTF_8);

        User user = createUser(2L, "user2@test.com");
        Project project = createProject(40L, user, workspaceRoot);
        SourceFile sourceFile = createSourceFile(41L, project, "src/Sample.java", "JAVA");
        sourceFile.setAnalysisHash("stale");

        when(userRepository.findByEmail("user2@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(40L, 2L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(40L, "src/Sample.java")).thenReturn(Optional.of(sourceFile));

        NotFoundException staleException = assertThrows(
                NotFoundException.class,
                () -> codeAnalysisService.getFunctionSummaries(40L, "src/Sample.java", "user2@test.com")
        );
        assertEquals("Analysis cache is stale. Re-run analysis.", staleException.getMessage());

        @SuppressWarnings("unchecked")
        Boolean complete = (Boolean) invoke(codeAnalysisService, "hasCompleteGraphCache", new Class[]{List.class}, List.of());
        assertTrue(complete);
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

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
