package com.sam.besameditor.repositories;

import com.sam.besameditor.models.AnalyzedFunction;
import com.sam.besameditor.models.FlowGraphData;
import com.sam.besameditor.models.Project;
import com.sam.besameditor.models.ProjectSourceType;
import com.sam.besameditor.models.SourceFile;
import com.sam.besameditor.models.SourceFileStatus;
import com.sam.besameditor.models.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AnalyzedFunctionRepositoryJpaTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private SourceFileRepository sourceFileRepository;

    @Autowired
    private AnalyzedFunctionRepository analyzedFunctionRepository;

    @Autowired
    private FlowGraphDataRepository flowGraphDataRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void deleteBySourceFileId_ShouldCascadeDeleteFlowGraphData() {
        User user = new User();
        user.setEmail("user@test.com");
        user.setFullName("Test User");
        User savedUser = userRepository.save(user);

        Project project = new Project();
        project.setUser(savedUser);
        project.setName("repo");
        project.setSourceType(ProjectSourceType.GITHUB);
        project.setSourceUrl("https://github.com/test/repo");
        Project savedProject = projectRepository.save(project);

        SourceFile sourceFile = new SourceFile();
        sourceFile.setProject(savedProject);
        sourceFile.setFilePath("src/AverageCalculator.java");
        sourceFile.setLanguage("JAVA");
        sourceFile.setStatus(SourceFileStatus.AVAILABLE);
        SourceFile savedSourceFile = sourceFileRepository.save(sourceFile);

        AnalyzedFunction analyzedFunction = new AnalyzedFunction();
        analyzedFunction.setSourceFile(savedSourceFile);
        analyzedFunction.setFunctionName("average");
        analyzedFunction.setSignature("int average(int[] value, int minimum, int maximum)");
        analyzedFunction.setStartLine(3);
        analyzedFunction.setEndLine(28);
        analyzedFunction.setCyclomaticComplexity(4);
        AnalyzedFunction savedFunction = analyzedFunctionRepository.save(analyzedFunction);

        FlowGraphData flowGraphData = new FlowGraphData();
        flowGraphData.setAnalyzedFunction(savedFunction);
        flowGraphData.setNodesJson("[]");
        flowGraphData.setEdgesJson("[]");
        flowGraphData.setEntryNodeId("n1");
        flowGraphData.setExitNodeIdsJson("[\"n2\"]");
        flowGraphDataRepository.save(flowGraphData);

        entityManager.flush();
        entityManager.clear();

        analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(savedSourceFile.getId());
        analyzedFunctionRepository.deleteBySourceFile_Id(savedSourceFile.getId());
        entityManager.flush();

        assertTrue(flowGraphDataRepository.findAll().isEmpty());
        assertEquals(0, analyzedFunctionRepository.findBySourceFile_IdOrderByStartLineAsc(savedSourceFile.getId()).size());
    }
}
