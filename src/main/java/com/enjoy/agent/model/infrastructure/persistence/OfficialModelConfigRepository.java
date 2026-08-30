package com.enjoy.agent.model.infrastructure.persistence;

import com.enjoy.agent.model.domain.entity.OfficialModelConfig;
import com.enjoy.agent.model.domain.enums.ModelType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficialModelConfigRepository extends JpaRepository<OfficialModelConfig, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    boolean existsByOfficialCredential_Id(Long officialCredentialId);

    @EntityGraph(attributePaths = "officialCredential")
    List<OfficialModelConfig> findAllByOrderByIdDesc();

    @EntityGraph(attributePaths = "officialCredential")
    Page<OfficialModelConfig> findAllPagedBy(Pageable pageable);

    @EntityGraph(attributePaths = "officialCredential")
    List<OfficialModelConfig> findAllByEnabledTrueOrderByIdDesc();

    @EntityGraph(attributePaths = "officialCredential")
    Page<OfficialModelConfig> findAllByEnabledTrue(Pageable pageable);

    @EntityGraph(attributePaths = "officialCredential")
    List<OfficialModelConfig> findAllByEnabledTrueAndModelTypeOrderByIdDesc(ModelType modelType);

    @EntityGraph(attributePaths = "officialCredential")
    Page<OfficialModelConfig> findAllByEnabledTrueAndModelType(ModelType modelType, Pageable pageable);

    @EntityGraph(attributePaths = "officialCredential")
    Optional<OfficialModelConfig> findByIdAndEnabledTrue(Long id);
}
