package com.enjoy.agent.support.domain.entity;

import com.enjoy.agent.shared.domain.BaseEntity;
import com.enjoy.agent.support.domain.enums.SupportRouteType;
import com.enjoy.agent.support.domain.enums.SupportTurnStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * 一次用户提问及其处理状态。
 *
 * <p>Turn 是幂等、并发栅栏和模型调用元数据的聚合点。最终文本保存在
 * {@link SupportMessage}，这样消息历史查询不需要读取运行时日志。</p>
 */
@Entity
@Table(
        name = "support_turn",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_support_turn_conversation_key",
                columnNames = {"conversation_id", "idempotency_key"}
        )
)
public class SupportTurn extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    private SupportConversation conversation;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /**
     * 当前执行尝试的所有权令牌（fencing token）。同一幂等 Turn 被重新接管时会换新，
     * 所有晚到的写操作必须携带并匹配它，防止旧线程覆盖新线程结果。
     */
    @Column(name = "owner_token", nullable = false, columnDefinition = "uuid")
    private UUID ownerToken;

    /** 与 ownerToken 配合，阻止基于旧实体快照的并发覆盖。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SupportTurnStatus status;

    @Column(name = "intent_code", length = 64)
    private String intentCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "route_type", length = 16)
    private SupportRouteType routeType;

    @Column(name = "model_provider", length = 32)
    private String modelProvider;

    @Column(name = "model_name", length = 128)
    private String modelName;

    @Column(name = "official_model_config_id")
    private Long officialModelConfigId;

    @Column(name = "model_latency_ms")
    private Long modelLatencyMs;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /**
     * 进程意外退出后，下一次请求可以接管已过期的 RUNNING Turn，避免唯一客服会话
     * 永久卡死。它是运行租约，不是用户授权令牌。
     */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public SupportConversation getConversation() {
        return conversation;
    }

    public void setConversation(SupportConversation conversation) {
        this.conversation = conversation;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public UUID getOwnerToken() {
        return ownerToken;
    }

    public void setOwnerToken(UUID ownerToken) {
        this.ownerToken = ownerToken;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public SupportTurnStatus getStatus() {
        return status;
    }

    public void setStatus(SupportTurnStatus status) {
        this.status = status;
    }

    public String getIntentCode() {
        return intentCode;
    }

    public void setIntentCode(String intentCode) {
        this.intentCode = intentCode;
    }

    public SupportRouteType getRouteType() {
        return routeType;
    }

    public void setRouteType(SupportRouteType routeType) {
        this.routeType = routeType;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Long getOfficialModelConfigId() {
        return officialModelConfigId;
    }

    public void setOfficialModelConfigId(Long officialModelConfigId) {
        this.officialModelConfigId = officialModelConfigId;
    }

    public Long getModelLatencyMs() {
        return modelLatencyMs;
    }

    public void setModelLatencyMs(Long modelLatencyMs) {
        this.modelLatencyMs = modelLatencyMs;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }

    public void setLeaseExpiresAt(Instant leaseExpiresAt) {
        this.leaseExpiresAt = leaseExpiresAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
