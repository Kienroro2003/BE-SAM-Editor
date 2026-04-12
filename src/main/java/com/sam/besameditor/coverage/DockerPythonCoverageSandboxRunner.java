package com.sam.besameditor.coverage;

import com.sam.besameditor.models.CoverageRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

@Service
public class DockerPythonCoverageSandboxRunner implements CoverageSandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerPythonCoverageSandboxRunner.class);
    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COPY_TIMEOUT = Duration.ofSeconds(30);
    private static final String CONTAINER_WORKDIR = "/sandbox/project";
    private static final String COVERAGE_REPORT_PATH = CONTAINER_WORKDIR + "/coverage.xml";

    private final String dockerBinary;
    private final String dockerHost;
    private final String dockerContext;
    private final String sandboxImage;
    private final String sandboxNetwork;
    private final String workspaceVolumeName;
    private final String pipCacheVolumeName;
    private final String workspaceStorageRoot;
    private final boolean preferRelatedTests;
    private final long timeoutSeconds;
    private final int maxOutputChars;

    public DockerPythonCoverageSandboxRunner(
            @Value("${app.analysis.coverage.sandbox.docker-binary:docker}") String dockerBinary,
            @Value("${app.analysis.coverage.sandbox.docker-host:}") String dockerHost,
            @Value("${app.analysis.coverage.sandbox.docker-context:}") String dockerContext,
            @Value("${app.analysis.coverage.sandbox.python.image:python:3.12-slim}") String sandboxImage,
            @Value("${app.analysis.coverage.sandbox.network:bridge}") String sandboxNetwork,
            @Value("${app.analysis.coverage.sandbox.workspace-volume:}") String workspaceVolumeName,
            @Value("${app.analysis.coverage.sandbox.python.pip-cache-volume:be-sam-editor-pip-cache}") String pipCacheVolumeName,
            @Value("${app.workspace.storage-root:./workspace-storage}") String workspaceStorageRoot,
            @Value("${app.analysis.coverage.sandbox.python.prefer-related-tests:true}") boolean preferRelatedTests,
            @Value("${app.analysis.coverage.sandbox.python.timeout-seconds:300}") long timeoutSeconds,
            @Value("${app.analysis.coverage.sandbox.python.max-output-chars:40000}") int maxOutputChars) {
        this.dockerBinary = dockerBinary;
        this.dockerHost = dockerHost;
        this.dockerContext = dockerContext;
        this.sandboxImage = sandboxImage;
        this.sandboxNetwork = sandboxNetwork;
        this.workspaceVolumeName = workspaceVolumeName;
        this.pipCacheVolumeName = pipCacheVolumeName;
        this.workspaceStorageRoot = workspaceStorageRoot;
        this.preferRelatedTests = preferRelatedTests;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public SandboxCoverageExecutionResult run(Path workspaceRoot, String sourceFilePath) {
        Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        if (!isPythonProject(normalizedWorkspaceRoot)) {
            throw new IllegalArgumentException("Only Python workspaces (requirements.txt, pyproject.toml, or setup.py) are supported for Python coverage");
        }

        MountSpec mountSpec = resolveMountSpec(normalizedWorkspaceRoot);
        TestSelection testSelection = resolveTestSelection(normalizedWorkspaceRoot, sourceFilePath);
        String logicalCommand = buildLogicalCommand(testSelection);
        String containerName = "be-sam-coverage-py-" + UUID.randomUUID().toString().replace("-", "");
        String containerScript = buildContainerScript(mountSpec.sourceDirectoryInContainer(), normalizedWorkspaceRoot, testSelection);
        List<String> createCommand = buildCreateCommand(containerName, mountSpec, containerScript);

        log.info(
                "Starting Docker sandbox Python coverage run for workspace {} using {} and test scope {}",
                normalizedWorkspaceRoot,
                describeDockerConnection(),
                testSelection.description());
        log.debug("Docker sandbox create command: {}", formatCommand(createCommand));

        CommandResult createResult = runCommand(createCommand, CREATE_TIMEOUT);
        if (createResult.timedOut() || createResult.exitCode() != 0) {
            log.warn(
                    "Docker sandbox create step failed for workspace {}: {}",
                    normalizedWorkspaceRoot,
                    truncate(createResult.output()));
            return new SandboxCoverageExecutionResult(
                    CoverageRunStatus.FAILED,
                    createResult.exitCode(),
                    logicalCommand,
                    createResult.output(),
                    buildCommandFailureMessage(
                            "create",
                            createCommand,
                            createResult,
                            "Check Docker daemon availability, Docker Desktop context, and socket configuration."),
                    null);
        }

        String containerId = extractContainerId(createResult.output());
        if (containerId == null) {
            log.warn(
                    "Docker sandbox create step did not return a container id for workspace {}: {}",
                    normalizedWorkspaceRoot,
                    truncate(createResult.output()));
            return new SandboxCoverageExecutionResult(
                    CoverageRunStatus.FAILED,
                    createResult.exitCode(),
                    logicalCommand,
                    createResult.output(),
                    buildCommandFailureMessage(
                            "create",
                            createCommand,
                            createResult,
                            "Docker create succeeded but did not return a container id."),
                    null);
        }

        List<String> startCommand = List.of(dockerBinary, "start", "-a", containerId);
        log.debug("Docker sandbox start command: {}", formatCommand(startCommand));
        CommandResult startResult = runCommand(startCommand, Duration.ofSeconds(timeoutSeconds));
        if (startResult.timedOut()) {
            cleanupContainer(containerId);
            return new SandboxCoverageExecutionResult(
                    CoverageRunStatus.TIMED_OUT,
                    null,
                    logicalCommand,
                    truncate(startResult.output()),
                    buildCommandFailureMessage(
                            "start",
                            startCommand,
                            startResult,
                            "Timed out after " + timeoutSeconds + " seconds"),
                    null);
        }
        if (startResult.exitCode() != 0) {
            cleanupContainer(containerId);
            log.warn(
                    "Docker sandbox start step failed for workspace {}: {}",
                    normalizedWorkspaceRoot,
                    truncate(startResult.output()));
            return new SandboxCoverageExecutionResult(
                    CoverageRunStatus.FAILED,
                    startResult.exitCode(),
                    logicalCommand,
                    truncate(startResult.output()),
                    buildCommandFailureMessage(
                            "start",
                            startCommand,
                            startResult,
                            "Sandbox tests failed before Cobertura report collection."),
                    null);
        }

        ReportCopyResult reportCopyResult = copyReport(containerId);
        if (reportCopyResult.reportPath() == null) {
            cleanupContainer(containerId);
            log.warn(
                    "Docker sandbox report copy step failed for workspace {}: {}",
                    normalizedWorkspaceRoot,
                    truncate(reportCopyResult.commandResult().output()));
            return new SandboxCoverageExecutionResult(
                    CoverageRunStatus.FAILED,
                    startResult.exitCode(),
                    logicalCommand,
                    combineOutputs(startResult.output(), reportCopyResult.commandResult().output()),
                    buildCommandFailureMessage(
                            "copy-report",
                            reportCopyResult.command(),
                            reportCopyResult.commandResult(),
                            "Cobertura XML report was not produced by sandbox test run."),
                    null);
        }

        cleanupContainer(containerId);
        return new SandboxCoverageExecutionResult(
                CoverageRunStatus.SUCCEEDED,
                startResult.exitCode(),
                logicalCommand,
                truncate(startResult.output()),
                "",
                reportCopyResult.reportPath());
    }

    boolean isPythonProject(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("requirements.txt"))
                || Files.exists(workspaceRoot.resolve("pyproject.toml"))
                || Files.exists(workspaceRoot.resolve("setup.py"))
                || Files.exists(workspaceRoot.resolve("setup.cfg"));
    }

    private String buildLogicalCommand(TestSelection testSelection) {
        String base = "pytest --cov --cov-report=xml:coverage.xml";
        if (testSelection.pattern() != null && !testSelection.pattern().isBlank()) {
            return base + " " + testSelection.pattern();
        }
        return base;
    }

    private String buildContainerScript(String sourceDirectoryInContainer, Path workspaceRoot, TestSelection testSelection) {
        String escapedSourceDir = escapeShell(sourceDirectoryInContainer);
        StringBuilder script = new StringBuilder();
        script.append("set -e; ");
        script.append("mkdir -p ").append(CONTAINER_WORKDIR).append("; ");
        script.append("cp -R '").append(escapedSourceDir).append("/.' '").append(CONTAINER_WORKDIR).append("/'; ");
        script.append("cd '").append(CONTAINER_WORKDIR).append("'; ");

        if (Files.exists(workspaceRoot.resolve("requirements.txt"))) {
            script.append("pip install --quiet -r requirements.txt 2>&1 || true; ");
        } else if (Files.exists(workspaceRoot.resolve("pyproject.toml"))) {
            script.append("pip install --quiet -e . 2>&1 || true; ");
        } else if (Files.exists(workspaceRoot.resolve("setup.py"))) {
            script.append("pip install --quiet -e . 2>&1 || true; ");
        }

        script.append("pip install --quiet pytest pytest-cov 2>&1; ");
        script.append("pytest --cov --cov-report=xml:coverage.xml -v");
        if (testSelection.pattern() != null && !testSelection.pattern().isBlank()) {
            script.append(" ").append(testSelection.pattern());
        }
        return script.toString();
    }

    private List<String> buildCreateCommand(String containerName, MountSpec mountSpec, String containerScript) {
        List<String> command = new ArrayList<>();
        command.add(dockerBinary);
        command.add("create");
        command.add("--name");
        command.add(containerName);
        if (sandboxNetwork != null && !sandboxNetwork.isBlank()) {
            command.add("--network");
            command.add(sandboxNetwork);
        }
        if (pipCacheVolumeName != null && !pipCacheVolumeName.isBlank()) {
            command.add("-v");
            command.add(pipCacheVolumeName + ":/root/.cache/pip");
        }
        command.add("-v");
        command.add(mountSpec.mountExpression());
        command.add(sandboxImage);
        command.add("sh");
        command.add("-lc");
        command.add(containerScript);
        return command;
    }

    private MountSpec resolveMountSpec(Path workspaceRoot) {
        if (workspaceVolumeName != null && !workspaceVolumeName.isBlank()) {
            Path normalizedStorageRoot = Path.of(workspaceStorageRoot).toAbsolutePath().normalize();
            if (workspaceRoot.startsWith(normalizedStorageRoot)) {
                String relativePath = normalizedStorageRoot.relativize(workspaceRoot)
                        .toString()
                        .replace('\\', '/');
                return new MountSpec(
                        workspaceVolumeName + ":/mounted-workspaces:ro",
                        relativePath.isBlank() ? "/mounted-workspaces" : "/mounted-workspaces/" + relativePath);
            }
        }
        return new MountSpec(workspaceRoot + ":/mounted-workspace:ro", "/mounted-workspace");
    }

    private ReportCopyResult copyReport(String containerId) {
        List<String> copyCommand = null;
        try {
            Path reportPath = Files.createTempFile("cobertura-report-", ".xml");
            copyCommand = List.of(dockerBinary, "cp", containerId + ":" + COVERAGE_REPORT_PATH, reportPath.toString());
            log.debug("Docker sandbox copy command: {}", formatCommand(copyCommand));
            CommandResult copyResult = runCommand(copyCommand, COPY_TIMEOUT);
            if (copyResult.timedOut() || copyResult.exitCode() != 0 || !Files.exists(reportPath)) {
                Files.deleteIfExists(reportPath);
                return new ReportCopyResult(null, copyCommand, copyResult);
            }
            return new ReportCopyResult(reportPath, copyCommand, copyResult);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to copy Cobertura report from sandbox container", ex);
        }
    }

    TestSelection resolveTestSelection(Path workspaceRoot, String sourceFilePath) {
        if (!preferRelatedTests) {
            return TestSelection.none("full-suite (related tests disabled)");
        }

        String sourceBaseName = extractPythonBaseName(sourceFilePath);
        if (sourceBaseName == null) {
            return TestSelection.none("full-suite (source file name could not be determined)");
        }

        List<Path> testDirs = findTestDirs(workspaceRoot);
        if (testDirs.isEmpty()) {
            return TestSelection.none("full-suite (no test directories found)");
        }

        List<String> matchingTests = new ArrayList<>();
        for (Path testDir : testDirs) {
            try (Stream<Path> paths = Files.walk(testDir)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".py"))
                        .filter(path -> isRelatedPythonTestFile(path.getFileName().toString(), sourceBaseName))
                        .map(path -> workspaceRoot.relativize(path).toString().replace('\\', '/'))
                        .distinct()
                        .sorted()
                        .forEach(matchingTests::add);
            } catch (IOException ex) {
                log.debug("Unable to scan tests in {}", testDir, ex);
            }
        }

        if (matchingTests.isEmpty()) {
            return TestSelection.none("full-suite (no related tests found)");
        }
        String pattern = String.join(" ", matchingTests);
        return new TestSelection(pattern, "related-tests " + matchingTests);
    }

    private List<Path> findTestDirs(Path workspaceRoot) {
        List<Path> testDirs = new ArrayList<>();
        for (String candidate : List.of("tests", "test", "spec")) {
            Path dir = workspaceRoot.resolve(candidate);
            if (Files.isDirectory(dir)) {
                testDirs.add(dir);
            }
        }
        if (testDirs.isEmpty()) {
            testDirs.add(workspaceRoot);
        }
        return testDirs;
    }

    private boolean isRelatedPythonTestFile(String fileName, String sourceBaseName) {
        String lowerFileName = fileName.toLowerCase();
        String lowerBaseName = sourceBaseName.toLowerCase();
        return lowerFileName.equals("test_" + lowerBaseName + ".py")
                || lowerFileName.equals(lowerBaseName + "_test.py");
    }

    private String extractPythonBaseName(String sourceFilePath) {
        if (sourceFilePath == null || sourceFilePath.isBlank()) {
            return null;
        }
        String normalizedPath = sourceFilePath.replace('\\', '/');
        int lastSlash = normalizedPath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;
        if (!fileName.endsWith(".py") || fileName.length() <= ".py".length()) {
            return null;
        }
        return fileName.substring(0, fileName.length() - ".py".length());
    }

    private void cleanupContainer(String containerId) {
        runCommand(List.of(dockerBinary, "rm", "-f", containerId), COPY_TIMEOUT);
    }

    private CommandResult runCommand(List<String> command, Duration timeout) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        applyDockerEnvironment(processBuilder);

        try {
            Process process = processBuilder.start();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> outputFuture = executor.submit(() -> readOutput(process.getInputStream()));
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    String partialOutput = getFutureOutput(outputFuture);
                    return new CommandResult(null, partialOutput, true);
                }
                return new CommandResult(process.exitValue(), getFutureOutput(outputFuture), false);
            } finally {
                executor.shutdownNow();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to execute docker sandbox command", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Docker sandbox execution was interrupted", ex);
        }
    }

    private void applyDockerEnvironment(ProcessBuilder processBuilder) {
        if (dockerHost != null && !dockerHost.isBlank()) {
            processBuilder.environment().put("DOCKER_HOST", dockerHost);
        }
        if (dockerContext != null && !dockerContext.isBlank()) {
            processBuilder.environment().put("DOCKER_CONTEXT", dockerContext);
        }
    }

    private String getFutureOutput(Future<String> outputFuture) {
        try {
            return truncate(outputFuture.get(5, TimeUnit.SECONDS));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException ex) {
            return "";
        }
    }

    private String readOutput(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String truncate(String output) {
        if (output == null) {
            return null;
        }
        if (output.length() <= maxOutputChars) {
            return output;
        }
        return output.substring(0, maxOutputChars) + "\n...[truncated]";
    }

    private String escapeShell(String value) {
        return value.replace("'", "'\"'\"'");
    }

    private String extractContainerId(String createOutput) {
        if (createOutput == null || createOutput.isBlank()) {
            return null;
        }
        String[] lines = createOutput.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String candidate = lines[i].trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private String buildCommandFailureMessage(String step, List<String> command, CommandResult result, String hint) {
        StringBuilder message = new StringBuilder();
        message.append("Docker sandbox ")
                .append(step)
                .append(" step failed");
        if (result.timedOut()) {
            message.append(" due to timeout");
        } else if (result.exitCode() != null) {
            message.append(" with exit code ").append(result.exitCode());
        }
        message.append(". ");
        message.append(describeDockerConnection());
        message.append(". Command: ").append(formatCommand(command)).append(".");
        if (hint != null && !hint.isBlank()) {
            message.append(" ").append(hint);
        }
        return message.toString();
    }

    private String describeDockerConnection() {
        return "Docker binary=" + dockerBinary
                + ", context=" + describeValue(dockerContext)
                + ", host=" + describeValue(dockerHost);
    }

    private String describeValue(String value) {
        return value == null || value.isBlank() ? "<default>" : value;
    }

    private String formatCommand(List<String> command) {
        return command.stream()
                .map(this::quoteCommandPart)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private String quoteCommandPart(String commandPart) {
        if (commandPart.matches("[A-Za-z0-9_./:=@%-]+")) {
            return commandPart;
        }
        return "'" + escapeShell(commandPart) + "'";
    }

    private String combineOutputs(String first, String second) {
        if (first == null || first.isBlank()) {
            return truncate(second);
        }
        if (second == null || second.isBlank()) {
            return truncate(first);
        }
        return truncate(first + System.lineSeparator() + second);
    }

    record MountSpec(String mountExpression, String sourceDirectoryInContainer) {
    }

    record CommandResult(Integer exitCode, String output, boolean timedOut) {
    }

    record ReportCopyResult(Path reportPath, List<String> command, CommandResult commandResult) {
    }

    record TestSelection(String pattern, String description) {

        static TestSelection none(String description) {
            return new TestSelection(null, description);
        }
    }
}
