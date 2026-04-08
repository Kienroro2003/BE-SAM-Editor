package com.sam.besameditor.services;

import com.sam.besameditor.exceptions.WorkspaceStorageException;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;

@Service
public class GitCloneWorkspaceSourceStorageService implements WorkspaceSourceStorageService {

    private final Path storageRootPath;
    private final int cloneTimeoutSeconds;

    public GitCloneWorkspaceSourceStorageService(
            @Value("${app.workspace.storage-root:./workspace-storage}") String storageRoot,
            @Value("${app.workspace.clone-timeout-seconds:60}") int cloneTimeoutSeconds) {
        this.storageRootPath = Paths.get(storageRoot).toAbsolutePath().normalize();
        this.cloneTimeoutSeconds = cloneTimeoutSeconds;
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
                Path destination = targetDir.resolve(relative.toString());
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile()) {
                    Path relative = sourceDir.relativize(file);
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
}
