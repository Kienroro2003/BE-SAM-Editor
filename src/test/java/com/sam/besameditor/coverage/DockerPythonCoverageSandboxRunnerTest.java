package com.sam.besameditor.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerPythonCoverageSandboxRunnerTest {

    @TempDir
    Path tempDir;

    private DockerPythonCoverageSandboxRunner createRunner() {
        return new DockerPythonCoverageSandboxRunner(
                "docker",
                "",
                "",
                "python:3.12-slim",
                "bridge",
                "",
                "pip-cache",
                tempDir.toString(),
                true,
                300,
                40000);
    }

    @Test
    void isPythonProject_ShouldReturnTrue_WhenRequirementsTxtExists() throws Exception {
        Path workspace = tempDir.resolve("req-project");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("requirements.txt"), "pytest\n");

        DockerPythonCoverageSandboxRunner runner = createRunner();
        assertTrue(runner.isPythonProject(workspace));
    }

    @Test
    void isPythonProject_ShouldReturnTrue_WhenPyprojectTomlExists() throws Exception {
        Path workspace = tempDir.resolve("pyp-project");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("pyproject.toml"), "[tool.poetry]\n");

        DockerPythonCoverageSandboxRunner runner = createRunner();
        assertTrue(runner.isPythonProject(workspace));
    }

    @Test
    void isPythonProject_ShouldReturnTrue_WhenSetupPyExists() throws Exception {
        Path workspace = tempDir.resolve("setup-project");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("setup.py"), "from setuptools import setup\n");

        DockerPythonCoverageSandboxRunner runner = createRunner();
        assertTrue(runner.isPythonProject(workspace));
    }

    @Test
    void isPythonProject_ShouldReturnFalse_WhenNoPythonFiles() throws Exception {
        Path workspace = tempDir.resolve("empty-project");
        Files.createDirectories(workspace);

        DockerPythonCoverageSandboxRunner runner = createRunner();
        assertFalse(runner.isPythonProject(workspace));
    }

    @Test
    void resolveTestSelection_ShouldFindRelatedTests() throws Exception {
        Path workspace = tempDir.resolve("test-resolve");
        Path testsDir = workspace.resolve("tests");
        Files.createDirectories(testsDir);
        Files.writeString(workspace.resolve("requirements.txt"), "pytest\n");
        Files.writeString(testsDir.resolve("test_helper.py"), "def test_foo(): pass");
        Files.writeString(testsDir.resolve("helper_test.py"), "def test_bar(): pass");
        Files.writeString(testsDir.resolve("test_other.py"), "def test_baz(): pass");

        DockerPythonCoverageSandboxRunner runner = createRunner();
        var selection = runner.resolveTestSelection(workspace, "src/helper.py");

        assertNotNull(selection.pattern());
        assertTrue(selection.pattern().contains("test_helper.py"));
        assertTrue(selection.pattern().contains("helper_test.py"));
        assertFalse(selection.pattern().contains("test_other.py"));
    }

    @Test
    void resolveTestSelection_ShouldReturnFullSuite_WhenNoRelatedTestsFound() throws Exception {
        Path workspace = tempDir.resolve("no-match");
        Path testsDir = workspace.resolve("tests");
        Files.createDirectories(testsDir);
        Files.writeString(workspace.resolve("requirements.txt"), "pytest\n");
        Files.writeString(testsDir.resolve("test_other.py"), "def test_baz(): pass");

        DockerPythonCoverageSandboxRunner runner = createRunner();
        var selection = runner.resolveTestSelection(workspace, "src/helper.py");

        assertNull(selection.pattern());
        assertTrue(selection.description().contains("full-suite"));
    }

    @Test
    void run_ShouldThrow_WhenNotPythonProject() throws Exception {
        Path workspace = tempDir.resolve("not-python");
        Files.createDirectories(workspace);

        DockerPythonCoverageSandboxRunner runner = createRunner();
        assertThrows(IllegalArgumentException.class, () -> runner.run(workspace, "src/module.py"));
    }
}
