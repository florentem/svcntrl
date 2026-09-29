package com.svcntrl.paper;

import com.svcntrl.data.BlockPos;
import com.svcntrl.data.Project;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PaperPluginTest {

    @Test
    public void testPaperCommandsTabCompletion() {
        PaperCommands commands = new PaperCommands();
        var completions = commands.onTabComplete(null, null, "svcntrl", new String[]{""});
        assertTrue(completions.contains("project"));
        assertTrue(completions.contains("branch"));
        assertTrue(completions.contains("save"));
        assertTrue(completions.contains("restore"));
        assertTrue(completions.contains("preview"));

        var projCompletions = commands.onTabComplete(null, null, "svcntrl", new String[]{"project", ""});
        assertTrue(projCompletions.contains("create"));
        assertTrue(projCompletions.contains("list"));
        assertTrue(projCompletions.contains("select"));
    }

    @Test
    public void testPaperPreviewManagerBasics() {
        PaperPreviewManager pm = PaperPreviewManager.getInstance();
        UUID fakeUuid = UUID.randomUUID();
        assertFalse(pm.hasPreview(fakeUuid));

        Project p = new Project("TestPreview", fakeUuid, "Player", new BlockPos(0, 0, 0), new BlockPos(5, 5, 5), "world");
        assertFalse(pm.isPreviewingProject(p));
    }
}
