package com.enjoy.agent.workflow.infrastructure.persistence;

import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowEdgeRepository extends JpaRepository<WorkflowEdge, Long> {

    List<WorkflowEdge> findAllByWorkflow_Id(Long workflowId);

    void deleteAllByWorkflow_Id(Long workflowId);
}
