package com.sam.besameditor.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sam.besameditor.coverage.CoverageLineStat;
import com.sam.besameditor.coverage.CoverageSandboxRunner;
import com.sam.besameditor.coverage.CoverageSandboxRunnerRegistry;
import com.sam.besameditor.coverage.CoverageReportParser;
import com.sam.besameditor.coverage.JaCoCoXmlParser;
import com.sam.besameditor.coverage.SandboxCoverageExecutionResult;
import com.sam.besameditor.dto.AnalysisGraphEdgeResponse;
import com.sam.besameditor.dto.AnalysisGraphNodeResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoverageServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SourceFileRepository sourceFileRepository;
    @Mock
    private AnalyzedFunctionRepository analyzedFunctionRepository;
    @Mock
    private CoverageRunRepository coverageRunRepository;
    @Mock
    private CodeAnalysisService codeAnalysisService;
    @Mock
    private CoverageSandboxRunnerRegistry runnerRegistry;
    @Mock
    private CoverageSandboxRunner coverageSandboxRunner;
    @Mock
    private CoverageReportParser coverageReportParser;

    private CoverageService coverageService;
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        coverageService = new CoverageService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                coverageRunRepository,
                codeAnalysisService,
                runnerRegistry,
                objectMapper);
    }

    @Test
    void runCoverage_ShouldPersistSuccessfulRunAndReturnFunctionCoverage_ForJava() throws Exception {
        User user = createUser(1L, "user@test.com");
        Project project = createProject(10L, user, tempDir.resolve("workspace"));
        Files.createDirectories(Path.of(project.getStoragePath()));
        Files.writeString(Path.of(project.getStoragePath()).resolve("pom.xml"), "<project/>");
        SourceFile sourceFile = createSourceFile(20L, project, "src/main/java/com/example/App.java", "JAVA", "hash-123");
        AnalyzedFunction function = createFunction(101L, sourceFile, "run", "void run()", 10, 12, 2);

        Path report = tempDir.resolve("jacoco.xml");
        Files.writeString(report, """
                <report name="demo">
                  <package name="com/example">
                    <sourcefile name="App.java">
                      <line nr="10" mi="0" ci="1" mb="0" cb="0"/>
                      <line nr="11" mi="1" ci="0" mb="0" cb="0"/>
                    </sourcefile>
                  </package>
                </report>
                """);

        when(runnerRegistry.isSupported("JAVA")).thenReturn(true);
        when(runnerRegistry.getRunner("JAVA")).thenReturn(coverageSandboxRunner);
        when(runnerRegistry.getParser("JAVA")).thenReturn(coverageReportParser);
        when(codeAnalysisService.analyzeJavaFile(10L, "src/main/java/com/example/App.java", "user@test.com"))
                .thenReturn(null);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(10L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(10L, "src/main/java/com/example/App.java"))
                .thenReturn(Optional.of(sourceFile));
        when(analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(20L)).thenReturn(List.of(function));
        when(coverageSandboxRunner.run(any(Path.class), eq("src/main/java/com/example/App.java")))
                .thenReturn(new SandboxCoverageExecutionResult(
                        CoverageRunStatus.SUCCEEDED,
                        0,
                        "./mvnw test",
                        "ok",
                        "",
                        report));
        when(coverageReportParser.parse(report)).thenReturn(Map.of(
                "com/example/App.java", List.of(
                        new CoverageLineStat(10, 0, 1, 0, 0),
                        new CoverageLineStat(11, 1, 0, 0, 0))));
        when(coverageRunRepository.save(any(CoverageRun.class))).thenAnswer(invocation -> {
            CoverageRun coverageRun = invocation.getArgument(0);
            coverageRun.setId(501L);
            return coverageRun;
        });

        JavaFileCoverageResponse response = coverageService.runCoverage(10L, "src/main/java/com/example/App.java", "user@test.com");

        assertEquals(501L, response.getCoverageRunId());
        assertEquals("SUCCEEDED", response.getStatus());
        assertEquals(1, response.getFunctions().size());
        assertEquals("COVERED", response.getFunctions().get(0).getCoverageStatus());
        assertEquals(1, response.getFunctions().get(0).getCoveredLineCount());
        assertEquals(1, response.getFunctions().get(0).getMissedLineCount());
    }

    @Test
    void runCoverage_ShouldReturnEmptyFunctions_ForJavaScript() throws Exception {
        User user = createUser(1L, "user@test.com");
        Project project = createProject(10L, user, tempDir.resolve("workspace"));
        Files.createDirectories(Path.of(project.getStoragePath()));
        Files.writeString(Path.of(project.getStoragePath()).resolve("package.json"), "{}");
        SourceFile sourceFile = createSourceFile(20L, project, "src/utils/helper.js", "JAVASCRIPT", null);

        Path report = tempDir.resolve("lcov.info");
        Files.writeString(report, """
                SF:src/utils/helper.js
                DA:1,5
                DA:2,0
                end_of_record
                """);

        when(runnerRegistry.isSupported("JAVASCRIPT")).thenReturn(true);
        when(runnerRegistry.getRunner("JAVASCRIPT")).thenReturn(coverageSandboxRunner);
        when(runnerRegistry.getParser("JAVASCRIPT")).thenReturn(coverageReportParser);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(10L, 1L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdAndFilePath(10L, "src/utils/helper.js"))
                .thenReturn(Optional.of(sourceFile));
        when(coverageSandboxRunner.run(any(Path.class), eq("src/utils/helper.js")))
                .thenReturn(new SandboxCoverageExecutionResult(
                        CoverageRunStatus.SUCCEEDED,
                        0,
                        "npx jest --coverage",
                        "ok",
                        "",
                        report));
        when(coverageReportParser.parse(report)).thenReturn(Map.of(
                "src/utils/helper.js", List.of(
                        new CoverageLineStat(1, 0, 1, 0, 0),
                        new CoverageLineStat(2, 1, 0, 0, 0))));
        when(coverageRunRepository.save(any(CoverageRun.class))).thenAnswer(invocation -> {
            CoverageRun coverageRun = invocation.getArgument(0);
            coverageRun.setId(502L);
            return coverageRun;
        });

        JavaFileCoverageResponse response = coverageService.runCoverage(10L, "src/utils/helper.js", "user@test.com");

        assertEquals(502L, response.getCoverageRunId());
        assertEquals("SUCCEEDED", response.getStatus());
        assertEquals(true, response.isOverlayAvailable());
        assertEquals(0, response.getFunctions().size());
    }

    @Test
    void getFunctionCfgWithCoverage_ShouldOverlayNodeCoverage() throws Exception {
        SourceFile sourceFile = createSourceFile(20L, new Project(), "src/main/java/com/example/App.java", "JAVA", "hash-123");
        AnalyzedFunction function = createFunction(101L, sourceFile, "run", "void run()", 10, 12, 2);
        CoverageRun coverageRun = new CoverageRun();
        coverageRun.setId(501L);
        coverageRun.setProjectId(10L);
        coverageRun.setSourceFilePath("src/main/java/com/example/App.java");
        coverageRun.setSourceHash("hash-123");
        coverageRun.setStatus(CoverageRunStatus.SUCCEEDED);
        coverageRun.setLineCoverageJson(objectMapper.writeValueAsString(List.of(
                new CoverageLineStat(10, 0, 1, 0, 0),
                new CoverageLineStat(11, 1, 0, 1, 0))));

        FunctionCfgResponse baseResponse = new FunctionCfgResponse(
                101L,
                "run",
                "void run()",
                10,
                12,
                2,
                "n1",
                List.of("n3"),
                List.of(
                        new AnalysisGraphNodeResponse("n1", "ENTRY", "run", 10, 10),
                        new AnalysisGraphNodeResponse("n2", "STATEMENT", "if", 10, 11)),
                List.of(new AnalysisGraphEdgeResponse("e1", "n1", "n2", null)));

        when(codeAnalysisService.getFunctionCfg(10L, 101L, "user@test.com")).thenReturn(baseResponse);
        when(analyzedFunctionRepository.findByIdAndSourceFile_Project_Id(101L, 10L)).thenReturn(Optional.of(function));
        when(coverageRunRepository.findByIdAndProjectId(501L, 10L)).thenReturn(Optional.of(coverageRun));

        FunctionCfgResponse response = coverageService.getFunctionCfgWithCoverage(10L, 101L, 501L, "user@test.com");

        assertEquals(501L, response.getCoverageRunId());
        assertEquals("COVERED", response.getCoverageStatus());
        assertEquals("NEUTRAL", response.getNodes().get(0).getCoverageStatus());
        assertEquals("COVERED", response.getNodes().get(1).getCoverageStatus());
        assertEquals(1, response.getNodes().get(1).getCoveredLineCount());
        assertEquals(1, response.getNodes().get(1).getMissedLineCount());
    }

    @Test
    void getFunctionCfgWithCoverage_ShouldThrow_WhenCoverageIsStale() throws Exception {
        SourceFile sourceFile = createSourceFile(20L, new Project(), "src/main/java/com/example/App.java", "JAVA", "fresh-hash");
        AnalyzedFunction function = createFunction(101L, sourceFile, "run", "void run()", 10, 12, 2);
        CoverageRun coverageRun = new CoverageRun();
        coverageRun.setId(501L);
        coverageRun.setProjectId(10L);
        coverageRun.setSourceFilePath("src/main/java/com/example/App.java");
        coverageRun.setSourceHash("old-hash");
        coverageRun.setStatus(CoverageRunStatus.SUCCEEDED);
        coverageRun.setLineCoverageJson(objectMapper.writeValueAsString(List.of()));

        when(codeAnalysisService.getFunctionCfg(10L, 101L, "user@test.com")).thenReturn(new FunctionCfgResponse(
                101L, "run", "void run()", 10, 12, 2, "n1", List.of("n2"), List.of(), List.of()));
        when(analyzedFunctionRepository.findByIdAndSourceFile_Project_Id(101L, 10L)).thenReturn(Optional.of(function));
        when(coverageRunRepository.findByIdAndProjectId(501L, 10L)).thenReturn(Optional.of(coverageRun));

        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> coverageService.getFunctionCfgWithCoverage(10L, 101L, 501L, "user@test.com"));

        assertEquals("Coverage cache is stale. Re-run coverage.", exception.getMessage());
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

    private SourceFile createSourceFile(Long id, Project project, String path, String language, String analysisHash) {
        SourceFile sourceFile = new SourceFile();
        sourceFile.setId(id);
        sourceFile.setProject(project);
        sourceFile.setFilePath(path);
        sourceFile.setLanguage(language);
        sourceFile.setAnalysisHash(analysisHash);
        return sourceFile;
    }

    private AnalyzedFunction createFunction(Long id, SourceFile sourceFile, String name, String signature, int startLine, int endLine, int cc) {
        AnalyzedFunction function = new AnalyzedFunction();
        function.setId(id);
        function.setSourceFile(sourceFile);
        function.setFunctionName(name);
        function.setSignature(signature);
        function.setStartLine(startLine);
        function.setEndLine(endLine);
        function.setCyclomaticComplexity(cc);
        return function;
    }
}
