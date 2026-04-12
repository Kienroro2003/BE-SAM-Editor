package com.sam.besameditor.coverage;

import com.sam.besameditor.models.CoverageRunStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class DockerJsCoverageSandboxRunner implements CoverageSandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerJsCoverageSandboxRunner.class);
    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COPY_TIMEOUT = Duration.ofSeconds(30);
    private static final String CONTAINER_WORKDIR = "/sandbox/project";
    private static final String LCOV_REPORT_PATH = CONTAINER_WORKDIR + "/coverage/lcov.info";
    private static final List<String> SUPPORTED_TEST_EXTENSIONS = List.of(".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs");
    private static final List<String> TEST_FILE_MARKERS = List.of(".test.", ".spec.", "-test.", "-spec.", "_test.", "_spec.");
    private static final TreeSet<String> TEST_SCAN_EXCLUDED_DIRS = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    static {
        TEST_SCAN_EXCLUDED_DIRS.add("node_modules");
        TEST_SCAN_EXCLUDED_DIRS.add(".git");
        TEST_SCAN_EXCLUDED_DIRS.add("coverage");
        TEST_SCAN_EXCLUDED_DIRS.add("dist");
        TEST_SCAN_EXCLUDED_DIRS.add("build");
        TEST_SCAN_EXCLUDED_DIRS.add(".next");
        TEST_SCAN_EXCLUDED_DIRS.add(".nuxt");
        TEST_SCAN_EXCLUDED_DIRS.add("out");
    }

    private final String dockerBinary;
    private final String dockerHost;
    private final String dockerContext;
    private final String sandboxImage;
    private final String sandboxNetwork;
    private final String workspaceVolumeName;
    private final String npmCacheVolumeName;
    private final String workspaceStorageRoot;
    private final boolean preferRelatedTests;
    private final long timeoutSeconds;
    private final int maxOutputChars;

    public DockerJsCoverageSandboxRunner(
            @Value("${app.analysis.coverage.sandbox.docker-binary:docker}") String dockerBinary,
            @Value("${app.analysis.coverage.sandbox.docker-host:}") String dockerHost,
            @Value("${app.analysis.coverage.sandbox.docker-context:}") String dockerContext,
            @Value("${app.analysis.coverage.sandbox.js.image:node:20-slim}") String sandboxImage,
            @Value("${app.analysis.coverage.sandbox.network:bridge}") String sandboxNetwork,
            @Value("${app.analysis.coverage.sandbox.workspace-volume:}") String workspaceVolumeName,
            @Value("${app.analysis.coverage.sandbox.js.npm-cache-volume:be-sam-editor-npm-cache}") String npmCacheVolumeName,
            @Value("${app.workspace.storage-root:./workspace-storage}") String workspaceStorageRoot,
            @Value("${app.analysis.coverage.sandbox.js.prefer-related-tests:true}") boolean preferRelatedTests,
            @Value("${app.analysis.coverage.sandbox.js.timeout-seconds:300}") long timeoutSeconds,
            @Value("${app.analysis.coverage.sandbox.js.max-output-chars:40000}") int maxOutputChars) {
        this.dockerBinary = dockerBinary;
        this.dockerHost = dockerHost;
        this.dockerContext = dockerContext;
        this.sandboxImage = sandboxImage;
        this.sandboxNetwork = sandboxNetwork;
        this.workspaceVolumeName = workspaceVolumeName;
        this.npmCacheVolumeName = npmCacheVolumeName;
        this.workspaceStorageRoot = workspaceStorageRoot;
        this.preferRelatedTests = preferRelatedTests;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public SandboxCoverageExecutionResult run(Path workspaceRoot, String sourceFilePath) {
        Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        if (!Files.exists(normalizedWorkspaceRoot.resolve("package.json"))) {
            throw new IllegalArgumentException("Only Node.js workspaces with package.json are supported for JS coverage");
        }

        MountSpec mountSpec = resolveMountSpec(normalizedWorkspaceRoot);
        TestRunnerType runnerType = detectTestRunner(normalizedWorkspaceRoot);
        TestSelection testSelection = resolveTestSelection(normalizedWorkspaceRoot, sourceFilePath);
        String logicalCommand = buildLogicalCommand(runnerType, testSelection);
        if (shouldShortCircuitNoTests(normalizedWorkspaceRoot, runnerType)) {
            return new SandboxCoverageExecutionResult(
                    CoverageRunStatus.NO_TESTS_FOUND,
                    0,
                    logicalCommand,
                    "",
                    "No recognized test files were found for this project, so coverage was not generated.",
                    null);
        }
        String containerName = "be-sam-coverage-js-" + UUID.randomUUID().toString().replace("-", "");
        String containerScript = buildContainerScript(
                normalizedWorkspaceRoot,
                mountSpec.sourceDirectoryInContainer(),
                runnerType,
                testSelection);
        List<String> createCommand = buildCreateCommand(containerName, mountSpec, containerScript);

        log.info(
                "Starting Docker sandbox JS coverage run for workspace {} using {} and test scope {}",
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
                            "Sandbox tests failed before LCOV report collection."),
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
                            "LCOV report was not produced by sandbox test run."),
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

    TestRunnerType detectTestRunner(Path workspaceRoot) {
        try {
            String packageJson = Files.readString(workspaceRoot.resolve("package.json"), StandardCharsets.UTF_8);
            if (hasVitestConfig(workspaceRoot) || packageJson.contains("\"vitest\"")) {
                return TestRunnerType.VITEST;
            }
            if (hasJestConfig(workspaceRoot) || packageJson.contains("\"jest\"")) {
                return TestRunnerType.JEST;
            }
            if (packageJson.contains("\"react-scripts\"")) {
                return TestRunnerType.REACT_SCRIPTS;
            }
        } catch (IOException ex) {
            log.debug("Unable to read package.json for test runner detection", ex);
        }
        return TestRunnerType.NPM_TEST;
    }

    private boolean hasJestConfig(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("jest.config.js"))
                || Files.exists(workspaceRoot.resolve("jest.config.ts"))
                || Files.exists(workspaceRoot.resolve("jest.config.mjs"))
                || Files.exists(workspaceRoot.resolve("jest.config.cjs"));
    }

    private boolean hasVitestConfig(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("vitest.config.js"))
                || Files.exists(workspaceRoot.resolve("vitest.config.ts"))
                || Files.exists(workspaceRoot.resolve("vitest.config.mjs"))
                || Files.exists(workspaceRoot.resolve("vitest.config.mts"))
                || Files.exists(workspaceRoot.resolve("vite.config.js"))
                || Files.exists(workspaceRoot.resolve("vite.config.ts"));
    }

    String buildLogicalCommand(TestRunnerType runnerType, TestSelection testSelection) {
        String patternSuffix = testSelection.pattern() != null && !testSelection.pattern().isBlank()
                ? " " + quoteCommandPart(testSelection.pattern())
                : "";
        return switch (runnerType) {
            case JEST -> "npx jest --coverage" + patternSuffix;
            case VITEST -> "npx vitest run --coverage" + patternSuffix;
            case REACT_SCRIPTS -> "CI=true npm test -- --watchAll=false --coverage --passWithNoTests" + patternSuffix;
            case NPM_TEST -> "npm test";
        };
    }

    String buildContainerScript(
            Path workspaceRoot,
            String sourceDirectoryInContainer,
            TestRunnerType runnerType,
            TestSelection testSelection) {
        String escapedSourceDir = escapeShell(sourceDirectoryInContainer);
        String installCommand = resolveInstallCommand(workspaceRoot);
        StringBuilder script = new StringBuilder();
        script.append("set -e; ");
        script.append("mkdir -p ").append(CONTAINER_WORKDIR).append("; ");
        script.append("cp -R '").append(escapedSourceDir).append("/.' '").append(CONTAINER_WORKDIR).append("/'; ");
        script.append("cd '").append(CONTAINER_WORKDIR).append("'; ");
        script.append(installCommand).append(" 2>&1 || true; ");

        switch (runnerType) {
            case JEST -> {
                script.append("npx jest --coverage --coverageReporters=lcov --forceExit");
                appendShellArgument(script, testSelection.pattern());
            }
            case VITEST -> {
                script.append("npx vitest run --coverage --reporter=default --coverage.reporter=lcov");
                appendShellArgument(script, testSelection.pattern());
            }
            case REACT_SCRIPTS -> {
                script.append("CI=true npm test -- --watchAll=false --coverage --passWithNoTests");
                appendShellArgument(script, testSelection.pattern());
            }
            case NPM_TEST -> script.append("npm test");
        }
        return script.toString();
    }

    String resolveInstallCommand(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("package-lock.json"))
                ? "npm ci --ignore-scripts"
                : "npm install --ignore-scripts";
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
        if (npmCacheVolumeName != null && !npmCacheVolumeName.isBlank()) {
            command.add("-v");
            command.add(npmCacheVolumeName + ":/root/.npm");
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
            Path reportPath = Files.createTempFile("lcov-report-", ".info");
            copyCommand = List.of(dockerBinary, "cp", containerId + ":" + LCOV_REPORT_PATH, reportPath.toString());
            log.debug("Docker sandbox copy command: {}", formatCommand(copyCommand));
            CommandResult copyResult = runCommand(copyCommand, COPY_TIMEOUT);
            if (copyResult.timedOut() || copyResult.exitCode() != 0 || !Files.exists(reportPath)) {
                Files.deleteIfExists(reportPath);
                return new ReportCopyResult(null, copyCommand, copyResult);
            }
            return new ReportCopyResult(reportPath, copyCommand, copyResult);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to copy LCOV report from sandbox container", ex);
        }
    }

    TestSelection resolveTestSelection(Path workspaceRoot, String sourceFilePath) {
        if (!preferRelatedTests) {
            return TestSelection.none("full-suite (related tests disabled)");
        }

        String sourceBaseName = extractBaseName(sourceFilePath);
        if (sourceBaseName == null) {
            return TestSelection.none("full-suite (source file name could not be determined)");
        }

        List<Path> recognizedTestFiles = findRecognizedTestFiles(workspaceRoot);
        if (recognizedTestFiles.isEmpty()) {
            return TestSelection.none("full-suite (no recognized test files found)");
        }

        List<String> matchingTests = recognizedTestFiles.stream()
                .filter(path -> isRelatedTestFile(path, sourceBaseName))
                .map(this::toTestPattern)
                .distinct()
                .sorted()
                .toList();

        if (matchingTests.isEmpty()) {
            return TestSelection.none("full-suite (no related tests found)");
        }
        String pattern = String.join("|", matchingTests);
        return new TestSelection(pattern, "related-tests " + matchingTests);
    }

    boolean hasRecognizedTests(Path workspaceRoot) {
        try {
            List<Path> recognizedTests = findRecognizedTestFiles(workspaceRoot);
            return !recognizedTests.isEmpty();
        } catch (RuntimeException ex) {
            log.debug("Unable to determine whether JS tests exist in {}", workspaceRoot, ex);
            return true;
        }
    }

    private boolean shouldShortCircuitNoTests(Path workspaceRoot, TestRunnerType runnerType) {
        return switch (runnerType) {
            case JEST, VITEST, REACT_SCRIPTS -> !hasRecognizedTests(workspaceRoot);
            case NPM_TEST -> false;
        };
    }

    private List<Path> findRecognizedTestFiles(Path workspaceRoot) {
        List<Path> testFiles = new ArrayList<>();
        try {
            Files.walkFileTree(workspaceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (workspaceRoot.equals(dir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String directoryName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (TEST_SCAN_EXCLUDED_DIRS.contains(directoryName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path relativePath = workspaceRoot.relativize(file);
                    if (isRecognizedTestFile(relativePath)) {
                        testFiles.add(relativePath);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to scan workspace for JS tests", ex);
        }
        return testFiles;
    }

    private boolean isRelatedTestFile(Path relativePath, String sourceBaseName) {
        String fileName = relativePath.getFileName() != null ? relativePath.getFileName().toString() : "";
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        String lowerBaseName = sourceBaseName.toLowerCase(Locale.ROOT);
        boolean conventionalMatch = (lowerFileName.startsWith(lowerBaseName + ".test.")
                || lowerFileName.startsWith(lowerBaseName + ".spec.")
                || lowerFileName.startsWith(lowerBaseName + "-test.")
                || lowerFileName.startsWith(lowerBaseName + "-spec.")
                || lowerFileName.startsWith(lowerBaseName + "_test.")
                || lowerFileName.startsWith(lowerBaseName + "_spec."))
                && hasSupportedTestExtension(lowerFileName);
        if (conventionalMatch) {
            return true;
        }
        return isInsideTestsDirectory(relativePath) && extractBaseName(fileName) != null
                && extractBaseName(fileName).equalsIgnoreCase(sourceBaseName);
    }

    private String toTestPattern(Path relativePath) {
        String fileName = relativePath.getFileName() != null ? relativePath.getFileName().toString() : relativePath.toString();
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(0, lastDot) : fileName;
    }

    private boolean isRecognizedTestFile(Path relativePath) {
        String fileName = relativePath.getFileName() != null ? relativePath.getFileName().toString() : "";
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        if (!hasSupportedTestExtension(lowerFileName)) {
            return false;
        }
        if (isInsideTestsDirectory(relativePath)) {
            return true;
        }
        return TEST_FILE_MARKERS.stream().anyMatch(lowerFileName::contains);
    }

    private boolean isInsideTestsDirectory(Path relativePath) {
        for (Path segment : relativePath) {
            if ("__tests__".equalsIgnoreCase(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSupportedTestExtension(String lowerFileName) {
        return SUPPORTED_TEST_EXTENSIONS.stream().anyMatch(lowerFileName::endsWith);
    }

    private String extractBaseName(String sourceFilePath) {
        if (sourceFilePath == null || sourceFilePath.isBlank()) {
            return null;
        }
        String normalizedPath = sourceFilePath.replace('\\', '/');
        int lastSlash = normalizedPath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;
        int firstDot = fileName.indexOf('.');
        if (firstDot <= 0) {
            return null;
        }
        return fileName.substring(0, firstDot);
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

    private void appendShellArgument(StringBuilder script, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        script.append(" '").append(escapeShell(value)).append("'");
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

    enum TestRunnerType {
        JEST,
        VITEST,
        REACT_SCRIPTS,
        NPM_TEST
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
