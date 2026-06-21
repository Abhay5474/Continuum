package io.continuum.activities;

import io.continuum.chaos.ChaosMonkey;
import io.continuum.core.activity.Activity;
import io.continuum.core.activity.ActivityContext;
import org.springframework.stereotype.Component;

/**
 * Stands in for a database / CRM read. Deterministic mock data keyed by customer
 * id so the rest of the demo is reproducible.
 */
@Component
public class FetchCustomerActivity implements Activity {

    public static final String TYPE = "customer.fetch";

    private final ChaosMonkey chaos;

    public FetchCustomerActivity(ChaosMonkey chaos) {
        this.chaos = chaos;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Object execute(String inputJson, ActivityContext ctx) {
        chaos.maybeFailActivity(TYPE);
        Input in = ctx.input(inputJson, Input.class);
        String id = in.customerId();
        long seed = Math.abs(id.hashCode());
        String tier = switch ((int) (seed % 3)) {
            case 0 -> "FREE";
            case 1 -> "PRO";
            default -> "ENTERPRISE";
        };
        return new Customer(
                id,
                "Customer " + id,
                "customer-" + id + "@example.com",
                tier,
                "Monthly spend $" + (100 + seed % 900) + "; " + (seed % 2 == 0 ? "on-time payments" : "two late payments"));
    }

    public record Input(String customerId) {
    }

    public record Customer(String id, String name, String email, String tier, String history) {
    }
}
