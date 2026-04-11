package com.enjoy.agent.market.infrastructure.persistence;

import com.enjoy.agent.market.domain.entity.MarketAssetInstall;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketAssetInstallRepository extends JpaRepository<MarketAssetInstall, Long> {
}
