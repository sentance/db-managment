package dev.pgcp.cp.jobs;

/**
 * Executes one job type. Handlers must be idempotent: after a control-plane crash a RUNNING job is re-queued and
 * runs again from the start, so every step has to tolerate having been done already.
 */
public interface JobHandler {

    String type();

    void run(JobContext ctx) throws Exception;

    /** Called after the final failed attempt, to leave related resources in an explicit state. */
    default void onFailure(JobContext ctx, Exception error) {
    }
}
