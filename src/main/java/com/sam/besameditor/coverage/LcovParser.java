package com.sam.besameditor.coverage;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class LcovParser implements CoverageReportParser {

    @Override
    public Map<String, List<CoverageLineStat>> parse(Path reportPath) {
        Map<String, List<CoverageLineStat>> coverageBySourceFile = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(reportPath, StandardCharsets.UTF_8)) {
            String currentSourceFile = null;
            Map<Integer, LineDraft> linesByNumber = new TreeMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("SF:")) {
                    currentSourceFile = trimmed.substring(3);
                    linesByNumber = new TreeMap<>();
                } else if (trimmed.startsWith("DA:")) {
                    parseLineData(trimmed, linesByNumber);
                } else if (trimmed.startsWith("BRDA:")) {
                    parseBranchData(trimmed, linesByNumber);
                } else if ("end_of_record".equals(trimmed)) {
                    if (currentSourceFile != null) {
                        coverageBySourceFile.put(currentSourceFile, toLineStats(linesByNumber));
                    }
                    currentSourceFile = null;
                    linesByNumber = new TreeMap<>();
                }
            }
            if (currentSourceFile != null) {
                coverageBySourceFile.put(currentSourceFile, toLineStats(linesByNumber));
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to parse LCOV report", ex);
        }
        return coverageBySourceFile;
    }

    private void parseLineData(String line, Map<Integer, LineDraft> linesByNumber) {
        String data = line.substring("DA:".length());
        String[] parts = data.split(",", 2);
        if (parts.length < 2) {
            return;
        }
        int lineNumber = parseIntSafe(parts[0]);
        int hitCount = parseIntSafe(parts[1]);
        if (lineNumber <= 0) {
            return;
        }
        LineDraft draft = linesByNumber.computeIfAbsent(lineNumber, LineDraft::new);
        draft.hitCount = hitCount;
    }

    private void parseBranchData(String line, Map<Integer, LineDraft> linesByNumber) {
        String data = line.substring("BRDA:".length());
        String[] parts = data.split(",", 4);
        if (parts.length < 4) {
            return;
        }
        int lineNumber = parseIntSafe(parts[0]);
        if (lineNumber <= 0) {
            return;
        }
        LineDraft draft = linesByNumber.computeIfAbsent(lineNumber, LineDraft::new);
        String taken = parts[3].trim();
        if ("-".equals(taken) || parseIntSafe(taken) == 0) {
            draft.missedBranches++;
        } else {
            draft.coveredBranches++;
        }
    }

    private List<CoverageLineStat> toLineStats(Map<Integer, LineDraft> linesByNumber) {
        List<CoverageLineStat> stats = new ArrayList<>(linesByNumber.size());
        for (LineDraft draft : linesByNumber.values()) {
            int coveredInstructions = draft.hitCount > 0 ? 1 : 0;
            int missedInstructions = draft.hitCount == 0 ? 1 : 0;
            stats.add(new CoverageLineStat(
                    draft.lineNumber,
                    missedInstructions,
                    coveredInstructions,
                    draft.missedBranches,
                    draft.coveredBranches));
        }
        return stats;
    }

    private int parseIntSafe(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static class LineDraft {
        final int lineNumber;
        int hitCount;
        int coveredBranches;
        int missedBranches;

        LineDraft(int lineNumber) {
            this.lineNumber = lineNumber;
        }
    }
}
