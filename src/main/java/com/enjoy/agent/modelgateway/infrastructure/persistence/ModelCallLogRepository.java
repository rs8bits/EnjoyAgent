package com.enjoy.agent.modelgateway.infrastructure.persistence;

import com.enjoy.agent.modelgateway.domain.entity.ModelCallLog;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 模型调用日志仓储接口。
 */
public interface ModelCallLogRepository extends JpaRepository<ModelCallLog, Long> {

    /**
     * 删除某个会话下的全部模型调用日志。
     */
    void deleteAllBySessionId(Long sessionId);
}
