package com.sam.besameditor.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "flow_graph_data")
@Getter
@Setter
@NoArgsConstructor
public class FlowGraphData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "analyzed_function_id", nullable = false, unique = true)
    private AnalyzedFunction analyzedFunction;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String nodesJson;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String edgesJson;

    @Column(nullable = false)
    private String entryNodeId;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String exitNodeIdsJson;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
