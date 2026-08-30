package com.enjoy.agent.workflow.infrastructure.persistence;

import com.enjoy.agent.workflow.domain.entity.WorkflowNode;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowNodeRepository extends JpaRepository<WorkflowNode, Long> {

    List<WorkflowNode> findAllByWorkflow_IdOrderByIdAsc(Long workflowId);

    long countByWorkflow_Id(Long workflowId);

    List<WorkflowNode> findAllByWorkflow_IdAndNodeKeyIn(Long workflowId, List<String> nodeKeys);

    List<WorkflowNode> findAllByWorkflow_IdAndIdIn(Long workflowId, List<Long> ids);

    @Query("select node.workflow.id, count(node.id) from WorkflowNode node "
            + "where node.workflow.id in :workflowIds group by node.workflow.id")
    List<Object[]> countGroupedByWorkflowId(@Param("workflowIds") List<Long> workflowIds);

    void deleteAllByWorkflow_Id(Long workflowId);
}
