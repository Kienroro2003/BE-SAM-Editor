package com.sam.besameditor.services;

import com.sam.besameditor.dto.DeleteWorkspaceFolderResponse;
import com.sam.besameditor.dto.DeleteWorkspaceResponse;
import com.sam.besameditor.dto.ImportGithubWorkspaceResponse;
import com.sam.besameditor.dto.WorkspaceFileContentResponse;
import com.sam.besameditor.dto.WorkspaceTreeResponse;
import com.sam.besameditor.exceptions.NotFoundException;
import com.sam.besameditor.exceptions.WorkspacePayloadTooLargeException;
import com.sam.besameditor.models.CloudinaryDeliveryType;
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
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

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
    void importFromGithub_ShouldSaveProjectAndSourceFiles() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(githubRepositoryTreeClient.listDirectory("owner", "repo", ""))
                .thenReturn(List.of(
                        new GithubRepositoryTreeClient.GithubContentItem("README.md", "file", 10L),
                        new GithubRepositoryTreeClient.GithubContentItem("src", "dir", 0L)
                ));
        when(githubRepositoryTreeClient.listDirectory("owner", "repo", "src"))
                .thenReturn(List.of(
                        new GithubRepositoryTreeClient.GithubContentItem("src/App.java", "file", 20L)
                ));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L);
            return project;
        });
        when(workspaceSourceStorageService.cloneGithubRepository(1L, 100L, "https://github.com/owner/repo.git"))
                .thenReturn("/tmp/workspace-storage/user-1/project-100");
        when(cloudinaryWorkspaceStorageService.uploadWorkspaceArchive(
                1L,
                100L,
                Path.of("/tmp/workspace-storage/user-1/project-100")))
                .thenReturn(new CloudinaryWorkspaceStorageService.CloudinaryUploadResult(
                        "workspaces/user-1/project-100",
                        "https://res.cloudinary.com/demo/raw/upload/workspaces/user-1/project-100.zip"));

        ImportGithubWorkspaceResponse response =
                workspaceService.importFromGithub("https://github.com/owner/repo", "user@test.com");

        assertEquals(100L, response.getProjectId());
        assertEquals("repo", response.getName());
        assertEquals(2, response.getTotalFiles());
        assertEquals(30L, response.getTotalSizeBytes());
        assertEquals(
                "https://res.cloudinary.com/demo/raw/upload/workspaces/user-1/project-100.zip",
                response.getCloudinaryUrl());
        verify(workspaceSourceStorageService)
                .cloneGithubRepository(1L, 100L, "https://github.com/owner/repo.git");
        verify(cloudinaryWorkspaceStorageService)
                .uploadWorkspaceArchive(1L, 100L, Path.of("/tmp/workspace-storage/user-1/project-100"));

        ArgumentCaptor<List<SourceFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(sourceFileRepository).saveAll(filesCaptor.capture());
        List<SourceFile> savedFiles = filesCaptor.getValue();
        assertEquals(2, savedFiles.size());
        assertTrue(savedFiles.stream().anyMatch(file -> "README.md".equals(file.getFilePath()) && "MARKDOWN".equals(file.getLanguage())));
        assertTrue(savedFiles.stream().anyMatch(file -> "src/App.java".equals(file.getFilePath()) && "JAVA".equals(file.getLanguage())));
    }

    @Test
    void importFromGithub_ShouldSkipBlacklistedDirectory() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(githubRepositoryTreeClient.listDirectory("owner", "repo", ""))
                .thenReturn(List.of(
                        new GithubRepositoryTreeClient.GithubContentItem("node_modules", "dir", 0L)
                ));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L);
            return project;
        });
        when(workspaceSourceStorageService.cloneGithubRepository(1L, 100L, "https://github.com/owner/repo.git"))
                .thenReturn("/tmp/workspace-storage/user-1/project-100");
        when(cloudinaryWorkspaceStorageService.uploadWorkspaceArchive(
                1L,
                100L,
                Path.of("/tmp/workspace-storage/user-1/project-100")))
                .thenReturn(new CloudinaryWorkspaceStorageService.CloudinaryUploadResult(
                        "workspaces/user-1/project-100",
                        "https://res.cloudinary.com/demo/raw/upload/workspaces/user-1/project-100.zip"));

        ImportGithubWorkspaceResponse response =
                workspaceService.importFromGithub("https://github.com/owner/repo", "user@test.com");

        assertEquals(100L, response.getProjectId());
        assertEquals(0, response.getTotalFiles());
        assertEquals(0L, response.getTotalSizeBytes());
        verify(projectRepository, atLeastOnce()).save(any(Project.class));
        verify(sourceFileRepository, never()).saveAll(anyList());
    }

    @Test
    void importFromGithub_ShouldSkipBlacklistedFileName() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(githubRepositoryTreeClient.listDirectory("owner", "repo", ""))
                .thenReturn(List.of(
                        new GithubRepositoryTreeClient.GithubContentItem("dist", "file", 10L),
                        new GithubRepositoryTreeClient.GithubContentItem("README.md", "file", 5L)
                ));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L);
            return project;
        });
        when(workspaceSourceStorageService.cloneGithubRepository(1L, 100L, "https://github.com/owner/repo.git"))
                .thenReturn("/tmp/workspace-storage/user-1/project-100");
        when(cloudinaryWorkspaceStorageService.uploadWorkspaceArchive(
                1L,
                100L,
                Path.of("/tmp/workspace-storage/user-1/project-100")))
                .thenReturn(new CloudinaryWorkspaceStorageService.CloudinaryUploadResult(
                        "workspaces/user-1/project-100",
                        "https://res.cloudinary.com/demo/raw/upload/workspaces/user-1/project-100.zip"));

        ImportGithubWorkspaceResponse response =
                workspaceService.importFromGithub("https://github.com/owner/repo", "user@test.com");

        assertEquals(1, response.getTotalFiles());
        assertEquals(5L, response.getTotalSizeBytes());
        ArgumentCaptor<List<SourceFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(sourceFileRepository).saveAll(filesCaptor.capture());
        List<SourceFile> savedFiles = filesCaptor.getValue();
        assertEquals(1, savedFiles.size());
        assertEquals("README.md", savedFiles.get(0).getFilePath());
    }

    @Test
    void importFromGithub_ShouldThrow_WhenSizeExceedsLimit() {
        workspaceService = new WorkspaceService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                flowGraphDataRepository,
                githubRepositoryTreeClient,
                workspaceSourceStorageService,
                cloudinaryWorkspaceStorageService,
                10L,
                1_048_576L,
                ".git,node_modules,target,dist,build,.idea,.vscode"
        );

        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(githubRepositoryTreeClient.listDirectory("owner", "repo", ""))
                .thenReturn(List.of(
                        new GithubRepositoryTreeClient.GithubContentItem("big-file.js", "file", 11L)
                ));

        assertThrows(WorkspacePayloadTooLargeException.class,
                () -> workspaceService.importFromGithub("https://github.com/owner/repo", "user@test.com"));
    }

    @Test
    void importFromGithub_ShouldThrow_WhenRepoUrlInvalid() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class,
                () -> workspaceService.importFromGithub("https://gitlab.com/owner/repo", "user@test.com"));
    }

    @Test
    void importFromZip_ShouldSaveProjectAndSourceFiles() throws IOException {
        MockMultipartFile zipFile = createZipFile("sample.zip", List.of(
                new ZipEntryData("README.md", "# Local Workspace"),
                new ZipEntryData("src/App.java", "class App {}")
        ));

        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L);
            return project;
        });
        when(workspaceSourceStorageService.extractZipArchive(1L, 100L, zipFile))
                .thenReturn("/tmp/workspace-storage/user-1/project-100");
        when(cloudinaryWorkspaceStorageService.uploadWorkspaceArchive(
                1L,
                100L,
                Path.of("/tmp/workspace-storage/user-1/project-100")))
                .thenReturn(new CloudinaryWorkspaceStorageService.CloudinaryUploadResult(
                        "workspaces/user-1/project-100",
                        "https://res.cloudinary.com/demo/raw/upload/workspaces/user-1/project-100.zip"));

        ImportGithubWorkspaceResponse response =
                workspaceService.importFromZip(zipFile, "local-project", "user@test.com");

        assertEquals(100L, response.getProjectId());
        assertEquals("local-project", response.getName());
        assertEquals(2, response.getTotalFiles());
        assertTrue(response.getTotalSizeBytes() > 0L);
        assertEquals(
                "https://res.cloudinary.com/demo/raw/upload/workspaces/user-1/project-100.zip",
                response.getCloudinaryUrl());
        verify(workspaceSourceStorageService)
                .extractZipArchive(1L, 100L, zipFile);
        verify(cloudinaryWorkspaceStorageService)
                .uploadWorkspaceArchive(1L, 100L, Path.of("/tmp/workspace-storage/user-1/project-100"));

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository, atLeastOnce()).save(projectCaptor.capture());
        assertTrue(projectCaptor.getAllValues().stream()
                .anyMatch(p -> p.getSourceType() == ProjectSourceType.LOCAL_FOLDER));

        ArgumentCaptor<List<SourceFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(sourceFileRepository).saveAll(filesCaptor.capture());
        List<SourceFile> savedFiles = filesCaptor.getValue();
        assertEquals(2, savedFiles.size());
        assertTrue(savedFiles.stream().anyMatch(file -> "README.md".equals(file.getFilePath()) && "MARKDOWN".equals(file.getLanguage())));
        assertTrue(savedFiles.stream().anyMatch(file -> "src/App.java".equals(file.getFilePath()) && "JAVA".equals(file.getLanguage())));
    }

    @Test
    void importFromZip_ShouldSkip_WhenContainsBlacklistedDirectory() throws IOException {
        MockMultipartFile zipFile = createZipFile("sample.zip", List.of(
                new ZipEntryData("node_modules/index.js", "module.exports = {};"),
                new ZipEntryData("README.md", "keep")
        ));

        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
            Project project = invocation.getArgument(0);
            project.setId(100L);
            return project;
        });
        when(workspaceSourceStorageService.extractZipArchive(1L, 100L, zipFile))
                .thenReturn("/tmp/workspace-storage/user-1/project-100");
        when(cloudinaryWorkspaceStorageService.uploadWorkspaceArchive(
                1L,
                100L,
                Path.of("/tmp/workspace-storage/user-1/project-100")))
                .thenReturn(new CloudinaryWorkspaceStorageService.CloudinaryUploadResult(
                        "workspaces/user-1/project-100",
                        "https://res.cloudinary.com/demo/raw/upload/workspaces/user-1/project-100.zip"));

        ImportGithubWorkspaceResponse response =
                workspaceService.importFromZip(zipFile, "local-project", "user@test.com");

        assertEquals(1, response.getTotalFiles());
        ArgumentCaptor<List<SourceFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(sourceFileRepository).saveAll(filesCaptor.capture());
        List<SourceFile> savedFiles = filesCaptor.getValue();
        assertEquals(1, savedFiles.size());
        assertEquals("README.md", savedFiles.get(0).getFilePath());
    }

    @Test
    void importFromZip_ShouldThrow_WhenZipContainsPathTraversal() throws IOException {
        MockMultipartFile zipFile = createZipFile("sample.zip", List.of(
                new ZipEntryData("../evil.js", "alert('x');")
        ));

        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> workspaceService.importFromZip(zipFile, "local-project", "user@test.com"));
        assertTrue(exception.getMessage().contains("invalid entry path"));

        verify(projectRepository, never()).save(any(Project.class));
        verify(sourceFileRepository, never()).saveAll(anyList());
        verify(workspaceSourceStorageService, never()).extractZipArchive(anyLong(), anyLong(), any());
    }

    @Test
    void importFromZip_ShouldThrow_WhenSizeExceedsLimit() throws IOException {
        workspaceService = new WorkspaceService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                flowGraphDataRepository,
                githubRepositoryTreeClient,
                workspaceSourceStorageService,
                cloudinaryWorkspaceStorageService,
                10L,
                1_048_576L,
                ".git,node_modules,target,dist,build,.idea,.vscode"
        );
        MockMultipartFile zipFile = createZipFile("sample.zip", List.of(
                new ZipEntryData("big.txt", "01234567890")
        ));

        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        assertThrows(WorkspacePayloadTooLargeException.class,
                () -> workspaceService.importFromZip(zipFile, "local-project", "user@test.com"));
    }

    @Test
    void getWorkspaceTree_ShouldBuildNestedTree() {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);

        SourceFile file1 = new SourceFile();
        file1.setProject(project);
        file1.setFilePath("README.md");
        file1.setLanguage("MARKDOWN");

        SourceFile file2 = new SourceFile();
        file2.setProject(project);
        file2.setFilePath("src/main/App.java");
        file2.setLanguage("JAVA");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.findByProject_IdOrderByFilePathAsc(22L)).thenReturn(List.of(file1, file2));

        WorkspaceTreeResponse response = workspaceService.getWorkspaceTree(22L, "user@test.com");

        assertEquals(22L, response.getProjectId());
        assertEquals("repo", response.getProjectName());
        assertEquals(2, response.getNodes().size());
        assertEquals("src", response.getNodes().get(0).getName());
        assertEquals("folder", response.getNodes().get(0).getType());
        assertEquals("README.md", response.getNodes().get(1).getName());
        assertEquals("file", response.getNodes().get(1).getType());
    }

    @Test
    void getWorkspaceTree_ShouldThrow_WhenProjectDoesNotBelongToUser() {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> workspaceService.getWorkspaceTree(22L, "user@test.com"));
    }

    @Test
    void getWorkspaceFileContent_ShouldReturnTextContent() throws IOException {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-22"));
        Path file = workspaceRoot.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}", StandardCharsets.UTF_8);

        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(workspaceRoot.toString());

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        WorkspaceFileContentResponse response =
                workspaceService.getWorkspaceFileContent(22L, "src/App.java", "user@test.com");

        assertEquals(22L, response.getProjectId());
        assertEquals("src/App.java", response.getPath());
        assertEquals("JAVA", response.getLanguage());
        assertEquals("class App {}", response.getContent());
        assertEquals(12L, response.getSizeBytes());
    }

    @Test
    void getWorkspaceFileContent_ShouldThrow_WhenProjectDoesNotBelongToUser() {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class,
                () -> workspaceService.getWorkspaceFileContent(22L, "src/App.java", "user@test.com"));
    }

    @Test
    void getWorkspaceFileContent_ShouldThrow_WhenFileNotFound() throws IOException {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-22"));
        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(workspaceRoot.toString());

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        assertThrows(NotFoundException.class,
                () -> workspaceService.getWorkspaceFileContent(22L, "src/Missing.java", "user@test.com"));
    }

    @Test
    void getWorkspaceFileContent_ShouldThrow_WhenPathTraversal() throws IOException {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-22"));
        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(workspaceRoot.toString());

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class,
                () -> workspaceService.getWorkspaceFileContent(22L, "../etc/passwd", "user@test.com"));
    }

    @Test
    void getWorkspaceFileContent_ShouldThrow_WhenExceedsFileContentLimit() throws IOException {
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
                10L,
                ".git,node_modules,target,dist,build,.idea,.vscode"
        );

        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-22"));
        Path file = workspaceRoot.resolve("notes.txt");
        Files.writeString(file, "01234567890", StandardCharsets.UTF_8);

        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(workspaceRoot.toString());

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        assertThrows(WorkspacePayloadTooLargeException.class,
                () -> workspaceService.getWorkspaceFileContent(22L, "notes.txt", "user@test.com"));
    }

    @Test
    void getWorkspaceFileContent_ShouldThrow_WhenBinaryFile() throws IOException {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-22"));
        Path file = workspaceRoot.resolve("image.bin");
        Files.write(file, new byte[]{0x01, 0x02, 0x00, 0x03});

        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(workspaceRoot.toString());

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class,
                () -> workspaceService.getWorkspaceFileContent(22L, "image.bin", "user@test.com"));
    }

    @Test
    void deleteWorkspaceFolder_ShouldMigrateLegacyWorkspaceToCloudinaryAndDeleteFolderMetadata() throws IOException {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-22"));
        Path removedFile = workspaceRoot.resolve("src/main/App.java");
        Path removedNestedFile = workspaceRoot.resolve("src/main/utils/Helper.java");

        Files.createDirectories(removedFile.getParent());
        Files.createDirectories(removedNestedFile.getParent());
        Files.writeString(removedFile, "class App {}", StandardCharsets.UTF_8);
        Files.writeString(removedNestedFile, "class Helper {}", StandardCharsets.UTF_8);

        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(workspaceRoot.toString());

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));
        when(cloudinaryWorkspaceStorageService.uploadWorkspaceArchive(7L, 22L, workspaceRoot))
                .thenReturn(new CloudinaryWorkspaceStorageService.CloudinaryUploadResult(
                        "workspace-archive-v1",
                        "https://res.cloudinary.com/demo/raw/upload/workspace-archive-v1.zip"));
        when(cloudinaryWorkspaceStorageService.deleteFolderInArchive(
                7L,
                22L,
                "workspace-archive-v1",
                "https://res.cloudinary.com/demo/raw/upload/workspace-archive-v1.zip",
                CloudinaryDeliveryType.PRIVATE,
                "src/main"))
                .thenReturn(new CloudinaryWorkspaceStorageService.CloudinaryUploadResult(
                        "workspace-archive-v2",
                        "https://res.cloudinary.com/demo/raw/upload/workspace-archive-v2.zip"));
        when(sourceFileRepository.deleteByProject_IdAndFilePath(22L, "src/main")).thenReturn(0L);
        when(sourceFileRepository.deleteByProject_IdAndFilePathStartingWith(22L, "src/main/")).thenReturn(2L);

        DeleteWorkspaceFolderResponse response =
                workspaceService.deleteWorkspaceFolder(22L, "src/main", "user@test.com");

        assertEquals(22L, response.getProjectId());
        assertEquals("src/main", response.getPath());
        assertEquals(2, response.getDeletedFiles());
        assertEquals("Folder deleted successfully.", response.getMessage());

        assertFalse(Files.exists(workspaceRoot));

        verify(cloudinaryWorkspaceStorageService).uploadWorkspaceArchive(7L, 22L, workspaceRoot);
        verify(cloudinaryWorkspaceStorageService).deleteFolderInArchive(
                7L,
                22L,
                "workspace-archive-v1",
                "https://res.cloudinary.com/demo/raw/upload/workspace-archive-v1.zip",
                CloudinaryDeliveryType.PRIVATE,
                "src/main");
        verify(cloudinaryWorkspaceStorageService)
                .deleteWorkspaceArchive("workspace-archive-v1", CloudinaryDeliveryType.PRIVATE);
        verify(sourceFileRepository).deleteByProject_IdAndFilePath(22L, "src/main");
        verify(sourceFileRepository).deleteByProject_IdAndFilePathStartingWith(22L, "src/main/");
    }

    @Test
    void deleteWorkspaceFolder_ShouldThrow_WhenFolderNotFound() throws IOException {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setCloudinaryPublicId("workspace-archive-v1");
        project.setCloudinaryUrl("https://res.cloudinary.com/demo/raw/upload/workspace-archive-v1.zip");
        project.setCloudinaryDeliveryType(CloudinaryDeliveryType.PRIVATE);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));
        when(cloudinaryWorkspaceStorageService.deleteFolderInArchive(
                7L,
                22L,
                "workspace-archive-v1",
                "https://res.cloudinary.com/demo/raw/upload/workspace-archive-v1.zip",
                CloudinaryDeliveryType.PRIVATE,
                "src/missing"))
                .thenThrow(new NotFoundException("Folder not found in workspace"));

        assertThrows(NotFoundException.class,
                () -> workspaceService.deleteWorkspaceFolder(22L, "src/missing", "user@test.com"));

        verify(sourceFileRepository, never()).deleteByProject_IdAndFilePath(anyLong(), anyString());
        verify(sourceFileRepository, never()).deleteByProject_IdAndFilePathStartingWith(anyLong(), anyString());
    }

    @Test
    void deleteWorkspaceFolder_ShouldThrow_WhenPathTraversal() throws IOException {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project-22"));
        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(workspaceRoot.toString());

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class,
                () -> workspaceService.deleteWorkspaceFolder(22L, "../etc", "user@test.com"));

        verify(sourceFileRepository, never()).deleteByProject_IdAndFilePath(anyLong(), anyString());
        verify(sourceFileRepository, never()).deleteByProject_IdAndFilePathStartingWith(anyLong(), anyString());
    }

    @Test
    void deleteWorkspace_ShouldDeleteStorageMetadataAndProject() throws IOException {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("user-7").resolve("project-22"));
        Path removedFile = workspaceRoot.resolve("src/App.java");
        Files.createDirectories(removedFile.getParent());
        Files.writeString(removedFile, "class App {}", StandardCharsets.UTF_8);

        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(workspaceRoot.toString());

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.countByProject_Id(22L)).thenReturn(2L);
        when(sourceFileRepository.deleteByProject_Id(22L)).thenReturn(2L);

        DeleteWorkspaceResponse response = workspaceService.deleteWorkspace(22L, "user@test.com");

        assertEquals(22L, response.getProjectId());
        assertEquals(2, response.getDeletedFiles());
        assertEquals("Workspace deleted successfully.", response.getMessage());
        assertFalse(Files.exists(workspaceRoot));

        verify(sourceFileRepository).countByProject_Id(22L);
        verify(sourceFileRepository).deleteByProject_Id(22L);
        verify(projectRepository).delete(project);
    }

    @Test
    void deleteWorkspace_ShouldDeleteProjectEvenWhenStorageMissing() {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(null);

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.countByProject_Id(22L)).thenReturn(0L);
        when(sourceFileRepository.deleteByProject_Id(22L)).thenReturn(0L);

        DeleteWorkspaceResponse response = workspaceService.deleteWorkspace(22L, "user@test.com");

        assertEquals(22L, response.getProjectId());
        assertEquals(0, response.getDeletedFiles());
        verify(sourceFileRepository).countByProject_Id(22L);
        verify(sourceFileRepository).deleteByProject_Id(22L);
        verify(projectRepository).delete(project);
    }

    @Test
    void deleteWorkspace_ShouldDeleteProjectEvenWhenLocalWorkspacePathDoesNotExist() {
        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");

        Project project = new Project();
        project.setId(22L);
        project.setName("repo");
        project.setUser(user);
        project.setStoragePath(tempDir.resolve("not-project-folder").toString());

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(22L, 7L)).thenReturn(Optional.of(project));
        when(sourceFileRepository.countByProject_Id(22L)).thenReturn(0L);
        when(sourceFileRepository.deleteByProject_Id(22L)).thenReturn(0L);

        DeleteWorkspaceResponse response = workspaceService.deleteWorkspace(22L, "user@test.com");

        assertEquals(22L, response.getProjectId());
        assertEquals(0, response.getDeletedFiles());
        verify(sourceFileRepository).countByProject_Id(22L);
        verify(sourceFileRepository).deleteByProject_Id(22L);
        verify(projectRepository).delete(project);
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
