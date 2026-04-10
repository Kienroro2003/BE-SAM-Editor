package com.sam.besameditor.models;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "analyzed_functions",
        indexes = {
                @Index(name = "idx_analyzed_functions_source_file_id", columnList = "source_file_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class AnalyzedFunction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_file_id", nullable = false)
    private SourceFile sourceFile;

    @Column(nullable = false)
    private String functionName;

    @Column(nullable = false, length = 1024)
    private String signature;

    @Column(nullable = false)
    private int startLine;

    @Column(nullable = false)
    private int endLine;

    @Column(nullable = false)
    private int cyclomaticComplexity;

    @OneToOne(mappedBy = "analyzedFunction", fetch = FetchType.LAZY, cascade = CascadeType.REMOVE, orphanRemoval = true)
    private FlowGraphData flowGraphData;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
