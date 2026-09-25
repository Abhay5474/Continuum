package io.continuum.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

/**
 * Builds the {@link AesGcmCipher} from the {@code CONTINUUM_MASTER_KEY}
 * environment variable.
 *
 * In production the env var MUST be set. If it is missing we fall back to an
 * ephemeral random key with a loud warning — this lets local dev run, but means
 * secrets encrypted in one run cannot be read after a restart, which is the
 * intended forcing function to configure a real key.
 */
@Configuration
public class VaultConfig {

    private static final Logger log = LoggerFactory.getLogger(VaultConfig.class);

    @Bean
    public AesGcmCipher credentialCipher(@Value("${CONTINUUM_MASTER_KEY:}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            byte[] ephemeral = new byte[32];
            new SecureRandom().nextBytes(ephemeral);
            log.warn("################################################################");
            log.warn("CONTINUUM_MASTER_KEY is NOT set — using an EPHEMERAL key.");
            log.warn("Stored provider credentials will NOT survive a restart.");
            log.warn("Set CONTINUUM_MASTER_KEY in any real deployment.");
            log.warn("################################################################");
            return new AesGcmCipher(ephemeral);
        }
        if (io.continuum.portal.PortalSessionService.PUBLIC_KEYS.contains(masterKey.trim())) {
            // Not replaced automatically: secrets already stored under this key
            // would become unreadable. But anyone holding a database dump and this
            // repository can decrypt them, so say so every time the server starts.
            log.warn("CONTINUUM_MASTER_KEY is the value published in application.yml. Stored provider "
                    + "credentials are encrypted with a public key; set a private CONTINUUM_MASTER_KEY "
                    + "and re-enter them.");
        }
        return new AesGcmCipher(AesGcmCipher.deriveKey(masterKey));
    }
}
