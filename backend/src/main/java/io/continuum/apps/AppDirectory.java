package io.continuum.apps;

import io.continuum.persistence.repository.AppRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether an app belongs to an account, asked on every console request that
 * names one. An app never changes account and is never deleted, so a yes is
 * kept; a no is not, because the id may be one the account creates later.
 */
@Component
public class AppDirectory {

    private final AppRepository apps;
    private final Map<String, Boolean> owned = new ConcurrentHashMap<>();

    public AppDirectory(AppRepository apps) {
        this.apps = apps;
    }

    public boolean belongs(String developerId, Long appId) {
        if (developerId == null || appId == null) {
            return false;
        }
        String key = developerId + "|" + appId;
        if (owned.containsKey(key)) {
            return true;
        }
        boolean yes = apps.findByIdAndDeveloperId(appId, developerId).isPresent();
        if (yes) {
            owned.put(key, Boolean.TRUE);
        }
        return yes;
    }
}
