package com.sam.besameditor.services;

import com.sam.besameditor.dto.ImportGithubWorkspaceResponse;
import com.sam.besameditor.dto.WorkspaceFileContentResponse;
import com.sam.besameditor.dto.WorkspaceSummaryResponse;
import com.sam.besameditor.exceptions.NotFoundException;
import com.sam.besameditor.exceptions.WorkspacePayloadTooLargeException;
import com.sam.besameditor.models.Project;
import com.sam.besameditor.models.ProjectSourceType;
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
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceCoverageTest {

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
    private GithubRepositoryTreeClient githubRepositoryTreeClient;
    @Mock
    private WorkspaceSourceStorageService workspaceSourceStorageService;
    @Mock
    private CloudinaryWorkspaceStorageService cloudinaryWorkspaceStorageService;

    @TempDir
    Path tempDir;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                flowGraphDataRepository,
                githubRepositoryTreeClient,
                workspaceSourceStorageService,
                cloudinaryWorkspaceStorageService,
                15_728_640L,
                1_048_576L,
                ".git,node_modules,target,dist,build,.idea,.vscode"
        );
    }

    @Test
    void importFromLocalFolder_ShouldPersistFilesAndDeriveWorkspaceName() throws IOException {
        Path sourceFolder = Files.createDirectories(tempDir.resolve("local-workspace"));
        Files.createDirectories(sourceFolder.resolve("src"));
        Files.createDirectories(sourceFolder.resolve("node_modules/lib"));
        Files.writeString(sourceFolder.resolve("README.md"), "# Demo", StandardCharsets.UTF_8);
        Files.writeString(sourceFolder.resolve("src/App.java"), "class App {}", StandardCharsets.UTF_8);
        Files.writeString(sourceFolder.resolve("node_modules/lib/index.js"), "module.exports = {}", StandardCharsets.UTF_8);

        User user = createUser(1L, "user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L);
            return project;
        });
        when(workspaceSourceStorageService.copyLocalFolder(1L, 100L, sourceFolder))
                .thenReturn(tempDir.resolve("copied").toString());

        ImportGithubWorkspaceResponse response =
                workspaceService.importFromLocalFolder(sourceFolder.toString(), "   ", "user@test.com");

        assertEquals(100L, response.getProjectId());
        assertEquals("local-workspace", response.getName());
        assertEquals(2, response.getTotalFiles());
        assertTrue(response.getTotalSizeBytes() > 0);

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, atLeastOnce()).save(projectCaptor.capture());
        assertTrue(projectCaptor.getAllValues().stream().anyMatch(project ->
                project.getSourceType() == ProjectSourceType.LOCAL_FOLDER
                        && project.getSourceUrl().startsWith("file:")));

        ArgumentCaptor<List<SourceFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(sourceFileRepository).saveAll(filesCaptor.capture());
        List<SourceFile> savedFiles = filesCaptor.getValue();
        assertEquals(2, savedFiles.size());
        assertTrue(savedFiles.stream().anyMatch(file -> "README.md".equals(file.getFilePath())));
        assertTrue(savedFiles.stream().anyMatch(file -> "src/App.java".equals(file.getFilePath())));
    }

    @Test
    void importFromLocalFolder_ShouldThrow_WhenPathPointsToFile() throws IOException {
        Path sourceFile = tempDir.resolve("notes.txt");
        Files.writeString(sourceFile, "demo", StandardCharsets.UTF_8);

        User user = createUser(1L, "user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> workspaceService.importFromLocalFolder(sourceFile.toString(), "workspace", "user@test.com")
        );

        assertEquals("folderPath must point to a directory", exception.getMessage());
    }

    @Test
    void importFromLocalFolder_ShouldThrow_WhenFolderExceedsSizeLimit() throws IOException {
        workspaceService = new WorkspaceService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                flowGraphDataRepository,
                githubRepositoryTreeClient,
                workspaceSourceStorageService,
                cloudinaryWorkspaceStorageService,
                4L,
                1_048_576L,
                ".git,node_modules,target,dist,build,.idea,.vscode"
        );
        Path sourceFolder = Files.createDirectories(tempDir.resolve("oversized"));
        Files.writeString(sourceFolder.resolve("README.md"), "12345", StandardCharsets.UTF_8);

        User user = createUser(1L, "user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        assertThrows(
                WorkspacePayloadTooLargeException.class,
                () -> workspaceService.importFromLocalFolder(sourceFolder.toString(), "workspace", "user@test.com")
        );
    }

    @Test
    void importFromZip_ShouldUseFilenameAsWorkspaceName_WhenBlank() throws IOException {
        MockMultipartFile zipFile = createZipFile("sample-workspace.zip", List.of(
                new ZipEntryData("src/App.java", "class App {}")
        ));
        User user = createUser(1L, "user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L);
            return project;
        });
        when(workspaceSourceStorageService.extractZipArchive(1L, 100L, zipFile))
                .thenReturn(tempDir.resolve("unzipped").toString());

        ImportGithubWorkspaceResponse response =
                workspaceService.importFromZip(zipFile, "   ", "user@test.com");

        assertEquals("sample-workspace", response.getName());
        assertEquals(1, response.getTotalFiles());
    }

    @Test
    void importFromZip_ShouldThrow_WhenFileMissing() {
        User user = createUser(1L, "user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> workspaceService.importFromZip(
                        new MockMultipartFile("file", "workspace.zip", "application/zip", new byte[0]),
                        "workspace",
                        "user@test.com")
        );

        assertEquals("file is required", exception.getMessage());
    }

    @Test
    void importFromZip_ShouldThrow_WhenFileExtensionIsNotZip() {
        User user = createUser(1L, "user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> workspaceService.importFromZip(
                        new MockMultipartFile("file", "workspace.txt", "text/plain", "demo".getBytes(StandardCharsets.UTF_8)),
                        "workspace",
                        "user@test.com")
        );

        assertEquals("Only .zip files are supported", exception.getMessage());
    }

    @Test
    void importFromZip_ShouldThrow_WhenArchiveIsUnreadable() {
        User user = createUser(1L, "user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        MockMultipartFile zipFile = new MockMultipartFile(
                "file",
                "broken.zip",
                "application/zip",
                "not a zip".getBytes(StandardCharsets.UTF_8)
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> workspaceService.importFromZip(zipFile, "workspace", "user@test.com")
        );

        assertTrue(exception.getMessage().contains("Unable to read uploaded ZIP file"));
    }

    @Test
    void getUserWorkspaces_ShouldReturnMappedSummaries() {
        User user = createUser(7L, "user@test.com");
        Project first = new Project();
        first.setId(11L);
        first.setUser(user);
        first.setName("repo-a");
        first.setSourceType(ProjectSourceType.GITHUB);
        first.setSourceUrl("https://github.com/openai/a");
        first.setCreatedAt(LocalDateTime.of(2026, 4, 10, 9, 0));
        first.setUpdatedAt(LocalDateTime.of(2026, 4, 10, 10, 0));

        Project second = new Project();
        second.setId(12L);
        second.setUser(user);
        second.setName("repo-b");
        second.setSourceType(ProjectSourceType.LOCAL_FOLDER);
        second.setSourceUrl("file:///tmp/repo-b");
        second.setCreatedAt(LocalDateTime.of(2026, 4, 9, 9, 0));
        second.setUpdatedAt(LocalDateTime.of(2026, 4, 9, 10, 0));

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByUser_IdOrderByUpdatedAtDesc(7L)).thenReturn(List.of(first, second));

        List<WorkspaceSummaryResponse> response = workspaceService.getUserWorkspaces("user@test.com");

        assertEquals(2, response.size());
        assertEquals(11L, response.get(0).getProjectId());
        assertEquals("repo-a", response.get(0).getName());
        assertEquals(ProjectSourceType.GITHUB, response.get(0).getSourceType());
        assertEquals("file:///tmp/repo-b", response.get(1).getSourceUrl());
    }

    @Test
    void getWorkspaceFileContent_ShouldThrow_WhenStorageMissing() {
        User user = createUser(7L, "user@test.com");
        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(null);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        NotFoundException exception = assertThrows(
                NotFoundException.class,
                () -> workspaceService.getWorkspaceFileContent(22L, "src/App.java", "user@test.com")
        );

        assertEquals("Workspace source not found on server", exception.getMessage());
    }

    @Test
    void getWorkspaceFileContent_ShouldThrow_WhenStoragePathInvalid() {
        User user = createUser(7L, "user@test.com");
        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath("\0");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        assertThrows(
                IllegalArgumentException.class,
                () -> workspaceService.getWorkspaceFileContent(22L, "src/App.java", "user@test.com")
        );
    }

    @Test
    void getWorkspaceFileContent_ShouldThrow_WhenUtf8DecodingFails() throws IOException {
        User user = createUser(7L, "user@test.com");
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-utf8"));
        Files.write(workspaceRoot.resolve("notes.txt"), new byte[]{(byte) 0xC3, (byte) 0x28});

        Project project = createProject(22L, user, workspaceRoot);
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> workspaceService.getWorkspaceFileContent(22L, "notes.txt", "user@test.com")
        );

        assertEquals("Only UTF-8 text files are supported", exception.getMessage());
    }

    @Test
    void getWorkspaceFileContent_ShouldThrow_WhenPathLooksLikeWindowsAbsolutePath() throws IOException {
        User user = createUser(7L, "user@test.com");
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-win"));
        Project project = createProject(22L, user, workspaceRoot);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> workspaceService.getWorkspaceFileContent(22L, "C:/secret.txt", "user@test.com")
        );

        assertEquals("Invalid file path", exception.getMessage());
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

    private MockMultipartFile createZipFile(String filename, List<ZipEntryData> entries) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
            for (ZipEntryData entryData : entries) {
                ZipEntry entry = new ZipEntry(entryData.path());
                zipOutputStream.putNextEntry(entry);
                zipOutputStream.write(entryData.content().getBytes(StandardCharsets.UTF_8));
                zipOutputStream.closeEntry();
            }
        }
        return new MockMultipartFile("file", filename, "application/zip", byteArrayOutputStream.toByteArray());
    }

    private record ZipEntryData(String path, String content) {
    }
}
