package com.sam.besameditor.controllers;

import com.sam.besameditor.dto.AnalysisGraphEdgeResponse;
import com.sam.besameditor.dto.AnalysisGraphNodeResponse;
import com.sam.besameditor.dto.CoverageFunctionSummaryResponse;
import com.sam.besameditor.dto.FunctionAnalysisSummaryResponse;
import com.sam.besameditor.dto.FunctionCfgResponse;
import com.sam.besameditor.dto.JavaFileAnalysisResponse;
import com.sam.besameditor.dto.JavaFileCoverageResponse;
import com.sam.besameditor.services.CodeAnalysisService;
import com.sam.besameditor.services.JavaCoverageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisControllerTest {

    @Mock
    private CodeAnalysisService codeAnalysisService;
    @Mock
    private JavaCoverageService javaCoverageService;

    @InjectMocks
    private AnalysisController analysisController;

    @Test
    void analyzeJavaFile_ShouldReturnResponse() {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        JavaFileAnalysisResponse serviceResponse = new JavaFileAnalysisResponse(
                10L,
                "src/App.java",
                "JAVA",
                false,
                List.of(new FunctionAnalysisSummaryResponse(1L, "run", "void run()", 5, 12, 2)));
        when(codeAnalysisService.analyzeJavaFile(10L, "src/App.java", "user@test.com"))
                .thenReturn(serviceResponse);

        JavaFileAnalysisResponse response = analysisController
                .analyzeJavaFile(10L, "src/App.java", authentication)
                .getBody();

        assertEquals(10L, response.getProjectId());
        assertEquals("src/App.java", response.getPath());
        assertEquals(1, response.getFunctions().size());
    }

    @Test
    void getFunctionSummaries_ShouldReturnCachedFunctions() {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        JavaFileAnalysisResponse serviceResponse = new JavaFileAnalysisResponse(
                10L,
                "src/App.java",
                "JAVA",
                true,
                List.of(new FunctionAnalysisSummaryResponse(1L, "run", "void run()", 5, 12, 2)));
        when(codeAnalysisService.getFunctionSummaries(10L, "src/App.java", "user@test.com"))
                .thenReturn(serviceResponse);

        JavaFileAnalysisResponse response = analysisController
                .getFunctionSummaries(10L, "src/App.java", authentication)
                .getBody();

        assertEquals(true, response.isCached());
        assertEquals("run", response.getFunctions().get(0).getFunctionName());
    }

    @Test
    void getFunctionCfg_ShouldReturnGraphPayload() {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        FunctionCfgResponse serviceResponse = new FunctionCfgResponse(
                1L,
                "run",
                "void run()",
                5,
                12,
                2,
                "n1",
                List.of("n4"),
                List.of(new AnalysisGraphNodeResponse("n1", "ENTRY", "run", 5, 5)),
                List.of(new AnalysisGraphEdgeResponse("e1", "n1", "n4", null)));
        when(codeAnalysisService.getFunctionCfg(10L, 1L, "user@test.com"))
                .thenReturn(serviceResponse);

        FunctionCfgResponse response = analysisController
                .getFunctionCfg(10L, 1L, null, authentication)
                .getBody();

        assertEquals(1L, response.getFunctionId());
        assertEquals("n1", response.getEntryNodeId());
        assertEquals(1, response.getNodes().size());
    }

    @Test
    void runJavaCoverage_ShouldReturnCoveragePayload() {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        JavaFileCoverageResponse serviceResponse = new JavaFileCoverageResponse(
                55L,
                10L,
                "src/App.java",
                "JAVA",
                "SUCCEEDED",
                0,
                true,
                "./mvnw test",
                "ok",
                "",
                null,
                null,
                List.of(new CoverageFunctionSummaryResponse(1L, "run", "void run()", 5, 12, 2, "COVERED", 1, 0, 0, 0)));
        when(javaCoverageService.runJavaCoverage(10L, "src/App.java", "user@test.com"))
                .thenReturn(serviceResponse);

        JavaFileCoverageResponse response = analysisController
                .runJavaCoverage(10L, "src/App.java", authentication)
                .getBody();

        assertEquals(55L, response.getCoverageRunId());
        assertEquals(true, response.isOverlayAvailable());
        assertEquals(1, response.getFunctions().size());
    }

    @Test
    void getFunctionCfg_ShouldUseCoverageService_WhenCoverageRunIdProvided() {
        Authentication authentication = org.mockito.Mockito.mock(Authentication.class);
        when(authentication.getName()).thenReturn("user@test.com");

        FunctionCfgResponse serviceResponse = new FunctionCfgResponse(
                1L,
                "run",
                "void run()",
                5,
                12,
                2,
                "n1",
                List.of("n4"),
                List.of(new AnalysisGraphNodeResponse("n1", "STATEMENT", "run", 5, 5, "COVERED", 1, 0, 0, 0)),
                List.of(new AnalysisGraphEdgeResponse("e1", "n1", "n4", null)),
                77L,
                "COVERED",
                1,
                0,
                0,
                0);
        when(javaCoverageService.getFunctionCfgWithCoverage(10L, 1L, 77L, "user@test.com"))
                .thenReturn(serviceResponse);

        FunctionCfgResponse response = analysisController
                .getFunctionCfg(10L, 1L, 77L, authentication)
                .getBody();

        assertEquals(77L, response.getCoverageRunId());
        assertEquals("COVERED", response.getCoverageStatus());
    }
}
