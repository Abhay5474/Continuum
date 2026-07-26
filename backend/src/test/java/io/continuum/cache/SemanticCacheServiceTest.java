package io.continuum.cache;

import io.continuum.persistence.entity.SemanticCacheEntryEntity;
import io.continuum.persistence.entity.SemanticCacheSettingEntity;
import io.continuum.persistence.repository.SemanticCacheEntryRepository;
import io.continuum.persistence.repository.SemanticCacheSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache's job is to be right before it is fast. These tests are mostly about
 * the cases where it must decline to answer: a different tenant, a different
 * model, an expired entry, or a prompt that is merely related rather than
 * equivalent.
 */
class SemanticCacheServiceTest {

    private InMemoryEntries entries;
    private InMemorySettings settings;
    private SemanticCacheService cache;

    private static final String DEV = "dev-1";

    @BeforeEach
    void setUp() {
        entries = new InMemoryEntries();
        settings = new InMemorySettings();
        cache = new SemanticCacheService(entries, settings);
        cache.setEnabled(DEV, true);
    }

    @Test
    @DisplayName("an identical prompt is served without scoring")
    void exactHit() {
        cache.store(DEV, "What is the refund window?", "gpt-x", "openai", "Thirty days.", 120, 0.004);

        Optional<SemanticCacheService.Hit> hit = cache.lookup(DEV, "What is the refund window?", "gpt-x");

        assertThat(hit).isPresent();
        assertThat(hit.get().response()).isEqualTo("Thirty days.");
        assertThat(hit.get().exact()).isTrue();
        assertThat(hit.get().similarity()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a reworded prompt still hits — that is the whole point")
    void nearHit() {
        cache.store(DEV, "Summarise the refund policy for annual plans", "gpt-x", "openai", "Pro-rated.", 90, 0.003);

        // Same terms, different order and punctuation: an exact-match cache
        // would miss this, which is why one is not enough in front of a model.
        Optional<SemanticCacheService.Hit> hit =
                cache.lookup(DEV, "summarise the annual plans refund policy", "gpt-x");

        assertThat(hit).isPresent();
        assertThat(hit.get().exact()).isFalse();
        assertThat(hit.get().similarity()).isGreaterThanOrEqualTo(0.92);
    }

    @Test
    @DisplayName("a merely related prompt does not hit")
    void unrelatedPromptMisses() {
        cache.store(DEV, "What is the refund window?", "gpt-x", "openai", "Thirty days.", 120, 0.004);

        assertThat(cache.lookup(DEV, "How do I rotate an API key?", "gpt-x")).isEmpty();
    }

    @Test
    @DisplayName("one tenant is never served another tenant's answer")
    void tenantsAreIsolated() {
        cache.store(DEV, "What is our internal margin?", "gpt-x", "openai", "42%.", 50, 0.001);
        cache.setEnabled("dev-2", true);

        assertThat(cache.lookup("dev-2", "What is our internal margin?", "gpt-x")).isEmpty();
    }

    @Test
    @DisplayName("a different model does not hit — a cheap answer is not a substitute")
    void modelsAreIsolated() {
        cache.store(DEV, "Draft a termination clause", "cheap-model", "groq", "…", 300, 0.002);

        assertThat(cache.lookup(DEV, "Draft a termination clause", "expensive-model")).isEmpty();
    }

    @Test
    @DisplayName("a request that names no model accepts whatever answered last time")
    void unspecifiedModelMatchesAnything() {
        cache.store(DEV, "Draft a termination clause", "cheap-model", "groq", "…", 300, 0.002);

        assertThat(cache.lookup(DEV, "Draft a termination clause", null)).isPresent();
    }

    @Test
    @DisplayName("an expired entry is not served")
    void expiryIsRespected() {
        cache.configure(DEV, null, 60);
        cache.store(DEV, "What is the refund window?", "gpt-x", "openai", "Thirty days.", 120, 0.004);
        entries.expireAll();

        assertThat(cache.lookup(DEV, "What is the refund window?", "gpt-x")).isEmpty();
    }

    @Test
    @DisplayName("nothing is stored or served while the cache is off")
    void disabledIsInert() {
        cache.setEnabled(DEV, false);
        cache.store(DEV, "What is the refund window?", "gpt-x", "openai", "Thirty days.", 120, 0.004);

        assertThat(entries.rows).isEmpty();
        assertThat(cache.lookup(DEV, "What is the refund window?", "gpt-x")).isEmpty();
    }

    @Test
    @DisplayName("a hit reports what it saved, so the savings figure is measured not guessed")
    void hitAccountsForSavings() {
        cache.store(DEV, "What is the refund window?", "gpt-x", "openai", "Thirty days.", 120, 0.004);
        cache.lookup(DEV, "What is the refund window?", "gpt-x");

        Map<String, Object> status = cache.status(DEV);
        assertThat(status.get("hits")).isEqualTo(1L);
        assertThat(status.get("tokensSaved")).isEqualTo(120L);
        assertThat((double) status.get("costSaved")).isEqualTo(0.004);
        assertThat((double) status.get("hitRate")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("the threshold is clamped rather than trusted")
    void thresholdIsClamped() {
        assertThat(cache.configure(DEV, 5.0, null).get("similarityThreshold")).isEqualTo(1.0);
        assertThat(cache.configure(DEV, -1.0, null).get("similarityThreshold")).isEqualTo(0.5);
    }

    @Test
    @DisplayName("raising the threshold turns a near hit back into a miss")
    void thresholdGovernsNearHits() {
        cache.store(DEV, "Summarise the refund policy for annual plans", "gpt-x", "openai", "Pro-rated.", 90, 0.003);
        cache.configure(DEV, 1.0, null);

        assertThat(cache.lookup(DEV, "summarise the annual plans refund policy", "gpt-x")).isEmpty();
    }

    // --- minimal in-memory repositories -------------------------------------
    // The service's logic is the thing under test; a database would only make
    // these slower without making them stronger.

    private static class InMemoryEntries implements SemanticCacheEntryRepository {
        final List<SemanticCacheEntryEntity> rows = new ArrayList<>();

        void expireAll() {
            rows.clear();
        }

        @Override
        public List<SemanticCacheEntryEntity> liveFor(String dev, Instant now, Pageable page) {
            return rows.stream()
                    .filter(e -> e.getDeveloperId().equals(dev) && e.getExpiresAt().isAfter(now))
                    .limit(page.getPageSize())
                    .toList();
        }

        @Override
        public List<SemanticCacheEntryEntity> byHash(String dev, String hash, Instant now, Pageable page) {
            return rows.stream()
                    .filter(e -> e.getDeveloperId().equals(dev) && e.getPromptHash().equals(hash)
                            && e.getExpiresAt().isAfter(now))
                    .limit(page.getPageSize())
                    .toList();
        }

        @Override
        public long countByDeveloperId(String developerId) {
            return rows.stream().filter(e -> e.getDeveloperId().equals(developerId)).count();
        }

        @Override
        public void deleteByDeveloperId(String developerId) {
            rows.removeIf(e -> e.getDeveloperId().equals(developerId));
        }

        @Override
        public int deleteExpired(Instant now) {
            return 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S extends SemanticCacheEntryEntity> S save(S entity) {
            if (!rows.contains(entity)) {
                rows.add(entity);
            }
            return entity;
        }

        // The remaining JpaRepository surface is unused by the service.
        @Override public List<SemanticCacheEntryEntity> findAll() { return rows; }
        @Override public List<SemanticCacheEntryEntity> findAll(org.springframework.data.domain.Sort sort) { return rows; }
        @Override public org.springframework.data.domain.Page<SemanticCacheEntryEntity> findAll(Pageable p) { throw new UnsupportedOperationException(); }
        @Override public List<SemanticCacheEntryEntity> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheEntryEntity> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public Optional<SemanticCacheEntryEntity> findById(Long id) { return Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) { }
        @Override public void delete(SemanticCacheEntryEntity e) { rows.remove(e); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { }
        @Override public void deleteAll(Iterable<? extends SemanticCacheEntryEntity> e) { }
        @Override public void deleteAll() { rows.clear(); }
        @Override public void flush() { }
        @Override public <S extends SemanticCacheEntryEntity> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends SemanticCacheEntryEntity> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<SemanticCacheEntryEntity> e) { }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { }
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public SemanticCacheEntryEntity getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public SemanticCacheEntryEntity getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public SemanticCacheEntryEntity getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheEntryEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheEntryEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheEntryEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheEntryEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheEntryEntity> long count(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheEntryEntity> boolean exists(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheEntryEntity, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
    }

    private static class InMemorySettings implements SemanticCacheSettingRepository {
        final Map<String, SemanticCacheSettingEntity> rows = new HashMap<>();

        @Override
        public Optional<SemanticCacheSettingEntity> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S extends SemanticCacheSettingEntity> S save(S entity) {
            rows.put(entity.getDeveloperId(), entity);
            return entity;
        }

        @Override public List<SemanticCacheSettingEntity> findAll() { return List.copyOf(rows.values()); }
        @Override public List<SemanticCacheSettingEntity> findAll(org.springframework.data.domain.Sort sort) { return findAll(); }
        @Override public org.springframework.data.domain.Page<SemanticCacheSettingEntity> findAll(Pageable p) { throw new UnsupportedOperationException(); }
        @Override public List<SemanticCacheSettingEntity> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheSettingEntity> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(String id) { return rows.containsKey(id); }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(String id) { rows.remove(id); }
        @Override public void delete(SemanticCacheSettingEntity e) { rows.remove(e.getDeveloperId()); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { }
        @Override public void deleteAll(Iterable<? extends SemanticCacheSettingEntity> e) { }
        @Override public void deleteAll() { rows.clear(); }
        @Override public void flush() { }
        @Override public <S extends SemanticCacheSettingEntity> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends SemanticCacheSettingEntity> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<SemanticCacheSettingEntity> e) { }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { }
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public SemanticCacheSettingEntity getOne(String id) { throw new UnsupportedOperationException(); }
        @Override public SemanticCacheSettingEntity getById(String id) { throw new UnsupportedOperationException(); }
        @Override public SemanticCacheSettingEntity getReferenceById(String id) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheSettingEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheSettingEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheSettingEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheSettingEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheSettingEntity> long count(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheSettingEntity> boolean exists(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends SemanticCacheSettingEntity, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
    }
}
