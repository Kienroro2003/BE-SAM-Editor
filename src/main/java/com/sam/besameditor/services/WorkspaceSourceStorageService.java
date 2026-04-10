package com.sam.besameditor.services;

import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

public interface WorkspaceSourceStorageService {

    String cloneGithubRepository(Long userId, Long projectId, String repositoryUrl);

    String copyLocalFolder(Long userId, Long projectId, Path sourceFolder);

    String extractZipArchive(Long userId, Long projectId, MultipartFile zipFile);
}
