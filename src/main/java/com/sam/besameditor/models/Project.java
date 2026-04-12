package com.sam.besameditor.models;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Getter
@Setter
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectSourceType sourceType = ProjectSourceType.GITHUB;

    @Column(nullable = false)
    private String sourceUrl;

    @Column(length = 1024)
    private String storagePath;

    @Column(length = 512)
    private String cloudinaryPublicId;

    @Column(length = 2048)
    private String cloudinaryUrl;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CloudinaryDeliveryType cloudinaryDeliveryType;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
