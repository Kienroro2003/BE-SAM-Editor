package com.sam.besameditor.services;

import java.nio.file.Path;

public interface WorkspaceSourceStorageService {

    String cloneGithubRepository(Long userId, Long projectId, String repositoryUrl);

    String copyLocalFolder(Long userId, Long projectId, Path sourceFolder);
}
