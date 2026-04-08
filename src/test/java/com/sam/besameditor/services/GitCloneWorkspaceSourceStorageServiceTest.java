package com.sam.besameditor.services;

import com.sam.besameditor.exceptions.WorkspaceStorageException;
import org.junit.jupiter.api.Test;
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
