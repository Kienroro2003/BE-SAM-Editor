package com.sam.besameditor.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.List;

public class JavaFileCoverageResponse {

    private final Long coverageRunId;
    private final Long projectId;
    private final String path;
    private final String language;
    private final String status;
    private final Integer exitCode;
    private final boolean overlayAvailable;
    private final String command;
    private final String stdout;
    private final String stderr;
    private final LocalDateTime startedAt;
    private final LocalDateTime completedAt;
    private final List<Integer> coveredLines;
    private final List<Integer> uncoveredLines;
    private final List<String> coveredBranches;
    private final List<String> uncoveredBranches;
    private final List<CoverageFunctionSummaryResponse> functions;

    public JavaFileCoverageResponse(
            Long coverageRunId,
            Long projectId,
            String path,
            String language,
            String status,
            Integer exitCode,
            boolean overlayAvailable,
            String command,
            String stdout,
            String stderr,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            List<CoverageFunctionSummaryResponse> functions) {
        this(
                coverageRunId,
                projectId,
                path,
                language,
                status,
                exitCode,
                overlayAvailable,
                command,
                stdout,
                stderr,
                startedAt,
                completedAt,
                functions,
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    public JavaFileCoverageResponse(
            Long coverageRunId,
            Long projectId,
            String path,
            String language,
            String status,
            Integer exitCode,
            boolean overlayAvailable,
            String command,
            String stdout,
            String stderr,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            List<CoverageFunctionSummaryResponse> functions,
            List<Integer> coveredLines,
            List<Integer> uncoveredLines,
            List<String> coveredBranches,
            List<String> uncoveredBranches) {
        this.coverageRunId = coverageRunId;
        this.projectId = projectId;
        this.path = path;
        this.language = language;
        this.status = status;
        this.exitCode = exitCode;
        this.overlayAvailable = overlayAvailable;
        this.command = command;
        this.stdout = stdout;
        this.stderr = stderr;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.functions = functions == null ? List.of() : List.copyOf(functions);
        this.coveredLines = coveredLines == null ? List.of() : List.copyOf(coveredLines);
        this.uncoveredLines = uncoveredLines == null ? List.of() : List.copyOf(uncoveredLines);
        this.coveredBranches = coveredBranches == null ? List.of() : List.copyOf(coveredBranches);
        this.uncoveredBranches = uncoveredBranches == null ? List.of() : List.copyOf(uncoveredBranches);
    }

    public Long getCoverageRunId() {
        return coverageRunId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getPath() {
        return path;
    }

    public String getLanguage() {
        return language;
    }

    public String getStatus() {
        return status;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public boolean isOverlayAvailable() {
        return overlayAvailable;
    }

    public String getCommand() {
        return command;
    }

    @JsonIgnore
    public String getStdout() {
        return stdout;
    }

    @JsonIgnore
    public String getStderr() {
        return stderr;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public List<Integer> getCoveredLines() {
        return coveredLines;
    }

    public List<Integer> getUncoveredLines() {
        return uncoveredLines;
    }

    public List<String> getCoveredBranches() {
        return coveredBranches;
    }

    public List<String> getUncoveredBranches() {
        return uncoveredBranches;
    }

    public List<CoverageFunctionSummaryResponse> getFunctions() {
        return functions;
    }
}
