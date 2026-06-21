package io.continuum.core.workflow;

/**
 * A deterministic orchestration. Implementations decide <em>what</em> happens
 * next; all non-deterministic <em>doing</em> lives in activities.
 *
 * The same {@link #execute} method is invoked repeatedly (replayed) for the life
 * of a workflow execution. It must therefore be a pure function of its input and
 * the recorded history exposed through {@link WorkflowContext}. Implementations
 * are singletons and must not hold per-execution mutable state.
 */
public interface Workflow {

    /** Stable identifier used to route executions to this implementation. */
    String type();

    /**
     * Run the orchestration logic. Returns the workflow result (serialized and
     * stored as {@code WORKFLOW_COMPLETED}). May call
     * {@link WorkflowContext#executeActivity} which can suspend the workflow.
     */
    Object execute(WorkflowContext ctx);
}
