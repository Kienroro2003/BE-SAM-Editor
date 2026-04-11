package com.sam.besameditor.services;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.sam.besameditor.exceptions.WorkspaceStorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
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
