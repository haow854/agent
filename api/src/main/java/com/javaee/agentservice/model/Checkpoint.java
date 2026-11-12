package com.javaee.agentservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The durable form of one Agent run: a frozen {@link AgentState} plus what the
 * store needs to write it safely.
 *
 * <p>This is the only persistent record of a task. There is no separate task
 * state key ? the snapshot is the state.</p>
 *
 * <p>What it holds is bounded by construction. The message chain stays in the
 * session history stream and is referenced by
 * {@link AgentState#getContextStartId()} / {@link AgentState#getContextEndId()},
 * never copied in; outstanding tool calls are the last assistant entry's calls
 * minus the responses that follow it, so they are derived rather than stored.
 * Rewriting the whole snapshot on every step is affordable only because of
 * that: a snapshot that grew with the run would cost O(n²) bytes over n
 * steps.</p>
 *
 * <p>A checkpoint is written only after its content has landed in the stream, so
 * it records what happened and never an intention. The stream is authoritative
 * and this is a derived cache: a checkpoint that lags behind a crash is
 * recovered by re-deriving from the stream, which is why the two need not be
 * written atomically to stay correct.</p>
 *
 * <p>Exactly one checkpoint is current per task, identified by {@link #seq}.
 * Checkpoints do not form a tree: the chain a resume replays is linear, so two
 * branches would describe one history and no version would be the one to
 * compare against. Going back to an earlier point means truncating the chain,
 * which is an operation on the stream.</p>
 */
public record Checkpoint(
        /**
         * Shape of this record when it was written. A checkpoint outlives the
         * deploy that wrote it, so a reader has to know which shape it is
         * looking at before it can migrate one.
         */
        int schemaVersion,

        /** Bumped by every write. Doubles as the compare-and-set version. */
        long seq,

        /**
         * Identity of the writer currently allowed to advance this run. Freshly
         * generated on each lease acquisition, so it also fences a stalled
         * writer: one that wakes up holding a previous value can no longer
         * write.
         */
        String leaseOwner,
        long leaseExpiresAt,

        long updatedAt,

        AgentState state
) {

    public static final int SCHEMA_VERSION = 1;

    public Checkpoint {
        state.validate();
        // Freeze it. The runner keeps mutating the instance it passed in.
        state = state.copy();
    }

    /** Opening checkpoint, written once the run's first messages are in the stream. */
    public static Checkpoint initial(AgentState state,
                                     String leaseOwner,
                                     long leaseExpiresAt,
                                     long now) {
        return new Checkpoint(
                SCHEMA_VERSION, 0L, leaseOwner, leaseExpiresAt, now, state);
    }

    /**
     * Next version of this run under the same owner. The caller has already
     * advanced {@code state}.
     *
     * <p>The deadline is a parameter rather than carried through, because a
     * writer that is still committing is still alive: every commit is its
     * heartbeat. Carried through, a run longer than one lease would look
     * abandoned while it was working, and anyone could take it.</p>
     *
     * @param leaseExpiresAt new deadline, or this checkpoint's own for a write
     *                       that claims no more time
     */
    public Checkpoint next(AgentState state, long now, long leaseExpiresAt) {
        return new Checkpoint(
                SCHEMA_VERSION, seq + 1, leaseOwner, leaseExpiresAt, now, state);
    }

    /**
     * Hands the run to a new writer. The previous owner's value is discarded, so
     * its subsequent writes no longer match and are rejected.
     */
    public Checkpoint withLease(String owner, long expiresAt, long now) {
        if (state.getPhase() == AgentState.Phase.FINISHED) {
            throw new IllegalStateException(
                    "a finished run cannot be leased: " + state.getTaskId());
        }
        return new Checkpoint(
                SCHEMA_VERSION, seq + 1, owner, expiresAt, now, state);
    }

    /** Not a component: annotated so it is not mistaken for a persisted field. */
    @JsonIgnore
    public String taskId() {
        return state.getTaskId();
    }

    /**
     * @param now caller's clock; when the answer decides whether a write is
     *            allowed, judge it against the store's clock instead
     */
    public boolean leaseExpired(long now) {
        return leaseOwner == null || leaseOwner.isBlank() || leaseExpiresAt <= now;
    }

    public boolean heldBy(String owner) {
        return leaseOwner != null && leaseOwner.equals(owner);
    }
}
