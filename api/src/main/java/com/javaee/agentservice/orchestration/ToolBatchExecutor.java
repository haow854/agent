package com.javaee.agentservice.orchestration;

import com.javaee.common.security.SecurityContexts;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the calls of one tool batch off the run's own thread.
 *
 * <p>Only execution moves. Nothing here touches the session stream, the
 * checkpoint or the run's state: a worker computes one result and hands it
 * back, and the run's thread records them in the order the model declared. The
 * store therefore still sees the single writer it is built around, and a
 * batch's stream entries stay deterministic however its calls interleave.</p>
 *
 * <p>The caller's identity travels with the work. The services underneath still
 * read it off the thread — bucket selection above all — so a worker without it
 * would act as nobody.</p>
 */
@Component
public class ToolBatchExecutor implements DisposableBean {

    private final ExecutorService pool;

    public ToolBatchExecutor(@Value("${ai.agent.tool-pool-size:8}") int poolSize) {
        this.pool = Executors.newFixedThreadPool(Math.max(1, poolSize), runnable -> {
            Thread thread = new Thread(runnable, "agent-tool");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts every call at once.
     *
     * <p>The futures come back positionally, so the caller reads them in the
     * order it submitted them rather than the order they finish. Waiting on the
     * first does not hold up the rest — they are already running.</p>
     */
    public <T> List<Future<T>> submitAll(List<Callable<T>> work) {
        SecurityContext caller = SecurityContextHolder.getContext();
        List<Future<T>> futures = new ArrayList<>(work.size());
        for (Callable<T> item : work) {
            futures.add(pool.submit(() -> SecurityContexts.callAs(caller, item)));
        }
        return futures;
    }

    @Override
    public void destroy() {
        pool.shutdownNow();
    }
}
