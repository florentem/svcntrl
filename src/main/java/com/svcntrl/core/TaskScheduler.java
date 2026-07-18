package com.svcntrl.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class TaskScheduler {
    private static final TaskScheduler INSTANCE = new TaskScheduler();
    
    public interface TickTask {
        boolean tick(long maxTimeNs); // Returns true when done
        default void onCancel(Throwable t) {}
    }

    private final List<TickTask> tasks = new ArrayList<>();
    private final java.util.Queue<TickTask> pendingAdd = new java.util.concurrent.ConcurrentLinkedQueue<>();

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
            try { task.onCancel(reason); } catch (Throwable t) {}
        }
        TickTask pendingTask;
        while ((pendingTask = pendingAdd.poll()) != null) {
            try { pendingTask.onCancel(reason); } catch (Throwable t) {}
        }
        tasks.clear();
        nextTaskIndex = 0;
    }

    public boolean hasActiveTasks() {
        return !tasks.isEmpty() || !pendingAdd.isEmpty();
    }

    private int nextTaskIndex = 0;

    public void tick() {
        TickTask pendingTask;
        while ((pendingTask = pendingAdd.poll()) != null) {
            tasks.add(pendingTask);
        }
        if (tasks.isEmpty()) return;
        
        long globalBudgetNs = com.svcntrl.config.SvcntrlConfig.getInstance().taskBudgetNs;
        long startNs = System.nanoTime();
        
        if (nextTaskIndex >= tasks.size()) nextTaskIndex = 0;
        
        int tasksToProcess = tasks.size();
        while (tasksToProcess > 0) {
            if (tasks.isEmpty()) break;
            
            long remainingNs = globalBudgetNs - (System.nanoTime() - startNs);
            if (remainingNs <= 500_000L) {
                break;
            }
            
            long timePerTask = remainingNs / tasksToProcess;
            
            TickTask task = tasks.get(nextTaskIndex);
            boolean done = false;
            try {
                done = task.tick(timePerTask);
            } catch (Throwable e) {
                com.svcntrl.SvcntrlMod.LOGGER.error("[svcntrl] Task failed with exception, aborting", e);
                try {
                    task.onCancel(e);
                } catch (Throwable e2) {}
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
