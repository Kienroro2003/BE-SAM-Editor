package com.sam.besameditor.services;

import com.sam.besameditor.exceptions.NotFoundException;
import com.sam.besameditor.exceptions.WorkspacePayloadTooLargeException;
import com.sam.besameditor.exceptions.WorkspaceStorageException;
import com.sam.besameditor.models.Project;
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
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServicePrivateCoverageTest {

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
                128L,
                32L,
                ".git,node_modules,target,dist,build,.idea,.vscode"
        );
    }

    @Test
    void privateHelpers_ShouldCoverNormalizationParsingAndLanguageDetection() throws IOException {
        IllegalArgumentException missingFolder = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(workspaceService, "parseLocalFolder", new Class[]{String.class, String.class}, null, "workspace")
        );
        assertEquals("folderPath is required", missingFolder.getMessage());

        IllegalArgumentException missingPath = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(workspaceService, "parseLocalFolder", new Class[]{String.class, String.class}, tempDir.resolve("missing").toString(), "workspace")
        );
        assertEquals("folderPath does not exist", missingPath.getMessage());

        Object localFolder = invoke(workspaceService, "parseLocalFolder", new Class[]{String.class, String.class}, "/", "   ");
        assertEquals("local-workspace", accessor(localFolder, "workspaceName"));

        IllegalArgumentException missingRepoUrl = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(workspaceService, "parseGithubRepoUrl", new Class[]{String.class}, "   ")
        );
        assertEquals("repoUrl is required", missingRepoUrl.getMessage());

        Object repoDescriptor = invoke(
                workspaceService,
                "parseGithubRepoUrl",
                new Class[]{String.class},
                " https://github.com/openai/demo.git?tab=readme#intro "
        );
        assertEquals("openai", accessor(repoDescriptor, "owner"));
        assertEquals("demo", accessor(repoDescriptor, "repo"));
        assertEquals("https://github.com/openai/demo", accessor(repoDescriptor, "canonicalUrl"));
        assertEquals("https://github.com/openai/demo.git", accessor(repoDescriptor, "cloneUrl"));

        MockMultipartFile defaultZip = new MockMultipartFile("file", ".zip", "application/zip", new byte[]{1});
        Object zipDescriptor = invoke(
                workspaceService,
                "parseZipUpload",
                new Class[]{org.springframework.web.multipart.MultipartFile.class, String.class},
                defaultZip,
                "   "
        );
        assertEquals("uploaded-workspace", accessor(zipDescriptor, "workspaceName"));
        assertEquals("upload://.zip", accessor(zipDescriptor, "sourceUrl"));

        MockMultipartFile unnamedZip = new MockMultipartFile("file", null, "application/zip", new byte[]{1});
        Object unnamedZipDescriptor = invoke(
                workspaceService,
                "parseZipUpload",
                new Class[]{org.springframework.web.multipart.MultipartFile.class, String.class},
                unnamedZip,
                "workspace"
        );
        assertEquals("upload://workspace.zip", accessor(unnamedZipDescriptor, "sourceUrl"));

        String longName = "x".repeat(256);
        IllegalArgumentException longWorkspaceName = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(
                        workspaceService,
                        "parseZipUpload",
                        new Class[]{org.springframework.web.multipart.MultipartFile.class, String.class},
                        new MockMultipartFile("file", "workspace.zip", "application/zip", new byte[]{1}),
                        longName)
        );
        assertEquals("workspaceName must be at most 255 characters", longWorkspaceName.getMessage());

        assertEquals("", invoke(workspaceService, "normalizePath", new Class[]{String.class}, new Object[]{null}));
        assertEquals("src/App.java", invoke(workspaceService, "normalizePath", new Class[]{String.class}, "\\src\\App.java/"));

        assertEquals("workspace.zip", invoke(workspaceService, "normalizeUploadedFilename", new Class[]{String.class}, new Object[]{null}));
        assertEquals("archive.zip", invoke(workspaceService, "normalizeUploadedFilename", new Class[]{String.class}, "nested/archive.zip"));
        assertEquals("workspace.zip", invoke(workspaceService, "normalizeUploadedFilename", new Class[]{String.class}, " / "));

        @SuppressWarnings("unchecked")
        Set<String> emptyBlacklist = (Set<String>) invoke(workspaceService, "parseBlacklist", new Class[]{String.class}, new Object[]{null});
        assertTrue(emptyBlacklist.isEmpty());

        @SuppressWarnings("unchecked")
        Set<String> blacklist = (Set<String>) invoke(workspaceService, "parseBlacklist", new Class[]{String.class}, " .git , node_modules ,, target ");
        assertEquals(Set.of(".git", "node_modules", "target"), blacklist);

        assertEquals("", invoke(workspaceService, "extractParentPath", new Class[]{String.class}, "App.java"));
        assertEquals("", invoke(workspaceService, "extractParentPath", new Class[]{String.class}, "/App.java"));
        assertEquals("src/main", invoke(workspaceService, "extractParentPath", new Class[]{String.class}, "src/main/App.java"));

        assertEquals("JAVA", invoke(workspaceService, "detectLanguage", new Class[]{String.class}, "App.java"));
        assertEquals("JAVASCRIPT", invoke(workspaceService, "detectLanguage", new Class[]{String.class}, "App.jsx"));
        assertEquals("TYPESCRIPT", invoke(workspaceService, "detectLanguage", new Class[]{String.class}, "App.tsx"));
        assertEquals("PYTHON", invoke(workspaceService, "detectLanguage", new Class[]{String.class}, "tool.py"));
        assertEquals("JSON", invoke(workspaceService, "detectLanguage", new Class[]{String.class}, "config.json"));
        assertEquals("YAML", invoke(workspaceService, "detectLanguage", new Class[]{String.class}, "config.yaml"));
        assertEquals("XML", invoke(workspaceService, "detectLanguage", new Class[]{String.class}, "config.xml"));
        assertEquals("MARKDOWN", invoke(workspaceService, "detectLanguage", new Class[]{String.class}, "README.md"));
        assertEquals("TEXT", invoke(workspaceService, "detectLanguage", new Class[]{String.class}, "notes.txt"));
    }

    @Test
    void privateHelpers_ShouldCoverWorkspaceRootAndRelativePathValidation() throws IOException {
        Project missingStorage = new Project();
        missingStorage.setId(10L);
        assertThrows(NotFoundException.class,
                () -> invoke(workspaceService, "resolveWorkspaceRoot", new Class[]{Project.class}, missingStorage));

        Project invalidStorage = new Project();
        invalidStorage.setId(11L);
        invalidStorage.setStoragePath("\0");
        assertThrows(IllegalArgumentException.class,
                () -> invoke(workspaceService, "resolveWorkspaceRoot", new Class[]{Project.class}, invalidStorage));

        Project missingDirectory = new Project();
        missingDirectory.setId(12L);
        missingDirectory.setStoragePath(tempDir.resolve("missing").toString());
        assertThrows(NotFoundException.class,
                () -> invoke(workspaceService, "resolveWorkspaceRoot", new Class[]{Project.class}, missingDirectory));

        Path workspaceRoot = Files.createDirectories(tempDir.resolve("user-1").resolve("project-22"));
        Project validProject = new Project();
        validProject.setId(22L);
        validProject.setStoragePath(workspaceRoot.toString());
        assertEquals(workspaceRoot.toAbsolutePath().normalize(),
                invoke(workspaceService, "resolveWorkspaceRoot", new Class[]{Project.class}, validProject));

        Project blankDeleteProject = new Project();
        blankDeleteProject.setId(22L);
        assertNull(invoke(workspaceService, "resolveWorkspaceRootForDelete", new Class[]{Project.class}, blankDeleteProject));

        Project invalidDeleteProject = new Project();
        invalidDeleteProject.setId(22L);
        invalidDeleteProject.setStoragePath(tempDir.resolve("wrong-name").toString());
        assertThrows(IllegalArgumentException.class,
                () -> invoke(workspaceService, "resolveWorkspaceRootForDelete", new Class[]{Project.class}, invalidDeleteProject));

        assertEquals(workspaceRoot.toAbsolutePath().normalize(),
                invoke(workspaceService, "resolveWorkspaceRootForDelete", new Class[]{Project.class}, validProject));

        assertThrows(IllegalArgumentException.class,
                () -> invoke(workspaceService, "resolveRelativeFilePath", new Class[]{String.class}, new Object[]{null}));
        assertThrows(IllegalArgumentException.class,
                () -> invoke(workspaceService, "resolveRelativeFilePath", new Class[]{String.class}, "/etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> invoke(workspaceService, "resolveRelativeFilePath", new Class[]{String.class}, "C:/secret.txt"));
        assertEquals(Path.of("src/App.java"),
                invoke(workspaceService, "resolveRelativeFilePath", new Class[]{String.class}, "src\\App.java"));

        assertThrows(IllegalArgumentException.class,
                () -> invoke(workspaceService, "resolveRelativeFolderPath", new Class[]{String.class}, new Object[]{null}));
        assertThrows(IllegalArgumentException.class,
                () -> invoke(workspaceService, "resolveRelativeFolderPath", new Class[]{String.class}, "/src"));
        assertThrows(IllegalArgumentException.class,
                () -> invoke(workspaceService, "resolveRelativeFolderPath", new Class[]{String.class}, "C:/src"));
        assertEquals(Path.of("src/main"),
                invoke(workspaceService, "resolveRelativeFolderPath", new Class[]{String.class}, "src\\main"));

        assertTrue((Boolean) invoke(workspaceService, "startsWithParentTraversal", new Class[]{Path.class}, Path.of("../outside")));
        assertFalse((Boolean) invoke(workspaceService, "startsWithParentTraversal", new Class[]{Path.class}, Path.of("inside")));
    }

    @Test
    void privateHelpers_ShouldCoverContentDetectionAndCleanup() throws IOException {
        Path textFile = tempDir.resolve("notes.txt");
        Files.writeString(textFile, "hello", StandardCharsets.UTF_8);

        Path binaryFile = tempDir.resolve("image.bin");
        Files.write(binaryFile, new byte[]{0x01, 0x02, 0x03, 0x04, 0x7F});

        WorkspaceService tinyContentLimitService = new WorkspaceService(
                userRepository,
                projectRepository,
                sourceFileRepository,
                analyzedFunctionRepository,
                flowGraphDataRepository,
                githubRepositoryTreeClient,
                workspaceSourceStorageService,
                cloudinaryWorkspaceStorageService,
                128L,
                4L,
                ".git,node_modules,target,dist,build,.idea,.vscode"
        );

        WorkspacePayloadTooLargeException tooLarge = assertThrows(
                WorkspacePayloadTooLargeException.class,
                () -> invoke(tinyContentLimitService, "readWorkspaceFile", new Class[]{Path.class}, textFile)
        );
        assertTrue(tooLarge.getMessage().contains("Workspace file content exceeds limit"));

        IllegalArgumentException missingRead = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(workspaceService, "readWorkspaceFile", new Class[]{Path.class}, tempDir.resolve("missing.txt"))
        );
        assertEquals("Unable to read workspace file", missingRead.getMessage());

        IllegalArgumentException binaryRead = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(workspaceService, "readWorkspaceFile", new Class[]{Path.class}, binaryFile)
        );
        assertEquals("Only text files are supported", binaryRead.getMessage());

        assertTrue((Boolean) invoke(workspaceService, "isTextContent", new Class[]{byte[].class}, new byte[0]));
        assertTrue((Boolean) invoke(workspaceService, "isTextContent", new Class[]{byte[].class}, "hello".getBytes(StandardCharsets.UTF_8)));
        assertFalse((Boolean) invoke(workspaceService, "isTextContent", new Class[]{byte[].class}, new byte[]{0x00}));
        assertFalse((Boolean) invoke(workspaceService, "isTextContent", new Class[]{byte[].class}, new byte[]{0x01, 0x02, 0x03, 0x04, 0x05}));

        invoke(workspaceService, "deleteTempFileIfExists", new Class[]{Path.class}, new Object[]{null});
        Path tempFile = Files.createTempFile(tempDir, "delete-me-", ".txt");
        invoke(workspaceService, "deleteTempFileIfExists", new Class[]{Path.class}, tempFile);
        assertFalse(Files.exists(tempFile));

        WorkspaceStorageException deleteFailure = assertThrows(
                WorkspaceStorageException.class,
                () -> invoke(workspaceService, "deleteDirectoryRecursively", new Class[]{Path.class}, tempDir.resolve("missing-folder"))
        );
        assertEquals("Failed to delete workspace folder", deleteFailure.getMessage());
    }

    @Test
    void privateHelpers_ShouldCoverRecursiveCollectionBranches() {
        when(githubRepositoryTreeClient.listDirectory("owner", "repo", ""))
                .thenReturn(List.of(
                        new GithubRepositoryTreeClient.GithubContentItem("/", "file", 10L),
                        new GithubRepositoryTreeClient.GithubContentItem("notes", null, 10L),
                        new GithubRepositoryTreeClient.GithubContentItem("node_modules", "dir", 0L),
                        new GithubRepositoryTreeClient.GithubContentItem("dist", "file", 10L),
                        new GithubRepositoryTreeClient.GithubContentItem("src", "dir", 0L),
                        new GithubRepositoryTreeClient.GithubContentItem("README.md", "file", 2L)
                ));
        when(githubRepositoryTreeClient.listDirectory("owner", "repo", "src"))
                .thenReturn(List.of(
                        new GithubRepositoryTreeClient.GithubContentItem("src/App.java", "file", -5L),
                        new GithubRepositoryTreeClient.GithubContentItem("src/unknown", "symlink", 5L)
                ));

        List<Object> snapshots = new ArrayList<>();
        long[] totalSize = new long[]{0L};

        invoke(workspaceService,
                "collectFilesRecursive",
                new Class[]{String.class, String.class, String.class, List.class, long[].class},
                "owner", "repo", "", snapshots, totalSize);

        assertEquals(2L, totalSize[0]);
        assertEquals(2, snapshots.size());
        assertTrue(snapshots.stream().anyMatch(snapshot -> "README.md".equals(accessor(snapshot, "filePath"))));
        assertTrue(snapshots.stream().anyMatch(snapshot -> "src/App.java".equals(accessor(snapshot, "filePath"))));
    }

    @Test
    void publicMethods_ShouldCoverRemainingLambdaAndSortBranches() {
        when(userRepository.findByEmail("missing@test.com")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> workspaceService.getUserWorkspaces("missing@test.com"));

        User user = new User();
        user.setId(7L);
        user.setEmail("user@test.com");
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));
        when(projectRepository.findByIdAndUser_Id(99L, 7L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> workspaceService.deleteWorkspace(99L, "user@test.com"));
        assertThrows(NotFoundException.class, () -> workspaceService.deleteWorkspaceFolder(99L, "src", "user@test.com"));

        Project project = new Project();
        project.setId(30L);
        project.setName("repo");
        project.setUser(user);
        when(projectRepository.findByIdAndUser_Id(30L, 7L)).thenReturn(Optional.of(project));

        com.sam.besameditor.models.SourceFile first = new com.sam.besameditor.models.SourceFile();
        first.setProject(project);
        first.setFilePath("b.txt");
        first.setLanguage("TEXT");

        com.sam.besameditor.models.SourceFile second = new com.sam.besameditor.models.SourceFile();
        second.setProject(project);
        second.setFilePath("A.txt");
        second.setLanguage("TEXT");

        com.sam.besameditor.models.SourceFile third = new com.sam.besameditor.models.SourceFile();
        third.setProject(project);
        third.setFilePath("src/Z.java");
        third.setLanguage("JAVA");

        when(sourceFileRepository.findByProject_IdOrderByFilePathAsc(30L)).thenReturn(List.of(first, second, third));

        var tree = workspaceService.getWorkspaceTree(30L, "user@test.com");
        assertEquals(3, tree.getNodes().size());
        assertEquals("src", tree.getNodes().get(0).getName());
        assertEquals("A.txt", tree.getNodes().get(1).getName());
        assertEquals("b.txt", tree.getNodes().get(2).getName());

        verifyNoInteractions(workspaceSourceStorageService);
    }

    @Test
    void privateZipCollector_ShouldHandleZeroByteFilesAndUnreadableFolders() throws IOException {
        MockMultipartFile zipFile = createZipFile(List.of(
                new ZipEntryData("src/", null),
                new ZipEntryData("target/ignored.txt", "skip"),
                new ZipEntryData("empty.txt", "")
        ));

        Object localImportResult = invoke(
                workspaceService,
                "collectFilesFromZipArchive",
                new Class[]{org.springframework.web.multipart.MultipartFile.class},
                zipFile
        );

        @SuppressWarnings("unchecked")
        List<Object> snapshots = (List<Object>) accessor(localImportResult, "snapshots");
        assertEquals(1, snapshots.size());
        assertEquals("empty.txt", accessor(snapshots.get(0), "filePath"));
        assertEquals(0L, accessor(localImportResult, "totalSizeBytes"));

        IllegalArgumentException unreadableFolder = assertThrows(
                IllegalArgumentException.class,
                () -> invoke(workspaceService, "collectFilesFromLocalFolder", new Class[]{Path.class}, tempDir.resolve("missing-folder"))
        );
        assertTrue(unreadableFolder.getMessage().contains("Unable to read source folder"));
    }

    private MockMultipartFile createZipFile(List<ZipEntryData> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(output)) {
            for (ZipEntryData entryData : entries) {
                ZipEntry entry = new ZipEntry(entryData.path());
                zipOutputStream.putNextEntry(entry);
                if (entryData.content() != null) {
                    zipOutputStream.write(entryData.content().getBytes(StandardCharsets.UTF_8));
                }
                zipOutputStream.closeEntry();
            }
        }
        return new MockMultipartFile("file", "workspace.zip", "application/zip", output.toByteArray());
    }

    private Object accessor(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
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

    private record ZipEntryData(String path, String content) {
    }
}
