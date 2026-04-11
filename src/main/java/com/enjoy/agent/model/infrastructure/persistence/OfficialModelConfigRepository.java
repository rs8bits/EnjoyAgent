package com.enjoy.agent.model.infrastructure.persistence;

import com.enjoy.agent.model.domain.entity.OfficialModelConfig;
import com.enjoy.agent.model.domain.enums.ModelType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficialModelConfigRepository extends JpaRepository<OfficialModelConfig, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<OfficialModelConfig> findAllByOrderByIdDesc();

    List<OfficialModelConfig> findAllByEnabledTrueOrderByIdDesc();

    List<OfficialModelConfig> findAllByEnabledTrueAndModelTypeOrderByIdDesc(ModelType modelType);

    Optional<OfficialModelConfig> findByIdAndEnabledTrue(Long id);
}
