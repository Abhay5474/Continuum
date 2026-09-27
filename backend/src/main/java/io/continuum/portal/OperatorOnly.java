package io.continuum.portal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint (or every endpoint of a controller) as the engine
 * operator's: engine-wide settings a tenant must not change for everyone else.
 * A signed-in developer gets 403; the check runs before the handler, in
 * {@link OperatorOnlyInterceptor}.
 *
 * <p>Replaces the private {@code requireOperator} helpers each controller used
 * to carry — one annotation is easy to see in review and hard to forget.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface OperatorOnly {
}
