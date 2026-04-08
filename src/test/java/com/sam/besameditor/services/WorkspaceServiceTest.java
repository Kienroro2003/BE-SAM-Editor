package com.sam.besameditor.services;

import com.sam.besameditor.dto.ImportGithubWorkspaceResponse;
import com.sam.besameditor.dto.WorkspaceTreeResponse;
import com.sam.besameditor.exceptions.NotFoundException;
import com.sam.besameditor.exceptions.WorkspacePayloadTooLargeException;
import com.sam.besameditor.models.Project;
import com.sam.besameditor.models.ProjectSourceType;
import com.sam.besameditor.models.SourceFile;
import com.sam.besameditor.models.User;
import com.sam.besameditor.repositories.ProjectRepository;
import com.sam.besameditor.repositories.SourceFileRepository;
import com.sam.besameditor.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
    private GithubRepositoryTreeClient githubRepositoryTreeClient;
    @Mock
    private WorkspaceSourceStorageService workspaceSourceStorageService;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        workspaceService = new WorkspaceService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                githubRepositoryTreeClient,
                workspaceSourceStorageService,
                15_728_640L,
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

        ImportGithubWorkspaceResponse response =
                workspaceService.importFromGithub("https://github.com/owner/repo", "user@test.com");

        assertEquals(100L, response.getProjectId());
        assertEquals("repo", response.getName());
        assertEquals(2, response.getTotalFiles());
        assertEquals(30L, response.getTotalSizeBytes());
        verify(workspaceSourceStorageService)
                .cloneGithubRepository(1L, 100L, "https://github.com/owner/repo.git");

        ArgumentCaptor<List<SourceFile>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(sourceFileRepository).saveAll(filesCaptor.capture());
        List<SourceFile> savedFiles = filesCaptor.getValue();
        assertEquals(2, savedFiles.size());
        assertTrue(savedFiles.stream().anyMatch(file -> "README.md".equals(file.getFilePath()) && "MARKDOWN".equals(file.getLanguage())));
        assertTrue(savedFiles.stream().anyMatch(file -> "src/App.java".equals(file.getFilePath()) && "JAVA".equals(file.getLanguage())));
    }

    @Test
    void importFromGithub_ShouldThrow_WhenContainsBlacklistedDirectory() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@test.com");

        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(githubRepositoryTreeClient.listDirectory("owner", "repo", ""))
                .thenReturn(List.of(
                        new GithubRepositoryTreeClient.GithubContentItem("node_modules", "dir", 0L)
                ));

        assertThrows(WorkspacePayloadTooLargeException.class,
                () -> workspaceService.importFromGithub("https://github.com/owner/repo", "user@test.com"));

        verify(projectRepository, never()).save(any(Project.class));
        verify(sourceFileRepository, never()).saveAll(anyList());
    }

    @Test
    void importFromGithub_ShouldThrow_WhenSizeExceedsLimit() {
        workspaceService = new WorkspaceService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                githubRepositoryTreeClient,
                workspaceSourceStorageService,
                10L,
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
    void importFromLocalFolder_ShouldSaveProjectAndSourceFiles() throws IOException {
        Path rootFolder = Files.createTempDirectory("workspace-local-import-");
        try {
            Files.writeString(rootFolder.resolve("README.md"), "# Local Workspace");
            Files.createDirectories(rootFolder.resolve("src"));
            Files.writeString(rootFolder.resolve("src/App.java"), "class App {}");

            User user = new User();
            user.setId(1L);
            user.setEmail("user@test.com");

            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
            when(projectRepository.save(any(Project.class))).thenAnswer(invocation -> {
                Project project = invocation.getArgument(0);
                project.setId(100L);
                return project;
            });
            when(workspaceSourceStorageService.copyLocalFolder(1L, 100L, rootFolder.toAbsolutePath().normalize()))
                    .thenReturn("/tmp/workspace-storage/user-1/project-100");

            ImportGithubWorkspaceResponse response =
                    workspaceService.importFromLocalFolder(rootFolder.toString(), "local-project", "user@test.com");

            assertEquals(100L, response.getProjectId());
            assertEquals("local-project", response.getName());
            assertEquals(2, response.getTotalFiles());
            assertTrue(response.getTotalSizeBytes() > 0L);
            verify(workspaceSourceStorageService)
                    .copyLocalFolder(1L, 100L, rootFolder.toAbsolutePath().normalize());

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
        } finally {
            deleteDirectory(rootFolder);
        }
    }

    @Test
    void importFromLocalFolder_ShouldThrow_WhenContainsBlacklistedDirectory() throws IOException {
        Path rootFolder = Files.createTempDirectory("workspace-local-import-blacklist-");
        try {
            Path blacklistedDir = rootFolder.resolve("node_modules");
            Files.createDirectories(blacklistedDir);
            Files.writeString(blacklistedDir.resolve("index.js"), "module.exports = {};");

            User user = new User();
            user.setId(1L);
            user.setEmail("user@test.com");
            when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

            assertThrows(WorkspacePayloadTooLargeException.class,
                    () -> workspaceService.importFromLocalFolder(rootFolder.toString(), "local-project", "user@test.com"));

            verify(projectRepository, never()).save(any(Project.class));
            verify(sourceFileRepository, never()).saveAll(anyList());
        } finally {
            deleteDirectory(rootFolder);
        }
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

    private void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best effort for temp test folders
                        }
                    });
        }
    }
}
