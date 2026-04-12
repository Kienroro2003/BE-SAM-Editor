package com.sam.besameditor.coverage;

import com.sam.besameditor.models.CoverageRunStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerJavaCoverageSandboxRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void run_ShouldPassDockerContextAndHostAndReturnCreateFailureDiagnostics() throws Exception {
        Path dockerHostFile = tempDir.resolve("docker-host.txt");
        Path dockerContextFile = tempDir.resolve("docker-context.txt");
        Path script = createFakeDockerScript("""
                printf '%s' "$DOCKER_HOST" > '__HOST_FILE__'
                printf '%s' "$DOCKER_CONTEXT" > '__CONTEXT_FILE__'
                if [ "$1" = "create" ]; then
                  echo "Error response from daemon: 404 page not found"
                  exit 1
                fi
                echo "unexpected command"
                exit 1
                """
                .replace("__HOST_FILE__", escapeForShell(dockerHostFile))
                .replace("__CONTEXT_FILE__", escapeForShell(dockerContextFile)));
        Path workspace = createWorkspace();

        DockerJavaCoverageSandboxRunner runner = new DockerJavaCoverageSandboxRunner(
                script.toString(),
                "unix:///Users/test/.docker/run/docker.sock",
                "desktop-linux",
                "maven:3.9.9-eclipse-temurin-17",
                "bridge",
                "",
                "be-sam-editor-maven-cache",
                tempDir.toString(),
                true,
                30,
                40000,
                "0.8.12");

        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.FAILED, result.status());
        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("404 page not found"));
        assertTrue(result.stderr().contains("create step failed"));
        assertTrue(result.stderr().contains("desktop-linux"));
        assertTrue(result.stderr().contains("unix:///Users/test/.docker/run/docker.sock"));
        assertEquals("unix:///Users/test/.docker/run/docker.sock", Files.readString(dockerHostFile));
        assertEquals("desktop-linux", Files.readString(dockerContextFile));
    }

    @Test
    void run_ShouldReturnStartFailureDiagnostics() throws Exception {
        Path script = createFakeDockerScript("""
                if [ "$1" = "create" ]; then
                  echo "container-123"
                  exit 0
                fi
                if [ "$1" = "start" ]; then
                  echo "Tests failed in sandbox"
                  exit 1
                fi
                if [ "$1" = "rm" ]; then
                  exit 0
                fi
                echo "unexpected command"
                exit 1
                """);
        Path workspace = createWorkspace();

        DockerJavaCoverageSandboxRunner runner = new DockerJavaCoverageSandboxRunner(
                script.toString(),
                "",
                "",
                "maven:3.9.9-eclipse-temurin-17",
                "bridge",
                "",
                "be-sam-editor-maven-cache",
                tempDir.toString(),
                true,
                30,
                40000,
                "0.8.12");

        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.FAILED, result.status());
        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("Tests failed in sandbox"));
        assertTrue(result.stderr().contains("start step failed"));
        assertTrue(result.stderr().contains("Sandbox tests failed before JaCoCo report collection"));
    }

    @Test
    void run_ShouldReturnReportPathWhenSandboxSucceeds() throws Exception {
        Path startArgFile = tempDir.resolve("start-arg.txt");
        Path createArgsFile = tempDir.resolve("create-args.txt");
        Path script = createFakeDockerScript("""
                if [ "$1" = "create" ]; then
                  printf '%s\n' "$@" > '__CREATE_ARGS_FILE__'
                  echo "Unable to find image 'maven:3.9.9-eclipse-temurin-17' locally"
                  echo "3.9.9-eclipse-temurin-17: Pulling from library/maven"
                  echo "Digest: sha256:test"
                  echo "Status: Downloaded newer image for maven:3.9.9-eclipse-temurin-17"
                  echo "container-123"
                  exit 0
                fi
                if [ "$1" = "start" ]; then
                  printf '%s' "$3" > '__START_ARG_FILE__'
                  if [ "$3" != "container-123" ]; then
                    echo "unexpected container id: $3"
                    exit 1
                  fi
                  echo "Tests run: 1, Failures: 0, Errors: 0"
                  exit 0
                fi
                if [ "$1" = "cp" ]; then
                  cat > "$3" <<'XML'
                <report name="demo">
                  <package name="com/example">
                    <sourcefile name="App.java">
                      <line nr="10" mi="0" ci="1" mb="0" cb="0"/>
                    </sourcefile>
                  </package>
                </report>
                XML
                  exit 0
                fi
                if [ "$1" = "rm" ]; then
                  exit 0
                fi
                echo "unexpected command"
                exit 1
                """
                .replace("__START_ARG_FILE__", escapeForShell(startArgFile))
                .replace("__CREATE_ARGS_FILE__", escapeForShell(createArgsFile)));
        Path workspace = createWorkspace();
        createRelatedTestFiles(workspace, "WorkspaceServiceTest", "WorkspaceServiceCoverageTest", "WorkspaceServicePrivateCoverageTest");

        DockerJavaCoverageSandboxRunner runner = new DockerJavaCoverageSandboxRunner(
                script.toString(),
                "",
                "",
                "maven:3.9.9-eclipse-temurin-17",
                "bridge",
                "",
                "be-sam-editor-maven-cache",
                tempDir.toString(),
                true,
                30,
                40000,
                "0.8.12");

        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/WorkspaceService.java");

        assertEquals(CoverageRunStatus.SUCCEEDED, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Tests run: 1"));
        assertEquals("", result.stderr());
        assertNotNull(result.reportPath());
        assertTrue(Files.exists(result.reportPath()));
        assertEquals("container-123", Files.readString(startArgFile));
        assertTrue(Files.readString(createArgsFile).contains("-Dtest=WorkspaceServiceCoverageTest,WorkspaceServicePrivateCoverageTest,WorkspaceServiceTest"));
        assertTrue(Files.readString(result.reportPath()).contains("<report name=\"demo\">"));
    }

    @Test
    void run_ShouldFallbackToFullSuite_WhenNoRelatedTestsExist() throws Exception {
        Path createArgsFile = tempDir.resolve("create-args-no-related.txt");
        Path script = createFakeDockerScript("""
                if [ "$1" = "create" ]; then
                  printf '%s\n' "$@" > '__CREATE_ARGS_FILE__'
                  echo "container-123"
                  exit 0
                fi
                if [ "$1" = "start" ]; then
                  echo "Tests run: 1, Failures: 0, Errors: 0"
                  exit 0
                fi
                if [ "$1" = "cp" ]; then
                  cat > "$3" <<'XML'
                <report name="demo">
                  <package name="com/example">
                    <sourcefile name="App.java">
                      <line nr="10" mi="0" ci="1" mb="0" cb="0"/>
                    </sourcefile>
                  </package>
                </report>
                XML
                  exit 0
                fi
                if [ "$1" = "rm" ]; then
                  exit 0
                fi
                exit 1
                """
                .replace("__CREATE_ARGS_FILE__", escapeForShell(createArgsFile)));
        Path workspace = createWorkspace();

        DockerJavaCoverageSandboxRunner runner = new DockerJavaCoverageSandboxRunner(
                script.toString(),
                "",
                "",
                "maven:3.9.9-eclipse-temurin-17",
                "bridge",
                "",
                "be-sam-editor-maven-cache",
                tempDir.toString(),
                true,
                30,
                40000,
                "0.8.12");

        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.SUCCEEDED, result.status());
        assertTrue(Files.readString(createArgsFile).contains("/root/.m2"));
        assertTrue(!Files.readString(createArgsFile).contains("-Dtest="));
    }

    private Path createWorkspace() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        return workspace;
    }

    private void createRelatedTestFiles(Path workspace, String... testClassNames) throws IOException {
        Path testDirectory = workspace.resolve("src/test/java/com/example");
        Files.createDirectories(testDirectory);
        for (String testClassName : testClassNames) {
            Files.writeString(
                    testDirectory.resolve(testClassName + ".java"),
                    "class " + testClassName + " {}",
                    StandardCharsets.UTF_8);
        }
    }

    private Path createFakeDockerScript(String body) throws IOException {
        Path script = tempDir.resolve("fake-docker.sh");
        Files.writeString(script, "#!/bin/sh\nset -eu\n" + body + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException ignored) {
            Set<PosixFilePermission> permissions = Set.of();
            if (!permissions.isEmpty()) {
                Files.setPosixFilePermissions(script, permissions);
            }
        }
        script.toFile().setExecutable(true);
        return script;
    }

    private String escapeForShell(Path path) {
        return path.toString().replace("'", "'\"'\"'");
    }
}
