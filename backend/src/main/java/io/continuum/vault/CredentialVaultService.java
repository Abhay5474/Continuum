package io.continuum.vault;

import io.continuum.persistence.entity.ProviderCredentialEntity;
import io.continuum.persistence.repository.ProviderCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The credential vault: encrypts developer-supplied provider secrets before
 * storage and decrypts them only transiently at execution time.
 *
 * Plaintext secrets never leave this service except as a return value to the
 * provider call path; they are never logged, never persisted in the clear, and
 * never serialized into an API response (only {@link #listProviders} metadata).
 */
@Service
public class CredentialVaultService {

    private final ProviderCredentialRepository repo;
    private final AesGcmCipher cipher;

    public CredentialVaultService(ProviderCredentialRepository repo, AesGcmCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    @Transactional
    public void store(String developerId, String provider, String plaintextSecret) {
        String encrypted = cipher.encrypt(plaintextSecret);
        ProviderCredentialEntity existing = repo.findByDeveloperIdAndProvider(developerId, provider).orElse(null);
        if (existing != null) {
            existing.setEncryptedSecret(encrypted);
            repo.save(existing);
        } else {
            repo.save(new ProviderCredentialEntity(developerId, provider, encrypted));
        }
    }

    /** Decrypt a developer's provider secret for immediate use. Never cached. */
    @Transactional(readOnly = true)
    public Optional<String> decrypt(String developerId, String provider) {
        return repo.findByDeveloperIdAndProvider(developerId, provider)
                .map(c -> cipher.decrypt(c.getEncryptedSecret()));
    }

    /** Metadata only — provider names and timestamps, never the secret. */
    @Transactional(readOnly = true)
    public List<CredentialInfo> listProviders(String developerId) {
        return repo.findByDeveloperId(developerId).stream()
                .map(c -> new CredentialInfo(c.getProvider(), c.getCreatedAt().toString(), c.getUpdatedAt().toString()))
                .toList();
    }

    @Transactional
    public void delete(String developerId, String provider) {
        repo.findByDeveloperIdAndProvider(developerId, provider).ifPresent(repo::delete);
    }

    public record CredentialInfo(String provider, String createdAt, String updatedAt) {
    }
}
