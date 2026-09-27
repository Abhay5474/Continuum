package io.continuum.core.event;

import io.continuum.persistence.entity.WorkflowEventEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recent workflows' histories, kept between decisions.
 *
 * <p>Every decision replays the whole history, and used to read the whole of it
 * from the database each time: a run of a thousand steps read its log a
 * thousand times, and half of each late decision was that read. A history only
 * ever grows at the end, so a decision needs only the events since the last one.
 *
 * <p>Bounded by the size of the payloads held (least recently used goes first),
 * because an LLM step's result can be kilobytes. {@code 0} turns it off. The
 * entries are detached entities and are only ever read.
 */
@Component
public class HistoryCache {

    private final long budgetChars;
    private long heldChars;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, 0.75f, true);

    record Entry(List<WorkflowEventEntity> events, long chars) {
    }

    public HistoryCache(@Value("${continuum.engine.history-cache-mb:32}") long budgetMb) {
        // A char is two bytes; the budget is in memory, not in characters.
        this.budgetChars = Math.max(0, budgetMb) * 1024 * 1024 / 2;
    }

    public boolean enabled() {
        return budgetChars > 0;
    }

    public synchronized List<WorkflowEventEntity> get(String workflowId) {
        Entry e = entries.get(workflowId);
        return e == null ? null : e.events();
    }

    public synchronized void put(String workflowId, List<WorkflowEventEntity> events) {
        if (!enabled()) {
            return;
        }
        remove(workflowId);
        long chars = sizeOf(events);
        if (chars > budgetChars / 4) {
            // One history that would crowd out everyone else is simply read
            // from the database each time, as before.
            return;
        }
        entries.put(workflowId, new Entry(List.copyOf(events), chars));
        heldChars += chars;
        Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
        while (heldChars > budgetChars && it.hasNext()) {
            heldChars -= it.next().getValue().chars();
            it.remove();
        }
    }

    public synchronized void remove(String workflowId) {
        Entry old = entries.remove(workflowId);
        if (old != null) {
            heldChars -= old.chars();
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    private static long sizeOf(List<WorkflowEventEntity> events) {
        long n = 0;
        for (WorkflowEventEntity e : events) {
            n += 64 + (e.getPayload() == null ? 0 : e.getPayload().length());
        }
        return n;
    }
}
