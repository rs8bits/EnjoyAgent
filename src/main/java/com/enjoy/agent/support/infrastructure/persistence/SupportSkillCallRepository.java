package com.enjoy.agent.support.infrastructure.persistence;

import com.enjoy.agent.support.domain.entity.SupportSkillCall;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Skill 调用审计仓储。完整请求和结果只归档，不直接塞进普通聊天上下文。 */
public interface SupportSkillCallRepository extends JpaRepository<SupportSkillCall, UUID> {

    List<SupportSkillCall> findAllByTurn_IdOrderByCallIndexAsc(UUID turnId);

    Optional<SupportSkillCall> findByIdAndTenant_IdAndUser_Id(UUID id, Long tenantId, Long userId);

    @Query("select coalesce(max(call.callIndex), 0) from SupportSkillCall call where call.turn.id = :turnId")
    int findMaxCallIndexByTurnId(@Param("turnId") UUID turnId);
}
