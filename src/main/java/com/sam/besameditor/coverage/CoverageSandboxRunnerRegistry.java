package com.sam.besameditor.coverage;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class CoverageSandboxRunnerRegistry {

    private static final Set<String> JAVA_LANGUAGES = Set.of("JAVA");
    private static final Set<String> JS_LANGUAGES = Set.of("JAVASCRIPT", "TYPESCRIPT", "JS", "TS", "JSX", "TSX");
    private static final Set<String> PYTHON_LANGUAGES = Set.of("PYTHON", "PY");

    private final DockerJavaCoverageSandboxRunner javaRunner;
    private final DockerJsCoverageSandboxRunner jsRunner;
    private final DockerPythonCoverageSandboxRunner pythonRunner;
    private final JaCoCoXmlParser jaCoCoXmlParser;
    private final LcovParser lcovParser;
    private final CoberturaXmlParser coberturaXmlParser;

    public CoverageSandboxRunnerRegistry(
            DockerJavaCoverageSandboxRunner javaRunner,
            DockerJsCoverageSandboxRunner jsRunner,
            DockerPythonCoverageSandboxRunner pythonRunner,
            JaCoCoXmlParser jaCoCoXmlParser,
            LcovParser lcovParser,
            CoberturaXmlParser coberturaXmlParser) {
        this.javaRunner = javaRunner;
        this.jsRunner = jsRunner;
        this.pythonRunner = pythonRunner;
        this.jaCoCoXmlParser = jaCoCoXmlParser;
        this.lcovParser = lcovParser;
        this.coberturaXmlParser = coberturaXmlParser;
    }

    public CoverageSandboxRunner getRunner(String language) {
        String normalizedLanguage = normalize(language);
        if (JAVA_LANGUAGES.contains(normalizedLanguage)) {
            return javaRunner;
        }
        if (JS_LANGUAGES.contains(normalizedLanguage)) {
            return jsRunner;
        }
        if (PYTHON_LANGUAGES.contains(normalizedLanguage)) {
            return pythonRunner;
        }
        throw new IllegalArgumentException("Coverage is not supported for language: " + language);
    }

    public CoverageReportParser getParser(String language) {
        String normalizedLanguage = normalize(language);
        if (JAVA_LANGUAGES.contains(normalizedLanguage)) {
            return jaCoCoXmlParser;
        }
        if (JS_LANGUAGES.contains(normalizedLanguage)) {
            return lcovParser;
        }
        if (PYTHON_LANGUAGES.contains(normalizedLanguage)) {
            return coberturaXmlParser;
        }
        throw new IllegalArgumentException("Coverage parsing is not supported for language: " + language);
    }

    public boolean isSupported(String language) {
        String normalizedLanguage = normalize(language);
        return JAVA_LANGUAGES.contains(normalizedLanguage)
                || JS_LANGUAGES.contains(normalizedLanguage)
                || PYTHON_LANGUAGES.contains(normalizedLanguage);
    }

    private String normalize(String language) {
        if (language == null || language.isBlank()) {
            return "";
        }
        return language.trim().toUpperCase();
    }
}
