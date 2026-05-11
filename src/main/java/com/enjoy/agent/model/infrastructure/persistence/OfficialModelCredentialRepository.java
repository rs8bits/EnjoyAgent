package com.enjoy.agent.model.infrastructure.persistence;

import com.enjoy.agent.model.domain.entity.OfficialModelCredential;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfficialModelCredentialRepository extends JpaRepository<OfficialModelCredential, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<OfficialModelCredential> findAllByOrderByIdDesc();

    Page<OfficialModelCredential> findAllPagedBy(Pageable pageable);

    Optional<OfficialModelCredential> findByIdAndEnabledTrue(Long id);
}
