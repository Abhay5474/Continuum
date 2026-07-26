package io.continuum.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Outbound email, with a default that is honest about not sending any.
 *
 * <p>No SMTP is configured in this deployment, and pretending otherwise would be
 * the worse failure: an invite that silently goes nowhere looks exactly like one
 * that was delivered. So {@link #canSend()} is false, the caller is told, and
 * the invite link is handed back to be shared directly — which works today,
 * without waiting on mail infrastructure.
 *
 * <p>Wiring a real transport means replacing this bean; nothing above it changes.
 */
@Component
public class Mailer {

    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    /** Whether this deployment can actually deliver mail. */
    public boolean canSend() {
        return false;
    }

    /**
     * Sends {@code body} to {@code to}.
     *
     * @return true if it was handed to a transport; false if the caller must
     *         deliver the content another way
     */
    public boolean send(String to, String subject, String body) {
        if (!canSend()) {
            // The address is logged, the body is not: invite links are credentials.
            log.info("Email not configured; '{}' to {} was not sent", subject, to);
            return false;
        }
        return false;
    }
}
