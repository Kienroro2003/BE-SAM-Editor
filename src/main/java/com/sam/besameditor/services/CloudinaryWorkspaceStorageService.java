package com.sam.besameditor.services;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.sam.besameditor.exceptions.NotFoundException;
import com.sam.besameditor.exceptions.WorkspacePayloadTooLargeException;
import com.sam.besameditor.exceptions.WorkspaceStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class CloudinaryWorkspaceStorageService {

    private final boolean enabled;
    private final String targetFolder;
    private final Cloudinary cloudinary;

    public CloudinaryWorkspaceStorageService(
            @Value("${app.cloudinary.enabled:false}") boolean enabled,
            @Value("${app.cloudinary.cloud-name:}") String cloudName,
            @Value("${app.cloudinary.api-key:}") String apiKey,
            @Value("${app.cloudinary.api-secret:}") String apiSecret,
            @Value("${app.cloudinary.workspace-folder:sam-workspaces}") String targetFolder) {
        this.enabled = enabled;
        this.targetFolder = targetFolder;

        if (enabled) {
            if (cloudName.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
                throw new IllegalArgumentException("Cloudinary is enabled but credentials are missing");
            }
            this.cloudinary = new Cloudinary(ObjectUtils.asMap(
                    "cloud_name", cloudName,
                    "api_key", apiKey,
                    "api_secret", apiSecret));
        } else {
            this.cloudinary = null;
        }
    }

    public CloudinaryUploadResult uploadWorkspaceArchive(Long userId, Long projectId, Path workspaceRoot) {
        if (!enabled) {
            return null;
        }

        Path tempZip = null;
        try {
            tempZip = Files.createTempFile("workspace-cloudinary-", ".zip");
            zipDirectory(workspaceRoot, tempZip);

            String publicId = "user-" + userId + "/project-" + projectId + "-" + UUID.randomUUID();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = cloudinary.uploader().upload(
                    tempZip.toFile(),
                    ObjectUtils.asMap(
                            "resource_type", "raw",
                            "folder", targetFolder,
                            "public_id", publicId,
                            "overwrite", true));

            String uploadedPublicId = (String) response.get("public_id");
            String secureUrl = (String) response.get("secure_url");
            return new CloudinaryUploadResult(uploadedPublicId, secureUrl);
        } catch (Exception ex) {
            throw new WorkspaceStorageException("Failed to upload workspace archive to Cloudinary", ex);
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    public void deleteWorkspaceArchive(String publicId) {
        if (!enabled || publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            cloudinary.uploader().destroy(
                    publicId,
                    ObjectUtils.asMap("resource_type", "raw"));
        } catch (Exception ex) {
            throw new WorkspaceStorageException("Failed to delete workspace archive from Cloudinary", ex);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public CloudinaryUploadResult patchFileInArchive(
            Long userId,
            Long projectId,
            String currentPublicId,
            String currentCloudinaryUrl,
            String targetPath,
            String content) {
        if (!enabled) {
            throw new WorkspaceStorageException("Cloudinary storage is disabled", new IllegalStateException("disabled"));
        }

        String downloadUrl = resolveDownloadUrl(currentPublicId, currentCloudinaryUrl);
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new NotFoundException("Workspace source not found on cloud storage");
        }

        Path sourceZip = null;
        Path patchedZip = null;
        try {
            sourceZip = Files.createTempFile("workspace-cloud-source-", ".zip");
            patchedZip = Files.createTempFile("workspace-cloud-patched-", ".zip");
            downloadToTempFile(downloadUrl, sourceZip);

            String normalizedTargetPath = normalizeArchiveEntryName(targetPath);
            rewriteZipWithPatchedFile(sourceZip, patchedZip, normalizedTargetPath, content);

            String nextPublicId = "user-" + userId + "/project-" + projectId + "-" + UUID.randomUUID();
            @SuppressWarnings("unchecked")
            Map<String, Object> response = cloudinary.uploader().upload(
                    patchedZip.toFile(),
                    ObjectUtils.asMap(
                            "resource_type", "raw",
                            "folder", targetFolder,
                            "public_id", nextPublicId,
                            "overwrite", true));

            String uploadedPublicId = (String) response.get("public_id");
            String secureUrl = (String) response.get("secure_url");
            return new CloudinaryUploadResult(uploadedPublicId, secureUrl);
        } catch (IOException ex) {
            throw new WorkspaceStorageException("Failed to patch workspace archive", ex);
        } catch (Exception ex) {
            throw new WorkspaceStorageException("Failed to upload patched workspace archive", ex);
        } finally {
            if (sourceZip != null) {
                try {
                    Files.deleteIfExists(sourceZip);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
            if (patchedZip != null) {
                try {
                    Files.deleteIfExists(patchedZip);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    public byte[] readFileFromArchive(String publicId, String cloudinaryUrl, String filePath, long maxSizeBytes) {
        if (!enabled) {
            throw new WorkspaceStorageException("Cloudinary storage is disabled", new IllegalStateException("disabled"));
        }

        String downloadUrl = resolveDownloadUrl(publicId, cloudinaryUrl);
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new NotFoundException("Workspace source not found on cloud storage");
        }

        Path tempZip = null;
        try {
            tempZip = Files.createTempFile("workspace-cloud-read-", ".zip");
            downloadToTempFile(downloadUrl, tempZip);
            return extractFileContentFromZip(tempZip, normalizeArchiveEntryName(filePath), maxSizeBytes);
        } catch (IOException ex) {
            throw new WorkspaceStorageException("Failed to read workspace archive from Cloudinary", ex);
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    public void restoreWorkspaceArchive(Path targetDir, String publicId, String cloudinaryUrl) {
        if (!enabled) {
            throw new WorkspaceStorageException("Cloudinary restore is disabled", new IllegalStateException("disabled"));
        }

        String signedOrPublicUrl = resolveDownloadUrl(publicId, cloudinaryUrl);
        if (signedOrPublicUrl == null || signedOrPublicUrl.isBlank()) {
            throw new WorkspaceStorageException("Cloudinary archive reference is missing", new IllegalArgumentException("missing cloudinary reference"));
        }

        Path tempZip = null;
        try {
            Files.createDirectories(targetDir.getParent());
            deleteDirectoryIfExists(targetDir);
            Files.createDirectories(targetDir);

            tempZip = Files.createTempFile("workspace-restore-", ".zip");
            downloadToTempFile(signedOrPublicUrl, tempZip);
            unzipArchive(tempZip, targetDir);
        } catch (IOException ex) {
            throw new WorkspaceStorageException("Failed to restore workspace archive from Cloudinary", ex);
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
        }
    }

    private byte[] extractFileContentFromZip(Path zipFile, String targetPath, long maxSizeBytes) throws IOException {
        try (InputStream in = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(in)) {

            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = normalizeArchiveEntryName(entry.getName());
                if (entry.isDirectory() || !entryName.equals(targetPath)) {
                    zis.closeEntry();
                    continue;
                }

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                long totalRead = 0L;
                int read;
                while ((read = zis.read(buffer)) != -1) {
                    totalRead += read;
                    if (totalRead > maxSizeBytes) {
                        throw new WorkspacePayloadTooLargeException(
                                "Workspace file content exceeds limit of " + maxSizeBytes + " bytes.");
                    }
                    out.write(buffer, 0, read);
                }
                zis.closeEntry();
                return out.toByteArray();
            }
        }

        throw new NotFoundException("File not found in workspace");
    }

    private void rewriteZipWithPatchedFile(Path sourceZip, Path patchedZip, String targetPath, String content) throws IOException {
        boolean replaced = false;
        byte[] buffer = new byte[8192];
        byte[] replacement = content.getBytes(StandardCharsets.UTF_8);

        try (InputStream in = Files.newInputStream(sourceZip);
             ZipInputStream zis = new ZipInputStream(in);
             ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(patchedZip))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = normalizeArchiveEntryName(entry.getName());
                if (entryName.isBlank()) {
                    zis.closeEntry();
                    continue;
                }

                ZipEntry outputEntry = new ZipEntry(entryName);
                zos.putNextEntry(outputEntry);

                if (!entry.isDirectory()) {
                    if (entryName.equals(targetPath)) {
                        zos.write(replacement);
                        replaced = true;
                    } else {
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            zos.write(buffer, 0, read);
                        }
                    }
                }

                zos.closeEntry();
                zis.closeEntry();
            }

            if (!replaced) {
                throw new NotFoundException("File not found in workspace");
            }
        }
    }

    private String resolveDownloadUrl(String publicId, String cloudinaryUrl) {
        if (publicId != null && !publicId.isBlank()) {
            return cloudinary.url()
                    .resourceType("raw")
                    .secure(true)
                    .generate(publicId);
        }
        return cloudinaryUrl;
    }

    private void downloadToTempFile(String fileUrl, Path destination) throws IOException {
        URL url = URI.create(fileUrl).toURL();
        try (InputStream in = url.openStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void unzipArchive(Path zipFile, Path targetDir) throws IOException {
        try (InputStream in = Files.newInputStream(zipFile);
             ZipInputStream zis = new ZipInputStream(in)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = normalizeArchiveEntryName(entry.getName());
                if (entryName.isBlank()) {
                    zis.closeEntry();
                    continue;
                }

                Path relative = Path.of(entryName).normalize();
                if (relative.isAbsolute() || startsWithParentTraversal(relative)) {
                    throw new IOException("Zip entry contains invalid path");
                }

                Path destination = targetDir.resolve(relative).normalize();
                if (!destination.startsWith(targetDir)) {
                    throw new IOException("Zip entry is outside target directory");
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                    zis.closeEntry();
                    continue;
                }

                Path parent = destination.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(zis, destination, StandardCopyOption.REPLACE_EXISTING);
                zis.closeEntry();
            }
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

    private void zipDirectory(Path sourceDir, Path zipPath) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            Files.walk(sourceDir)
                    .filter(path -> !Files.isDirectory(path))
                    .forEach(path -> {
                        String relative = sourceDir.relativize(path).toString().replace("\\", "/");
                        try {
                            zos.putNextEntry(new ZipEntry(relative));
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        } catch (RuntimeException ex) {
            if (ex.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw ex;
        }
    }

    public record CloudinaryUploadResult(String publicId, String secureUrl) {
    }
}
