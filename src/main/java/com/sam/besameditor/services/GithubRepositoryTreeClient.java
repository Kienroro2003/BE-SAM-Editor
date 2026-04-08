package com.sam.besameditor.services;

import java.util.List;

public interface GithubRepositoryTreeClient {

    List<GithubRepositoryTreeClient.GithubContentItem> listDirectory(String owner, String repo, String path);

    record GithubContentItem(String path, String type, Long size) {
    }
}
