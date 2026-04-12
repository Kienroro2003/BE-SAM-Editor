package com.sam.besameditor.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public class AiSuggestTestsRequest {

    @NotBlank(message = "sourceCode is required")
    private String sourceCode;

    @NotBlank(message = "testCode is required")
    private String testCode;

    @NotNull(message = "coverageResult is required")
    @Valid
    private CoverageResult coverageResult;

    @NotBlank(message = "language is required")
    private String language;

    public String getSourceCode() {
        return sourceCode;
    }

    public void setSourceCode(String sourceCode) {
        this.sourceCode = sourceCode;
    }

    public String getTestCode() {
        return testCode;
    }

    public void setTestCode(String testCode) {
        this.testCode = testCode;
    }

    public CoverageResult getCoverageResult() {
        return coverageResult;
    }

    public void setCoverageResult(CoverageResult coverageResult) {
        this.coverageResult = coverageResult;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public static class CoverageResult {
        @NotNull(message = "coveredLines is required")
        private List<Object> coveredLines;

        @NotNull(message = "uncoveredLines is required")
        private List<Object> uncoveredLines;

        @NotNull(message = "coveredBranches is required")
        private List<Object> coveredBranches;

        @NotNull(message = "uncoveredBranches is required")
        private List<Object> uncoveredBranches;

        @NotNull(message = "coveredFunctions is required")
        private List<Object> coveredFunctions;

        @NotNull(message = "uncoveredFunctions is required")
        private List<Object> uncoveredFunctions;

        @NotNull(message = "coveragePercentage is required")
        private Double coveragePercentage;

        public List<Object> getCoveredLines() {
            return coveredLines;
        }

        public void setCoveredLines(List<Object> coveredLines) {
            this.coveredLines = coveredLines;
        }

        public List<Object> getUncoveredLines() {
            return uncoveredLines;
        }

        public void setUncoveredLines(List<Object> uncoveredLines) {
            this.uncoveredLines = uncoveredLines;
        }

        public List<Object> getCoveredBranches() {
            return coveredBranches;
        }

        public void setCoveredBranches(List<Object> coveredBranches) {
            this.coveredBranches = coveredBranches;
        }

        public List<Object> getUncoveredBranches() {
            return uncoveredBranches;
        }

        public void setUncoveredBranches(List<Object> uncoveredBranches) {
            this.uncoveredBranches = uncoveredBranches;
        }

        public List<Object> getCoveredFunctions() {
            return coveredFunctions;
        }

        public void setCoveredFunctions(List<Object> coveredFunctions) {
            this.coveredFunctions = coveredFunctions;
        }

        public List<Object> getUncoveredFunctions() {
            return uncoveredFunctions;
        }

        public void setUncoveredFunctions(List<Object> uncoveredFunctions) {
            this.uncoveredFunctions = uncoveredFunctions;
        }

        public Double getCoveragePercentage() {
            return coveragePercentage;
        }

        public void setCoveragePercentage(Double coveragePercentage) {
            this.coveragePercentage = coveragePercentage;
        }

        public boolean isFullyCovered() {
            return coveragePercentage != null && coveragePercentage >= 100.0
                    && (uncoveredLines == null || uncoveredLines.isEmpty())
                    && (uncoveredBranches == null || uncoveredBranches.isEmpty())
                    && (uncoveredFunctions == null || uncoveredFunctions.isEmpty());
        }

        public Map<String, Object> toMap() {
            return Map.of(
                    "coveredLines", coveredLines,
                    "uncoveredLines", uncoveredLines,
                    "coveredBranches", coveredBranches,
                    "uncoveredBranches", uncoveredBranches,
                    "coveredFunctions", coveredFunctions,
                    "uncoveredFunctions", uncoveredFunctions,
                    "coveragePercentage", coveragePercentage
            );
        }
    }
}
