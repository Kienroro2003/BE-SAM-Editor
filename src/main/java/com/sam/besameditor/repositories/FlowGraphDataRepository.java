package com.sam.besameditor.repositories;

import com.sam.besameditor.models.FlowGraphData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FlowGraphDataRepository extends JpaRepository<FlowGraphData, Long> {

    Optional<FlowGraphData> findByAnalyzedFunction_Id(Long analyzedFunctionId);

    List<FlowGraphData> findByAnalyzedFunction_IdIn(Collection<Long> analyzedFunctionIds);

    long deleteByAnalyzedFunction_SourceFile_Id(Long sourceFileId);

    long deleteByAnalyzedFunction_SourceFile_Project_Id(Long projectId);

    long deleteByAnalyzedFunction_SourceFile_Project_IdAndAnalyzedFunction_SourceFile_FilePath(Long projectId, String filePath);

    long deleteByAnalyzedFunction_SourceFile_Project_IdAndAnalyzedFunction_SourceFile_FilePathStartingWith(Long projectId, String filePathPrefix);
}
