package com.sam.besameditor.repositories;

import com.sam.besameditor.models.Project;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByUser_IdOrderByUpdatedAtDesc(Long userId);

    Optional<Project> findByIdAndUser_Id(Long id, Long userId);
}
