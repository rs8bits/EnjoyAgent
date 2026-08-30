package com.enjoy.agent.workflow.infrastructure.persistence;

import com.enjoy.agent.workflow.domain.entity.WorkflowEdge;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowEdgeRepository extends JpaRepository<WorkflowEdge, Long> {

    List<WorkflowEdge> findAllByWorkflow_Id(Long workflowId);

    long countByWorkflow_Id(Long workflowId);

    @Query("select edge.workflow.id, count(edge.id) from WorkflowEdge edge "
            + "where edge.workflow.id in :workflowIds group by edge.workflow.id")
    List<Object[]> countGroupedByWorkflowId(@Param("workflowIds") List<Long> workflowIds);

    void deleteAllByWorkflow_Id(Long workflowId);
}
