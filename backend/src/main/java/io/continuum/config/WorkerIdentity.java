package io.continuum.config;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** A stable id for this worker process, stamped onto claimed tasks. */
@Component
public class WorkerIdentity {

    private final String id = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public String id() {
        return id;
    }
}
