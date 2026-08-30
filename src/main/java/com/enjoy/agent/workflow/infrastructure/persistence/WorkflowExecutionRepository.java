package com.enjoy.agent.workflow.infrastructure.persistence;

import com.enjoy.agent.workflow.domain.entity.WorkflowExecution;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, Long> {

    Page<WorkflowExecution> findAllByWorkflow_IdAndTenant_IdOrderByCreatedAtDesc(
            Long workflowId, Long tenantId, Pageable pageable);

    Optional<WorkflowExecution> findByIdAndTenant_Id(Long executionId, Long tenantId);
}
