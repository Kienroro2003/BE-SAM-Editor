package com.sam.besameditor.services;

import com.sam.besameditor.models.CloudinaryDeliveryType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudinaryWorkspaceStorageServicePrivateCoverageTest {

    @Test
    void resolveDownloadUrl_ShouldGenerateExpectedUrlForUploadAndPrivateDelivery() {
        CloudinaryWorkspaceStorageService service = new CloudinaryWorkspaceStorageService(
                true,
                "demo-cloud",
                "demo-key",
                "demo-secret",
                "sam-workspaces",
                300L
        );

        String uploadUrl = (String) invoke(
                service,
                "resolveDownloadUrl",
                new Class[]{String.class, String.class, CloudinaryDeliveryType.class},
                "sam-workspaces/user-1/project-10-archive",
                "https://res.cloudinary.com/demo-cloud/raw/upload/v1/sam-workspaces/user-1/project-10-archive.zip",
                CloudinaryDeliveryType.UPLOAD
        );
        assertTrue(uploadUrl.contains("/raw/download?"));

        String privateUrl = (String) invoke(
                service,
                "resolveDownloadUrl",
                new Class[]{String.class, String.class, CloudinaryDeliveryType.class},
                "sam-workspaces/user-1/project-10-archive",
                "https://res.cloudinary.com/demo-cloud/raw/upload/v1/sam-workspaces/user-1/project-10-archive.zip",
                CloudinaryDeliveryType.PRIVATE
        );
        assertTrue(privateUrl.contains("/raw/download?"));

        assertEquals(
                CloudinaryDeliveryType.UPLOAD,
                CloudinaryDeliveryType.resolve(null)
        );
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
