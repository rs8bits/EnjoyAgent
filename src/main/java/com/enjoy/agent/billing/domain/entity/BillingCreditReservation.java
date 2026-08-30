package com.enjoy.agent.billing.domain.entity;

import com.enjoy.agent.billing.domain.enums.BillingReservationStatus;
import com.enjoy.agent.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "billing_credit_reservation")
public class BillingCreditReservation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "official_model_config_id", nullable = false)
    private Long officialModelConfigId;

    @Column(name = "reserved_amount", nullable = false, precision = 18, scale = 6)
    private BigDecimal reservedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BillingReservationStatus status = BillingReservationStatus.ACTIVE;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "linked_at")
    private Instant linkedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "release_claimed_at")
    private Instant releaseClaimedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getOfficialModelConfigId() { return officialModelConfigId; }
    public void setOfficialModelConfigId(Long officialModelConfigId) { this.officialModelConfigId = officialModelConfigId; }
    public BigDecimal getReservedAmount() { return reservedAmount; }
    public void setReservedAmount(BigDecimal reservedAmount) { this.reservedAmount = reservedAmount; }
    public BillingReservationStatus getStatus() { return status; }
    public void setStatus(BillingReservationStatus status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLinkedAt() { return linkedAt; }
    public void setLinkedAt(Instant linkedAt) { this.linkedAt = linkedAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
    public Instant getReleaseClaimedAt() { return releaseClaimedAt; }
    public void setReleaseClaimedAt(Instant releaseClaimedAt) { this.releaseClaimedAt = releaseClaimedAt; }
}
