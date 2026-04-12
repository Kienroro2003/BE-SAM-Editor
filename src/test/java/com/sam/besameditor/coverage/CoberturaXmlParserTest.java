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

class CoberturaXmlParserTest {

    private final CoberturaXmlParser parser = new CoberturaXmlParser();

    @TempDir
    Path tempDir;

    @Test
    void parse_ShouldExtractLineCoverageFromCoberturaXml() throws Exception {
        Path reportFile = tempDir.resolve("coverage.xml");
        Files.writeString(reportFile, """
                <?xml version="1.0" ?>
                <coverage version="5.5" timestamp="1234567890">
                  <packages>
                    <package name="mypackage">
                      <classes>
                        <class name="mypackage.module" filename="src/module.py" line-rate="0.5">
                          <lines>
                            <line number="1" hits="5"/>
                            <line number="2" hits="0"/>
                            <line number="3" hits="2"/>
                          </lines>
                        </class>
                      </classes>
                    </package>
                  </packages>
                </coverage>
                """);

        Map<String, List<CoverageLineStat>> result = parser.parse(reportFile);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("src/module.py"));
        List<CoverageLineStat> stats = result.get("src/module.py");
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
    }

    @Test
    void parse_ShouldExtractBranchCoverageFromConditionAttribute() throws Exception {
        Path reportFile = tempDir.resolve("coverage.xml");
        Files.writeString(reportFile, """
                <?xml version="1.0" ?>
                <coverage>
                  <packages>
                    <package name="pkg">
                      <classes>
                        <class name="pkg.svc" filename="src/svc.py">
                          <lines>
                            <line number="10" hits="1" branch="true" condition-coverage="50% (1/2)"/>
                            <line number="11" hits="3" branch="true" condition-coverage="100% (4/4)"/>
                          </lines>
                        </class>
                      </classes>
                    </package>
                  </packages>
                </coverage>
                """);

        Map<String, List<CoverageLineStat>> result = parser.parse(reportFile);

        List<CoverageLineStat> stats = result.get("src/svc.py");
        assertEquals(2, stats.size());

        CoverageLineStat line10 = stats.get(0);
        assertEquals(10, line10.lineNumber());
        assertEquals(1, line10.coveredBranches());
        assertEquals(1, line10.missedBranches());

        CoverageLineStat line11 = stats.get(1);
        assertEquals(11, line11.lineNumber());
        assertEquals(4, line11.coveredBranches());
        assertEquals(0, line11.missedBranches());
    }

    @Test
    void parse_ShouldHandleMultipleClassesInSameFile() throws Exception {
        Path reportFile = tempDir.resolve("coverage.xml");
        Files.writeString(reportFile, """
                <?xml version="1.0" ?>
                <coverage>
                  <packages>
                    <package name="pkg">
                      <classes>
                        <class name="pkg.mod.ClassA" filename="src/mod.py">
                          <lines>
                            <line number="1" hits="1"/>
                          </lines>
                        </class>
                        <class name="pkg.mod.ClassB" filename="src/mod.py">
                          <lines>
                            <line number="10" hits="0"/>
                          </lines>
                        </class>
                      </classes>
                    </package>
                  </packages>
                </coverage>
                """);

        Map<String, List<CoverageLineStat>> result = parser.parse(reportFile);

        assertEquals(1, result.size());
        List<CoverageLineStat> stats = result.get("src/mod.py");
        assertEquals(2, stats.size());
        assertEquals(1, stats.get(0).lineNumber());
        assertEquals(10, stats.get(1).lineNumber());
    }

    @Test
    void parse_ShouldHandleEmptyReport() throws Exception {
        Path reportFile = tempDir.resolve("coverage.xml");
        Files.writeString(reportFile, """
                <?xml version="1.0" ?>
                <coverage>
                  <packages/>
                </coverage>
                """);

        Map<String, List<CoverageLineStat>> result = parser.parse(reportFile);

        assertTrue(result.isEmpty());
    }

    @Test
    void parse_ShouldThrow_WhenFileNotFound() {
        Path missing = tempDir.resolve("missing.xml");

        assertThrows(IllegalArgumentException.class, () -> parser.parse(missing));
    }
}
