package io.continuum.apps;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.util.function.Supplier;

/**
 * Data captured on behalf of one application.
 *
 * <p>Tagged with the app it was captured for, and filtered by app on every
 * query, without each repository having to remember to. The filter is switched
 * on for every session and reads {@link AppContext}, so a query written years
 * before apps existed is scoped the same as one written today. Lookups by
 * primary key are not filtered; those are already checked against the account.
 */
@MappedSuperclass
@FilterDef(name = AppScopedEntity.FILTER, autoEnabled = true,
        parameters = {
                @ParamDef(name = "scopeMode", type = Integer.class, resolver = AppScopedEntity.Mode.class),
                @ParamDef(name = "scopeApp", type = Long.class, resolver = AppScopedEntity.App.class)
        },
        // ALL: everything. ONLY: the app. OWN_AND_SHARED: the app and untagged
        // rows. UNTAGGED: the app parameter is -1, which no row carries.
        // Hibernate rewrites ":name" to ":filter.name" by plain replacement, so
        // no parameter may be a prefix of the filter's own name: "app" would
        // also rewrite the "app" in "appScope".
        defaultCondition = "(:scopeMode = 0 or app_id = :scopeApp or (:scopeMode >= 2 and app_id is null))")
@Filter(name = AppScopedEntity.FILTER)
public abstract class AppScopedEntity {

    public static final String FILTER = "appScope";

    /** Set when the row is built, on the thread doing the app's work. */
    @Column(name = "app_id")
    private Long appId = AppContext.writeApp();

    @PrePersist
    void tagApp() {
        if (appId == null) {
            appId = AppContext.writeApp();
        }
    }

    public Long getAppId() {
        return appId;
    }

    public void setAppId(Long appId) {
        this.appId = appId;
    }

    /** The filter's mode, read when a query runs. */
    public static final class Mode implements Supplier<Integer> {
        @Override
        public Integer get() {
            return AppContext.readMode().code;
        }
    }

    /** The filter's app; never null, because a filter parameter must be bound. */
    public static final class App implements Supplier<Long> {
        @Override
        public Long get() {
            Long app = AppContext.readMode() == AppContext.Read.UNTAGGED ? null : AppContext.readApp();
            return app == null ? -1L : app;
        }
    }
}
