package com.enjoy.agent.support.infrastructure.persistence;

import com.enjoy.agent.support.domain.entity.SupportMessage;
import com.enjoy.agent.support.domain.enums.SupportTurnStatus;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 客服消息仓储。消息历史一律按稳定 sequence 查询。 */
public interface SupportMessageRepository extends JpaRepository<SupportMessage, Long> {

    Slice<SupportMessage> findAllByConversation_IdOrderBySequenceDesc(Long conversationId, Pageable pageable);

    Slice<SupportMessage> findAllByConversation_IdAndSequenceLessThanOrderBySequenceDesc(
            Long conversationId,
            long beforeSequence,
            Pageable pageable
    );

    List<SupportMessage> findAllByTurn_IdOrderBySequenceAsc(UUID turnId);

    /**
     * 模型只读取已完成历史和当前 Turn。失败请求仍完整归档并可在页面查看，但不会
     * 在没有对应助手回答的情况下污染下一轮对话语义。
     */
    @Query("""
            select message
            from SupportMessage message
            where message.conversation.id = :conversationId
              and (message.turn.status = :completedStatus or message.turn.id = :currentTurnId)
            order by message.sequence desc
            """)
    List<SupportMessage> findLatestForModel(
            @Param("conversationId") Long conversationId,
            @Param("currentTurnId") UUID currentTurnId,
            @Param("completedStatus") SupportTurnStatus completedStatus,
            Pageable pageable
    );

    /**
     * 读取水位之后、且不属于“最近保留 Turn”的已完成消息，供增量摘要使用。
     *
     * <p>查询仍限定 conversation，调用前还会校验 tenant + user + conversation
     * 归属。这里只返回 COMPLETED Turn，失败请求不会进入长期记忆。</p>
     */
    @Query("""
            select message
            from SupportMessage message
            where message.conversation.id = :conversationId
              and message.turn.status = :completedStatus
              and message.sequence > :coveredThroughSequence
              and message.turn.id not in :excludedTurnIds
            order by message.sequence asc
            """)
    List<SupportMessage> findPendingForSummary(
            @Param("conversationId") Long conversationId,
            @Param("coveredThroughSequence") long coveredThroughSequence,
            @Param("completedStatus") SupportTurnStatus completedStatus,
            @Param("excludedTurnIds") Collection<UUID> excludedTurnIds,
            Pageable pageable
    );
}
