package com.svcntrl.core;

import com.svcntrl.config.SvcntrlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TaskScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger("svcntrl");
    private static final TaskScheduler INSTANCE = new TaskScheduler();

    public interface TickTask {
        boolean tick(long maxTimeNs); // Returns true when done
        default void onCancel(Throwable t) {}
    }

    private final List<TickTask> tasks = new ArrayList<>();
    private final Queue<TickTask> pendingAdd = new ConcurrentLinkedQueue<>();
    private int nextTaskIndex = 0;

    private TaskScheduler() {}

    public static TaskScheduler getInstance() {
        return INSTANCE;
    }

    public void schedule(TickTask task) {
        pendingAdd.add(task);
    }

    public void clear() {
        Throwable reason = new RuntimeException("Task scheduler cleared (Server Stopping)");
        for (TickTask task : tasks) {
            try { task.onCancel(reason); } catch (Throwable ignored) {}
        }
        TickTask pendingTask;
        while ((pendingTask = pendingAdd.poll()) != null) {
            try { pendingTask.onCancel(reason); } catch (Throwable ignored) {}
        }
        tasks.clear();
        nextTaskIndex = 0;
    }

    public boolean hasActiveTasks() {
        return !tasks.isEmpty() || !pendingAdd.isEmpty();
    }

    public void tick() {
        TickTask pendingTask;
        while ((pendingTask = pendingAdd.poll()) != null) {
            tasks.add(pendingTask);
        }
        if (tasks.isEmpty()) return;

        long globalBudgetNs = SvcntrlConfig.getInstance().taskBudgetNs;
        long startNs = System.nanoTime();

        if (nextTaskIndex >= tasks.size()) nextTaskIndex = 0;

        int tasksToProcess = tasks.size();
        while (tasksToProcess > 0) {
            if (tasks.isEmpty()) break;

            long elapsed = System.nanoTime() - startNs;
            if (elapsed >= globalBudgetNs) {
                break;
            }
            long remainingNs = globalBudgetNs - elapsed;
            long timePerTask = Math.max(10_000L, remainingNs / tasksToProcess);

            TickTask task = tasks.get(nextTaskIndex);
            boolean done = false;
            try {
                done = task.tick(timePerTask);
            } catch (Throwable e) {
                LOGGER.error("[svcntrl] Task failed with exception, aborting", e);
                try {
                    task.onCancel(e);
                } catch (Throwable ignored) {}
                done = true;
            }

            if (done) {
                int lastIdx = tasks.size() - 1;
                tasks.set(nextTaskIndex, tasks.get(lastIdx));
                tasks.remove(lastIdx);
            } else {
                nextTaskIndex++;
            }

            if (nextTaskIndex >= tasks.size()) {
                nextTaskIndex = 0;
            }
            tasksToProcess--;
        }
    }
}
