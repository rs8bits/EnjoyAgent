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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * 用户在当前租户客服会话中的长期摘要。
 *
 * <p>摘要只是为了控制发送给模型的上下文大小，不能替代 {@link SupportMessage}
 * 保存的完整历史。{@code coveredThroughSequence} 是关键水位：摘要只覆盖不大于
 * 该序号的消息，模型上下文只补充水位之后的最近消息。</p>
 */
@Entity
@Table(
        name = "support_conversation_memory",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_support_conversation_memory_conversation",
                columnNames = "conversation_id"
        )
)
public class SupportConversationMemory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private SupportConversation conversation;

    /**
     * tenant 和 user 虽然可以从 conversation 间接得到，仍在摘要表中显式保存。
     * 这样每条仓储查询都能在 SQL 层直接带上完整作用域，而不是先查出摘要再做
     * Java 层权限判断。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "summary", nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "covered_through_sequence", nullable = false)
    private long coveredThroughSequence;

    /**
     * 乐观锁版本由 JPA 自动递增，不应由业务代码手动修改。它阻止耗时较久的旧
     * 摘要任务在新任务之后提交，覆盖更新的水位和摘要正文。
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

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

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public long getCoveredThroughSequence() {
        return coveredThroughSequence;
    }

    public void setCoveredThroughSequence(long coveredThroughSequence) {
        this.coveredThroughSequence = coveredThroughSequence;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
