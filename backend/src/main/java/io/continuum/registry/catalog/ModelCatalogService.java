package io.continuum.registry.catalog;

import io.continuum.common.Json;
import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.ModelRepository;
import io.continuum.registry.ModelCapabilities;
import io.continuum.registry.ModelStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Keeps the model catalogue true to what each provider actually offers.
 *
 * <h2>Where the facts come from</h2>
 * Only the providers' own APIs: the model list (one GET, no tokens) and a test
 * call (one chat request capped at a few output tokens). Nothing is scraped. A
 * model is "free" when it answered a test call on a free-tier key — a fact, not
 * a label that may be stale.
 *
 * <h2>What can change a model, and what cannot</h2>
 * <ul>
 *   <li>A failed or refused list changes nothing.</li>
 *   <li>A list that is empty, or far shorter than the last one, adds models but
 *       retires none — a provider hiccup must not empty the catalogue.</li>
 *   <li>A model that was listed before is retired only when two successful
 *       lists in a row leave it out, or one does and a live request was told it
 *       is gone. After the first miss it stops being routed but is kept.</li>
 *   <li>A model never seen in a list (an out-of-date seed, or a name kept from
 *       before the catalogue) is retired by the first successful list without it.</li>
 *   <li>A busy or failing test call changes nothing; the model is tested again
 *       next time.</li>
 * </ul>
 *
 * <h2>What it costs</h2>
 * One list per provider per check, and at most
 * {@link ModelCatalogSettings#maxProbesPerProvider()} test calls, spaced apart
 * and stopped early when the provider pushes back. One check runs at a time,
 * across every instance; the scheduled one is due ten days after the last
 * success; "Check now" has a cooldown.
 */
@Service
public class ModelCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);
    private static final Duration LEASE_TTL = Duration.ofMinutes(20);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._/:-]{1,120}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    /** How a model can be sleeping between test calls in tests. */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    public enum StartOutcome { STARTED, BUSY, COOLING_DOWN, NOTHING_TO_CHECK }

    public record Start(StartOutcome outcome, Instant startedAt, long retryAfterSeconds, String message) {
    }

    /** The check in progress, as the UI shows it. */
    public record Running(String trigger, String requestedBy, Instant startedAt, String phase) {
    }

    private final ModelRepository models;
    private final ModelCatalogStore store;
    private final Map<String, ProviderCatalogClient> clients = new LinkedHashMap<>();
    private final ModelResolver resolver;
    private final ModelCatalogSettings settings;
    private final Json json;
    private final Clock clock;
    private final Sleeper sleeper;
    private final String holder = "catalogue-" + UUID.randomUUID();
    private final AtomicReference<Running> running = new AtomicReference<>();
    /** What a live request said when it reported a model gone, for the confirming check to record. */
    private final Map<String, String> reported = new ConcurrentHashMap<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "model-catalogue");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public ModelCatalogService(ModelRepository models, ModelCatalogStore store, List<ProviderCatalogClient> clients,
                               ModelResolver resolver, ModelCatalogSettings settings, Json json) {
        this(models, store, clients, resolver, settings, json, Clock.systemUTC(), Thread::sleep);
    }

    public ModelCatalogService(ModelRepository models, ModelCatalogStore store, List<ProviderCatalogClient> clients,
                               ModelResolver resolver, ModelCatalogSettings settings, Json json,
                               Clock clock, Sleeper sleeper) {
        this.models = models;
        this.store = store;
        for (ProviderCatalogClient c : clients) {
            this.clients.put(c.provider(), c);
        }
        this.resolver = resolver;
        this.settings = settings;
        this.json = json;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public Collection<ProviderCatalogClient> clients() {
        return clients.values();
    }

    public Optional<Running> running() {
        return Optional.ofNullable(running.get());
    }

    // ------------------------------------------------------------------ triggers

    /** The "Check now" button: every configured provider, list and tests, behind a cooldown. */
    public Start requestCheck(String requestedBy) {
        Running now = running.get();
        if (now != null) {
            return new Start(StartOutcome.BUSY, now.startedAt(), 0, "A check is already running.");
        }
        Optional<ModelCatalogStore.Run> last = store.lastRun();
        if (last.isPresent() && last.get().startedAt() != null) {
            Instant next = last.get().startedAt().plus(settings.manualCooldown());
            if (next.isAfter(clock.instant())) {
                long wait = Math.max(1, Duration.between(clock.instant(), next).toSeconds());
                return new Start(StartOutcome.COOLING_DOWN, last.get().startedAt(), wait,
                        "The last check started moments ago; the next one can start in " + humanWait(wait) + ".");
            }
        }
        List<String> providers = configuredProviders();
        if (providers.isEmpty()) {
            return new Start(StartOutcome.NOTHING_TO_CHECK, null, 0,
                    "No provider API key is configured, so there is nothing to ask.");
        }
        return startAsync("MANUAL", requestedBy, providers, false);
    }

    /** When "Check now" can next start; null when it can start now. */
    public Instant nextManualAt() {
        Optional<ModelCatalogStore.Run> last = store.lastRun();
        if (last.isEmpty() || last.get().startedAt() == null) {
            return null;
        }
        Instant next = last.get().startedAt().plus(settings.manualCooldown());
        return next.isAfter(clock.instant()) ? next : null;
    }

    /** The scheduler's hourly tick. Reads the database only; calls a provider only when one is due. */
    public void runIfDue() {
        List<String> due = new ArrayList<>();
        for (String p : configuredProviders()) {
            if (isDue(store.state(p))) {
                due.add(p);
            }
        }
        if (!due.isEmpty() && running.get() == null) {
            startAsync("SCHEDULED", null, due, false);
        }
    }

    /** When the next scheduled check of this provider is due. */
    public Instant nextDue(ModelCatalogStore.ProviderState s) {
        if (s.lastListOkAt == null) {
            boolean failed = s.lastAttemptAt != null;
            return failed ? s.lastAttemptAt.plus(settings.retryAfterFailure()) : clock.instant();
        }
        Instant byInterval = s.lastListOkAt.plus(settings.checkInterval());
        boolean lastFailed = s.lastAttemptAt != null && s.lastAttemptAt.isAfter(s.lastListOkAt);
        if (lastFailed) {
            Instant retry = s.lastAttemptAt.plus(settings.retryAfterFailure());
            return retry.isAfter(byInterval) ? retry : byInterval;
        }
        return byInterval;
    }

    boolean isDue(ModelCatalogStore.ProviderState s) {
        return !nextDue(s).isAfter(clock.instant());
    }

    /**
     * A live request was told the model is gone, or has no free quota. Called on
     * the request's thread, so it only does in-memory work there: the model is
     * set aside immediately, and the rest happens on the catalogue's thread.
     */
    public void reportUnavailable(String provider, String model, boolean gone, String evidence) {
        if (!clients.containsKey(provider) || model == null || model.isBlank()) {
            return;
        }
        // Already retired or unusable (a second in-flight call hearing the same
        // news): the catalogue knows, and requests are already redirected.
        if (resolver.isRedirected(provider, model)) {
            return;
        }
        boolean fresh = !resolver.isQuarantined(provider, model);
        resolver.quarantine(provider, model);
        if (!fresh) {
            return;
        }
        reported.put(provider + "/" + model, evidence == null ? "" : truncate(evidence, 300));
        try {
            worker.submit(() -> handleReport(provider, model, gone, evidence));
        } catch (RuntimeException e) {
            log.debug("Could not queue the model report: {}", e.getMessage());
        }
    }

    private void handleReport(String provider, String model, boolean gone, String evidence) {
        try {
            if (!gone) {
                // No free quota is conclusive on its own: nothing to confirm.
                models.findByProviderAndModelName(provider, model).ifPresent(m -> {
                    ModelStatus before = m.getStatus();
                    m.setStatus(ModelStatus.UNAVAILABLE);
                    m.setProbeOutcome(ProbeResult.Outcome.NOT_FREE.name());
                    m.setProbedAt(clock.instant());
                    m.setStatusReason("No free-tier quota for this model on this key (seen on a live request)");
                    models.save(m);
                    if (before != ModelStatus.UNAVAILABLE) {
                        store.event(provider, model, "UNAVAILABLE", m.getStatusReason(), null);
                    }
                });
                resolver.release(provider, model);
                ModelCatalogStore.ProviderState st = store.state(provider);
                chooseDefault(provider, st, null, Set.of());
                store.saveState(st);
                resolver.refresh();
                return;
            }
            store.event(provider, model, "REPORTED_GONE",
                    "A live request was told the model does not exist; set aside while the model list is checked", null);
            ModelCatalogStore.ProviderState st = store.state(provider);
            Instant now = clock.instant();
            if (st.lastConfirmAt != null && st.lastConfirmAt.plus(settings.confirmCooldown()).isAfter(now)) {
                return; // confirmed recently; the quarantine covers the gap
            }
            if (!running.compareAndSet(null, new Running("CONFIRM", null, now, "Confirming a missing model"))) {
                return; // a check is running; the quarantine holds until the next one
            }
            if (!store.acquireLease(holder, LEASE_TTL)) {
                running.set(null);
                return;
            }
            st.lastConfirmAt = now;
            store.saveState(st);
            execute("CONFIRM", null, List.of(provider), true);
        } catch (RuntimeException e) {
            log.warn("Handling a model report for {}/{} failed: {}", provider, model, e.getMessage());
        }
    }

    /**
     * Seeds for providers whose list has never been read: the catalogue is not
     * empty on a fresh install or without keys. Database only, no provider calls.
     */
    public void applySeeds() {
        for (ProviderCatalogClient c : clients.values()) {
            if (store.state(c.provider()).lastListOkAt != null) {
                continue;
            }
            // Names the provider has announced it retired, kept from before the
            // catalogue: shown as retired rather than "assumed" until the first
            // real list confirms it. Never re-activates anything.
            c.retiredBeforeCatalogue().forEach((name, why) -> models.findByProviderAndModelName(c.provider(), name)
                    .filter(m -> !"live".equals(m.getSource()) && m.getStatus() != ModelStatus.REMOVED)
                    .ifPresent(m -> {
                        m.setStatus(ModelStatus.REMOVED);
                        m.setRetiredAt(clock.instant());
                        m.setStatusReason(why);
                        m.setReplacedBy(c.seeds().isEmpty() ? null : c.seeds().get(0).id());
                        models.save(m);
                        event(c.provider(), name, "RETIRED", why, null);
                    }));
            for (ListedModel s : c.seeds()) {
                if (models.findByProviderAndModelName(c.provider(), s.id()).isPresent()) {
                    continue;
                }
                ModelEntity m = new ModelEntity(c.provider(), s.id(), ModelStatus.ACTIVE, contextOr(s.contextWindow()),
                        json.write(capsFor(c.provider(), s)), pricingJson(capsFor(c.provider(), s)));
                fill(m, s);
                m.setSource("seed");
                m.setStatusReason("Assumed from the built-in list until " + label(c.provider())
                        + "'s own model list has been read");
                models.save(m);
            }
        }
    }

    /** Pins a provider's default, or clears the pin with {@code null}. */
    public void pin(String provider, String model, String by) {
        if (!clients.containsKey(provider)) {
            throw new IllegalArgumentException("Unknown provider: " + provider);
        }
        ModelCatalogStore.ProviderState st = store.state(provider);
        if (model != null) {
            ModelEntity m = models.findByProviderAndModelName(provider, model)
                    .orElseThrow(() -> new IllegalArgumentException("No such model: " + provider + "/" + model));
            if (m.getStatus() != ModelStatus.ACTIVE || !m.isChat()) {
                throw new IllegalArgumentException(model + " is not a usable chat model right now.");
            }
        }
        st.pinnedModel = model;
        chooseDefault(provider, st, null, Set.of());
        store.saveState(st);
        store.event(provider, model, model == null ? "UNPINNED" : "PINNED",
                model == null ? "Default chosen automatically again" : "Pinned as the default" + (by == null ? "" : " by " + by), null);
        resolver.refresh();
    }

    // ------------------------------------------------------------------ the check

    private Start startAsync(String trigger, String by, List<String> providers, boolean listOnly) {
        Instant now = clock.instant();
        if (!running.compareAndSet(null, new Running(trigger, by, now, "Starting"))) {
            Running r = running.get();
            return new Start(StartOutcome.BUSY, r == null ? now : r.startedAt(), 0, "A check is already running.");
        }
        if (!store.acquireLease(holder, LEASE_TTL)) {
            running.set(null);
            return new Start(StartOutcome.BUSY, now, 0, "Another instance is running a check.");
        }
        try {
            worker.submit(() -> execute(trigger, by, providers, listOnly));
        } catch (RuntimeException e) {
            running.set(null);
            store.releaseLease(holder);
            throw e;
        }
        return new Start(StartOutcome.STARTED, now, 0, "Checking " + String.join(" and ", providers.stream().map(ModelCatalogService::label).toList()) + ".");
    }

    /** Runs a check on the calling thread. For tests and the confirming path; takes the same gates. */
    public boolean runNow(String trigger, String by, List<String> providers, boolean listOnly) {
        if (!running.compareAndSet(null, new Running(trigger, by, clock.instant(), "Starting"))) {
            return false;
        }
        if (!store.acquireLease(holder, LEASE_TTL)) {
            running.set(null);
            return false;
        }
        execute(trigger, by, providers, listOnly);
        return true;
    }

    /** Assumes {@link #running} is set and the lease is held; clears and releases both. */
    private void execute(String trigger, String by, List<String> providers, boolean listOnly) {
        long runId = -1;
        int[] calls = new int[2]; // lists, probes
        Map<String, Object> summary = new LinkedHashMap<>();
        boolean anyOk = false;
        boolean anyFailed = false;
        try {
            runId = store.startRun(trigger, by);
            for (String p : providers) {
                ProviderCatalogClient c = clients.get(p);
                if (c == null || !c.configured()) {
                    continue;
                }
                Map<String, Object> ps = checkProvider(c, runId, listOnly, calls);
                summary.put(p, ps);
                if (Boolean.TRUE.equals(ps.get("ok"))) {
                    anyOk = true;
                } else {
                    anyFailed = true;
                }
            }
        } catch (RuntimeException e) {
            anyFailed = true;
            summary.put("error", e.getClass().getSimpleName() + ": " + truncate(String.valueOf(e.getMessage()), 200));
            log.warn("Model catalogue check failed: {}", e.toString());
        } finally {
            String outcome = anyOk && !anyFailed ? "OK" : anyOk ? "PARTIAL" : "FAILED";
            try {
                if (runId > 0) {
                    store.finishRun(runId, outcome, json.write(summary), calls[0], calls[1]);
                }
            } catch (RuntimeException e) {
                log.warn("Could not record the catalogue check: {}", e.getMessage());
            }
            store.releaseLease(holder);
            running.set(null);
            resolver.refresh();
            log.info("Model catalogue check ({}) {}: {} list call(s), {} test call(s)", trigger, outcome, calls[0], calls[1]);
        }
    }

    private Map<String, Object> checkProvider(ProviderCatalogClient c, long runId, boolean listOnly, int[] calls) {
        String p = c.provider();
        Map<String, Object> out = new LinkedHashMap<>();
        ModelCatalogStore.ProviderState st = store.state(p);
        st.lastAttemptAt = clock.instant();
        phase("Reading " + label(p) + "'s model list");
        ListResult list = c.list();
        calls[0]++;
        if (!list.ok()) {
            st.lastError = list.error();
            store.saveState(st);
            store.event(p, null, "CHECK_FAILED", list.error() + " — nothing was changed", runId);
            out.put("ok", false);
            out.put("error", list.error());
            return out;
        }
        Set<String> retiredNow = new HashSet<>();
        Map<String, Integer> counts = reconcile(p, list.models(), runId, retiredNow);
        out.putAll(counts);
        st.lastListOkAt = clock.instant();
        st.lastError = null;
        st.listedCount = list.models().size();

        // A confirming check lists only — unless that leaves the provider with
        // nothing usable, when a few tests are worth more than waiting ten days.
        int budget = settings.maxProbesPerProvider();
        if (listOnly) {
            budget = usable(p).isEmpty() ? Math.min(3, budget) : 0;
        }
        Map<String, Integer> probed = probe(c, runId, budget, calls, st);
        out.putAll(probed);
        chooseDefault(p, st, runId, retiredNow);
        store.saveState(st);
        out.put("default", st.defaultModel);
        out.put("ok", true);
        return out;
    }

    /** Applies one successful list. Returns counts for the run summary. */
    Map<String, Integer> reconcile(String p, List<ListedModel> listed, Long runId, Set<String> retiredNow) {
        List<ModelEntity> rows = models.findByProvider(p);
        Map<String, ModelEntity> byName = new HashMap<>();
        for (ModelEntity m : rows) {
            byName.put(m.getModelName(), m);
        }
        boolean firstLive = rows.stream().noneMatch(m -> "live".equals(m.getSource()));
        long knownLive = rows.stream().filter(m -> "live".equals(m.getSource()) && m.getStatus() != ModelStatus.REMOVED
                && m.getStatus() != ModelStatus.DEPRECATED).count();
        boolean suspicious = listed.isEmpty() || (knownLive >= 4 && listed.size() < knownLive * 0.3);

        int added = 0, returned = 0, missing = 0, retired = 0, chat = 0;
        Set<String> listedIds = new HashSet<>();
        for (ListedModel lm : listed) {
            if (!SAFE_ID.matcher(lm.id()).matches()) {
                continue; // not a name we would put in a URL
            }
            listedIds.add(lm.id());
            if (lm.kind() == ModelKind.CHAT) {
                chat++;
            }
            ModelEntity m = byName.get(lm.id());
            if (m == null) {
                ModelCapabilities caps = capsFor(p, lm);
                boolean isChat = lm.kind() == ModelKind.CHAT;
                m = new ModelEntity(p, lm.id(), isChat ? ModelStatus.DISCOVERED : ModelStatus.ACTIVE,
                        contextOr(lm.contextWindow()), json.write(caps), pricingJson(caps));
                fill(m, lm);
                m.setSource("live");
                m.setStatusReason(isChat ? "In " + label(p) + "'s model list; waiting for a test call"
                        : lm.note());
                models.save(m);
                added++;
                if (!firstLive && isChat) {
                    event(p, lm.id(), "ADDED", "Appeared in " + label(p) + "'s model list", runId);
                }
                continue;
            }
            boolean wasLive = "live".equals(m.getSource());
            fill(m, lm);
            ModelCapabilities caps = capsFor(p, lm);
            m.setCapabilitiesJson(json.write(caps));
            m.setPricingJson(pricingJson(caps));
            m.setSource("live");
            m.markChecked();
            if (m.getStatus() == ModelStatus.REMOVED || m.getStatus() == ModelStatus.DEPRECATED) {
                boolean wasRemoved = m.getStatus() == ModelStatus.REMOVED;
                ModelStatus next = !m.isChat() || m.getVerifiedAt() != null && !wasRemoved
                        ? ModelStatus.ACTIVE : ModelStatus.DISCOVERED;
                m.setStatus(next);
                m.setRetiredAt(null);
                m.setReplacedBy(null);
                m.setStatusReason(next == ModelStatus.ACTIVE ? "Listed again" : "Listed again; waiting for a test call");
                returned++;
                event(p, m.getModelName(), "RETURNED", "Back in " + label(p) + "'s model list", runId);
            }
            m.setMissCount(0);
            m.setMissingSince(null);
            // A live request said "not found", but the provider still lists it:
            // the key is refused it. Not usable, whatever the list says.
            String report = reported.remove(p + "/" + m.getModelName());
            if (report != null && resolver.isQuarantined(p, m.getModelName())) {
                m.setStatus(ModelStatus.UNAVAILABLE);
                m.setProbeOutcome(ProbeResult.Outcome.NO_ACCESS.name());
                m.setProbedAt(clock.instant());
                m.setStatusReason("Still listed, but a live request was refused: " + report);
                resolver.release(p, m.getModelName());
                event(p, m.getModelName(), "UNAVAILABLE", m.getStatusReason(), runId);
            }
            if (!wasLive && m.getStatus() == ModelStatus.ACTIVE && m.isChat() && m.getVerifiedAt() == null) {
                m.setStatusReason("Confirmed in " + label(p) + "'s model list; test call pending");
            }
            models.save(m);
        }
        if (firstLive && added > 0) {
            event(p, null, "CATALOGUED", "Read " + listed.size() + " models from " + label(p) + "'s list ("
                    + chat + " chat)", runId);
        }

        if (suspicious) {
            event(p, null, "CHECK_WARNING", "The list had " + listed.size() + " models where " + knownLive
                    + " were known; nothing was retired on the strength of it", runId);
        } else {
            for (ModelEntity m : rows) {
                if (listedIds.contains(m.getModelName()) || m.getStatus() == ModelStatus.REMOVED) {
                    continue;
                }
                boolean wasLive = "live".equals(m.getSource());
                m.setMissCount(m.getMissCount() + 1);
                if (m.getMissingSince() == null) {
                    m.setMissingSince(clock.instant());
                }
                boolean reportedGone = resolver.isQuarantined(p, m.getModelName());
                if (!wasLive || m.getMissCount() >= 2 || reportedGone) {
                    m.setStatus(ModelStatus.REMOVED);
                    m.setRetiredAt(clock.instant());
                    m.setStatusReason(!wasLive
                            ? "Not in " + label(p) + "'s model list"
                            : "Not in " + label(p) + "'s model list since " + DAY.format(m.getMissingSince())
                                    + (reportedGone ? ", and a live request was told it does not exist" : ""));
                    resolver.release(p, m.getModelName());
                    reported.remove(p + "/" + m.getModelName());
                    retired++;
                    retiredNow.add(m.getModelName());
                    event(p, m.getModelName(), "RETIRED", m.getStatusReason(), runId);
                } else {
                    m.setStatus(ModelStatus.DEPRECATED);
                    m.setStatusReason("Missing from " + label(p) + "'s model list on "
                            + DAY.format(clock.instant()) + "; not routed, and retired if still missing at the next check");
                    missing++;
                    event(p, m.getModelName(), "MISSING", m.getStatusReason(), runId);
                }
                models.save(m);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("listed", listed.size());
        counts.put("chat", chat);
        counts.put("added", added);
        counts.put("returned", returned);
        counts.put("missing", missing);
        counts.put("retired", retired);
        return counts;
    }

    /** Test calls, most useful first, within the budget. */
    private Map<String, Integer> probe(ProviderCatalogClient c, Long runId, int budget, int[] calls,
                                       ModelCatalogStore.ProviderState st) {
        String p = c.provider();
        Instant now = clock.instant();
        Instant stale = now.minus(settings.reprobeAfter());
        String preferred = st.pinnedModel != null ? st.pinnedModel
                : st.defaultModel != null ? st.defaultModel : resolver.configured(p);
        List<ModelEntity> pending = models.findByProvider(p).stream()
                .filter(m -> m.isChat() && "live".equals(m.getSource()) && SAFE_ID.matcher(m.getModelName()).matches())
                .filter(m -> m.getStatus() == ModelStatus.DISCOVERED
                        || (m.getStatus() == ModelStatus.ACTIVE && (m.getVerifiedAt() == null || m.getVerifiedAt().isBefore(stale)))
                        // "No free quota" is re-tested at every check (and every Check now):
                        // free tiers change, and a wrong verdict must not stand for a month.
                        // The refusal is instant and costs no tokens.
                        || (m.getStatus() == ModelStatus.UNAVAILABLE && (ProbeResult.Outcome.NOT_FREE.name().equals(m.getProbeOutcome())
                                || m.getProbedAt() == null || m.getProbedAt().isBefore(stale))))
                .sorted(priority(preferred))
                .toList();
        int probed = 0, verified = 0, unusable = 0, softInARow = 0;
        boolean stopped = false;
        for (ModelEntity m : pending) {
            if (probed >= budget) {
                break;
            }
            if (probed > 0) {
                try {
                    sleeper.sleep(settings.probeSpacingMs(p));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            phase("Testing " + m.getModelName() + " (" + (probed + 1) + " of " + Math.min(budget, pending.size()) + ")");
            ProbeResult r = c.probe(m.getModelName());
            probed++;
            calls[1]++;
            ModelStatus before = m.getStatus();
            m.setProbedAt(clock.instant());
            m.setProbeOutcome(r.outcome().name());
            switch (r.outcome()) {
                case CALLABLE -> {
                    m.setStatus(ModelStatus.ACTIVE);
                    m.setVerifiedAt(clock.instant());
                    if (!r.limits().isEmpty()) {
                        m.setLimitsJson(json.write(r.limits()));
                    }
                    m.setStatusReason(c.freeTier()
                            ? "Answered a test call on your free-tier key"
                            : "Answered a test call (this key is not declared free-tier)");
                    verified++;
                    if (before != ModelStatus.ACTIVE) {
                        event(p, m.getModelName(), "VERIFIED", m.getStatusReason(), runId);
                    }
                }
                case NOT_FREE, NO_ACCESS, REJECTED -> {
                    m.setStatus(ModelStatus.UNAVAILABLE);
                    m.setStatusReason(r.detail());
                    unusable++;
                    if (before != ModelStatus.UNAVAILABLE) {
                        event(p, m.getModelName(), "UNAVAILABLE", r.detail(), runId);
                    }
                }
                case RATE_LIMITED, TRANSIENT -> {
                    if (before == ModelStatus.DISCOVERED) {
                        m.setStatusReason("Test deferred — " + r.detail());
                    }
                }
                case KEY_REJECTED -> st.lastError = r.detail();
            }
            models.save(m);
            if (r.outcome() == ProbeResult.Outcome.KEY_REJECTED) {
                event(p, null, "PROBES_STOPPED", r.detail() + " — testing stopped for this check", runId);
                stopped = true;
                break;
            }
            // Two soft failures in a row: the provider is pushing back. Stop,
            // rather than spend the rest of the budget being refused.
            softInARow = r.conclusive() ? 0 : softInARow + 1;
            if (softInARow >= 2) {
                event(p, null, "PROBES_PAUSED", "Rate-limited or not answering — remaining tests wait for the next check", runId);
                stopped = true;
                break;
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("tested", probed);
        out.put("verified", verified);
        out.put("unusable", unusable);
        out.put("deferred", Math.max(0, pending.size() - probed));
        out.put("stoppedEarly", stopped ? 1 : 0);
        return out;
    }

    /** Pinned/current default first, then its family, then stable and strong, then the rest. */
    private static Comparator<ModelEntity> priority(String preferred) {
        String fam = preferred == null ? null : DefaultModelPicker.family(preferred);
        return Comparator
                .comparing((ModelEntity m) -> m.getModelName().equals(preferred) ? 0 : 1)
                .thenComparing(m -> m.getStatus() == ModelStatus.UNAVAILABLE ? 2 : m.getStatus() == ModelStatus.ACTIVE ? 1 : 0)
                .thenComparing(m -> fam != null && fam.equals(DefaultModelPicker.family(m.getModelName())) ? 0 : 1)
                .thenComparing(m -> m.isPreview() ? 1 : 0)
                .thenComparing(m -> -DefaultModelPicker.strength(m.getModelName()))
                .thenComparing(m -> -DefaultModelPicker.version(m.getModelName()))
                .thenComparing(ModelEntity::getModelName);
    }

    /** Chooses the default, and a replacement for every model that stopped being usable. */
    void chooseDefault(String p, ModelCatalogStore.ProviderState st, Long runId, Set<String> retiredNow) {
        List<DefaultModelPicker.Candidate> usable = usable(p);
        String before = st.defaultModel;
        // No default recorded yet (the first check after an upgrade): the model
        // the deployment was already running counts as current, so the upgrade
        // itself does not move every request to a different model.
        String current = before;
        if (current == null) {
            ProviderCatalogClient c = clients.get(p);
            current = c == null || c.seeds().isEmpty() ? null : c.seeds().get(0).id();
        }
        Optional<DefaultModelPicker.Choice> choice =
                DefaultModelPicker.pick(usable, st.pinnedModel, resolver.configured(p), current, current);
        if (choice.isPresent()) {
            String next = choice.get().model();
            if (!next.equals(before)) {
                st.defaultModel = next;
                String why = before == null && next.equals(current) && "still available".equals(choice.get().reason())
                        ? "the model this deployment was already running"
                        : choice.get().reason();
                event(p, next, "DEFAULT_CHANGED", before == null
                        ? "Default set to " + next + " — " + why
                        : "Default moved from " + before + " to " + next + " — " + why, runId);
            }
        } else if (before != null && usable.isEmpty()) {
            st.defaultModel = null;
            event(p, null, "NO_USABLE_MODEL", "No usable " + label(p)
                    + " model right now; requests fail over to the other providers", runId);
        }
        for (ModelEntity m : models.findByProvider(p)) {
            boolean unusable = m.getStatus() == ModelStatus.REMOVED || m.getStatus() == ModelStatus.UNAVAILABLE
                    || m.getStatus() == ModelStatus.DEPRECATED;
            if (!unusable || !m.isChat()) {
                continue;
            }
            boolean stale = m.getReplacedBy() == null || usable.stream().noneMatch(c -> c.id().equals(m.getReplacedBy()));
            if (!stale) {
                continue;
            }
            Optional<String> to = DefaultModelPicker.replacementFor(m.getModelName(), usable, st.defaultModel);
            if (to.isPresent() && !Objects.equals(to.get(), m.getReplacedBy())) {
                m.setReplacedBy(to.get());
                models.save(m);
                if (retiredNow.contains(m.getModelName())) {
                    event(p, m.getModelName(), "REPLACED", "Requests naming " + m.getModelName() + " now go to " + to.get(), runId);
                }
            }
        }
    }

    private List<DefaultModelPicker.Candidate> usable(String p) {
        return models.findByProvider(p).stream()
                .filter(m -> m.getStatus() == ModelStatus.ACTIVE && m.isChat() && !resolver.isQuarantined(p, m.getModelName()))
                .map(ModelResolver::candidate)
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private List<String> configuredProviders() {
        return clients.values().stream().filter(ProviderCatalogClient::configured).map(ProviderCatalogClient::provider).toList();
    }

    private void phase(String text) {
        running.updateAndGet(r -> r == null ? null : new Running(r.trigger(), r.requestedBy(), r.startedAt(), text));
    }

    private void event(String p, String model, String type, String detail, Long runId) {
        try {
            store.event(p, model, type, detail, runId);
        } catch (RuntimeException e) {
            log.debug("Could not record model event: {}", e.getMessage());
        }
    }

    private static void fill(ModelEntity m, ListedModel lm) {
        m.setKind(lm.kind().name());
        m.setDisplayName(truncate(lm.displayName(), 160));
        m.setDescription(truncate(lm.description(), 2000));
        m.setNote(lm.note());
        if (lm.contextWindow() > 0) {
            m.setContextWindow(lm.contextWindow());
        }
        m.setMaxOutputTokens(Math.max(0, lm.maxOutputTokens()));
        m.setProviderCreatedAt(Math.max(0, lm.createdEpoch()));
        m.setPreview(lm.preview());
    }

    /**
     * Capabilities for routing. Only the context window comes from the provider;
     * the rest is read from the name, and the prices are list-price estimates
     * used to rank cheap against strong — on a free-tier key nothing is billed.
     */
    static ModelCapabilities capsFor(String provider, ListedModel lm) {
        String id = lm.id().toLowerCase(Locale.ROOT);
        String tier = DefaultModelPicker.tierOf(lm.id());
        double in, out;
        switch (tier) {
            case "pro" -> { in = 0.00125; out = 0.005; }
            case "versatile" -> { in = 0.00015; out = 0.0006; }
            case "lite" -> { in = 0.00004; out = 0.00015; }
            default -> { in = 0.000075; out = 0.0003; }
        }
        boolean vision = "gemini".equals(provider)
                || id.contains("vision") || id.contains("llama-4") || id.contains("scout") || id.contains("maverick");
        return new ModelCapabilities(contextOr(lm.contextWindow()), vision, true, true, in, out, tier);
    }

    private String pricingJson(ModelCapabilities caps) {
        return json.write(Map.of("costInputPer1k", caps.costInputPer1k(), "costOutputPer1k", caps.costOutputPer1k(),
                "basis", "list-price estimate"));
    }

    private static int contextOr(int ctx) {
        return ctx > 0 ? ctx : 128_000;
    }

    public static String label(String provider) {
        return switch (provider) {
            case "groq" -> "Groq";
            case "gemini" -> "Gemini";
            default -> provider;
        };
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }

    private static String humanWait(long seconds) {
        return seconds < 90 ? seconds + " seconds" : (seconds + 59) / 60 + " minutes";
    }
}
