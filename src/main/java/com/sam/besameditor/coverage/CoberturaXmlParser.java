package com.sam.besameditor.coverage;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CoberturaXmlParser implements CoverageReportParser {

    private static final Pattern CONDITION_COVERAGE_PATTERN = Pattern.compile("\\d+%\\s*\\((\\d+)/(\\d+)\\)");

    @Override
    public Map<String, List<CoverageLineStat>> parse(Path reportPath) {
        try (InputStream inputStream = Files.newInputStream(reportPath)) {
            DocumentBuilder documentBuilder = createSecureDocumentBuilder();
            Document document = documentBuilder.parse(inputStream);
            document.getDocumentElement().normalize();

            Map<String, List<CoverageLineStat>> coverageBySourceFile = new HashMap<>();
            NodeList classNodes = document.getElementsByTagName("class");
            for (int classIndex = 0; classIndex < classNodes.getLength(); classIndex++) {
                Node classNode = classNodes.item(classIndex);
                if (classNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element classElement = (Element) classNode;
                String filename = classElement.getAttribute("filename");
                if (filename == null || filename.isBlank()) {
                    continue;
                }
                List<CoverageLineStat> lineStats = parseClassLines(classElement);
                coverageBySourceFile.merge(filename, lineStats, (existing, incoming) -> {
                    Map<Integer, CoverageLineStat> merged = new TreeMap<>();
                    for (CoverageLineStat stat : existing) {
                        merged.put(stat.lineNumber(), stat);
                    }
                    for (CoverageLineStat stat : incoming) {
                        merged.merge(stat.lineNumber(), stat, this::mergeStats);
                    }
                    return new ArrayList<>(merged.values());
                });
            }
            return coverageBySourceFile;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse Cobertura XML report", ex);
        }
    }

    private List<CoverageLineStat> parseClassLines(Element classElement) {
        Map<Integer, CoverageLineStat> statsByLine = new TreeMap<>();
        NodeList linesNodes = classElement.getElementsByTagName("lines");
        for (int linesIndex = 0; linesIndex < linesNodes.getLength(); linesIndex++) {
            Node linesNode = linesNodes.item(linesIndex);
            if (linesNode.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            NodeList lineNodes = ((Element) linesNode).getElementsByTagName("line");
            for (int lineIndex = 0; lineIndex < lineNodes.getLength(); lineIndex++) {
                Node lineNode = lineNodes.item(lineIndex);
                if (lineNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element lineElement = (Element) lineNode;
                int lineNumber = parseIntSafe(lineElement.getAttribute("number"));
                int hits = parseIntSafe(lineElement.getAttribute("hits"));
                if (lineNumber <= 0) {
                    continue;
                }

                int coveredBranches = 0;
                int missedBranches = 0;
                boolean isBranch = "true".equalsIgnoreCase(lineElement.getAttribute("branch"));
                if (isBranch) {
                    String conditionCoverage = lineElement.getAttribute("condition-coverage");
                    if (conditionCoverage != null && !conditionCoverage.isBlank()) {
                        Matcher matcher = CONDITION_COVERAGE_PATTERN.matcher(conditionCoverage);
                        if (matcher.find()) {
                            coveredBranches = parseIntSafe(matcher.group(1));
                            int totalBranches = parseIntSafe(matcher.group(2));
                            missedBranches = totalBranches - coveredBranches;
                        }
                    }
                }

                int coveredInstructions = hits > 0 ? 1 : 0;
                int missedInstructions = hits == 0 ? 1 : 0;
                CoverageLineStat stat = new CoverageLineStat(
                        lineNumber,
                        missedInstructions,
                        coveredInstructions,
                        missedBranches,
                        coveredBranches);
                statsByLine.merge(lineNumber, stat, this::mergeStats);
            }
        }
        return new ArrayList<>(statsByLine.values());
    }

    private CoverageLineStat mergeStats(CoverageLineStat a, CoverageLineStat b) {
        return new CoverageLineStat(
                a.lineNumber(),
                Math.min(a.missedInstructions(), b.missedInstructions()),
                Math.max(a.coveredInstructions(), b.coveredInstructions()),
                Math.min(a.missedBranches(), b.missedBranches()),
                Math.max(a.coveredBranches(), b.coveredBranches()));
    }

    private DocumentBuilder createSecureDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        DocumentBuilder documentBuilder = factory.newDocumentBuilder();
        documentBuilder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return documentBuilder;
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
}
