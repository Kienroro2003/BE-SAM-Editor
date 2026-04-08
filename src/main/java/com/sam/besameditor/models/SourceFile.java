package com.sam.besameditor.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "source_files",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_source_files_project_path", columnNames = {"project_id", "file_path"})
        },
        indexes = {
                @Index(name = "idx_source_files_project_id", columnList = "project_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class SourceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 512)
    private String filePath;

    @Column(nullable = false, length = 30)
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceFileStatus status = SourceFileStatus.AVAILABLE;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
