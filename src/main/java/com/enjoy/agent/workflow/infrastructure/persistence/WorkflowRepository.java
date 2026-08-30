package com.enjoy.agent.workflow.infrastructure.persistence;

import com.enjoy.agent.workflow.domain.entity.Workflow;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    @EntityGraph(attributePaths = "tenant")
    Page<Workflow> findAllByTenant_Id(Long tenantId, Pageable pageable);

    @EntityGraph(attributePaths = "tenant")
    Optional<Workflow> findByIdAndTenant_Id(Long id, Long tenantId);

    boolean existsByTenant_IdAndName(Long tenantId, String name);

    boolean existsByTenant_IdAndNameAndIdNot(Long tenantId, String name, Long id);
}
