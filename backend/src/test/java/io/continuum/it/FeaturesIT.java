package io.continuum.it;

import io.continuum.cache.SemanticCacheService;
import io.continuum.features.FeatureCatalog;
import io.continuum.portal.PortalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The feature-named switches set exactly what the per-feature services read. */
class FeaturesIT extends PostgresIT {

    @Autowired FeatureCatalog catalog;
    @Autowired SemanticCacheService cache;
    @Autowired PortalService portal;

    @Test
    void everyFeatureReportsItsStateAndAPlainSwitchFlipsTheRealSetting() {
        String dev = portal.signup("Features IT", "f-" + UUID.randomUUID() + "@example.com", "correct-horse-9").developerId();

        List<Map<String, Object>> all = catalog.list(dev);
        assertThat(all).extracting(m -> m.get("key")).contains("semantic-cache", "verification-engine", "quality-gate");
        assertThat(all).allSatisfy(m -> assertThat(m.get("on")).as((String) m.get("key")).isNotNull());
        assertThat(all).filteredOn(m -> Boolean.TRUE.equals(m.get("labs")))
                .extracting(m -> m.get("key")).containsExactlyInAnyOrder("verification-engine", "context-optimizer", "adaptive-policy");

        assertThat(cache.enabledFor(dev)).isFalse();
        assertThat(catalog.set(dev, "semantic-cache", true).get("on")).isEqualTo(true);
        assertThat(cache.enabledFor(dev)).isTrue();

        assertThatThrownBy(() -> catalog.set(dev, "quality-gate", true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("its page");
        assertThatThrownBy(() -> catalog.set(dev, "no-such-feature", true))
                .isInstanceOf(io.continuum.portal.RequestScope.NotFoundException.class);
    }
}
