package io.continuum.api;

import io.continuum.portal.PortalAuthFilter;
import io.continuum.scheduling.DeadlineScheduler;
import io.continuum.scheduling.SchedulerService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Priority and deadline scheduling. Tenant-scoped; OFF by default. */
@RestController
@RequestMapping("/api/portal/developer/scheduling")
public class SchedulingController {

    private final SchedulerService scheduler;

    public SchedulingController(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    private String dev(HttpServletRequest req) {
        return (String) req.getAttribute(PortalAuthFilter.DEVELOPER_ID_ATTRIBUTE);
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest req) {
        return scheduler.status(dev(req));
    }

    @PutMapping("/settings")
    public Map<String, Object> settings(HttpServletRequest req, @RequestBody Settings body) {
        return scheduler.configure(dev(req), body.enabled());
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(HttpServletRequest req) {
        return scheduler.reset(dev(req));
    }

    /**
     * Orders a hypothetical queue without running anything.
     *
     * <p>Exposed because the ordering is the part worth checking before it is
     * trusted with real traffic: a scheduler that silently disagrees with what an
     * operator expected is worse than no scheduler. It is also the only way to
     * see the deadline-miss and aging rules fire on demand rather than by
     * waiting for a real overload.
     */
    @PostMapping("/order")
    public Map<String, Object> order(@RequestBody Order body) {
        Instant now = Instant.now();
        List<DeadlineScheduler.Task> tasks = new ArrayList<>();
        for (Item i : body.tasks() == null ? List.<Item>of() : body.tasks()) {
            tasks.add(new DeadlineScheduler.Task(
                    i.id(),
                    DeadlineScheduler.Priority.of(i.priority()),
                    i.waitedSeconds() == null ? now : now.minusSeconds(i.waitedSeconds()),
                    i.deadlineSeconds() == null ? null : now.plusSeconds(i.deadlineSeconds()),
                    Duration.ofSeconds(i.estimateSeconds() == null ? 0 : i.estimateSeconds())));
        }
        return Map.of("decisions",
                DeadlineScheduler.order(tasks, now).stream().map(DeadlineScheduler.Decision::describe).toList());
    }

    public record Settings(Boolean enabled) {
    }

    public record Order(List<Item> tasks) {
    }

    public record Item(String id, String priority, Long waitedSeconds, Long deadlineSeconds,
                       Long estimateSeconds) {
    }
}
