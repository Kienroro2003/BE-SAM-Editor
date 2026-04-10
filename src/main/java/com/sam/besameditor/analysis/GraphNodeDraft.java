package com.sam.besameditor.analysis;

public record GraphNodeDraft(
        String id,
        String type,
        String label,
        Integer startLine,
        Integer endLine) {
}
