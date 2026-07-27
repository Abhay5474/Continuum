package io.continuum.uncertainty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.continuum.persistence.entity.UncertaintyMeasurementEntity;
import io.continuum.persistence.entity.UncertaintySettingEntity;
import io.continuum.persistence.repository.UncertaintyMeasurementRepository;
import io.continuum.persistence.repository.UncertaintySettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The entropy maths, and the guard against the one failure that would make this
 * feature actively harmful: confidently reporting certainty it did not measure.
 */
class SemanticUncertaintyServiceTest {

    private SemanticUncertaintyService service;
    private Settings settings;

    private static final String DEV = "dev-1";

    @BeforeEach
    void setUp() {
        settings = new Settings();
        service = new SemanticUncertaintyService(settings, new Measurements(),
                new AnswerClusterer(), new ObjectMapper());
    }

    @Test
    @DisplayName("total agreement is zero entropy and full confidence")
    void agreementIsCertainty() {
        var m = service.measure(DEV, List.of(
                "The window is 30 days.",
                "30 days.",
                "You have 30 days to claim."));

        assertThat(m.clusters()).isEqualTo(1);
        assertThat(m.entropy()).isCloseTo(0.0, within(1e-9));
        assertThat(m.confidence()).isCloseTo(1.0, within(1e-9));
        assertThat(m.lowConfidence()).isFalse();
    }

    @Test
    @DisplayName("total disagreement is maximal entropy and zero confidence")
    void disagreementIsUncertainty() {
        var m = service.measure(DEV, List.of(
                "The company filed in 2019.",
                "The company filed in 2021.",
                "The company filed in 1998."));

        assertThat(m.clusters()).isEqualTo(3);
        // Three equal clusters over three samples is log(3), the maximum.
        assertThat(m.entropy()).isCloseTo(Math.log(3), within(1e-9));
        assertThat(m.normalised()).isCloseTo(1.0, within(1e-9));
        assertThat(m.confidence()).isCloseTo(0.0, within(1e-9));
        assertThat(m.lowConfidence()).isTrue();
    }

    @Test
    @DisplayName("a 2:1 split lands between the extremes")
    void partialAgreement() {
        var m = service.measure(DEV, List.of("It is 42.", "The answer is 42.", "The answer is 7."));

        assertThat(m.clusters()).isEqualTo(2);
        assertThat(m.confidence()).isStrictlyBetween(0.0, 1.0);
        // Majority first, so a caller reading breakdown[0] gets the consensus.
        assertThat(m.breakdown().get(0).size()).isEqualTo(2);
        assertThat(m.breakdown().get(0).share()).isCloseTo(2.0 / 3, within(1e-9));
    }

    @Test
    @DisplayName("normalisation makes different sample counts comparable")
    void normalisationIsScaleFree() {
        var three = service.measure(DEV, List.of("A is 1.", "B is 2.", "C is 3."));
        var four = service.measure(DEV, List.of("A is 1.", "B is 2.", "C is 3.", "D is 4."));

        // Both are total disagreement. Raw entropy differs — log(3) vs log(4) —
        // so without normalisation, sampling more would look like less
        // confidence rather than the same finding measured harder.
        assertThat(three.entropy()).isNotEqualTo(four.entropy());
        assertThat(three.normalised()).isCloseTo(four.normalised(), within(1e-9));
    }

    @Test
    @DisplayName("a single sample reports no confidence rather than full confidence")
    void oneSampleMeasuresNothing() {
        var m = service.measure(DEV, List.of("30 days."));

        // The dangerous failure would be returning 1.0 here: an unmeasured
        // answer presented as a certain one.
        assertThat(m.confidence()).isNaN();
        assertThat(m.breakdown()).isEmpty();
    }

    @Test
    @DisplayName("the low-confidence flag follows the account's own threshold")
    void lowConfidenceThresholdIsPerTenant() {
        service.configure(DEV, null, null, null, 0.9);
        var m = service.measure(DEV, List.of("It is 42.", "The answer is 42.", "The answer is 7."));

        // ~0.42 confidence: fine under the default 0.5 bar, flagged under 0.9.
        assertThat(m.lowConfidence()).isTrue();
    }

    @Test
    @DisplayName("modes decide when a measurement happens")
    void modesGateMeasurement() {
        service.configure(DEV, "OFF", null, null, null);
        assertThat(service.shouldMeasure(DEV, true, true)).isFalse();

        service.configure(DEV, "ON_DEMAND", null, null, null);
        assertThat(service.shouldMeasure(DEV, true, false)).isTrue();
        assertThat(service.shouldMeasure(DEV, false, true)).isFalse();

        service.configure(DEV, "ADAPTIVE", null, null, null);
        assertThat(service.shouldMeasure(DEV, false, true)).isTrue();
        assertThat(service.shouldMeasure(DEV, false, false)).isFalse();

        service.configure(DEV, "ALWAYS", null, null, null);
        assertThat(service.shouldMeasure(DEV, false, false)).isTrue();
    }

    @Test
    @DisplayName("temperature cannot be set to zero — it would measure nothing")
    void temperatureIsFloored() {
        // At temperature 0 every sample is identical, entropy is always 0, and
        // the feature would confidently declare certainty about everything.
        var s = service.configure(DEV, null, null, 0.0, null);
        assertThat((double) s.get("temperature")).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("sample count is clamped to a useful, affordable range")
    void samplesAreClamped() {
        assertThat(service.configure(DEV, null, 1, null, null).get("samples")).isEqualTo(2);
        assertThat(service.configure(DEV, null, 50, null, null).get("samples")).isEqualTo(7);
    }

    @Test
    @DisplayName("an unknown mode is refused rather than silently ignored")
    void unknownModeIsRejected() {
        try {
            service.configure(DEV, "SOMETIMES", null, null, null);
            org.junit.jupiter.api.Assertions.fail("expected a refusal");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("SOMETIMES");
        }
    }

    // --- in-memory repositories ---------------------------------------------

    private static class Settings implements UncertaintySettingRepository {
        final Map<String, UncertaintySettingEntity> rows = new HashMap<>();

        @Override public Optional<UncertaintySettingEntity> findById(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override @SuppressWarnings("unchecked")
        public <S extends UncertaintySettingEntity> S save(S e) {
            rows.put(e.getDeveloperId(), e);
            return e;
        }

        @Override public List<UncertaintySettingEntity> findAll() { return List.copyOf(rows.values()); }
        @Override public List<UncertaintySettingEntity> findAll(org.springframework.data.domain.Sort s) { return findAll(); }
        @Override public org.springframework.data.domain.Page<UncertaintySettingEntity> findAll(Pageable p) { throw new UnsupportedOperationException(); }
        @Override public List<UncertaintySettingEntity> findAllById(Iterable<String> i) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintySettingEntity> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(String id) { return rows.containsKey(id); }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(String id) { rows.remove(id); }
        @Override public void delete(UncertaintySettingEntity e) { rows.remove(e.getDeveloperId()); }
        @Override public void deleteAllById(Iterable<? extends String> i) { }
        @Override public void deleteAll(Iterable<? extends UncertaintySettingEntity> e) { }
        @Override public void deleteAll() { rows.clear(); }
        @Override public void flush() { }
        @Override public <S extends UncertaintySettingEntity> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends UncertaintySettingEntity> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<UncertaintySettingEntity> e) { }
        @Override public void deleteAllByIdInBatch(Iterable<String> i) { }
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public UncertaintySettingEntity getOne(String id) { throw new UnsupportedOperationException(); }
        @Override public UncertaintySettingEntity getById(String id) { throw new UnsupportedOperationException(); }
        @Override public UncertaintySettingEntity getReferenceById(String id) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintySettingEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintySettingEntity> List<S> findAll(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintySettingEntity> List<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintySettingEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> e, Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintySettingEntity> long count(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintySettingEntity> boolean exists(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintySettingEntity, R> R findBy(org.springframework.data.domain.Example<S> e, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { throw new UnsupportedOperationException(); }
    }

    private static class Measurements implements UncertaintyMeasurementRepository {
        final List<UncertaintyMeasurementEntity> rows = new ArrayList<>();

        @Override public List<UncertaintyMeasurementEntity> recentFor(String dev, Pageable p) {
            return rows.stream().filter(r -> r.getDeveloperId().equals(dev)).limit(p.getPageSize()).toList();
        }

        @Override public void deleteByDeveloperId(String dev) {
            rows.removeIf(r -> r.getDeveloperId().equals(dev));
        }

        @Override @SuppressWarnings("unchecked")
        public <S extends UncertaintyMeasurementEntity> S save(S e) {
            rows.add(e);
            return e;
        }

        @Override public List<UncertaintyMeasurementEntity> findAll() { return rows; }
        @Override public List<UncertaintyMeasurementEntity> findAll(org.springframework.data.domain.Sort s) { return rows; }
        @Override public org.springframework.data.domain.Page<UncertaintyMeasurementEntity> findAll(Pageable p) { throw new UnsupportedOperationException(); }
        @Override public List<UncertaintyMeasurementEntity> findAllById(Iterable<Long> i) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintyMeasurementEntity> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public Optional<UncertaintyMeasurementEntity> findById(Long id) { return Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) { }
        @Override public void delete(UncertaintyMeasurementEntity e) { rows.remove(e); }
        @Override public void deleteAllById(Iterable<? extends Long> i) { }
        @Override public void deleteAll(Iterable<? extends UncertaintyMeasurementEntity> e) { }
        @Override public void deleteAll() { rows.clear(); }
        @Override public void flush() { }
        @Override public <S extends UncertaintyMeasurementEntity> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends UncertaintyMeasurementEntity> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<UncertaintyMeasurementEntity> e) { }
        @Override public void deleteAllByIdInBatch(Iterable<Long> i) { }
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public UncertaintyMeasurementEntity getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public UncertaintyMeasurementEntity getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public UncertaintyMeasurementEntity getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintyMeasurementEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintyMeasurementEntity> List<S> findAll(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintyMeasurementEntity> List<S> findAll(org.springframework.data.domain.Example<S> e, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintyMeasurementEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> e, Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintyMeasurementEntity> long count(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintyMeasurementEntity> boolean exists(org.springframework.data.domain.Example<S> e) { throw new UnsupportedOperationException(); }
        @Override public <S extends UncertaintyMeasurementEntity, R> R findBy(org.springframework.data.domain.Example<S> e, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { throw new UnsupportedOperationException(); }
    }
}
