package com.sam.besameditor.repositories;

import com.sam.besameditor.models.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SourceFileRepository extends JpaRepository<SourceFile, Long> {

    List<SourceFile> findByProject_IdOrderByFilePathAsc(Long projectId);

    Optional<SourceFile> findByProject_IdAndFilePath(Long projectId, String filePath);

    long countByProject_Id(Long projectId);

    long deleteByProject_Id(Long projectId);

    long deleteByProject_IdAndFilePath(Long projectId, String filePath);

    long deleteByProject_IdAndFilePathStartingWith(Long projectId, String filePathPrefix);
}
