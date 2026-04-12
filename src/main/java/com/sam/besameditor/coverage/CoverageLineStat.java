package com.sam.besameditor.coverage;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record CoverageLineStat(
        int lineNumber,
        int missedInstructions,
        int coveredInstructions,
        int missedBranches,
        int coveredBranches) {

    @JsonIgnore
    public boolean isExecutable() {
        return missedInstructions > 0 || coveredInstructions > 0 || missedBranches > 0 || coveredBranches > 0;
    }

    @JsonIgnore
    public boolean isCovered() {
        return coveredInstructions > 0 || coveredBranches > 0;
    }
}
