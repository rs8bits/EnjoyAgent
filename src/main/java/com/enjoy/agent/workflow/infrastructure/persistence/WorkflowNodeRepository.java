package com.enjoy.agent.workflow.infrastructure.persistence;

import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowNodeRepository extends JpaRepository<WorkflowNode, Long> {

    List<WorkflowNode> findAllByWorkflow_IdOrderByIdAsc(Long workflowId);

    void deleteAllByWorkflow_Id(Long workflowId);
}
