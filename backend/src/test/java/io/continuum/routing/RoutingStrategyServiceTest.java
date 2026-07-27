package io.continuum.routing;

import io.continuum.autopilot.engine.ContextualBanditEngine;
import io.continuum.persistence.entity.RoutingStrategyDecisionEntity;
import io.continuum.persistence.repository.RoutingStrategyDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of this service is that the routing switch means something and the
 * bandit is allowed to act. These tests pin both, plus the guard that stops a
 * barely-observed arm from taking over production traffic.
 */
class RoutingStrategyServiceTest {

    private ModelRoutingState state;
    private ContextualBanditEngine bandit;
    private Decisions decisions;
    private RoutingStrategyService service;

    private static final List<String> HEURISTIC = List.of("groq", "gemini", "mock");
    private static final List<String> AVAILABLE = List.of("gemini", "groq", "mock");

    @BeforeEach
    void setUp() {
        state = new ModelRoutingState();
        bandit = new ContextualBanditEngine();
        decisions = new Decisions();
        service = new RoutingStrategyService(state, bandit, decisions);
    }

    @Test
    @DisplayName("routing off means the static availability order — the switch is not decorative")
    void disabledUsesStaticOrder() {
        state.setEnabled(false);
        state.setStrategy(ModelRoutingState.Strategy.LEARNED);

        RoutingStrategyService.Decision d = service.decide(HEURISTIC, AVAILABLE, 0.5);

        assertThat(d.strategy()).isEqualTo(ModelRoutingState.Strategy.STATIC);
        assertThat(d.order()).isEqualTo(AVAILABLE);
    }

    @Test
    @DisplayName("the heuristic strategy passes the scorer's order through unchanged")
    void heuristicPassesThrough() {
        state.setEnabled(true);
        state.setStrategy(ModelRoutingState.Strategy.HEURISTIC);

        assertThat(service.decide(HEURISTIC, AVAILABLE, 0.5).order()).isEqualTo(HEURISTIC);
    }

    @Test
    @DisplayName("a thinly-observed arm does not get to displace the scorer")
    void unobservedBanditDefersToHeuristic() {
        state.setEnabled(true);
        state.setStrategy(ModelRoutingState.Strategy.LEARNED);

        // With no observations at all, every arm is prior. Exploration is
        // random, so assert the property that holds either way: we never claim
        // confidence we do not have.
        for (int i = 0; i < 40; i++) {
            RoutingStrategyService.Decision d = service.decide(HEURISTIC, AVAILABLE, 0.5);
            if (!d.explored()) {
                assertThat(d.order()).isEqualTo(HEURISTIC);
                assertThat(d.explanation()).contains("observations");
            }
        }
    }

    @Test
    @DisplayName("once an arm is well observed the bandit chooses, and says why")
    void learnedRoutingActsOnEvidence() {
        state.setEnabled(true);
        state.setStrategy(ModelRoutingState.Strategy.LEARNED);

        // Teach it that gemini works and groq does not, in this context.
        for (int i = 0; i < 40; i++) {
            bandit.observe(0.5, "gemini", true, 300, 0.001);
            bandit.observe(0.5, "groq", false, 900, 0.002);
        }

        RoutingStrategyService.Decision d = service.decide(HEURISTIC, AVAILABLE, 0.5);

        assertThat(d.strategy()).isEqualTo(ModelRoutingState.Strategy.LEARNED);
        assertThat(d.chosen()).isEqualTo("gemini");
        // The scorer wanted groq; learning overrode it. That divergence is the
        // whole point, and is what the comparison endpoint measures.
        assertThat(d.baseline()).isEqualTo("groq");
        assertThat(d.explanation()).contains("bandit chose gemini");
    }

    @Test
    @DisplayName("a decision that overrode the baseline is recorded as diverged")
    void divergenceIsRecorded() {
        state.setEnabled(true);
        state.setStrategy(ModelRoutingState.Strategy.LEARNED);
        for (int i = 0; i < 40; i++) {
            bandit.observe(0.5, "gemini", true, 300, 0.001);
        }

        RoutingStrategyService.Decision d = service.decide(HEURISTIC, AVAILABLE, 0.5);
        service.record("dev-1", d, 0.5, d.chosen(), true, 310, 0.001);

        assertThat(decisions.rows).hasSize(1);
        RoutingStrategyDecisionEntity row = decisions.rows.get(0);
        assertThat(row.getBaselineProvider()).isEqualTo("groq");
        assertThat(row.isDiverged()).isEqualTo(!"groq".equals(row.getChosenProvider()));
    }

    @Test
    @DisplayName("the comparison separates agreed from diverged — the aggregate would hide the effect")
    void comparisonSeparatesDivergentTraffic() {
        state.setEnabled(true);
        state.setStrategy(ModelRoutingState.Strategy.LEARNED);

        // 8 agreed and successful, 2 diverged and failed. A single aggregate
        // would read 80% and look fine; the split shows learning did badly.
        for (int i = 0; i < 8; i++) {
            decisions.save(new RoutingStrategyDecisionEntity("dev-1", "LEARNED", "MODERATE", 0.5,
                    "gemini", "gemini", false, true, 300, 0.001));
        }
        for (int i = 0; i < 2; i++) {
            decisions.save(new RoutingStrategyDecisionEntity("dev-1", "LEARNED", "MODERATE", 0.5,
                    "groq", "gemini", false, false, 900, 0.002));
        }

        Map<String, Object> c = service.comparison("dev-1", 100);

        assertThat(c.get("decisions")).isEqualTo(10L);
        assertThat(c.get("diverged")).isEqualTo(2L);
        @SuppressWarnings("unchecked")
        Map<String, Object> diverged = (Map<String, Object>) c.get("whenDiverged");
        @SuppressWarnings("unchecked")
        Map<String, Object> agreed = (Map<String, Object>) c.get("whenAgreed");
        assertThat((double) diverged.get("successRate")).isEqualTo(0.0);
        assertThat((double) agreed.get("successRate")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recording never throws, whatever the repository does")
    void recordingIsSafe() {
        RoutingStrategyService broken = new RoutingStrategyService(state, bandit, new Decisions() {
            @Override
            public <S extends RoutingStrategyDecisionEntity> S save(S entity) {
                throw new IllegalStateException("database is down");
            }
        });
        RoutingStrategyService.Decision d = broken.decide(HEURISTIC, AVAILABLE, 0.5);

        // A telemetry failure must not become a request failure.
        broken.record("dev-1", d, 0.5, "gemini", true, 100, 0.001);
    }

    // --- in-memory repository ------------------------------------------------

    private static class Decisions implements RoutingStrategyDecisionRepository {
        final List<RoutingStrategyDecisionEntity> rows = new ArrayList<>();

        @Override
        public List<RoutingStrategyDecisionEntity> recentFor(String dev, Pageable page) {
            return rows.stream().filter(r -> r.getDeveloperId().equals(dev)).limit(page.getPageSize()).toList();
        }

        @Override
        public List<RoutingStrategyDecisionEntity> recentAll(Pageable page) {
            return rows.stream().limit(page.getPageSize()).toList();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S extends RoutingStrategyDecisionEntity> S save(S entity) {
            rows.add(entity);
            return entity;
        }

        @Override public List<RoutingStrategyDecisionEntity> findAll() { return rows; }
        @Override public List<RoutingStrategyDecisionEntity> findAll(org.springframework.data.domain.Sort s) { return rows; }
        @Override public org.springframework.data.domain.Page<RoutingStrategyDecisionEntity> findAll(Pageable p) { throw new UnsupportedOperationException(); }
        @Override public List<RoutingStrategyDecisionEntity> findAllById(Iterable<Long> ids) { throw new UnsupportedOperationException(); }
        @Override public <S extends RoutingStrategyDecisionEntity> List<S> saveAll(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public Optional<RoutingStrategyDecisionEntity> findById(Long id) { return Optional.empty(); }
        @Override public boolean existsById(Long id) { return false; }
        @Override public long count() { return rows.size(); }
        @Override public void deleteById(Long id) { }
        @Override public void delete(RoutingStrategyDecisionEntity e) { rows.remove(e); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) { }
        @Override public void deleteAll(Iterable<? extends RoutingStrategyDecisionEntity> e) { }
        @Override public void deleteAll() { rows.clear(); }
        @Override public void flush() { }
        @Override public <S extends RoutingStrategyDecisionEntity> S saveAndFlush(S e) { return save(e); }
        @Override public <S extends RoutingStrategyDecisionEntity> List<S> saveAllAndFlush(Iterable<S> e) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<RoutingStrategyDecisionEntity> e) { }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) { }
        @Override public void deleteAllInBatch() { rows.clear(); }
        @Override public RoutingStrategyDecisionEntity getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public RoutingStrategyDecisionEntity getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public RoutingStrategyDecisionEntity getReferenceById(Long id) { throw new UnsupportedOperationException(); }
        @Override public <S extends RoutingStrategyDecisionEntity> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends RoutingStrategyDecisionEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends RoutingStrategyDecisionEntity> List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort s) { throw new UnsupportedOperationException(); }
        @Override public <S extends RoutingStrategyDecisionEntity> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, Pageable p) { throw new UnsupportedOperationException(); }
        @Override public <S extends RoutingStrategyDecisionEntity> long count(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends RoutingStrategyDecisionEntity> boolean exists(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
        @Override public <S extends RoutingStrategyDecisionEntity, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> fn) { throw new UnsupportedOperationException(); }
    }
}
