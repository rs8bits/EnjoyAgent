package com.enjoy.agent.market.infrastructure.persistence;

import com.enjoy.agent.market.domain.entity.MarketAsset;
import com.enjoy.agent.market.domain.enums.MarketAssetStatus;
import com.enjoy.agent.market.domain.enums.MarketAssetType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketAssetRepository extends JpaRepository<MarketAsset, Long> {

    @EntityGraph(attributePaths = {"submitterUser", "reviewedBy"})
    Optional<MarketAsset> findById(Long id);

    @EntityGraph(attributePaths = {"submitterUser", "reviewedBy"})
    Optional<MarketAsset> findByAssetTypeAndSourceTenantIdAndSourceEntityId(
            MarketAssetType assetType,
            Long sourceTenantId,
            Long sourceEntityId
    );

    @EntityGraph(attributePaths = {"submitterUser", "reviewedBy"})
    List<MarketAsset> findAllBySubmitterUser_IdOrderByIdDesc(Long userId);

    @EntityGraph(attributePaths = {"submitterUser", "reviewedBy"})
    List<MarketAsset> findAllByStatusOrderByPublishedAtDescIdDesc(MarketAssetStatus status);

    @EntityGraph(attributePaths = {"submitterUser", "reviewedBy"})
    List<MarketAsset> findAllByStatusAndAssetTypeOrderByPublishedAtDescIdDesc(MarketAssetStatus status, MarketAssetType assetType);

    @EntityGraph(attributePaths = {"submitterUser", "reviewedBy"})
    List<MarketAsset> findAllByOrderByIdDesc();

    @EntityGraph(attributePaths = {"submitterUser", "reviewedBy"})
    List<MarketAsset> findAllByStatusOrderByIdDesc(MarketAssetStatus status);

    @EntityGraph(attributePaths = {"submitterUser", "reviewedBy"})
    List<MarketAsset> findAllByAssetTypeOrderByIdDesc(MarketAssetType assetType);

    @EntityGraph(attributePaths = {"submitterUser", "reviewedBy"})
    List<MarketAsset> findAllByStatusAndAssetTypeOrderByIdDesc(MarketAssetStatus status, MarketAssetType assetType);
}
