package com.enjoy.agent.chat.infrastructure.persistence;

import com.enjoy.agent.chat.domain.entity.ChatMessage;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 聊天消息仓储接口。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 按消息主键升序读取完整聊天记录。
     */
    List<ChatMessage> findAllBySession_IdOrderByIdAsc(Long sessionId);

    /**
     * 按消息主键倒序读取最近 N 条消息，用于构建滑动窗口。
     */
    List<ChatMessage> findAllBySession_IdOrderByIdDesc(Long sessionId, Pageable pageable);

    /**
     * 查询某条消息之后新增的全部消息。
     */
    List<ChatMessage> findAllBySession_IdAndIdGreaterThanOrderByIdAsc(Long sessionId, Long messageId);

    /**
     * 删除某个会话下的全部消息。
     */
    void deleteAllBySession_Id(Long sessionId);
}
