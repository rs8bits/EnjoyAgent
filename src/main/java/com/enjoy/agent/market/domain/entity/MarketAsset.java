package com.enjoy.agent.market.domain.entity;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.market.domain.enums.MarketAssetStatus;
import com.enjoy.agent.market.domain.enums.MarketAssetType;
import com.enjoy.agent.shared.domain.BaseEntity;
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
import java.time.Instant;

/**
 * 共享市场资产。
 */
@Entity
@Table(
        name = "market_asset",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_market_asset_source",
                columnNames = {"asset_type", "source_tenant_id", "source_entity_id"}
        )
)
public class MarketAsset extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 32)
    private MarketAssetType assetType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submitter_user_id", nullable = false)
    private AppUser submitterUser;

    @Column(name = "source_tenant_id", nullable = false)
    private Long sourceTenantId;

    @Column(name = "source_entity_id", nullable = false)
    private Long sourceEntityId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "summary", length = 512)
    private String summary;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MarketAssetStatus status = MarketAssetStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private AppUser reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_remark", length = 512)
    private String reviewRemark;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "install_count", nullable = false)
    private Integer installCount = 0;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public MarketAssetType getAssetType() {
        return assetType;
    }

    public void setAssetType(MarketAssetType assetType) {
        this.assetType = assetType;
    }

    public AppUser getSubmitterUser() {
        return submitterUser;
    }

    public void setSubmitterUser(AppUser submitterUser) {
        this.submitterUser = submitterUser;
    }

    public Long getSourceTenantId() {
        return sourceTenantId;
    }

    public void setSourceTenantId(Long sourceTenantId) {
        this.sourceTenantId = sourceTenantId;
    }

    public Long getSourceEntityId() {
        return sourceEntityId;
    }

    public void setSourceEntityId(Long sourceEntityId) {
        this.sourceEntityId = sourceEntityId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
    }

    public MarketAssetStatus getStatus() {
        return status;
    }

    public void setStatus(MarketAssetStatus status) {
        this.status = status;
    }

    public AppUser getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(AppUser reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewRemark() {
        return reviewRemark;
    }

    public void setReviewRemark(String reviewRemark) {
        this.reviewRemark = reviewRemark;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Integer getInstallCount() {
        return installCount;
    }

    public void setInstallCount(Integer installCount) {
        this.installCount = installCount;
    }
}
