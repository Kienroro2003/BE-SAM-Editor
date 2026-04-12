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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class DockerJavaCoverageSandboxRunner implements CoverageSandboxRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerJavaCoverageSandboxRunner.class);
    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COPY_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern GRADLE_TEST_REPORT_PATH_PATTERN =
            Pattern.compile("See the report at:\\s+file:///sandbox/project/([^\\s]+)");
    private static final String CONTAINER_WORKDIR = "/sandbox/project";
    private static final String MAVEN_REPORT_PATH = CONTAINER_WORKDIR + "/target/site/jacoco/jacoco.xml";
    private static final String GRADLE_REPORT_PATH = CONTAINER_WORKDIR + "/build/reports/jacoco/test/jacocoTestReport.xml";
    private static final String GRADLE_USER_HOME = "/home/gradle/.gradle";
    private static final String GRADLE_INIT_SCRIPT_PATH = CONTAINER_WORKDIR + "/.sam-jacoco.init.gradle";

    private final String dockerBinary;
    private final String dockerHost;
    private final String dockerContext;
    private final String mavenSandboxImage;
    private final String gradleSandboxImage;
    private final String sandboxNetwork;
    private final String workspaceVolumeName;
    private final String mavenCacheVolumeName;
    private final String gradleCacheVolumeName;
    private final String workspaceStorageRoot;
    private final boolean preferRelatedTests;
    private final long timeoutSeconds;
    private final int maxOutputChars;
    private final String jacocoPluginVersion;

    public DockerJavaCoverageSandboxRunner(
            @Value("${app.analysis.coverage.sandbox.docker-binary:docker}") String dockerBinary,
            @Value("${app.analysis.coverage.sandbox.docker-host:}") String dockerHost,
            @Value("${app.analysis.coverage.sandbox.docker-context:}") String dockerContext,
            @Value("${app.analysis.coverage.sandbox.image:maven:3.9.9-eclipse-temurin-17}") String mavenSandboxImage,
            @Value("${app.analysis.coverage.sandbox.gradle-image:gradle:8-jdk17}") String gradleSandboxImage,
            @Value("${app.analysis.coverage.sandbox.network:bridge}") String sandboxNetwork,
            @Value("${app.analysis.coverage.sandbox.workspace-volume:}") String workspaceVolumeName,
            @Value("${app.analysis.coverage.sandbox.maven-cache-volume:be-sam-editor-maven-cache}") String mavenCacheVolumeName,
            @Value("${app.analysis.coverage.sandbox.gradle-cache-volume:be-sam-editor-gradle-cache}") String gradleCacheVolumeName,
            @Value("${app.workspace.storage-root:./workspace-storage}") String workspaceStorageRoot,
            @Value("${app.analysis.coverage.sandbox.prefer-related-tests:true}") boolean preferRelatedTests,
            @Value("${app.analysis.coverage.sandbox.timeout-seconds:300}") long timeoutSeconds,
            @Value("${app.analysis.coverage.sandbox.max-output-chars:40000}") int maxOutputChars,
            @Value("${app.analysis.coverage.jacoco-plugin-version:0.8.12}") String jacocoPluginVersion) {
        this.dockerBinary = dockerBinary;
        this.dockerHost = dockerHost;
        this.dockerContext = dockerContext;
        this.mavenSandboxImage = mavenSandboxImage;
        this.gradleSandboxImage = gradleSandboxImage;
        this.sandboxNetwork = sandboxNetwork;
        this.workspaceVolumeName = workspaceVolumeName;
        this.mavenCacheVolumeName = mavenCacheVolumeName;
        this.gradleCacheVolumeName = gradleCacheVolumeName;
        this.workspaceStorageRoot = workspaceStorageRoot;
        this.preferRelatedTests = preferRelatedTests;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputChars = maxOutputChars;
        this.jacocoPluginVersion = jacocoPluginVersion;
    }

    @Override
    public SandboxCoverageExecutionResult run(Path workspaceRoot, String sourceFilePath) {
        Path normalizedWorkspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        BuildTool buildTool = detectBuildTool(normalizedWorkspaceRoot);
        boolean recognizedTestsExist = hasRecognizedTests(normalizedWorkspaceRoot);
        TestSelection testSelection = resolveTestSelection(normalizedWorkspaceRoot, sourceFilePath);
        String logicalCommand = buildLogicalCommand(buildTool, testSelection);
        if (!recognizedTestsExist) {
            return new SandboxCoverageExecutionResult(
                    CoverageRunStatus.NO_TESTS_FOUND,
                    0,
                    logicalCommand,
                    "",
                    "No recognized Java test files were found for this project, so coverage was not generated.",
                    null);
        }
        ImageAvailabilityResult imageAvailability = ensureSandboxImageAvailable(buildTool);
        if (!imageAvailability.available()) {
            log.warn(
                    "Docker sandbox {} step failed for workspace {}: {}",
                    imageAvailability.step(),
                    normalizedWorkspaceRoot,
                    truncate(imageAvailability.commandResult().output()));
            return new SandboxCoverageExecutionResult(
                    CoverageRunStatus.FAILED,
                    imageAvailability.commandResult().exitCode(),
                    logicalCommand,
                    imageAvailability.commandResult().output(),
                    buildCommandFailureMessage(
                            imageAvailability.step(),
                            imageAvailability.command(),
                            imageAvailability.commandResult(),
                            imageAvailability.hint()),
                    null);
        }
        MountSpec mountSpec = resolveMountSpec(normalizedWorkspaceRoot);
        String containerName = "be-sam-coverage-" + UUID.randomUUID().toString().replace("-", "");
        String containerScript = buildContainerScript(mountSpec.sourceDirectoryInContainer(), buildTool, testSelection);
        List<String> createCommand = buildCreateCommand(containerName, mountSpec, buildTool, containerScript);

        log.info(
                "Starting Docker sandbox coverage run for workspace {} using {} with {} and test scope {}",
                normalizedWorkspaceRoot,
                describeDockerConnection(),
                buildTool.displayName(),
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
            StartFailureDiagnostic startFailureDiagnostic = diagnoseStartFailure(buildTool, startResult.output());
            log.warn(
                    "{} for workspace {}: {}",
                    startFailureDiagnostic.logSummary(),
                    normalizedWorkspaceRoot,
                    truncate(startResult.output()));
            return new SandboxCoverageExecutionResult(
                    CoverageRunStatus.FAILED,
                    startResult.exitCode(),
                    logicalCommand,
                    truncate(startResult.output()),
                    buildFailureMessage(
                            startFailureDiagnostic.messagePrefix(),
                            startCommand,
                            startResult,
                            startFailureDiagnostic.hint()),
                    null);
        }

        ReportCopyResult reportCopyResult = copyReport(containerId, buildTool);
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
                            "JaCoCo XML report was not produced by sandbox test run."),
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

    BuildTool detectBuildTool(Path workspaceRoot) {
        if (Files.exists(workspaceRoot.resolve("pom.xml"))) {
            return BuildTool.MAVEN;
        }
        if (hasGradleBuildFiles(workspaceRoot)) {
            return BuildTool.GRADLE;
        }
        throw new IllegalArgumentException("Only Maven or Gradle Java workspaces are supported for coverage");
    }

    String buildLogicalCommand(BuildTool buildTool, TestSelection testSelection) {
        return switch (buildTool) {
            case MAVEN -> "./mvnw or mvn " + buildMavenArguments(testSelection);
            case GRADLE -> "./gradlew or gradle " + buildGradleArguments(testSelection);
        };
    }

    String buildContainerScript(String sourceDirectoryInContainer, BuildTool buildTool, TestSelection testSelection) {
        String escapedSourceDir = escapeShell(sourceDirectoryInContainer);
        return switch (buildTool) {
            case MAVEN -> "set -e; "
                    + "mkdir -p " + CONTAINER_WORKDIR + "; "
                    + "cp -R '" + escapedSourceDir + "/.' '" + CONTAINER_WORKDIR + "/'; "
                    + "cd '" + CONTAINER_WORKDIR + "'; "
                    + "chmod +x ./mvnw || true; "
                    + "if [ -f ./mvnw ]; then ./mvnw -q " + buildMavenArguments(testSelection)
                    + "; else mvn -q " + buildMavenArguments(testSelection) + "; fi";
            case GRADLE -> """
                    set -e;
                    mkdir -p %s;
                    cp -R '%s/.' '%s/';
                    cd '%s';
                    mkdir -p '%s';
                    cat > '%s' <<'GRADLE_INIT'
                    %s
                    GRADLE_INIT
                    chmod +x ./gradlew || true;
                    export GRADLE_USER_HOME='%s';
                    if [ -f ./gradlew ]; then ./gradlew %s; else gradle %s; fi
                    """.formatted(
                    CONTAINER_WORKDIR,
                    escapedSourceDir,
                    CONTAINER_WORKDIR,
                    CONTAINER_WORKDIR,
                    GRADLE_USER_HOME,
                    GRADLE_INIT_SCRIPT_PATH,
                    buildGradleInitScript(),
                    GRADLE_USER_HOME,
                    buildGradleArguments(testSelection),
                    buildGradleArguments(testSelection));
        };
    }

    private String buildMavenArguments(TestSelection testSelection) {
        String jacocoGoal = "org.jacoco:jacoco-maven-plugin:" + jacocoPluginVersion;
        List<String> arguments = new ArrayList<>();
        arguments.add("-Dspring.devtools.restart.enabled=false");
        if (!testSelection.testClassNames().isEmpty()) {
            arguments.add("-Dtest=" + String.join(",", testSelection.testClassNames()));
        }
        arguments.add(jacocoGoal + ":prepare-agent");
        arguments.add("test");
        arguments.add(jacocoGoal + ":report");
        return String.join(" ", arguments);
    }

    String buildGradleArguments(TestSelection testSelection) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--no-daemon");
        arguments.add("-q");
        arguments.add("--console=plain");
        arguments.add("-I");
        arguments.add(GRADLE_INIT_SCRIPT_PATH);
        arguments.add("test");
        for (String testClassName : testSelection.testClassNames()) {
            arguments.add("--tests");
            arguments.add(testClassName);
        }
        arguments.add("jacocoTestReport");
        return joinShellArguments(arguments);
    }

    private String buildGradleInitScript() {
        return """
                import org.gradle.api.tasks.testing.Test
                import org.gradle.testing.jacoco.tasks.JacocoReport

                allprojects {
                    pluginManager.withPlugin('java') {
                        apply plugin: 'jacoco'

                        jacoco {
                            toolVersion = '%s'
                        }

                        tasks.withType(Test).configureEach {
                            systemProperty 'spring.devtools.restart.enabled', 'false'
                            finalizedBy 'jacocoTestReport'
                        }

                        tasks.withType(JacocoReport).configureEach {
                            dependsOn tasks.withType(Test)
                            reports {
                                xml.required = true
                                html.required = false
                                csv.required = false
                            }
                        }
                    }
                }
                """.formatted(jacocoPluginVersion);
    }

    private List<String> buildCreateCommand(String containerName, MountSpec mountSpec, BuildTool buildTool, String containerScript) {
        List<String> command = new ArrayList<>();
        command.add(dockerBinary);
        command.add("create");
        command.add("--name");
        command.add(containerName);
        if (sandboxNetwork != null && !sandboxNetwork.isBlank()) {
            command.add("--network");
            command.add(sandboxNetwork);
        }
        String cacheVolumeName = resolveCacheVolumeName(buildTool);
        if (cacheVolumeName != null && !cacheVolumeName.isBlank()) {
            command.add("-v");
            command.add(cacheVolumeName + ":" + resolveCacheMountPath(buildTool));
        }
        command.add("-v");
        command.add(mountSpec.mountExpression());
        command.add(resolveSandboxImage(buildTool));
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

    private ImageAvailabilityResult ensureSandboxImageAvailable(BuildTool buildTool) {
        String sandboxImage = resolveSandboxImage(buildTool);
        List<String> inspectCommand = List.of(dockerBinary, "image", "inspect", sandboxImage);
        log.debug("Docker sandbox image inspect command: {}", formatCommand(inspectCommand));
        CommandResult inspectResult = runCommand(inspectCommand, CREATE_TIMEOUT);
        if (inspectResult.timedOut()) {
            return new ImageAvailabilityResult(
                    false,
                    "inspect-image",
                    inspectCommand,
                    inspectResult,
                    "Inspecting the sandbox image timed out before container creation.");
        }
        if (inspectResult.exitCode() == 0) {
            return ImageAvailabilityResult.ready();
        }

        List<String> pullCommand = List.of(dockerBinary, "pull", sandboxImage);
        log.info("Docker sandbox image {} is not available locally. Pulling it before coverage run.", sandboxImage);
        log.debug("Docker sandbox image pull command: {}", formatCommand(pullCommand));
        CommandResult pullResult = runCommand(pullCommand, Duration.ofSeconds(timeoutSeconds));
        if (pullResult.timedOut() || pullResult.exitCode() != 0) {
            return new ImageAvailabilityResult(
                    false,
                    "pull-image",
                    pullCommand,
                    pullResult,
                    "Ensure the sandbox image can be pulled and Docker registry access is available.");
        }
        return ImageAvailabilityResult.ready();
    }

    private ReportCopyResult copyReport(String containerId, BuildTool buildTool) {
        List<String> copyCommand = null;
        try {
            Path reportPath = Files.createTempFile("jacoco-report-", ".xml");
            copyCommand = List.of(dockerBinary, "cp", containerId + ":" + resolveReportPath(buildTool), reportPath.toString());
            log.debug("Docker sandbox copy command: {}", formatCommand(copyCommand));
            CommandResult copyResult = runCommand(copyCommand, COPY_TIMEOUT);
            if (copyResult.timedOut() || copyResult.exitCode() != 0 || !Files.exists(reportPath)) {
                Files.deleteIfExists(reportPath);
                return new ReportCopyResult(null, copyCommand, copyResult);
            }
            return new ReportCopyResult(reportPath, copyCommand, copyResult);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to copy JaCoCo XML report from sandbox container", ex);
        }
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

    private boolean hasGradleBuildFiles(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("build.gradle"))
                || Files.exists(workspaceRoot.resolve("build.gradle.kts"))
                || Files.exists(workspaceRoot.resolve("settings.gradle"))
                || Files.exists(workspaceRoot.resolve("settings.gradle.kts"));
    }

    boolean hasRecognizedTests(Path workspaceRoot) {
        Path testRoot = workspaceRoot.resolve("src/test/java");
        if (!Files.isDirectory(testRoot)) {
            return false;
        }

        try (Stream<Path> paths = Files.walk(testRoot)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .anyMatch(this::isRecognizedJavaTestFile);
        } catch (IOException ex) {
            log.debug("Unable to scan workspace for Java tests in {}", workspaceRoot, ex);
            return false;
        }
    }

    private TestSelection resolveTestSelection(Path workspaceRoot, String sourceFilePath) {
        if (!preferRelatedTests) {
            return TestSelection.none("full-suite (related tests disabled)");
        }

        String sourceBaseName = extractJavaBaseName(sourceFilePath);
        if (sourceBaseName == null) {
            return TestSelection.none("full-suite (source file is not a Java class)");
        }

        Path testRoot = workspaceRoot.resolve("src/test/java");
        if (!Files.isDirectory(testRoot)) {
            return TestSelection.none("full-suite (no src/test/java)");
        }

        try (Stream<Path> paths = Files.walk(testRoot)) {
            List<String> matchingTests = paths
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.startsWith(sourceBaseName) && isRecognizedJavaTestFile(fileName))
                    .map(fileName -> fileName.substring(0, fileName.length() - ".java".length()))
                    .distinct()
                    .sorted()
                    .toList();
            if (matchingTests.isEmpty()) {
                return TestSelection.none("full-suite (no related tests found)");
            }
            return new TestSelection(matchingTests, "related-tests " + matchingTests);
        } catch (IOException ex) {
            log.debug("Unable to scan tests for source {}", sourceFilePath, ex);
            return TestSelection.none("full-suite (failed to scan related tests)");
        }
    }

    private boolean isRecognizedJavaTestFile(String fileName) {
        return fileName.endsWith("Test.java") || fileName.endsWith("Tests.java");
    }

    private String extractJavaBaseName(String sourceFilePath) {
        if (sourceFilePath == null || sourceFilePath.isBlank()) {
            return null;
        }
        String normalizedPath = sourceFilePath.replace('\\', '/');
        int lastSlash = normalizedPath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;
        if (!fileName.endsWith(".java") || fileName.length() <= ".java".length()) {
            return null;
        }
        return fileName.substring(0, fileName.length() - ".java".length());
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
        return buildFailureMessage("Docker sandbox " + step + " step failed", command, result, hint);
    }

    private String buildFailureMessage(String prefix, List<String> command, CommandResult result, String hint) {
        StringBuilder message = new StringBuilder();
        message.append(prefix);
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

    private StartFailureDiagnostic diagnoseStartFailure(BuildTool buildTool, String output) {
        if (buildTool == BuildTool.GRADLE && isGradleTestFailure(output)) {
            String reportPath = extractGradleTestReportPath(output);
            StringBuilder hint = new StringBuilder("Gradle tests failed inside sandbox before JaCoCo report collection.");
            if (reportPath != null) {
                hint.append(" Test report: ").append(reportPath).append(".");
            }
            return new StartFailureDiagnostic(
                    "Coverage sandbox test execution failed",
                    "Coverage sandbox test execution failed",
                    hint.toString());
        }
        return new StartFailureDiagnostic(
                "Docker sandbox start step failed",
                "Docker sandbox start step failed",
                "Sandbox tests failed before JaCoCo report collection.");
    }

    private boolean isGradleTestFailure(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return output.contains("There were failing tests")
                || output.contains("Execution failed for task ':test'")
                || output.matches("(?s).*\\b\\d+\\s+test(?:s)?\\s+completed,\\s+\\d+\\s+failed\\b.*");
    }

    private String extractGradleTestReportPath(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        Matcher matcher = GRADLE_TEST_REPORT_PATH_PATTERN.matcher(output);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private String describeDockerConnection() {
        return "Docker binary=" + dockerBinary
                + ", context=" + describeValue(dockerContext)
                + ", host=" + describeValue(dockerHost);
    }

    private String describeValue(String value) {
        return value == null || value.isBlank() ? "<default>" : value;
    }

    private String resolveSandboxImage(BuildTool buildTool) {
        return switch (buildTool) {
            case MAVEN -> mavenSandboxImage;
            case GRADLE -> gradleSandboxImage;
        };
    }

    private String resolveCacheVolumeName(BuildTool buildTool) {
        return switch (buildTool) {
            case MAVEN -> mavenCacheVolumeName;
            case GRADLE -> gradleCacheVolumeName;
        };
    }

    private String resolveCacheMountPath(BuildTool buildTool) {
        return switch (buildTool) {
            case MAVEN -> "/root/.m2";
            case GRADLE -> GRADLE_USER_HOME;
        };
    }

    private String resolveReportPath(BuildTool buildTool) {
        return switch (buildTool) {
            case MAVEN -> MAVEN_REPORT_PATH;
            case GRADLE -> GRADLE_REPORT_PATH;
        };
    }

    private String formatCommand(List<String> command) {
        return command.stream()
                .map(this::quoteCommandPart)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private String joinShellArguments(List<String> arguments) {
        return arguments.stream()
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

    private record MountSpec(String mountExpression, String sourceDirectoryInContainer) {
    }

    private record CommandResult(Integer exitCode, String output, boolean timedOut) {
    }

    private record ReportCopyResult(Path reportPath, List<String> command, CommandResult commandResult) {
    }

    private record ImageAvailabilityResult(
            boolean available,
            String step,
            List<String> command,
            CommandResult commandResult,
            String hint) {

        private static ImageAvailabilityResult ready() {
            return new ImageAvailabilityResult(true, null, List.of(), null, null);
        }
    }

    private record StartFailureDiagnostic(String logSummary, String messagePrefix, String hint) {
    }

    record TestSelection(List<String> testClassNames, String description) {

        private static TestSelection none(String description) {
            return new TestSelection(List.of(), description);
        }
    }

    enum BuildTool {
        MAVEN("Maven"),
        GRADLE("Gradle");

        private final String displayName;

        BuildTool(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }
}
