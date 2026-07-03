package io.continuum.autopilot;

import io.continuum.autopilot.model.PolicyBundle;
import io.continuum.autopilot.model.PolicyStatus;
import io.continuum.common.Json;
import io.continuum.persistence.entity.PolicyBundleEntity;
import io.continuum.persistence.repository.PolicyBundleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Creates and reads versioned, content-immutable policy bundles. New content is
 * always a new version/row; existing rows are never rewritten, preserving full
 * history and rollback targets.
 */
@Service
public class PolicyBundleService {

    private final PolicyBundleRepository repo;
    private final Json json;

    public PolicyBundleService(PolicyBundleRepository repo, Json json) {
        this.repo = repo;
        this.json = json;
    }

    @Transactional
    public PolicyBundleEntity create(String developerId, PolicyBundle content, PolicyStatus status,
                                     Long parentId, String source, String notes) {
        int version = (int) repo.countByDeveloperId(developerId) + 1;
        return repo.save(new PolicyBundleEntity(developerId, version, status,
                json.write(content), parentId, source, notes));
    }

    @Transactional
    public void setStatus(Long bundleId, PolicyStatus status) {
        repo.findById(bundleId).ifPresent(b -> {
            b.setStatus(status);
            repo.save(b);
        });
    }

    @Transactional(readOnly = true)
    public Optional<PolicyBundleEntity> entity(Long bundleId) {
        return bundleId == null ? Optional.empty() : repo.findById(bundleId);
    }

    @Transactional(readOnly = true)
    public Optional<PolicyBundle> bundle(Long bundleId) {
        return entity(bundleId).map(e -> json.read(e.getBundleJson(), PolicyBundle.class));
    }

    public PolicyBundle parse(PolicyBundleEntity e) {
        return json.read(e.getBundleJson(), PolicyBundle.class);
    }

    @Transactional(readOnly = true)
    public List<PolicyBundleEntity> history(String developerId) {
        return repo.findByDeveloperIdOrderByVersionDesc(developerId);
    }
}
