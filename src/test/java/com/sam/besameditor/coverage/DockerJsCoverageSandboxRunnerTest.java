package com.sam.besameditor.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DockerJsCoverageSandboxRunnerTest {

    @TempDir
    Path tempDir;

    private DockerJsCoverageSandboxRunner createRunner() {
        return new DockerJsCoverageSandboxRunner(
                "docker",
                "",
                "",
                "node:20-slim",
                "bridge",
                "",
                "npm-cache",
                tempDir.toString(),
                true,
                300,
                40000);
    }

    @Test
    void detectTestRunner_ShouldReturnJest_WhenJestInPackageJson() throws Exception {
        Path workspace = tempDir.resolve("jest-project");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("package.json"), """
                {
                  "devDependencies": {
                    "jest": "^29.0.0"
                  }
                }
                """);

        DockerJsCoverageSandboxRunner runner = createRunner();
        assertEquals(DockerJsCoverageSandboxRunner.TestRunnerType.JEST, runner.detectTestRunner(workspace));
    }

    @Test
    void detectTestRunner_ShouldReturnVitest_WhenVitestConfigExists() throws Exception {
        Path workspace = tempDir.resolve("vitest-project");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("package.json"), "{}");
        Files.writeString(workspace.resolve("vitest.config.ts"), "export default {}");

        DockerJsCoverageSandboxRunner runner = createRunner();
        assertEquals(DockerJsCoverageSandboxRunner.TestRunnerType.VITEST, runner.detectTestRunner(workspace));
    }

    @Test
    void detectTestRunner_ShouldPreferVitest_WhenBothExist() throws Exception {
        Path workspace = tempDir.resolve("both-project");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("package.json"), """
                {
                  "devDependencies": {
                    "vitest": "^1.0.0",
                    "jest": "^29.0.0"
                  }
                }
                """);
        Files.writeString(workspace.resolve("vitest.config.ts"), "export default {}");

        DockerJsCoverageSandboxRunner runner = createRunner();
        assertEquals(DockerJsCoverageSandboxRunner.TestRunnerType.VITEST, runner.detectTestRunner(workspace));
    }

    @Test
    void detectTestRunner_ShouldReturnNpmTest_WhenNoRunnerDetected() throws Exception {
        Path workspace = tempDir.resolve("generic-project");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("package.json"), "{}");

        DockerJsCoverageSandboxRunner runner = createRunner();
        assertEquals(DockerJsCoverageSandboxRunner.TestRunnerType.NPM_TEST, runner.detectTestRunner(workspace));
    }

    @Test
    void resolveTestSelection_ShouldFindRelatedTests() throws Exception {
        Path workspace = tempDir.resolve("test-resolve");
        Path testsDir = workspace.resolve("__tests__");
        Files.createDirectories(testsDir);
        Files.writeString(workspace.resolve("package.json"), "{}");
        Files.writeString(testsDir.resolve("helper.test.js"), "test('a', () => {})");
        Files.writeString(testsDir.resolve("helper.spec.ts"), "test('b', () => {})");
        Files.writeString(testsDir.resolve("other.test.js"), "test('c', () => {})");

        DockerJsCoverageSandboxRunner runner = createRunner();
        var selection = runner.resolveTestSelection(workspace, "src/utils/helper.js");

        assertNotNull(selection.pattern());
        assertEquals(true, selection.pattern().contains("helper"));
        assertEquals(false, selection.pattern().contains("other"));
    }

    @Test
    void resolveTestSelection_ShouldReturnFullSuite_WhenNoRelatedTestsFound() throws Exception {
        Path workspace = tempDir.resolve("no-match");
        Path testsDir = workspace.resolve("__tests__");
        Files.createDirectories(testsDir);
        Files.writeString(workspace.resolve("package.json"), "{}");
        Files.writeString(testsDir.resolve("other.test.js"), "test('a', () => {})");

        DockerJsCoverageSandboxRunner runner = createRunner();
        var selection = runner.resolveTestSelection(workspace, "src/utils/helper.js");

        assertNull(selection.pattern());
        assertEquals(true, selection.description().contains("full-suite"));
    }

    @Test
    void run_ShouldThrow_WhenNoPackageJson() throws Exception {
        Path workspace = tempDir.resolve("no-pkg");
        Files.createDirectories(workspace);

        DockerJsCoverageSandboxRunner runner = createRunner();
        assertThrows(IllegalArgumentException.class, () -> runner.run(workspace, "src/index.js"));
    }
}
