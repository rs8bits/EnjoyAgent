package com.enjoy.agent.model.infrastructure.persistence;

import com.enjoy.agent.model.domain.entity.OfficialModelConfig;
import com.enjoy.agent.model.domain.enums.ModelType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficialModelConfigRepository extends JpaRepository<OfficialModelConfig, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<OfficialModelConfig> findAllByOrderByIdDesc();

    Page<OfficialModelConfig> findAllPagedBy(Pageable pageable);

    List<OfficialModelConfig> findAllByEnabledTrueOrderByIdDesc();

    Page<OfficialModelConfig> findAllByEnabledTrue(Pageable pageable);

    List<OfficialModelConfig> findAllByEnabledTrueAndModelTypeOrderByIdDesc(ModelType modelType);

    Page<OfficialModelConfig> findAllByEnabledTrueAndModelType(ModelType modelType, Pageable pageable);

    Optional<OfficialModelConfig> findByIdAndEnabledTrue(Long id);
}
