package com.sam.besameditor.repositories;

import com.sam.besameditor.models.SourceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SourceFileRepository extends JpaRepository<SourceFile, Long> {

    List<SourceFile> findByProject_IdOrderByFilePathAsc(Long projectId);
}
