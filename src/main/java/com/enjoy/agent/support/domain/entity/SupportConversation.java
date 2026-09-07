package com.enjoy.agent.support.domain.entity;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.shared.domain.BaseEntity;
import com.enjoy.agent.tenant.domain.entity.Tenant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 用户在某个租户中的唯一长期客服会话。
 *
 * <p>唯一键必须同时包含 user 和 tenant。用户切换工作区后，旧工作区的消息不会被
 * 注入新工作区的模型上下文，从数据模型层面降低跨租户泄漏风险。</p>
 */
@Entity
@Table(
        name = "support_conversation",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_support_conversation_tenant_user",
                columnNames = {"tenant_id", "user_id"}
        )
)
public class SupportConversation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /**
     * 会话内最后一个消息序号。新增消息时先悲观锁定会话，再加一，确保多实例下
     * sequence 仍然严格递增且没有重复值。
     */
    @Column(name = "last_message_sequence", nullable = false)
    private long lastMessageSequence;

    public long allocateNextMessageSequence() {
        return ++lastMessageSequence;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public long getLastMessageSequence() {
        return lastMessageSequence;
    }

    public void setLastMessageSequence(long lastMessageSequence) {
        this.lastMessageSequence = lastMessageSequence;
    }
}
