package com.sam.besameditor.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitCloneWorkspaceSourceStorageServicePrivateCoverageTest {

    @TempDir
    Path tempDir;

    @Test
    void privateHelpers_ShouldCoverBlacklistNormalizationAndTraversalChecks() {
        GitCloneWorkspaceSourceStorageService service = new GitCloneWorkspaceSourceStorageService(
                tempDir.toString(),
                30,
                ".git,node_modules,target"
        );

        @SuppressWarnings("unchecked")
        Set<String> empty = (Set<String>) invoke(service, "parseBlacklist", new Class[]{String.class}, new Object[]{null});
        assertTrue(empty.isEmpty());

        @SuppressWarnings("unchecked")
        Set<String> blacklist = (Set<String>) invoke(service, "parseBlacklist", new Class[]{String.class}, " .git , node_modules ,, target ");
        assertEquals(Set.of(".git", "node_modules", "target"), blacklist);

        assertFalse((Boolean) invoke(service, "containsBlacklistedPathSegment", new Class[]{String.class}, new Object[]{null}));
        assertFalse((Boolean) invoke(service, "containsBlacklistedPathSegment", new Class[]{String.class}, "src/main"));
        assertTrue((Boolean) invoke(service, "containsBlacklistedPathSegment", new Class[]{String.class}, "src\\node_modules\\lib"));

        assertEquals("", invoke(service, "normalizeArchiveEntryName", new Class[]{String.class}, new Object[]{null}));
        assertEquals("src/App.java", invoke(service, "normalizeArchiveEntryName", new Class[]{String.class}, " /src/App.java/ "));

        assertTrue((Boolean) invoke(service, "startsWithParentTraversal", new Class[]{Path.class}, Path.of("../outside")));
        assertFalse((Boolean) invoke(service, "startsWithParentTraversal", new Class[]{Path.class}, Path.of("inside")));
    }

    @Test
    void privateHelpers_ShouldCoverDeleteDirectoryAndUnzipBranches() throws IOException {
        GitCloneWorkspaceSourceStorageService service = new GitCloneWorkspaceSourceStorageService(
                tempDir.toString(),
                30,
                ".git,node_modules,target"
        );

        Path existingDir = Files.createDirectories(tempDir.resolve("existing"));
        Files.writeString(existingDir.resolve("file.txt"), "content", StandardCharsets.UTF_8);
        invoke(service, "deleteDirectoryIfExists", new Class[]{Path.class}, existingDir);
        assertFalse(Files.exists(existingDir));

        invoke(service, "deleteDirectoryIfExists", new Class[]{Path.class}, tempDir.resolve("missing"));

        Path unzipTarget = Files.createDirectories(tempDir.resolve("unzipped"));
        MockMultipartFile zipFile = createZipFile(List.of(
                new ZipEntryData("/src/App.java", "class App {}"),
                new ZipEntryData("target/ignored.txt", "skip"),
                new ZipEntryData("folder/", null)
        ));
        invoke(service, "unzipArchive", new Class[]{org.springframework.web.multipart.MultipartFile.class, Path.class}, zipFile, unzipTarget);
        assertTrue(Files.exists(unzipTarget.resolve("src/App.java")));
        assertFalse(Files.exists(unzipTarget.resolve("target")));
        assertTrue(Files.isDirectory(unzipTarget.resolve("folder")));
    }

    private MockMultipartFile createZipFile(java.util.List<ZipEntryData> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(output)) {
            for (ZipEntryData entryData : entries) {
                zipOutputStream.putNextEntry(new ZipEntry(entryData.path()));
                if (entryData.content() != null) {
                    zipOutputStream.write(entryData.content().getBytes(StandardCharsets.UTF_8));
                }
                zipOutputStream.closeEntry();
            }
        }
        return new MockMultipartFile("file", "workspace.zip", "application/zip", output.toByteArray());
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
