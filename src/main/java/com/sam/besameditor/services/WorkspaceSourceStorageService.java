package com.sam.besameditor.services;

public interface WorkspaceSourceStorageService {

    String cloneGithubRepository(Long userId, Long projectId, String repositoryUrl);
}
