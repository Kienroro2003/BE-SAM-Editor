package com.sam.besameditor.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcovParserTest {

    private final LcovParser parser = new LcovParser();

    @TempDir
    Path tempDir;

    @Test
    void parse_ShouldExtractLineAndBranchCoverage() throws Exception {
        Path lcovFile = tempDir.resolve("lcov.info");
        Files.writeString(lcovFile, """
                SF:src/utils/helper.js
                DA:1,5
                DA:2,0
                DA:3,1
                BRDA:3,0,0,1
                BRDA:3,0,1,0
                end_of_record
                """);

        Map<String, List<CoverageLineStat>> result = parser.parse(lcovFile);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("src/utils/helper.js"));
        List<CoverageLineStat> stats = result.get("src/utils/helper.js");
        assertEquals(3, stats.size());

        CoverageLineStat line1 = stats.get(0);
        assertEquals(1, line1.lineNumber());
        assertEquals(1, line1.coveredInstructions());
        assertEquals(0, line1.missedInstructions());
        assertTrue(line1.isCovered());

        CoverageLineStat line2 = stats.get(1);
        assertEquals(2, line2.lineNumber());
        assertEquals(0, line2.coveredInstructions());
        assertEquals(1, line2.missedInstructions());

        CoverageLineStat line3 = stats.get(2);
        assertEquals(3, line3.lineNumber());
        assertEquals(1, line3.coveredBranches());
        assertEquals(1, line3.missedBranches());
    }

    @Test
    void parse_ShouldHandleMultipleSourceFiles() throws Exception {
        Path lcovFile = tempDir.resolve("lcov.info");
        Files.writeString(lcovFile, """
                SF:src/a.js
                DA:1,1
                end_of_record
                SF:src/b.js
                DA:1,0
                DA:2,3
                end_of_record
                """);

        Map<String, List<CoverageLineStat>> result = parser.parse(lcovFile);

        assertEquals(2, result.size());
        assertEquals(1, result.get("src/a.js").size());
        assertEquals(2, result.get("src/b.js").size());
    }

    @Test
    void parse_ShouldHandleDashInBranchData() throws Exception {
        Path lcovFile = tempDir.resolve("lcov.info");
        Files.writeString(lcovFile, """
                SF:src/c.js
                DA:5,1
                BRDA:5,0,0,-
                BRDA:5,0,1,2
                end_of_record
                """);

        Map<String, List<CoverageLineStat>> result = parser.parse(lcovFile);

        CoverageLineStat line5 = result.get("src/c.js").get(0);
        assertEquals(1, line5.missedBranches());
        assertEquals(1, line5.coveredBranches());
    }

    @Test
    void parse_ShouldHandleEmptyFile() throws Exception {
        Path lcovFile = tempDir.resolve("lcov.info");
        Files.writeString(lcovFile, "");

        Map<String, List<CoverageLineStat>> result = parser.parse(lcovFile);

        assertTrue(result.isEmpty());
    }

    @Test
    void parse_ShouldThrow_WhenFileNotFound() {
        Path missing = tempDir.resolve("missing.info");

        assertThrows(IllegalArgumentException.class, () -> parser.parse(missing));
    }
}
