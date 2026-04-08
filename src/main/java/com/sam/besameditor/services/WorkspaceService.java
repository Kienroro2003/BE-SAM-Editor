package com.sam.besameditor.services;

import com.sam.besameditor.dto.ImportGithubWorkspaceResponse;
import com.sam.besameditor.dto.WorkspaceSummaryResponse;
import com.sam.besameditor.dto.WorkspaceTreeNodeResponse;
import com.sam.besameditor.dto.WorkspaceTreeResponse;
import com.sam.besameditor.exceptions.NotFoundException;
import com.sam.besameditor.exceptions.WorkspacePayloadTooLargeException;
import com.sam.besameditor.models.Project;
import com.sam.besameditor.models.ProjectSourceType;
import com.sam.besameditor.models.SourceFile;
import com.sam.besameditor.models.SourceFileStatus;
import com.sam.besameditor.models.User;
import com.sam.besameditor.repositories.ProjectRepository;
import com.sam.besameditor.repositories.SourceFileRepository;
import com.sam.besameditor.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WorkspaceService {

    private static final Pattern GITHUB_REPO_URL_PATTERN =
            Pattern.compile("^https?://github\\.com/([^/]+)/([^/?#]+?)(?:\\.git)?/?(?:\\?.*)?(?:#.*)?$");

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final GithubRepositoryTreeClient githubRepositoryTreeClient;
    private final WorkspaceSourceStorageService workspaceSourceStorageService;
    private final long maxSizeBytes;
    private final Set<String> blacklistedDirs;

    public WorkspaceService(
            UserRepository userRepository,
            ProjectRepository projectRepository,
            SourceFileRepository sourceFileRepository,
            GithubRepositoryTreeClient githubRepositoryTreeClient,
            WorkspaceSourceStorageService workspaceSourceStorageService,
            @Value("${app.workspace.max-size-bytes:15728640}") long maxSizeBytes,
            @Value("${app.workspace.blacklist-dirs:.git,node_modules,target,dist,build,.idea,.vscode}") String blacklistDirs) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.githubRepositoryTreeClient = githubRepositoryTreeClient;
        this.workspaceSourceStorageService = workspaceSourceStorageService;
        this.maxSizeBytes = maxSizeBytes;
        this.blacklistedDirs = parseBlacklist(blacklistDirs);
    }

    @Transactional
    public ImportGithubWorkspaceResponse importFromGithub(String repoUrl, String userEmail) {
        User user = findUserByEmail(userEmail);
        RepoDescriptor repoDescriptor = parseGithubRepoUrl(repoUrl);
        List<SourceFileSnapshot> snapshots = new ArrayList<>();
        long[] totalSize = new long[]{0L};

        collectFilesRecursive(repoDescriptor.owner(), repoDescriptor.repo(), "", snapshots, totalSize);

        Project project = new Project();
        project.setUser(user);
        project.setName(repoDescriptor.repo());
        project.setSourceType(ProjectSourceType.GITHUB);
        project.setSourceUrl(repoDescriptor.canonicalUrl());
        Project savedProject = projectRepository.save(project);

        String storagePath = workspaceSourceStorageService.cloneGithubRepository(
                user.getId(),
                savedProject.getId(),
                repoDescriptor.cloneUrl());
        savedProject.setStoragePath(storagePath);
        projectRepository.save(savedProject);

        if (!snapshots.isEmpty()) {
            List<SourceFile> files = snapshots.stream()
                    .map(snapshot -> {
                        SourceFile sourceFile = new SourceFile();
                        sourceFile.setProject(savedProject);
                        sourceFile.setFilePath(snapshot.filePath());
                        sourceFile.setLanguage(snapshot.language());
                        sourceFile.setStatus(SourceFileStatus.AVAILABLE);
                        return sourceFile;
                    })
                    .toList();
            sourceFileRepository.saveAll(files);
        }

        return new ImportGithubWorkspaceResponse(
                savedProject.getId(),
                savedProject.getName(),
                savedProject.getSourceUrl(),
                snapshots.size(),
                totalSize[0]);
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSummaryResponse> getUserWorkspaces(String userEmail) {
        User user = findUserByEmail(userEmail);
        return projectRepository.findByUser_IdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(project -> new WorkspaceSummaryResponse(
                        project.getId(),
                        project.getName(),
                        project.getSourceType(),
                        project.getSourceUrl(),
                        project.getCreatedAt(),
                        project.getUpdatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkspaceTreeResponse getWorkspaceTree(Long projectId, String userEmail) {
        User user = findUserByEmail(userEmail);
        Project project = projectRepository.findByIdAndUser_Id(projectId, user.getId())
                .orElseThrow(() -> new NotFoundException("Workspace not found"));

        List<SourceFile> files = sourceFileRepository.findByProject_IdOrderByFilePathAsc(projectId);
        List<WorkspaceTreeNodeResponse> nodes = buildTreeNodes(files);
        return new WorkspaceTreeResponse(project.getId(), project.getName(), nodes);
    }

    private void collectFilesRecursive(
            String owner,
            String repo,
            String path,
            List<SourceFileSnapshot> snapshots,
            long[] totalSize) {
        List<GithubRepositoryTreeClient.GithubContentItem> items =
                githubRepositoryTreeClient.listDirectory(owner, repo, path);

        for (GithubRepositoryTreeClient.GithubContentItem item : items) {
            String normalizedPath = normalizePath(item.path());
            if (normalizedPath.isBlank()) {
                continue;
            }

            String type = item.type() == null ? "" : item.type().toLowerCase(Locale.ROOT);
            if ("dir".equals(type)) {
                if (containsBlacklistedDir(normalizedPath)) {
                    throw new WorkspacePayloadTooLargeException(
                            "Workspace import rejected: repository contains blacklisted directory in path '" + normalizedPath + "'.");
                }
                collectFilesRecursive(owner, repo, normalizedPath, snapshots, totalSize);
                continue;
            }
            if (!"file".equals(type)) {
                continue;
            }

            String parentPath = extractParentPath(normalizedPath);
            if (!parentPath.isBlank() && containsBlacklistedDir(parentPath)) {
                throw new WorkspacePayloadTooLargeException(
                        "Workspace import rejected: repository contains blacklisted directory in path '" + normalizedPath + "'.");
            }

            long fileSize = item.size() == null ? 0L : Math.max(item.size(), 0L);
            long nextSize = totalSize[0] + fileSize;
            if (nextSize > maxSizeBytes) {
                throw new WorkspacePayloadTooLargeException(
                        "Workspace import rejected: repository size exceeds limit of " + maxSizeBytes + " bytes.");
            }
            totalSize[0] = nextSize;
            snapshots.add(new SourceFileSnapshot(normalizedPath, detectLanguage(normalizedPath)));
        }
    }

    private List<WorkspaceTreeNodeResponse> buildTreeNodes(List<SourceFile> sourceFiles) {
        MutableNode root = new MutableNode("", "", "folder", null);
        for (SourceFile sourceFile : sourceFiles) {
            upsertPath(root, sourceFile.getFilePath(), sourceFile.getLanguage());
        }
        return toTreeResponse(root.children.values());
    }

    private void upsertPath(MutableNode root, String filePath, String language) {
        String[] parts = normalizePath(filePath).split("/");
        MutableNode current = root;
        StringBuilder cumulativePath = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank()) {
                continue;
            }
            if (!cumulativePath.isEmpty()) {
                cumulativePath.append("/");
            }
            cumulativePath.append(part);

            boolean isFileNode = i == parts.length - 1;
            String nodePath = cumulativePath.toString();
            MutableNode next = current.children.computeIfAbsent(part,
                    ignored -> new MutableNode(
                            part,
                            nodePath,
                            isFileNode ? "file" : "folder",
                            isFileNode ? language : null));

            if (isFileNode) {
                next.type = "file";
                next.language = language;
            }
            current = next;
        }
    }

    private List<WorkspaceTreeNodeResponse> toTreeResponse(Collection<MutableNode> nodes) {
        List<MutableNode> ordered = new ArrayList<>(nodes);
        ordered.sort((a, b) -> {
            if (!a.type.equals(b.type)) {
                return "folder".equals(a.type) ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });

        return ordered.stream()
                .map(node -> new WorkspaceTreeNodeResponse(
                        node.name,
                        node.path,
                        node.type,
                        node.language,
                        "folder".equals(node.type) ? toTreeResponse(node.children.values()) : null))
                .toList();
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private RepoDescriptor parseGithubRepoUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl is required");
        }
        Matcher matcher = GITHUB_REPO_URL_PATTERN.matcher(rawUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid GitHub repository URL");
        }
        String owner = matcher.group(1);
        String repo = matcher.group(2);
        String canonicalUrl = "https://github.com/" + owner + "/" + repo;
        String cloneUrl = canonicalUrl + ".git";
        return new RepoDescriptor(owner, repo, canonicalUrl, cloneUrl);
    }

    private String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace("\\", "/").trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Set<String> parseBlacklist(String blacklistDirs) {
        Set<String> parsed = new HashSet<>();
        if (blacklistDirs == null || blacklistDirs.isBlank()) {
            return parsed;
        }
        String[] rawItems = blacklistDirs.split(",");
        for (String rawItem : rawItems) {
            String item = rawItem.trim().toLowerCase(Locale.ROOT);
            if (!item.isBlank()) {
                parsed.add(item);
            }
        }
        return parsed;
    }

    private String extractParentPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "";
        }
        return path.substring(0, lastSlash);
    }

    private boolean containsBlacklistedDir(String path) {
        String[] parts = normalizePath(path).split("/");
        for (String part : parts) {
            String normalized = part.trim().toLowerCase(Locale.ROOT);
            if (blacklistedDirs.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String detectLanguage(String filePath) {
        String lower = filePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "JAVA";
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "JAVASCRIPT";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "TYPESCRIPT";
        if (lower.endsWith(".py")) return "PYTHON";
        if (lower.endsWith(".json")) return "JSON";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "YAML";
        if (lower.endsWith(".xml")) return "XML";
        if (lower.endsWith(".md")) return "MARKDOWN";
        return "TEXT";
    }

    private record RepoDescriptor(String owner, String repo, String canonicalUrl, String cloneUrl) {
    }

    private record SourceFileSnapshot(String filePath, String language) {
    }

    private static class MutableNode {
        private final String name;
        private final String path;
        private String type;
        private String language;
        private final Map<String, MutableNode> children = new HashMap<>();

        private MutableNode(String name, String path, String type, String language) {
            this.name = name;
            this.path = path;
            this.type = type;
            this.language = language;
        }
    }
}
