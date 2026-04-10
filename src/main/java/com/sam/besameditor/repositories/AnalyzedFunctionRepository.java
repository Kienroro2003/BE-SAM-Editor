package com.sam.besameditor.repositories;

import com.sam.besameditor.models.AnalyzedFunction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalyzedFunctionRepository extends JpaRepository<AnalyzedFunction, Long> {

    List<AnalyzedFunction> findBySourceFile_IdOrderByStartLineAsc(Long sourceFileId);

    Optional<AnalyzedFunction> findByIdAndSourceFile_Project_Id(Long id, Long projectId);

    long deleteBySourceFile_Id(Long sourceFileId);

    long deleteBySourceFile_Project_Id(Long projectId);

    long deleteBySourceFile_Project_IdAndSourceFile_FilePath(Long projectId, String filePath);

    long deleteBySourceFile_Project_IdAndSourceFile_FilePathStartingWith(Long projectId, String filePathPrefix);
}
