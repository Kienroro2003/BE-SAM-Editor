package com.sam.besameditor.coverage;

import com.sam.besameditor.models.CoverageRunStatus;

import java.nio.file.Path;

public record SandboxCoverageExecutionResult(
        CoverageRunStatus status,
        Integer exitCode,
        String command,
        String stdout,
        String stderr,
        Path reportPath) {
}
