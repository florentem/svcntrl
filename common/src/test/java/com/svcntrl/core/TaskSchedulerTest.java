package com.svcntrl.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TaskSchedulerTest {

    @Test
    public void testTaskExecution() {
        TaskScheduler scheduler = TaskScheduler.getInstance();
        scheduler.clear();
        
        final boolean[] executed = {false};
        scheduler.schedule(maxTimeNs -> {
            executed[0] = true;
            return true; // true means complete
        });
        
        scheduler.tick();
        assertTrue(executed[0], "Task should have been executed");
    }

    @Test
    public void testTaskYielding() {
        TaskScheduler scheduler = TaskScheduler.getInstance();
        scheduler.clear();
        
        final int[] executions = {0};
        scheduler.schedule(maxTimeNs -> {
            executions[0]++;
            return executions[0] >= 3; // completes on 3rd tick
        });
        
        scheduler.tick();
        assertEquals(1, executions[0]);
        
        scheduler.tick();
        assertEquals(2, executions[0]);
        
        scheduler.tick();
        assertEquals(3, executions[0]);
        
        // Should be removed now
        scheduler.tick();
        assertEquals(3, executions[0]);
    }
}
