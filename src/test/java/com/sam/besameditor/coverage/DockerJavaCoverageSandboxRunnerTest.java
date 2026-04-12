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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerJavaCoverageSandboxRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void detectBuildTool_ShouldReturnGradle_WhenBuildGradleExists() throws Exception {
        Path workspace = createGradleWorkspace(false);
        DockerJavaCoverageSandboxRunner runner = createRunner("docker");

        assertEquals(DockerJavaCoverageSandboxRunner.BuildTool.GRADLE, runner.detectBuildTool(workspace));
    }

    @Test
    void detectBuildTool_ShouldThrow_WhenNeitherMavenNorGradleBuildExists() throws Exception {
        Path workspace = tempDir.resolve("plain-java-workspace");
        Files.createDirectories(workspace);
        DockerJavaCoverageSandboxRunner runner = createRunner("docker");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> runner.run(workspace, "src/main/java/com/example/App.java"));

        assertTrue(exception.getMessage().contains("Maven or Gradle"));
    }

    @Test
    void buildContainerScript_ShouldInjectGradleJacocoAndFallbackToGradleBinary() {
        DockerJavaCoverageSandboxRunner runner = createRunner("docker");

        String script = runner.buildContainerScript(
                "/mounted-workspace",
                DockerJavaCoverageSandboxRunner.BuildTool.GRADLE,
                new DockerJavaCoverageSandboxRunner.TestSelection(Set.of("WorkspaceServiceTest").stream().toList(), "related-tests [WorkspaceServiceTest]"));

        assertTrue(script.contains("cat > '/sandbox/project/.sam-jacoco.init.gradle' <<'GRADLE_INIT'"));
        assertTrue(script.contains("toolVersion = '0.8.12'"));
        assertTrue(script.contains("if [ -f ./gradlew ]; then ./gradlew"));
        assertTrue(script.contains("else gradle --no-daemon -q --console=plain -I /sandbox/project/.sam-jacoco.init.gradle test --tests WorkspaceServiceTest jacocoTestReport; fi"));
    }

    @Test
    void hasRecognizedTests_ShouldReturnTrue_WhenTestsSuffixExists() throws Exception {
        Path workspace = createWorkspace();
        createTestFile(workspace, "src/test/java/com/example/AppTests.java");

        DockerJavaCoverageSandboxRunner runner = createRunner("docker");

        assertTrue(runner.hasRecognizedTests(workspace));
    }

    @Test
    void runMaven_ShouldReturnNoTestsFound_WhenTestDirectoryIsMissing() throws Exception {
        Path dockerInvocationFile = tempDir.resolve("maven-no-tests-docker-invoked.txt");
        Path script = createFakeDockerScript("""
                printf 'invoked' > '__DOCKER_INVOCATION_FILE__'
                exit 1
                """.replace("__DOCKER_INVOCATION_FILE__", escapeForShell(dockerInvocationFile)));
        Path workspace = createWorkspace();

        DockerJavaCoverageSandboxRunner runner = createRunner(script.toString());
        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.NO_TESTS_FOUND, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.command().startsWith("./mvnw or mvn"));
        assertTrue(result.stderr().contains("No recognized Java test files"));
        assertFalse(Files.exists(dockerInvocationFile));
    }

    @Test
    void runGradle_ShouldReturnNoTestsFound_WhenTestDirectoryIsMissing() throws Exception {
        Path dockerInvocationFile = tempDir.resolve("gradle-no-tests-docker-invoked.txt");
        Path script = createFakeDockerScript("""
                printf 'invoked' > '__DOCKER_INVOCATION_FILE__'
                exit 1
                """.replace("__DOCKER_INVOCATION_FILE__", escapeForShell(dockerInvocationFile)));
        Path workspace = createGradleWorkspace(false);

        DockerJavaCoverageSandboxRunner runner = createRunner(script.toString());
        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.NO_TESTS_FOUND, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(result.command().startsWith("./gradlew or gradle"));
        assertTrue(result.stderr().contains("No recognized Java test files"));
        assertFalse(Files.exists(dockerInvocationFile));
    }

    @Test
    void runMaven_ShouldReturnNoTestsFound_WhenTestDirectoryIsEmpty() throws Exception {
        Path dockerInvocationFile = tempDir.resolve("maven-empty-tests-docker-invoked.txt");
        Path script = createFakeDockerScript("""
                printf 'invoked' > '__DOCKER_INVOCATION_FILE__'
                exit 1
                """.replace("__DOCKER_INVOCATION_FILE__", escapeForShell(dockerInvocationFile)));
        Path workspace = createWorkspace();
        Files.createDirectories(workspace.resolve("src/test/java"));

        DockerJavaCoverageSandboxRunner runner = createRunner(script.toString());
        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.NO_TESTS_FOUND, result.status());
        assertEquals(0, result.exitCode());
        assertFalse(Files.exists(dockerInvocationFile));
    }

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
        createRelatedTestFiles(workspace, "OtherTest");

        DockerJavaCoverageSandboxRunner runner = new DockerJavaCoverageSandboxRunner(
                script.toString(),
                "unix:///Users/test/.docker/run/docker.sock",
                "desktop-linux",
                "maven:3.9.9-eclipse-temurin-17",
                "gradle:8-jdk17",
                "bridge",
                "",
                "be-sam-editor-maven-cache",
                "be-sam-editor-gradle-cache",
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
        createRelatedTestFiles(workspace, "OtherTest");

        DockerJavaCoverageSandboxRunner runner = new DockerJavaCoverageSandboxRunner(
                script.toString(),
                "",
                "",
                "maven:3.9.9-eclipse-temurin-17",
                "gradle:8-jdk17",
                "bridge",
                "",
                "be-sam-editor-maven-cache",
                "be-sam-editor-gradle-cache",
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
                "gradle:8-jdk17",
                "bridge",
                "",
                "be-sam-editor-maven-cache",
                "be-sam-editor-gradle-cache",
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
        createRelatedTestFiles(workspace, "OtherTest");

        DockerJavaCoverageSandboxRunner runner = new DockerJavaCoverageSandboxRunner(
                script.toString(),
                "",
                "",
                "maven:3.9.9-eclipse-temurin-17",
                "gradle:8-jdk17",
                "bridge",
                "",
                "be-sam-editor-maven-cache",
                "be-sam-editor-gradle-cache",
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

    @Test
    void runGradle_ShouldReturnCreateFailureDiagnostics() throws Exception {
        Path script = createFakeDockerScript("""
                if [ "$1" = "create" ]; then
                  echo "Gradle image missing"
                  exit 1
                fi
                exit 1
                """);
        Path workspace = createGradleWorkspace(false);
        createRelatedTestFiles(workspace, "OtherTest");

        DockerJavaCoverageSandboxRunner runner = createRunner(script.toString());
        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.FAILED, result.status());
        assertTrue(result.command().startsWith("./gradlew or gradle"));
        assertTrue(result.stdout().contains("Gradle image missing"));
        assertTrue(result.stderr().contains("create step failed"));
    }

    @Test
    void runGradle_ShouldReturnTestFailureDiagnostics_WhenGradleTestsFail() throws Exception {
        Path script = createFakeDockerScript("""
                if [ "$1" = "create" ]; then
                  echo "container-123"
                  exit 0
                fi
                if [ "$1" = "start" ]; then
                  cat <<'OUT'
                Note: Some input files use or override a deprecated API.
                Note: Recompile with -Xlint:deprecation for details.

                1 test completed, 1 failed

                FAILURE: Build failed with an exception.

                * What went wrong:
                Execution failed for task ':test'.
                > There were failing tests. See the report at: file:///sandbox/project/build/reports/tests/test/index.html
                OUT
                  exit 1
                fi
                if [ "$1" = "rm" ]; then
                  exit 0
                fi
                exit 1
                """);
        Path workspace = createGradleWorkspace(false);
        createRelatedTestFiles(workspace, "OtherTest");

        DockerJavaCoverageSandboxRunner runner = createRunner(script.toString());
        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.FAILED, result.status());
        assertEquals(1, result.exitCode());
        assertTrue(result.stdout().contains("1 test completed, 1 failed"));
        assertTrue(result.stderr().contains("Coverage sandbox test execution failed"));
        assertTrue(result.stderr().contains("Gradle tests failed inside sandbox before JaCoCo report collection."));
        assertTrue(result.stderr().contains("build/reports/tests/test/index.html"));
        assertTrue(!result.stderr().contains("Docker sandbox start step failed"));
    }

    @Test
    void runGradle_ShouldPullImageBeforeCreate_WhenImageIsMissing() throws Exception {
        Path commandLog = tempDir.resolve("gradle-image-pull-command-log.txt");
        Path script = createFakeDockerScriptWithSetup(
                """
                if [ "$1" = "image" ] && [ "${2:-}" = "inspect" ]; then
                  printf 'inspect %s\\n' "$3" >> '__COMMAND_LOG__'
                  exit 1
                fi
                if [ "$1" = "pull" ]; then
                  printf 'pull %s\\n' "$2" >> '__COMMAND_LOG__'
                  echo "Status: Downloaded newer image for $2"
                  exit 0
                fi
                """.replace("__COMMAND_LOG__", escapeForShell(commandLog)),
                """
                if [ "$1" = "create" ]; then
                  printf 'create\\n' >> '__COMMAND_LOG__'
                  echo "container-123"
                  exit 0
                fi
                if [ "$1" = "start" ]; then
                  echo "BUILD SUCCESSFUL"
                  exit 0
                fi
                if [ "$1" = "cp" ]; then
                  cat > "$3" <<'XML'
                <report name="demo">
                  <package name="com/example">
                    <sourcefile name="WorkspaceService.java">
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
                """.replace("__COMMAND_LOG__", escapeForShell(commandLog)));
        Path workspace = createGradleWorkspace(false);
        createRelatedTestFiles(workspace, "OtherTest");

        DockerJavaCoverageSandboxRunner runner = createRunner(script.toString());
        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.SUCCEEDED, result.status());
        String commandSequence = Files.readString(commandLog);
        assertTrue(commandSequence.contains("inspect gradle:8-jdk17"));
        assertTrue(commandSequence.contains("pull gradle:8-jdk17"));
        assertTrue(commandSequence.contains("create"));
        assertTrue(commandSequence.indexOf("inspect gradle:8-jdk17") < commandSequence.indexOf("pull gradle:8-jdk17"));
        assertTrue(commandSequence.indexOf("pull gradle:8-jdk17") < commandSequence.indexOf("create"));
    }

    @Test
    void runGradle_ShouldReturnReportPathWhenSandboxSucceeds() throws Exception {
        Path createArgsFile = tempDir.resolve("gradle-create-args.txt");
        Path copySourceFile = tempDir.resolve("gradle-copy-source.txt");
        Path script = createFakeDockerScript("""
                if [ "$1" = "create" ]; then
                  printf '%s\n' "$@" > '__CREATE_ARGS_FILE__'
                  echo "container-123"
                  exit 0
                fi
                if [ "$1" = "start" ]; then
                  echo "BUILD SUCCESSFUL"
                  exit 0
                fi
                if [ "$1" = "cp" ]; then
                  printf '%s' "$2" > '__COPY_SOURCE_FILE__'
                  cat > "$3" <<'XML'
                <report name="demo">
                  <package name="com/example">
                    <sourcefile name="WorkspaceService.java">
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
                .replace("__CREATE_ARGS_FILE__", escapeForShell(createArgsFile))
                .replace("__COPY_SOURCE_FILE__", escapeForShell(copySourceFile)));
        Path workspace = createGradleWorkspace(true);
        createRelatedTestFiles(workspace, "WorkspaceServiceCoverageTest", "WorkspaceServicePrivateCoverageTest", "WorkspaceServiceTest");

        DockerJavaCoverageSandboxRunner runner = createRunner(script.toString());
        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/WorkspaceService.java");

        assertEquals(CoverageRunStatus.SUCCEEDED, result.status());
        assertEquals(0, result.exitCode());
        assertTrue(Files.readString(createArgsFile).contains("gradle:8-jdk17"));
        assertTrue(Files.readString(createArgsFile).contains("/home/gradle/.gradle"));
        assertTrue(Files.readString(createArgsFile).contains("--tests WorkspaceServiceCoverageTest --tests WorkspaceServicePrivateCoverageTest --tests WorkspaceServiceTest"));
        assertTrue(Files.readString(createArgsFile).contains(".sam-jacoco.init.gradle"));
        assertEquals("container-123:/sandbox/project/build/reports/jacoco/test/jacocoTestReport.xml", Files.readString(copySourceFile));
        assertTrue(Files.readString(result.reportPath()).contains("<report name=\"demo\">"));
    }

    @Test
    void runGradle_ShouldFallbackToFullSuite_WhenNoRelatedTestsExist() throws Exception {
        Path createArgsFile = tempDir.resolve("gradle-create-args-no-related.txt");
        Path script = createFakeDockerScript("""
                if [ "$1" = "create" ]; then
                  printf '%s\n' "$@" > '__CREATE_ARGS_FILE__'
                  echo "container-123"
                  exit 0
                fi
                if [ "$1" = "start" ]; then
                  echo "BUILD SUCCESSFUL"
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
        Path workspace = createGradleWorkspace(false);
        createRelatedTestFiles(workspace, "OtherTest");

        DockerJavaCoverageSandboxRunner runner = createRunner(script.toString());
        SandboxCoverageExecutionResult result = runner.run(workspace, "src/main/java/com/example/App.java");

        assertEquals(CoverageRunStatus.SUCCEEDED, result.status());
        assertTrue(Files.readString(createArgsFile).contains("test jacocoTestReport"));
        assertTrue(!Files.readString(createArgsFile).contains("--tests"));
    }

    private DockerJavaCoverageSandboxRunner createRunner(String dockerBinary) {
        return new DockerJavaCoverageSandboxRunner(
                dockerBinary,
                "",
                "",
                "maven:3.9.9-eclipse-temurin-17",
                "gradle:8-jdk17",
                "bridge",
                "",
                "be-sam-editor-maven-cache",
                "be-sam-editor-gradle-cache",
                tempDir.toString(),
                true,
                30,
                40000,
                "0.8.12");
    }

    private Path createWorkspace() throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("pom.xml"), "<project/>", StandardCharsets.UTF_8);
        return workspace;
    }

    private Path createGradleWorkspace(boolean withWrapper) throws IOException {
        Path workspace = tempDir.resolve(withWrapper ? "gradle-workspace-wrapper" : "gradle-workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("build.gradle"), "plugins { id 'java' }", StandardCharsets.UTF_8);
        if (withWrapper) {
            Files.writeString(workspace.resolve("gradlew"), "#!/bin/sh\nexit 0\n", StandardCharsets.UTF_8);
        }
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

    private void createTestFile(Path workspace, String relativePath) throws IOException {
        Path testFile = workspace.resolve(relativePath);
        Files.createDirectories(testFile.getParent());
        Files.writeString(testFile, "class " + testFile.getFileName().toString().replace(".java", "") + " {}", StandardCharsets.UTF_8);
    }

    private Path createFakeDockerScript(String body) throws IOException {
        return createFakeDockerScriptWithSetup(
                """
                if [ "$1" = "image" ] && [ "${2:-}" = "inspect" ]; then
                  exit 0
                fi
                if [ "$1" = "pull" ]; then
                  exit 0
                fi
                """,
                body);
    }

    private Path createFakeDockerScriptWithSetup(String setup, String body) throws IOException {
        Path script = tempDir.resolve("fake-docker.sh");
        Files.writeString(script, "#!/bin/sh\nset -eu\n" + setup + "\n" + body + "\n", StandardCharsets.UTF_8);
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
