package com.sam.besameditor.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "coverage_runs",
        indexes = {
                @Index(name = "idx_coverage_runs_project_file", columnList = "projectId, sourceFilePath")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class CoverageRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 512)
    private String sourceFilePath;

    @Column(nullable = false, length = 30)
    private String language;

    @Column(nullable = false, length = 64)
    private String sourceHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CoverageRunStatus status;

    @Column(nullable = false, length = 2048)
    private String command;

    private Integer exitCode;

    @Column(columnDefinition = "LONGTEXT")
    private String stdoutText;

    @Column(columnDefinition = "LONGTEXT")
    private String stderrText;

    @Column(columnDefinition = "LONGTEXT")
    private String lineCoverageJson;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
