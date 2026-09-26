package io.continuum.registry.catalog;

import io.continuum.persistence.entity.ModelEntity;
import io.continuum.persistence.repository.ModelRepository;
import io.continuum.registry.ModelStatus;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory stand-ins for the catalogue's storage, so its rules can be tested without a database. */
final class Fakes {

    private Fakes() {
    }

    /** A ModelRepository backed by a list; only the methods the catalogue uses. */
    static ModelRepository models(List<ModelEntity> rows) {
        return (ModelRepository) Proxy.newProxyInstance(ModelRepository.class.getClassLoader(),
                new Class<?>[]{ModelRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "findAll" -> new ArrayList<>(rows);
                    case "findByProvider" -> rows.stream().filter(m -> m.getProvider().equals(args[0])).toList();
                    case "findByStatus" -> rows.stream().filter(m -> m.getStatus() == args[0]).toList();
                    case "findByProviderAndStatus" -> rows.stream()
                            .filter(m -> m.getProvider().equals(args[0]) && m.getStatus() == (ModelStatus) args[1]).toList();
                    case "findByProviderAndModelName" -> rows.stream()
                            .filter(m -> m.getProvider().equals(args[0]) && m.getModelName().equals(args[1])).findFirst();
                    case "save" -> {
                        ModelEntity m = (ModelEntity) args[0];
                        if (!rows.contains(m)) {
                            rows.add(m);
                        }
                        yield m;
                    }
                    case "toString" -> "fake models";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    static final class Store implements ModelCatalogStore {
        final Map<String, ProviderState> states = new HashMap<>();
        final List<Run> runs = new CopyOnWriteArrayList<>();
        final List<Event> events = new CopyOnWriteArrayList<>();
        String leaseHolder;
        long nextId = 1;
        Instant now = Instant.parse("2026-09-26T10:00:00Z");

        @Override
        public ProviderState state(String provider) {
            ProviderState s = states.get(provider);
            if (s == null) {
                return new ProviderState(provider);
            }
            ProviderState copy = new ProviderState(provider);
            copy.lastListOkAt = s.lastListOkAt;
            copy.lastAttemptAt = s.lastAttemptAt;
            copy.lastError = s.lastError;
            copy.listedCount = s.listedCount;
            copy.defaultModel = s.defaultModel;
            copy.pinnedModel = s.pinnedModel;
            copy.lastConfirmAt = s.lastConfirmAt;
            return copy;
        }

        @Override
        public List<ProviderState> states() {
            return new ArrayList<>(states.values());
        }

        @Override
        public void saveState(ProviderState s) {
            states.put(s.provider, s);
        }

        @Override
        public long startRun(String trigger, String requestedBy) {
            long id = nextId++;
            runs.add(new Run(id, trigger, requestedBy, now, null, "RUNNING", null, 0, 0));
            return id;
        }

        @Override
        public void finishRun(long id, String outcome, String summaryJson, int listCalls, int probeCalls) {
            runs.replaceAll(r -> r.id() == id
                    ? new Run(id, r.trigger(), r.requestedBy(), r.startedAt(), now, outcome, summaryJson, listCalls, probeCalls)
                    : r);
        }

        @Override
        public Optional<Run> lastRun() {
            return runs.stream().filter(r -> !"CONFIRM".equals(r.trigger())).reduce((a, b) -> b);
        }

        @Override
        public List<Run> recentRuns(int limit) {
            return List.copyOf(runs);
        }

        @Override
        public void event(String provider, String model, String type, String detail, Long runId) {
            events.add(new Event(events.size() + 1, provider, model, type, detail, now));
        }

        @Override
        public List<Event> events(int limit) {
            return List.copyOf(events);
        }

        @Override
        public boolean acquireLease(String holder, Duration ttl) {
            if (leaseHolder != null && !leaseHolder.equals(holder)) {
                return false;
            }
            leaseHolder = holder;
            return true;
        }

        @Override
        public void releaseLease(String holder) {
            if (holder.equals(leaseHolder)) {
                leaseHolder = null;
            }
        }

        List<String> eventTypes() {
            return events.stream().map(e -> e.type() + (e.model() == null ? "" : ":" + e.model())).toList();
        }
    }

    /** A provider that answers from fixtures and counts every call. */
    static class Client implements ProviderCatalogClient {
        final String provider;
        boolean configured = true;
        ListResult list;
        final Map<String, ProbeResult> probes = new HashMap<>();
        ProbeResult defaultProbe = ProbeResult.of(ProbeResult.Outcome.CALLABLE, "ok");
        int listCalls;
        final List<String> probed = new ArrayList<>();
        List<ListedModel> seeds = List.of();

        Client(String provider) {
            this.provider = provider;
        }

        Client listing(String... ids) {
            List<ListedModel> ms = new ArrayList<>();
            for (String id : ids) {
                ms.add(chat(id));
            }
            list = ListResult.ok(ms);
            return this;
        }

        static ListedModel chat(String id) {
            return new ListedModel(id, ModelKind.CHAT, null, null, 128_000, 0, 0,
                    id.contains("preview"), null);
        }

        @Override
        public String provider() {
            return provider;
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public boolean freeTier() {
            return true;
        }

        @Override
        public ListResult list() {
            listCalls++;
            return list;
        }

        @Override
        public ProbeResult probe(String modelId) {
            probed.add(modelId);
            return probes.getOrDefault(modelId, defaultProbe);
        }

        @Override
        public List<ListedModel> seeds() {
            return seeds;
        }

        @Override
        public boolean isModelGone(int status, String body) {
            return false;
        }
    }
}
