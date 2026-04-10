package com.sam.besameditor.controllers;

import com.sam.besameditor.dto.FunctionCfgResponse;
import com.sam.besameditor.dto.JavaFileAnalysisResponse;
import com.sam.besameditor.services.CodeAnalysisService;
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

    public AnalysisController(CodeAnalysisService codeAnalysisService) {
        this.codeAnalysisService = codeAnalysisService;
    }

    @PostMapping("/java")
    public ResponseEntity<JavaFileAnalysisResponse> analyzeJavaFile(
            @PathVariable Long projectId,
            @RequestParam("path") String path,
            Authentication authentication) {
        return ResponseEntity.ok(codeAnalysisService.analyzeJavaFile(projectId, path, authentication.getName()));
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
            Authentication authentication) {
        return ResponseEntity.ok(codeAnalysisService.getFunctionCfg(projectId, functionId, authentication.getName()));
    }
}
