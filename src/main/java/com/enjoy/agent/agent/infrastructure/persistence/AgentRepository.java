package com.enjoy.agent.agent.infrastructure.persistence;

import com.enjoy.agent.agent.domain.entity.Agent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Agent 仓储接口。
 */
public interface AgentRepository extends JpaRepository<Agent, Long> {

    /**
     * 查询当前租户下的全部 Agent，并预加载租户和模型配置。
     */
    @EntityGraph(attributePaths = {"tenant", "modelConfig", "knowledgeBase", "rerankModelConfig"})
    List<Agent> findAllByTenant_IdOrderByIdDesc(Long tenantId);

    /**
     * 按 ID 和租户查询 Agent，防止跨租户访问。
     */
    @EntityGraph(attributePaths = {
            "tenant",
            "modelConfig",
            "modelConfig.credential",
            "knowledgeBase",
            "knowledgeBase.embeddingModelConfig",
            "knowledgeBase.embeddingModelConfig.credential",
            "rerankModelConfig",
            "rerankModelConfig.credential"
    })
    Optional<Agent> findByIdAndTenant_Id(Long id, Long tenantId);

    /**
     * 判断当前租户下是否已有同名 Agent。
     */
    boolean existsByTenant_IdAndName(Long tenantId, String name);

    /**
     * 更新时排除自己，判断是否和其他 Agent 重名。
     */
    boolean existsByTenant_IdAndNameAndIdNot(Long tenantId, String name, Long id);

    /**
     * 判断是否还有 Agent 正在使用某个知识库。
     */
    boolean existsByKnowledgeBase_Id(Long knowledgeBaseId);
}
