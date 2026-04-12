package com.sam.besameditor.coverage;

import java.nio.file.Path;

public interface CoverageSandboxRunner {

    SandboxCoverageExecutionResult run(Path workspaceRoot, String sourceFilePath);
}
