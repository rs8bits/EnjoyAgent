package com.enjoy.agent.market.domain.entity;

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

/**
 * 市场资产安装记录。
 */
@Entity
@Table(name = "market_asset_install")
public class MarketAssetInstall extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "market_asset_id", nullable = false)
    private MarketAsset marketAsset;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "installer_user_id", nullable = false)
    private AppUser installerUser;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "installed_entity_id", nullable = false)
    private Long installedEntityId;

    @Column(name = "installed_name", nullable = false, length = 128)
    private String installedName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public MarketAsset getMarketAsset() {
        return marketAsset;
    }

    public void setMarketAsset(MarketAsset marketAsset) {
        this.marketAsset = marketAsset;
    }

    public AppUser getInstallerUser() {
        return installerUser;
    }

    public void setInstallerUser(AppUser installerUser) {
        this.installerUser = installerUser;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setTenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public Long getInstalledEntityId() {
        return installedEntityId;
    }

    public void setInstalledEntityId(Long installedEntityId) {
        this.installedEntityId = installedEntityId;
    }

    public String getInstalledName() {
        return installedName;
    }

    public void setInstalledName(String installedName) {
        this.installedName = installedName;
    }
}
