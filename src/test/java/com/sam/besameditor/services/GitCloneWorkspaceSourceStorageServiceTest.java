package com.sam.besameditor.services;

import com.sam.besameditor.exceptions.WorkspaceStorageException;
import org.junit.jupiter.api.Test;
import org.eclipse.jgit.api.Git;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitCloneWorkspaceSourceStorageServiceTest {

    @Test
    void extractZipArchive_ShouldSkipBlacklistedPathsAndExtractFiles() throws IOException {
        Path storageRoot = Files.createTempDirectory("workspace-storage-");

        try {
            GitCloneWorkspaceSourceStorageService service = new GitCloneWorkspaceSourceStorageService(
                    storageRoot.toString(),
                    60,
                    ".git,node_modules,target,dist,build,.idea,.vscode"
            );

            MockMultipartFile zipFile = createZipFile("workspace.zip", List.of(
                    new ZipEntryData("src/App.java", "class App {}"),
                    new ZipEntryData("node_modules/lib/index.js", "module.exports = {};"),
                    new ZipEntryData(".git/config", "[core]"),
                    new ZipEntryData("README.md", "# Demo")
            ));

            String targetPath = service.extractZipArchive(7L, 99L, zipFile);
            Path targetRoot = Paths.get(targetPath);

            assertTrue(Files.exists(targetRoot.resolve("src/App.java")));
            assertTrue(Files.exists(targetRoot.resolve("README.md")));
            assertFalse(Files.exists(targetRoot.resolve("node_modules")));
            assertFalse(Files.exists(targetRoot.resolve(".git")));
        } finally {
            deleteDirectory(storageRoot);
        }
    }

    @Test
    void extractZipArchive_ShouldThrow_WhenZipContainsPathTraversal() throws IOException {
        Path storageRoot = Files.createTempDirectory("workspace-storage-");
        try {
            GitCloneWorkspaceSourceStorageService service = new GitCloneWorkspaceSourceStorageService(
                    storageRoot.toString(),
                    60,
                    ".git,node_modules,target,dist,build,.idea,.vscode"
            );
            MockMultipartFile zipFile = createZipFile("workspace.zip", List.of(
                    new ZipEntryData("../evil.sh", "echo hacked")
            ));

            assertThrows(WorkspaceStorageException.class, () -> service.extractZipArchive(7L, 99L, zipFile));
        } finally {
            deleteDirectory(storageRoot);
        }
    }

    @Test
    void copyLocalFolder_ShouldCopyFilesAndSkipBlacklistedPaths() throws IOException {
        Path storageRoot = Files.createTempDirectory("workspace-storage-");
        Path sourceRoot = Files.createTempDirectory("workspace-source-");
        try {
            Files.createDirectories(sourceRoot.resolve("src"));
            Files.createDirectories(sourceRoot.resolve("node_modules/lib"));
            Files.createDirectories(sourceRoot.resolve(".git"));
            Files.writeString(sourceRoot.resolve("src/App.java"), "class App {}", StandardCharsets.UTF_8);
            Files.writeString(sourceRoot.resolve("node_modules/lib/index.js"), "module.exports = {}", StandardCharsets.UTF_8);
            Files.writeString(sourceRoot.resolve(".git/config"), "[core]", StandardCharsets.UTF_8);

            GitCloneWorkspaceSourceStorageService service = new GitCloneWorkspaceSourceStorageService(
                    storageRoot.toString(),
                    60,
                    ".git,node_modules,target,dist,build,.idea,.vscode"
            );

            String targetPath = service.copyLocalFolder(7L, 99L, sourceRoot);
            Path targetRoot = Paths.get(targetPath);

            assertTrue(Files.exists(targetRoot.resolve("src/App.java")));
            assertFalse(Files.exists(targetRoot.resolve("node_modules")));
            assertFalse(Files.exists(targetRoot.resolve(".git")));
        } finally {
            deleteDirectory(storageRoot);
            deleteDirectory(sourceRoot);
        }
    }

    @Test
    void copyLocalFolder_ShouldThrow_WhenSourceFolderMissing() throws IOException {
        Path storageRoot = Files.createTempDirectory("workspace-storage-");
        try {
            GitCloneWorkspaceSourceStorageService service = new GitCloneWorkspaceSourceStorageService(
                    storageRoot.toString(),
                    60,
                    ".git,node_modules,target,dist,build,.idea,.vscode"
            );

            assertThrows(
                    WorkspaceStorageException.class,
                    () -> service.copyLocalFolder(7L, 99L, storageRoot.resolve("missing-folder"))
            );
        } finally {
            deleteDirectory(storageRoot);
        }
    }

    @Test
    void cloneGithubRepository_ShouldCloneLocalRepository() throws Exception {
        Path storageRoot = Files.createTempDirectory("workspace-storage-");
        Path sourceRepo = Files.createTempDirectory("workspace-source-repo-");
        try {
            Files.writeString(sourceRepo.resolve("README.md"), "# Demo", StandardCharsets.UTF_8);
            try (Git git = Git.init().setDirectory(sourceRepo.toFile()).call()) {
                git.add().addFilepattern("README.md").call();
                git.commit()
                        .setMessage("init")
                        .setAuthor("Test User", "test@example.com")
                        .setCommitter("Test User", "test@example.com")
                        .call();
            }

            GitCloneWorkspaceSourceStorageService service = new GitCloneWorkspaceSourceStorageService(
                    storageRoot.toString(),
                    60,
                    ".git,node_modules,target,dist,build,.idea,.vscode"
            );

            String clonedPath = service.cloneGithubRepository(7L, 99L, sourceRepo.toUri().toString());
            Path clonedRoot = Paths.get(clonedPath);

            assertTrue(Files.exists(clonedRoot.resolve("README.md")));
            assertTrue(Files.exists(clonedRoot.resolve(".git")));
        } finally {
            deleteDirectory(storageRoot);
            deleteDirectory(sourceRepo);
        }
    }

    @Test
    void cloneGithubRepository_ShouldThrow_WhenRepositoryUrlInvalid() throws IOException {
        Path storageRoot = Files.createTempDirectory("workspace-storage-");
        try {
            GitCloneWorkspaceSourceStorageService service = new GitCloneWorkspaceSourceStorageService(
                    storageRoot.toString(),
                    60,
                    ".git,node_modules,target,dist,build,.idea,.vscode"
            );

            assertThrows(
                    WorkspaceStorageException.class,
                    () -> service.cloneGithubRepository(7L, 99L, "file:///missing/repository.git")
            );
        } finally {
            deleteDirectory(storageRoot);
        }
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
                            // best effort cleanup for temp test folders
                        }
                    });
        }
    }

    private record ZipEntryData(String path, String content) {
    }
}
