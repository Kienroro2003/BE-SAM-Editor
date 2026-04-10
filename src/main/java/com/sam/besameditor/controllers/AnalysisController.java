package com.sam.besameditor.controllers;

import com.sam.besameditor.dto.FunctionCfgResponse;
import com.sam.besameditor.dto.JavaFileAnalysisResponse;
import com.sam.besameditor.dto.JavaFileCoverageResponse;
import com.sam.besameditor.services.CodeAnalysisService;
import com.sam.besameditor.services.JavaCoverageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspaces/{projectId}/analysis")
public class AnalysisController {

    private final CodeAnalysisService codeAnalysisService;
    private final JavaCoverageService javaCoverageService;

    public AnalysisController(CodeAnalysisService codeAnalysisService, JavaCoverageService javaCoverageService) {
        this.codeAnalysisService = codeAnalysisService;
        this.javaCoverageService = javaCoverageService;
    }

    @PostMapping("/java")
    public ResponseEntity<JavaFileAnalysisResponse> analyzeJavaFile(
            @PathVariable Long projectId,
            @RequestParam("path") String path,
            Authentication authentication) {
        return ResponseEntity.ok(codeAnalysisService.analyzeJavaFile(projectId, path, authentication.getName()));
    }

    @PostMapping("/java/coverage")
    public ResponseEntity<JavaFileCoverageResponse> runJavaCoverage(
            @PathVariable Long projectId,
            @RequestParam("path") String path,
            Authentication authentication) {
        return ResponseEntity.ok(javaCoverageService.runJavaCoverage(projectId, path, authentication.getName()));
    }

    @GetMapping("/functions")
    public ResponseEntity<JavaFileAnalysisResponse> getFunctionSummaries(
            @PathVariable Long projectId,
            @RequestParam("path") String path,
            Authentication authentication) {
        return ResponseEntity.ok(codeAnalysisService.getFunctionSummaries(projectId, path, authentication.getName()));
    }

    @GetMapping("/functions/{functionId}/cfg")
    public ResponseEntity<FunctionCfgResponse> getFunctionCfg(
            @PathVariable Long projectId,
            @PathVariable Long functionId,
            @RequestParam(value = "coverageRunId", required = false) Long coverageRunId,
            Authentication authentication) {
        if (coverageRunId == null) {
            return ResponseEntity.ok(codeAnalysisService.getFunctionCfg(projectId, functionId, authentication.getName()));
        }
        return ResponseEntity.ok(javaCoverageService.getFunctionCfgWithCoverage(projectId, functionId, coverageRunId, authentication.getName()));
    }
}
