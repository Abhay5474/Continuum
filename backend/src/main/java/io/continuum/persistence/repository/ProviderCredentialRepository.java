package io.continuum.persistence.repository;

import io.continuum.persistence.entity.ProviderCredentialEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderCredentialRepository extends JpaRepository<ProviderCredentialEntity, Long> {

    Optional<ProviderCredentialEntity> findByDeveloperIdAndProvider(String developerId, String provider);

    List<ProviderCredentialEntity> findByDeveloperId(String developerId);
}
