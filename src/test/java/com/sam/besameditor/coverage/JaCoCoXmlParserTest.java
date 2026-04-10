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

class JaCoCoXmlParserTest {

    private final JaCoCoXmlParser parser = new JaCoCoXmlParser();

    @TempDir
    Path tempDir;

    @Test
    void parse_ShouldReadLineCoveragePerSourceFile() throws Exception {
        Path report = tempDir.resolve("jacoco.xml");
        Files.writeString(report, """
                <?xml version=\"1.0\" encoding=\"UTF-8\"?>
                <report name=\"demo\">
                  <package name=\"com/example\">
                    <sourcefile name=\"App.java\">
                      <line nr=\"10\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/>
                      <line nr=\"11\" mi=\"2\" ci=\"0\" mb=\"1\" cb=\"0\"/>
                    </sourcefile>
                  </package>
                </report>
                """);

        Map<String, List<CoverageLineStat>> parsed = parser.parse(report);

        assertTrue(parsed.containsKey("com/example/App.java"));
        assertEquals(2, parsed.get("com/example/App.java").size());
        assertEquals(10, parsed.get("com/example/App.java").get(0).lineNumber());
        assertTrue(parsed.get("com/example/App.java").get(0).isCovered());
        assertEquals(1, parsed.get("com/example/App.java").get(1).missedBranches());
    }

    @Test
    void parse_ShouldIgnoreJacocoDoctype() throws Exception {
        Path report = tempDir.resolve("jacoco-doctype.xml");
        Files.writeString(report, """
                <?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name=\"demo\">
                  <package name=\"com/example\">
                    <sourcefile name=\"App.java\">
                      <line nr=\"15\" mi=\"0\" ci=\"1\" mb=\"0\" cb=\"0\"/>
                    </sourcefile>
                  </package>
                </report>
                """);

        Map<String, List<CoverageLineStat>> parsed = parser.parse(report);

        assertTrue(parsed.containsKey("com/example/App.java"));
        assertEquals(1, parsed.get("com/example/App.java").size());
        assertEquals(15, parsed.get("com/example/App.java").get(0).lineNumber());
    }

    @Test
    void parse_ShouldThrow_WhenXmlIsInvalid() throws Exception {
        Path report = tempDir.resolve("broken.xml");
        Files.writeString(report, "<report><package>");

        assertThrows(IllegalArgumentException.class, () -> parser.parse(report));
    }
}
