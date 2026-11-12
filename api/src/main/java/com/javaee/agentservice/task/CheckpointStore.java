package com.javaee.agentservice.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.agentservice.model.AgentState;
import com.javaee.agentservice.model.Checkpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The only persistent store of Agent tasks.
 *
 * <p>One key per task, {@code agent:task:{taskId}:checkpoint}. The metadata the
 * store itself compares — sequence, lease, status — is kept in flat hash fields
 * so a write can inspect it without decoding the snapshot; the snapshot travels
 * as one JSON field.</p>
 *
 * <p>A write succeeds only if the stored sequence is the one its author read and
 * the lease is still theirs. That pair is what keeps two writers from
 * interleaving entries into one session stream, which would rebuild into a
 * message chain with duplicated turns.</p>
 *
 * <p>The checkpoint's retention has to be no longer than the session history it
 * points at. A checkpoint that outlives its messages names a slice that can no
 * longer be read, and the task is unresumable while still looking alive.</p>
 */
@Component
public class CheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(CheckpointStore.class);

    private static final int LIST_LIMIT = 200;
    private static final String TASK_KEY_PREFIX = "agent:task:";
    private static final String CHECKPOINT_SUFFIX = ":checkpoint";
    private static final String TASK_ALL_ZSET = "agent:tasks:all";
    private static final String TASK_USER_ZSET_PREFIX = "agent:tasks:user:";

    private static final String F_SCHEMA = "schemaVersion";
    private static final String F_SEQ = "seq";
    private static final String F_LEASE_OWNER = "leaseOwner";
    private static final String F_LEASE_EXPIRES = "leaseExpiresAt";
    private static final String F_UPDATED_AT = "updatedAt";
    private static final String F_STATUS = "status";
    private static final String F_STATE = "state";

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${ai.agent.task-expiry-days:7}")
    private long taskTtlDays = 7L;

    // Every commit renews the lease, so this has to outlast one step rather
    // than a whole run: the longest single model call plus its tool batch.
    @Value("${ai.agent.lease-seconds:180}")
    private long leaseSeconds = 180L;

    /** Identity for a lease this process is about to take. */
    public String newLeaseOwner() {
        return UUID.randomUUID().toString();
    }

    public long leaseDeadline() {
        return System.currentTimeMillis() + Duration.ofSeconds(leaseSeconds).toMillis();
    }

    /**
     * Writes the first checkpoint of a task. Fails if one already exists, so a
     * resubmission carrying the same idempotency key cannot start a second run
     * over the id it derives.
     *
     * <p>The check and the write are two round trips rather than one
     * transaction, which leaves a window: two submissions of one key arriving
     * inside the same round trip both find nothing and both write. Closing it
     * takes making the check and the claim a single operation.</p>
     */
    public boolean create(Checkpoint checkpoint) {
        String key = key(checkpoint.taskId());
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            return false;
        }
        write(redisTemplate, key, checkpoint);
        return true;
    }

    public Checkpoint load(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        // A missing key reads back as an empty hash, not as null.
        Map<Object, Object> stored = redisTemplate.opsForHash().entries(key(taskId));
        if (stored.isEmpty()) {
            return null;
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        stored.forEach((field, value) -> fields.put(String.valueOf(field), value));
        int schemaVersion = (int) asLong(fields.get(F_SCHEMA));
        if (schemaVersion != Checkpoint.SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "unsupported checkpoint schema " + schemaVersion
                            + ", this build reads " + Checkpoint.SCHEMA_VERSION);
        }
        return new Checkpoint(
                schemaVersion,
                asLong(fields.get(F_SEQ)),
                asString(fields.get(F_LEASE_OWNER)),
                asLong(fields.get(F_LEASE_EXPIRES)),
                asLong(fields.get(F_UPDATED_AT)),
                deserialize(asString(fields.get(F_STATE))));
    }

    /**
     * Advances a task. Rejected when another writer has moved the sequence on,
     * when the lease has changed hands, or when the task was cancelled in the
     * meantime — a cancelled task never returns to running.
     *
     * @return true if the write landed
     */
    public boolean save(Checkpoint checkpoint) {
        return write(checkpoint, checkpoint.leaseOwner());
    }

    /**
     * Advances a task without holding its lease. Only cancellation may do this:
     * it comes from outside the run, and the sticky check on the stored status
     * is what stops the running writer from undoing it.
     */
    public boolean saveWithoutLease(Checkpoint checkpoint) {
        return write(checkpoint, null);
    }

    /**
     * Takes the lease if it is free or has lapsed, returning the checkpoint the
     * new owner should continue from.
     *
     * @return the leased checkpoint, or null if the task is missing, finished,
     *         or still held by a live writer
     */
    public Checkpoint acquireLease(String taskId, String owner) {
        Checkpoint current = load(taskId);
        if (current == null) {
            return null;
        }
        if (current.state().getPhase() == AgentState.Phase.FINISHED) {
            return null;
        }
        if (current.state().getPhase() != AgentState.Phase.PAUSED
                && !current.leaseExpired(System.currentTimeMillis())) {
            return null;
        }
        Checkpoint leased = current.withLease(owner, leaseDeadline(),
                System.currentTimeMillis());
        // Expect the sequence we read: whoever else took it first wins.
        return write(leased, current.leaseOwner()) ? leased : null;
    }

    public List<Checkpoint> listByUser(Long userId) {
        return userId == null ? List.of() : range(TASK_USER_ZSET_PREFIX + userId);
    }

    public List<Checkpoint> listAll() {
        return range(TASK_ALL_ZSET);
    }

    public boolean delete(String taskId) {
        Checkpoint current = load(taskId);
        if (current == null) {
            return false;
        }
        Boolean removed = redisTemplate.delete(key(taskId));
        redisTemplate.opsForZSet().remove(TASK_ALL_ZSET, taskId);
        Long userId = current.state().getUserId();
        if (userId != null) {
            redisTemplate.opsForZSet().remove(TASK_USER_ZSET_PREFIX + userId, taskId);
        }
        return Boolean.TRUE.equals(removed);
    }

    /**
     * @param expectedOwner lease the caller must still hold, or null to write
     *                      without one
     */
    private boolean write(Checkpoint checkpoint, String expectedOwner) {
        String key = key(checkpoint.taskId());
        long expectedSeq = checkpoint.seq() - 1L;
        String incomingStatus = checkpoint.state().getStatus().name();

        Boolean committed = redisTemplate.execute(new SessionCallback<>() {
            @Override
            @SuppressWarnings({"rawtypes", "unchecked"})
            public Boolean execute(RedisOperations operations) {
                operations.watch(key);
                Map<Object, Object> stored = operations.opsForHash().entries(key);
                if (stored.isEmpty()) {
                    operations.unwatch();
                    return false;
                }
                if (asLong(stored.get(F_SEQ)) != expectedSeq) {
                    operations.unwatch();
                    return false;
                }
                if (expectedOwner != null
                        && !expectedOwner.equals(asString(stored.get(F_LEASE_OWNER)))) {
                    operations.unwatch();
                    return false;
                }
                if (AgentState.TaskStatus.CANCELLED.name()
                        .equals(asString(stored.get(F_STATUS)))
                        && !AgentState.TaskStatus.CANCELLED.name().equals(incomingStatus)) {
                    operations.unwatch();
                    return false;
                }

                operations.multi();
                write(operations, key, checkpoint);
                return operations.exec() != null;
            }
        });
        return Boolean.TRUE.equals(committed);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void write(RedisOperations operations, String key, Checkpoint checkpoint) {
        AgentState state = checkpoint.state();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(F_SCHEMA, checkpoint.schemaVersion());
        fields.put(F_SEQ, checkpoint.seq());
        fields.put(F_LEASE_OWNER, checkpoint.leaseOwner());
        fields.put(F_LEASE_EXPIRES, checkpoint.leaseExpiresAt());
        fields.put(F_UPDATED_AT, checkpoint.updatedAt());
        fields.put(F_STATUS, state.getStatus().name());
        fields.put(F_STATE, serialize(state));

        operations.opsForHash().putAll(key, fields);
        operations.expire(key, taskTtlDays, TimeUnit.DAYS);
        operations.opsForZSet().add(TASK_ALL_ZSET, state.getTaskId(), checkpoint.updatedAt());
        if (state.getUserId() != null) {
            operations.opsForZSet().add(TASK_USER_ZSET_PREFIX + state.getUserId(),
                    state.getTaskId(), checkpoint.updatedAt());
        }
    }

    private List<Checkpoint> range(String zsetKey) {
        Set<Object> ids = redisTemplate.opsForZSet().reverseRange(zsetKey, 0, LIST_LIMIT - 1);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Checkpoint> result = new ArrayList<>(ids.size());
        for (Object id : ids) {
            Checkpoint checkpoint = load(String.valueOf(id));
            if (checkpoint != null) {
                result.add(checkpoint);
            }
        }
        result.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));
        return result;
    }

    private String serialize(AgentState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to serialize state for task " + state.getTaskId(), e);
        }
    }

    private AgentState deserialize(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException("checkpoint carries no state");
        }
        try {
            return objectMapper.readValue(json, AgentState.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read checkpoint state", e);
        }
    }

    private String key(String taskId) {
        return TASK_KEY_PREFIX + taskId + CHECKPOINT_SUFFIX;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            log.warn("checkpoint field is not a number: {}", value);
            return 0L;
        }
    }
}
