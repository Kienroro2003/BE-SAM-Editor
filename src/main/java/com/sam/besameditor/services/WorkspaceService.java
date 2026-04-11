package com.sam.besameditor.services;

import com.sam.besameditor.dto.DeleteWorkspaceFolderResponse;
import com.sam.besameditor.dto.DeleteWorkspaceResponse;
import com.sam.besameditor.dto.ImportGithubWorkspaceResponse;
import com.sam.besameditor.dto.WorkspaceFileContentResponse;
import com.sam.besameditor.dto.WorkspaceSummaryResponse;
import com.sam.besameditor.dto.WorkspaceTreeNodeResponse;
import com.sam.besameditor.dto.WorkspaceTreeResponse;
import com.sam.besameditor.exceptions.NotFoundException;
import com.sam.besameditor.exceptions.WorkspacePayloadTooLargeException;
import com.sam.besameditor.exceptions.WorkspaceStorageException;
import com.sam.besameditor.models.Project;
import com.sam.besameditor.models.ProjectSourceType;
import com.sam.besameditor.models.SourceFile;
import com.sam.besameditor.models.SourceFileStatus;
import com.sam.besameditor.models.User;
import com.sam.besameditor.repositories.AnalyzedFunctionRepository;
import com.sam.besameditor.repositories.FlowGraphDataRepository;
import com.sam.besameditor.repositories.ProjectRepository;
import com.sam.besameditor.repositories.SourceFileRepository;
import com.sam.besameditor.repositories.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
public class WorkspaceService {

    private static final Pattern GITHUB_REPO_URL_PATTERN =
            Pattern.compile("^https?://github\\.com/([^/]+)/([^/?#]+?)(?:\\.git)?/?(?:\\?.*)?(?:#.*)?$");

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final SourceFileRepository sourceFileRepository;
    private final AnalyzedFunctionRepository analyzedFunctionRepository;
    private final FlowGraphDataRepository flowGraphDataRepository;
    private final GithubRepositoryTreeClient githubRepositoryTreeClient;
    private final WorkspaceSourceStorageService workspaceSourceStorageService;
    private final CloudinaryWorkspaceStorageService cloudinaryWorkspaceStorageService;
    private final long maxSizeBytes;
    private final long fileContentMaxBytes;
    private final Set<String> blacklistedDirs;

    public WorkspaceService(
            UserRepository userRepository,
            ProjectRepository projectRepository,
            SourceFileRepository sourceFileRepository,
            AnalyzedFunctionRepository analyzedFunctionRepository,
            FlowGraphDataRepository flowGraphDataRepository,
            GithubRepositoryTreeClient githubRepositoryTreeClient,
            WorkspaceSourceStorageService workspaceSourceStorageService,
            CloudinaryWorkspaceStorageService cloudinaryWorkspaceStorageService,
            @Value("${app.workspace.max-size-bytes:15728640}") long maxSizeBytes,
            @Value("${app.workspace.file-content-max-bytes:1048576}") long fileContentMaxBytes,
            @Value("${app.workspace.blacklist-dirs:.git,node_modules,target,dist,build,.idea,.vscode}") String blacklistDirs) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.analyzedFunctionRepository = analyzedFunctionRepository;
        this.flowGraphDataRepository = flowGraphDataRepository;
        this.githubRepositoryTreeClient = githubRepositoryTreeClient;
        this.workspaceSourceStorageService = workspaceSourceStorageService;
        this.cloudinaryWorkspaceStorageService = cloudinaryWorkspaceStorageService;
        this.maxSizeBytes = maxSizeBytes;
        this.fileContentMaxBytes = fileContentMaxBytes;
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

        CloudinaryWorkspaceStorageService.CloudinaryUploadResult cloudinaryUploadResult =
                cloudinaryWorkspaceStorageService.uploadWorkspaceArchive(user.getId(), savedProject.getId(), Path.of(storagePath));
        if (cloudinaryUploadResult != null) {
            savedProject.setCloudinaryPublicId(cloudinaryUploadResult.publicId());
            savedProject.setCloudinaryUrl(cloudinaryUploadResult.secureUrl());
        }

        projectRepository.save(savedProject);

        persistSourceFiles(savedProject, snapshots);

        return new ImportGithubWorkspaceResponse(
                savedProject.getId(),
                savedProject.getName(),
                savedProject.getSourceUrl(),
                snapshots.size(),
                totalSize[0],
                savedProject.getCloudinaryUrl());
    }

    @Transactional
    public ImportGithubWorkspaceResponse importFromLocalFolder(String folderPath, String workspaceName, String userEmail) {
        User user = findUserByEmail(userEmail);
        LocalFolderDescriptor localFolderDescriptor = parseLocalFolder(folderPath, workspaceName);
        LocalImportResult localImportResult = collectFilesFromLocalFolder(localFolderDescriptor.path());

        Project project = new Project();
        project.setUser(user);
        project.setName(localFolderDescriptor.workspaceName());
        project.setSourceType(ProjectSourceType.LOCAL_FOLDER);
        project.setSourceUrl(localFolderDescriptor.sourceUrl());
        Project savedProject = projectRepository.save(project);

        String storagePath = workspaceSourceStorageService.copyLocalFolder(
                user.getId(),
                savedProject.getId(),
                localFolderDescriptor.path());
        savedProject.setStoragePath(storagePath);

        CloudinaryWorkspaceStorageService.CloudinaryUploadResult cloudinaryUploadResult =
                cloudinaryWorkspaceStorageService.uploadWorkspaceArchive(user.getId(), savedProject.getId(), Path.of(storagePath));
        if (cloudinaryUploadResult != null) {
            savedProject.setCloudinaryPublicId(cloudinaryUploadResult.publicId());
            savedProject.setCloudinaryUrl(cloudinaryUploadResult.secureUrl());
        }

        projectRepository.save(savedProject);

        persistSourceFiles(savedProject, localImportResult.snapshots());

        return new ImportGithubWorkspaceResponse(
                savedProject.getId(),
                savedProject.getName(),
                savedProject.getSourceUrl(),
                localImportResult.snapshots().size(),
                localImportResult.totalSizeBytes(),
                savedProject.getCloudinaryUrl());
    }

    @Transactional
    public ImportGithubWorkspaceResponse importFromZip(MultipartFile zipFile, String workspaceName, String userEmail) {
        User user = findUserByEmail(userEmail);
        ZipImportDescriptor zipImportDescriptor = parseZipUpload(zipFile, workspaceName);
        LocalImportResult localImportResult = collectFilesFromZipArchive(zipFile);

        Project project = new Project();
        project.setUser(user);
        project.setName(zipImportDescriptor.workspaceName());
        project.setSourceType(ProjectSourceType.LOCAL_FOLDER);
        project.setSourceUrl(zipImportDescriptor.sourceUrl());
        Project savedProject = projectRepository.save(project);

        String storagePath = workspaceSourceStorageService.extractZipArchive(
                user.getId(),
                savedProject.getId(),
                zipFile);
        savedProject.setStoragePath(storagePath);

        CloudinaryWorkspaceStorageService.CloudinaryUploadResult cloudinaryUploadResult =
                cloudinaryWorkspaceStorageService.uploadWorkspaceArchive(user.getId(), savedProject.getId(), Path.of(storagePath));
        if (cloudinaryUploadResult != null) {
            savedProject.setCloudinaryPublicId(cloudinaryUploadResult.publicId());
            savedProject.setCloudinaryUrl(cloudinaryUploadResult.secureUrl());
        }

        projectRepository.save(savedProject);

        persistSourceFiles(savedProject, localImportResult.snapshots());

        return new ImportGithubWorkspaceResponse(
                savedProject.getId(),
                savedProject.getName(),
                savedProject.getSourceUrl(),
                localImportResult.snapshots().size(),
                localImportResult.totalSizeBytes(),
                savedProject.getCloudinaryUrl());
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
                        project.getCloudinaryUrl(),
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

    @Transactional(readOnly = true)
    public WorkspaceFileContentResponse getWorkspaceFileContent(Long projectId, String path, String userEmail) {
        User user = findUserByEmail(userEmail);
        Project project = projectRepository.findByIdAndUser_Id(projectId, user.getId())
                .orElseThrow(() -> new NotFoundException("Workspace not found"));

        Path workspaceRoot = resolveWorkspaceRoot(project);
        Path relativeFilePath = resolveRelativeFilePath(path);
        Path targetFile = workspaceRoot.resolve(relativeFilePath).normalize();
        if (!targetFile.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
            throw new NotFoundException("File not found in workspace");
        }

        byte[] fileBytes = readWorkspaceFile(targetFile);
        String normalizedPath = normalizePath(relativeFilePath.toString());
        String content = decodeUtf8Text(fileBytes);

        return new WorkspaceFileContentResponse(
                project.getId(),
                normalizedPath,
                detectLanguage(normalizedPath),
                content,
                fileBytes.length);
    }

    @Transactional
    public DeleteWorkspaceFolderResponse deleteWorkspaceFolder(Long projectId, String path, String userEmail) {
        User user = findUserByEmail(userEmail);
        Project project = projectRepository.findByIdAndUser_Id(projectId, user.getId())
                .orElseThrow(() -> new NotFoundException("Workspace not found"));

        Path workspaceRoot = resolveWorkspaceRoot(project);
        Path relativeFolderPath = resolveRelativeFolderPath(path);
        Path targetFolder = workspaceRoot.resolve(relativeFolderPath).normalize();

        if (!targetFolder.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("Invalid folder path");
        }
        if (!Files.exists(targetFolder) || !Files.isDirectory(targetFolder)) {
            throw new NotFoundException("Folder not found in workspace");
        }

        deleteDirectoryRecursively(targetFolder);

        String normalizedFolderPath = normalizePath(relativeFolderPath.toString());
        String folderPrefix = normalizedFolderPath + "/";

        analyzedFunctionRepository.deleteBySourceFile_Project_IdAndSourceFile_FilePath(projectId, normalizedFolderPath);
        analyzedFunctionRepository.deleteBySourceFile_Project_IdAndSourceFile_FilePathStartingWith(projectId, folderPrefix);

        long deletedFiles = sourceFileRepository.deleteByProject_IdAndFilePath(projectId, normalizedFolderPath);
        deletedFiles += sourceFileRepository.deleteByProject_IdAndFilePathStartingWith(projectId, folderPrefix);

        return new DeleteWorkspaceFolderResponse(
                projectId,
                normalizedFolderPath,
                Math.toIntExact(deletedFiles),
                "Folder deleted successfully.");
    }

    @Transactional
    public DeleteWorkspaceResponse deleteWorkspace(Long projectId, String userEmail) {
        User user = findUserByEmail(userEmail);
        Project project = projectRepository.findByIdAndUser_Id(projectId, user.getId())
                .orElseThrow(() -> new NotFoundException("Workspace not found"));

        Path workspaceRoot = resolveWorkspaceRootForDelete(project);
        long deletedFileCount = sourceFileRepository.countByProject_Id(projectId);
        analyzedFunctionRepository.deleteBySourceFile_Project_Id(projectId);
        sourceFileRepository.deleteByProject_Id(projectId);

        if (workspaceRoot != null && Files.exists(workspaceRoot)) {
            deleteDirectoryRecursively(workspaceRoot);
        }

        cloudinaryWorkspaceStorageService.deleteWorkspaceArchive(project.getCloudinaryPublicId());
        projectRepository.delete(project);

        return new DeleteWorkspaceResponse(projectId, Math.toIntExact(deletedFileCount), "Workspace deleted successfully.");
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
                    continue;
                }
                collectFilesRecursive(owner, repo, normalizedPath, snapshots, totalSize);
                continue;
            }
            if (!"file".equals(type)) {
                continue;
            }

            if (containsBlacklistedDir(normalizedPath)) {
                continue;
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

    private LocalImportResult collectFilesFromLocalFolder(Path rootFolder) {
        List<SourceFileSnapshot> snapshots = new ArrayList<>();
        long[] totalSize = new long[]{0L};

        try {
            Files.walkFileTree(rootFolder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(rootFolder)) {
                        return FileVisitResult.CONTINUE;
                    }

                    String relativeDir = normalizePath(rootFolder.relativize(dir).toString());
                    if (containsBlacklistedDir(relativeDir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }

                    String relativeFile = normalizePath(rootFolder.relativize(file).toString());
                    if (relativeFile.isBlank()) {
                        return FileVisitResult.CONTINUE;
                    }

                    if (containsBlacklistedDir(relativeFile)) {
                        return FileVisitResult.CONTINUE;
                    }

                    long fileSize = Math.max(attrs.size(), 0L);
                    long nextSize = totalSize[0] + fileSize;
                    if (nextSize > maxSizeBytes) {
                        throw new WorkspacePayloadTooLargeException(
                                "Workspace import rejected: repository size exceeds limit of " + maxSizeBytes + " bytes.");
                    }
                    totalSize[0] = nextSize;
                    snapshots.add(new SourceFileSnapshot(relativeFile, detectLanguage(relativeFile)));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (WorkspacePayloadTooLargeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read source folder at path '" + rootFolder + "'.", ex);
        }

        return new LocalImportResult(snapshots, totalSize[0]);
    }

    private LocalImportResult collectFilesFromZipArchive(MultipartFile zipFile) {
        List<SourceFileSnapshot> snapshots = new ArrayList<>();
        long[] totalSize = new long[]{0L};
        Path tempZipFile = null;
        try {
            tempZipFile = Files.createTempFile("workspace-upload-", ".zip");
            try (InputStream uploadStream = zipFile.getInputStream()) {
                Files.copy(uploadStream, tempZipFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            try (ZipFile parsedZip = new ZipFile(tempZipFile.toFile())) {
                Enumeration<? extends ZipEntry> entries = parsedZip.entries();
                byte[] buffer = new byte[8192];
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String normalizedPath = normalizeArchiveEntryName(entry.getName());
                    if (normalizedPath.isBlank()) {
                        continue;
                    }

                    Path relativePath = Path.of(normalizedPath).normalize();
                    if (relativePath.getNameCount() == 0) {
                        continue;
                    }
                    if (relativePath.isAbsolute() || startsWithParentTraversal(relativePath)) {
                        throw new IllegalArgumentException("ZIP contains invalid entry path: '" + normalizedPath + "'.");
                    }

                    if (containsBlacklistedDir(normalizedPath)) {
                        continue;
                    }

                    if (entry.isDirectory()) {
                        continue;
                    }

                    long entrySize = 0L;
                    try (InputStream entryInputStream = parsedZip.getInputStream(entry)) {
                        int read;
                        while ((read = entryInputStream.read(buffer)) != -1) {
                            entrySize += read;
                            long nextSize = totalSize[0] + read;
                            if (nextSize > maxSizeBytes) {
                                throw new WorkspacePayloadTooLargeException(
                                        "Workspace import rejected: repository size exceeds limit of " + maxSizeBytes + " bytes.");
                            }
                            totalSize[0] = nextSize;
                        }
                    }

                    if (entrySize > 0 || !normalizedPath.isBlank()) {
                        snapshots.add(new SourceFileSnapshot(normalizedPath, detectLanguage(normalizedPath)));
                    }
                }
            }
        } catch (WorkspacePayloadTooLargeException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (IOException ex) {
            String detail = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "Unknown ZIP parsing error"
                    : ex.getMessage();
            throw new IllegalArgumentException("Unable to read uploaded ZIP file: " + detail, ex);
        } finally {
            deleteTempFileIfExists(tempZipFile);
        }

        return new LocalImportResult(snapshots, totalSize[0]);
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

    private void persistSourceFiles(Project project, List<SourceFileSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        List<SourceFile> files = snapshots.stream()
                .map(snapshot -> {
                    SourceFile sourceFile = new SourceFile();
                    sourceFile.setProject(project);
                    sourceFile.setFilePath(snapshot.filePath());
                    sourceFile.setLanguage(snapshot.language());
                    sourceFile.setStatus(SourceFileStatus.AVAILABLE);
                    return sourceFile;
                })
                .toList();
        sourceFileRepository.saveAll(files);
    }

    private LocalFolderDescriptor parseLocalFolder(String rawPath, String rawWorkspaceName) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("folderPath is required");
        }

        Path path;
        try {
            path = Paths.get(rawPath.trim()).toAbsolutePath().normalize();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid folderPath");
        }

        if (!Files.exists(path)) {
            throw new IllegalArgumentException("folderPath does not exist");
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("folderPath must point to a directory");
        }

        String workspaceName = (rawWorkspaceName == null ? "" : rawWorkspaceName.trim());
        if (workspaceName.isBlank()) {
            Path folderName = path.getFileName();
            workspaceName = folderName == null ? "local-workspace" : folderName.toString();
        }

        return new LocalFolderDescriptor(path, workspaceName, path.toUri().toString());
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

    private ZipImportDescriptor parseZipUpload(MultipartFile zipFile, String rawWorkspaceName) {
        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }

        String originalFilename = zipFile.getOriginalFilename();
        String normalizedFilename = normalizeUploadedFilename(originalFilename);
        if (!normalizedFilename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IllegalArgumentException("Only .zip files are supported");
        }

        String workspaceName = rawWorkspaceName == null ? "" : rawWorkspaceName.trim();
        if (workspaceName.length() > 255) {
            throw new IllegalArgumentException("workspaceName must be at most 255 characters");
        }
        if (workspaceName.isBlank()) {
            workspaceName = normalizedFilename.substring(0, normalizedFilename.length() - 4).trim();
            if (workspaceName.isBlank()) {
                workspaceName = "uploaded-workspace";
            }
        }

        String sourceUrl = "upload://" + normalizedFilename;
        return new ZipImportDescriptor(workspaceName, sourceUrl);
    }

    private Path resolveWorkspaceRoot(Project project) {
        String rawStoragePath = project.getStoragePath();
        if (rawStoragePath == null || rawStoragePath.isBlank()) {
            throw new NotFoundException("Workspace source not found on server");
        }

        Path workspaceRoot;
        try {
            workspaceRoot = Path.of(rawStoragePath).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Workspace source path is invalid", ex);
        }

        if ((!Files.exists(workspaceRoot) || !Files.isDirectory(workspaceRoot))
                && cloudinaryWorkspaceStorageService.isEnabled()
                && hasCloudinaryArchive(project)) {
            cloudinaryWorkspaceStorageService.restoreWorkspaceArchive(
                    workspaceRoot,
                    project.getCloudinaryPublicId(),
                    project.getCloudinaryUrl());
        }

        if (!Files.exists(workspaceRoot) || !Files.isDirectory(workspaceRoot)) {
            throw new NotFoundException("Workspace source not found on server");
        }
        return workspaceRoot;
    }

    private Path resolveWorkspaceRootForDelete(Project project) {
        String rawStoragePath = project.getStoragePath();
        if (rawStoragePath == null || rawStoragePath.isBlank()) {
            return null;
        }

        Path workspaceRoot;
        try {
            workspaceRoot = Path.of(rawStoragePath).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Workspace source path is invalid", ex);
        }

        String expectedProjectDirectoryName = "project-" + project.getId();
        Path lastSegment = workspaceRoot.getFileName();
        if (lastSegment == null || !expectedProjectDirectoryName.equals(lastSegment.toString())) {
            throw new IllegalArgumentException("Workspace source path is invalid");
        }

        return workspaceRoot;
    }

    private Path resolveRelativeFilePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }

        String trimmedPath = rawPath.trim();
        if (trimmedPath.startsWith("/") || trimmedPath.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid file path");
        }

        String slashNormalizedPath = trimmedPath.replace("\\", "/");
        if (slashNormalizedPath.matches("^[a-zA-Z]:/.*")) {
            throw new IllegalArgumentException("Invalid file path");
        }

        String normalizedPath = normalizePath(slashNormalizedPath);
        if (normalizedPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }

        Path relativePath;
        try {
            relativePath = Path.of(normalizedPath).normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Invalid file path", ex);
        }

        if (relativePath.isAbsolute()
                || relativePath.getNameCount() == 0
                || startsWithParentTraversal(relativePath)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        return relativePath;
    }

    private Path resolveRelativeFolderPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }

        String trimmedPath = rawPath.trim();
        if (trimmedPath.startsWith("/") || trimmedPath.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid folder path");
        }

        String slashNormalizedPath = trimmedPath.replace("\\", "/");
        if (slashNormalizedPath.matches("^[a-zA-Z]:/.*")) {
            throw new IllegalArgumentException("Invalid folder path");
        }

        String normalizedPath = normalizePath(slashNormalizedPath);
        if (normalizedPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }

        Path relativePath;
        try {
            relativePath = Path.of(normalizedPath).normalize();
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException("Invalid folder path", ex);
        }

        if (relativePath.isAbsolute()
                || relativePath.getNameCount() == 0
                || startsWithParentTraversal(relativePath)) {
            throw new IllegalArgumentException("Invalid folder path");
        }
        return relativePath;
    }

    private byte[] readWorkspaceFile(Path targetFile) {
        long fileSize;
        try {
            fileSize = Files.size(targetFile);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read workspace file", ex);
        }

        if (fileSize > fileContentMaxBytes) {
            throw new WorkspacePayloadTooLargeException(
                    "Workspace file content exceeds limit of " + fileContentMaxBytes + " bytes.");
        }

        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(targetFile);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read workspace file", ex);
        }

        if (fileBytes.length > fileContentMaxBytes) {
            throw new WorkspacePayloadTooLargeException(
                    "Workspace file content exceeds limit of " + fileContentMaxBytes + " bytes.");
        }
        if (!isTextContent(fileBytes)) {
            throw new IllegalArgumentException("Only text files are supported");
        }
        return fileBytes;
    }

    private String decodeUtf8Text(byte[] fileBytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(fileBytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Only UTF-8 text files are supported", ex);
        }
    }

    private boolean isTextContent(byte[] fileBytes) {
        if (fileBytes.length == 0) {
            return true;
        }
        int suspiciousControlChars = 0;
        int sampleLength = Math.min(fileBytes.length, 8192);
        for (int i = 0; i < sampleLength; i++) {
            int value = fileBytes[i] & 0xFF;
            if (value == 0) {
                return false;
            }
            boolean isControl = (value < 0x09) || (value > 0x0D && value < 0x20) || value == 0x7F;
            if (isControl) {
                suspiciousControlChars++;
            }
        }
        return suspiciousControlChars * 5 <= sampleLength;
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

    private String normalizeArchiveEntryName(String entryName) {
        return normalizePath(entryName);
    }

    private String normalizeUploadedFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "workspace.zip";
        }
        String normalized = filename.replace("\\", "/").trim();
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0) {
            normalized = normalized.substring(lastSlash + 1);
        }
        if (normalized.isBlank()) {
            return "workspace.zip";
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

    private boolean startsWithParentTraversal(Path path) {
        return path.getNameCount() > 0 && "..".equals(path.getName(0).toString());
    }

    private boolean hasCloudinaryArchive(Project project) {
        return (project.getCloudinaryPublicId() != null && !project.getCloudinaryPublicId().isBlank())
                || (project.getCloudinaryUrl() != null && !project.getCloudinaryUrl().isBlank());
    }

    private void deleteTempFileIfExists(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private void deleteDirectoryRecursively(Path folderPath) {
        try (var walk = Files.walk(folderPath)) {
            List<Path> sortedPaths = walk
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : sortedPaths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            throw new WorkspaceStorageException("Failed to delete workspace folder", ex);
        }
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

    private record LocalFolderDescriptor(Path path, String workspaceName, String sourceUrl) {
    }

    private record LocalImportResult(List<SourceFileSnapshot> snapshots, long totalSizeBytes) {
    }

    private record ZipImportDescriptor(String workspaceName, String sourceUrl) {
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
