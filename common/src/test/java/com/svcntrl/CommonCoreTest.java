package com.svcntrl;

import com.svcntrl.config.SvcntrlConfig;
import com.svcntrl.core.TaskScheduler;
import com.svcntrl.data.BlockPos;
import com.svcntrl.data.Project;
import com.svcntrl.data.ProjectManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CommonCoreTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        SvcntrlConfig.setConfigPath(tempDir.resolve("svcntrl.json"));
        SvcntrlConfig.load();
    }

    @Test
    void testConfigClamping() {
        SvcntrlConfig config = SvcntrlConfig.getInstance();
        config.taskBudgetNs = 10L; // too small
        config.maxRegionVolume = -1; // invalid
        SvcntrlConfig.save();
        SvcntrlConfig.load();

        SvcntrlConfig reloaded = SvcntrlConfig.getInstance();
        assertTrue(reloaded.taskBudgetNs >= 100_000L);
        assertTrue(reloaded.maxRegionVolume > 0);
        
        // restore normal budget for subsequent tests
        reloaded.taskBudgetNs = 25_000_000L;
    }

    @Test
    void testProjectGeometryAndSnapshots() {
        UUID owner = UUID.randomUUID();
        Project project = new Project("TestProj", owner, "Owner",
                new BlockPos(0, 0, 0), new BlockPos(10, 10, 10), "minecraft:overworld");

        assertEquals(11 * 11 * 11, project.getVolume());
        assertTrue(project.contains(new BlockPos(5, 5, 5)));
        assertFalse(project.contains(new BlockPos(15, 5, 5)));

        Project otherIntersecting = new Project("Other", owner, "Owner",
                new BlockPos(5, 5, 5), new BlockPos(15, 15, 15), "minecraft:overworld");
        assertTrue(project.intersects(otherIntersecting));

        Project otherDifferentWorld = new Project("Nether", owner, "Owner",
                new BlockPos(5, 5, 5), new BlockPos(15, 15, 15), "minecraft:the_nether");
        assertFalse(project.intersects(otherDifferentWorld));

        int snap1 = project.addManualSnapshot("main", "First save", owner, "Owner");
        assertEquals(1, snap1);
        assertEquals(1, project.getBranch("main").getManualSnapshots().size());

        int auto1 = project.addAutoSnapshot("main", "Auto save", owner, "Owner");
        assertEquals(1, auto1);
        assertEquals(1, project.getBranch("main").getAutoSnapshots().size());
    }

    @Test
    void testProjectManagerSpatialGrid() {
        ProjectManager pm = ProjectManager.getInstance();
        pm.loadProjects(tempDir.resolve("svcntrl_data"));

        UUID owner = UUID.randomUUID();
        Project p1 = new Project("P1", owner, "Owner",
                new BlockPos(100, 64, 100), new BlockPos(200, 80, 200), "minecraft:overworld");
        Project p2 = new Project("P2", owner, "Owner",
                new BlockPos(10000, 64, 10000), new BlockPos(10100, 80, 10100), "minecraft:overworld");

        assertTrue(pm.createProject(p1));
        assertTrue(pm.createProject(p2));

        Set<Project> near = pm.getProjectsNearPos("minecraft:overworld", 150, 150, 256);
        assertTrue(near.contains(p1));
        assertFalse(near.contains(p2));

        pm.setActiveProject(owner, "P1");
        assertEquals("p1", pm.getActiveProject(owner).getName().toLowerCase());
    }

    @Test
    void testTaskSchedulerBudgetAndCancel() {
        TaskScheduler scheduler = TaskScheduler.getInstance();
        scheduler.clear();

        AtomicInteger steps = new AtomicInteger(0);
        scheduler.schedule(maxTimeNs -> {
            steps.incrementAndGet();
            return steps.get() >= 3;
        });

        assertTrue(scheduler.hasActiveTasks());
        scheduler.tick();
        assertTrue(steps.get() >= 1);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        scheduler.schedule(new TaskScheduler.TickTask() {
            @Override
            public boolean tick(long maxTimeNs) {
                return false;
            }

            @Override
            public void onCancel(Throwable t) {
                cancelled.set(true);
            }
        });

        scheduler.clear();
        assertTrue(cancelled.get());
        assertFalse(scheduler.hasActiveTasks());
    }

    @Test
    void testNbtRoundTrip() throws Exception {
        com.svcntrl.nbt.NbtCompound root = new com.svcntrl.nbt.NbtCompound();
        root.putInt("Version", 2);
        root.putString("Name", "TestSnapshot");
        root.putIntArray("BlockData", new int[]{0, 1, 2, 3});

        com.svcntrl.nbt.NbtList list = new com.svcntrl.nbt.NbtList();
        com.svcntrl.nbt.NbtCompound item = new com.svcntrl.nbt.NbtCompound();
        item.putString("BlockId", "minecraft:stone");
        list.add(item);
        root.put("Palette", list);

        Path nbtFile = tempDir.resolve("snapshot_1.nbt");
        com.svcntrl.nbt.NbtIo.writeCompressed(root, nbtFile);

        assertTrue(java.nio.file.Files.exists(nbtFile));
        com.svcntrl.nbt.NbtCompound read = com.svcntrl.nbt.NbtIo.readCompressed(nbtFile);
        assertEquals(2, read.getInt("Version", 0));
        assertEquals("TestSnapshot", read.getString("Name", ""));
        assertArrayEquals(new int[]{0, 1, 2, 3}, read.getIntArray("BlockData"));
        assertEquals("minecraft:stone", read.getListOrEmpty("Palette").getCompoundOrEmpty(0).getString("BlockId", ""));
    }
}
