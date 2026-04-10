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

@Component
public class JaCoCoXmlParser {

    public Map<String, List<CoverageLineStat>> parse(Path xmlPath) {
        try (InputStream inputStream = Files.newInputStream(xmlPath)) {
            DocumentBuilder documentBuilder = createSecureDocumentBuilder();
            Document document = documentBuilder.parse(inputStream);
            document.getDocumentElement().normalize();

            Map<String, List<CoverageLineStat>> coverageBySourceFile = new HashMap<>();
            NodeList packageNodes = document.getElementsByTagName("package");
            for (int packageIndex = 0; packageIndex < packageNodes.getLength(); packageIndex++) {
                Node packageNode = packageNodes.item(packageIndex);
                if (packageNode.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element packageElement = (Element) packageNode;
                String packageName = packageElement.getAttribute("name");
                NodeList childNodes = packageElement.getChildNodes();
                for (int childIndex = 0; childIndex < childNodes.getLength(); childIndex++) {
                    Node childNode = childNodes.item(childIndex);
                    if (childNode.getNodeType() != Node.ELEMENT_NODE || !"sourcefile".equals(childNode.getNodeName())) {
                        continue;
                    }
                    Element sourceFileElement = (Element) childNode;
                    String sourceFileName = sourceFileElement.getAttribute("name");
                    String key = packageName == null || packageName.isBlank()
                            ? sourceFileName
                            : packageName + "/" + sourceFileName;
                    coverageBySourceFile.put(key, parseLineStats(sourceFileElement));
                }
            }
            return coverageBySourceFile;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse JaCoCo XML report", ex);
        }
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

    private List<CoverageLineStat> parseLineStats(Element sourceFileElement) {
        List<CoverageLineStat> lineStats = new ArrayList<>();
        NodeList childNodes = sourceFileElement.getChildNodes();
        for (int index = 0; index < childNodes.getLength(); index++) {
            Node childNode = childNodes.item(index);
            if (childNode.getNodeType() != Node.ELEMENT_NODE || !"line".equals(childNode.getNodeName())) {
                continue;
            }
            Element lineElement = (Element) childNode;
            lineStats.add(new CoverageLineStat(
                    parseIntAttribute(lineElement, "nr"),
                    parseIntAttribute(lineElement, "mi"),
                    parseIntAttribute(lineElement, "ci"),
                    parseIntAttribute(lineElement, "mb"),
                    parseIntAttribute(lineElement, "cb")));
        }
        return lineStats;
    }

    private int parseIntAttribute(Element element, String attributeName) {
        String value = element.getAttribute(attributeName);
        if (value == null || value.isBlank()) {
            return 0;
        }
        return Integer.parseInt(value);
    }
}
