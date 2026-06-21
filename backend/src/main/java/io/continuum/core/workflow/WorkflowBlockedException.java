package io.continuum.core.workflow;

/**
 * Internal control-flow signal used to suspend a workflow when it reaches an
 * activity whose result is not yet available.
 *
 * Continuum runs workflow code by replaying it from the top on every decision.
 * When the code asks for an activity result that the history does not yet
 * contain, we throw this to unwind the stack and hand control back to the
 * engine, which persists the scheduling decision and waits. This is NOT an
 * error — it is the normal "park until the world changes" mechanism.
 */
public final class WorkflowBlockedException extends RuntimeException {

    public static final WorkflowBlockedException INSTANCE = new WorkflowBlockedException();

    private WorkflowBlockedException() {
        super(null, null, false, false); // no message, no stack trace — it's hot-path control flow
    }
}
