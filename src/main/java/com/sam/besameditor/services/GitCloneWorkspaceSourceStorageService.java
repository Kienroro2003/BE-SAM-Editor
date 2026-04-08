package com.sam.besameditor.services;

import com.sam.besameditor.exceptions.WorkspaceStorageException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class GitCloneWorkspaceSourceStorageService implements WorkspaceSourceStorageService {

    private final Path storageRootPath;
    private final int cloneTimeoutSeconds;
    private final Set<String> blacklistedPathSegments;

    public GitCloneWorkspaceSourceStorageService(
            @Value("${app.workspace.storage-root:./workspace-storage}") String storageRoot,
            @Value("${app.workspace.clone-timeout-seconds:60}") int cloneTimeoutSeconds,
            @Value("${app.workspace.blacklist-dirs:.git,node_modules,target,dist,build,.idea,.vscode}") String blacklistDirs) {
        this.storageRootPath = Paths.get(storageRoot).toAbsolutePath().normalize();
        this.cloneTimeoutSeconds = cloneTimeoutSeconds;
        this.blacklistedPathSegments = parseBlacklist(blacklistDirs);
    }

    @Override
    public String cloneGithubRepository(Long userId, Long projectId, String repositoryUrl) {
        Path targetDir = storageRootPath
                .resolve("user-" + userId)
                .resolve("project-" + projectId);

        try {
            Files.createDirectories(targetDir.getParent());
            deleteDirectoryIfExists(targetDir);

            Git.cloneRepository()
                    .setURI(repositoryUrl)
                    .setDirectory(targetDir.toFile())
                    .setCloneAllBranches(false)
                    .setDepth(1)
                    .setTimeout(cloneTimeoutSeconds)
                    .call()
                    .close();

            return targetDir.toString();
        } catch (GitAPIException | IOException ex) {
            try {
                deleteDirectoryIfExists(targetDir);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw new WorkspaceStorageException("Failed to clone repository", ex);
        }
    }

    @Override
    public String copyLocalFolder(Long userId, Long projectId, Path sourceFolder) {
        Path targetDir = storageRootPath
                .resolve("user-" + userId)
                .resolve("project-" + projectId);

        try {
            Files.createDirectories(targetDir.getParent());
            deleteDirectoryIfExists(targetDir);
            copyDirectory(sourceFolder, targetDir);
            return targetDir.toString();
        } catch (IOException ex) {
            try {
                deleteDirectoryIfExists(targetDir);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw new WorkspaceStorageException("Failed to copy local source folder", ex);
        }
    }

    @Override
    public String extractZipArchive(Long userId, Long projectId, MultipartFile zipFile) {
        Path targetDir = storageRootPath
                .resolve("user-" + userId)
                .resolve("project-" + projectId);

        try {
            Files.createDirectories(targetDir.getParent());
            deleteDirectoryIfExists(targetDir);
            Files.createDirectories(targetDir);
            unzipArchive(zipFile, targetDir);
            return targetDir.toString();
        } catch (IOException ex) {
            try {
                deleteDirectoryIfExists(targetDir);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw new WorkspaceStorageException("Failed to extract workspace zip archive", ex);
        }
    }

    private void deleteDirectoryIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw ex;
        }
    }

    private void copyDirectory(Path sourceDir, Path targetDir) throws IOException {
        Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = sourceDir.relativize(dir);
                if (!relative.toString().isBlank() && containsBlacklistedPathSegment(relative.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path destination = targetDir.resolve(relative.toString());
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Path relative = sourceDir.relativize(file);
                    if (containsBlacklistedPathSegment(relative.toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path destination = targetDir.resolve(relative.toString());
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Set<String> parseBlacklist(String blacklistDirs) {
        Set<String> parsed = new HashSet<>();
        if (blacklistDirs == null || blacklistDirs.isBlank()) {
            return parsed;
        }
        for (String token : blacklistDirs.split(",")) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                parsed.add(normalized);
            }
        }
        return parsed;
    }

    private boolean containsBlacklistedPathSegment(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalizedPath = path.replace("\\", "/");
        String[] segments = normalizedPath.split("/");
        for (String segment : segments) {
            String normalizedSegment = segment.trim().toLowerCase(Locale.ROOT);
            if (blacklistedPathSegments.contains(normalizedSegment)) {
                return true;
            }
        }
        return false;
    }

    private void unzipArchive(MultipartFile zipFile, Path targetDir) throws IOException {
        Path tempZipFile = Files.createTempFile("workspace-archive-", ".zip");
        try {
            try (InputStream uploadStream = zipFile.getInputStream()) {
                Files.copy(uploadStream, tempZipFile, StandardCopyOption.REPLACE_EXISTING);
            }

            try (ZipFile parsedZip = new ZipFile(tempZipFile.toFile())) {
                Enumeration<? extends ZipEntry> entries = parsedZip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String entryName = normalizeArchiveEntryName(entry.getName());
                    if (entryName.isBlank()) {
                        continue;
                    }

                    Path normalizedRelative = Paths.get(entryName).normalize();
                    if (normalizedRelative.isAbsolute() || startsWithParentTraversal(normalizedRelative)) {
                        throw new IOException("Zip entry contains invalid path");
                    }

                    if (containsBlacklistedPathSegment(normalizedRelative.toString())) {
                        continue;
                    }

                    Path destination = targetDir.resolve(normalizedRelative).normalize();
                    if (!destination.startsWith(targetDir)) {
                        throw new IOException("Zip entry is outside target directory");
                    }

                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                        continue;
                    }

                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (InputStream entryInputStream = parsedZip.getInputStream(entry)) {
                        Files.copy(entryInputStream, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } finally {
            try {
                Files.deleteIfExists(tempZipFile);
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
    }

    private String normalizeArchiveEntryName(String entryName) {
        if (entryName == null) {
            return "";
        }
        String normalized = entryName.replace("\\", "/").trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean startsWithParentTraversal(Path path) {
        return path.getNameCount() > 0 && "..".equals(path.getName(0).toString());
    }
}
