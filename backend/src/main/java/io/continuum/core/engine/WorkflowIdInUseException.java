package io.continuum.core.engine;

/**
 * A start named a workflow id that already belongs to a different run — another
 * account's, or one of a different type. Answering such a start with the
 * existing run would hand the caller somebody else's workflow id as if it were
 * theirs, so it is refused instead.
 */
public class WorkflowIdInUseException extends RuntimeException {

    public WorkflowIdInUseException(String workflowId) {
        super("The workflowId \"" + workflowId + "\" is already in use. Choose another id, or omit it to have one generated.");
    }
}
