package com.enjoy.agent.support.domain.entity;

import com.enjoy.agent.shared.domain.BaseEntity;
import com.enjoy.agent.support.domain.enums.SupportMessageRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** 客服会话中可由用户查看的完整消息。 */
@Entity
@Table(
        name = "support_message",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_support_message_conversation_sequence",
                columnNames = {"conversation_id", "sequence"}
        )
)
public class SupportMessage extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private SupportConversation conversation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "turn_id", nullable = false)
    private SupportTurn turn;

    @Column(name = "sequence", nullable = false)
    private long sequence;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private SupportMessageRole role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public SupportConversation getConversation() {
        return conversation;
    }

    public void setConversation(SupportConversation conversation) {
        this.conversation = conversation;
    }

    public SupportTurn getTurn() {
        return turn;
    }

    public void setTurn(SupportTurn turn) {
        this.turn = turn;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public SupportMessageRole getRole() {
        return role;
    }

    public void setRole(SupportMessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
