package com.sam.besameditor.repositories;

import com.sam.besameditor.models.CoverageRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CoverageRunRepository extends JpaRepository<CoverageRun, Long> {

    Optional<CoverageRun> findByIdAndProjectId(Long id, Long projectId);

    List<CoverageRun> findByProjectIdAndSourceFilePathOrderByCreatedAtDesc(Long projectId, String sourceFilePath);
}
