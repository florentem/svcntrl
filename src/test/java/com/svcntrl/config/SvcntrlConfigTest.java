package com.svcntrl.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SvcntrlConfigTest {

    @Test
    public void testDefaultValues() {
        SvcntrlConfig config = new SvcntrlConfig();
        // default maxAutoSnapshots should be 10
        assertEquals(10, config.maxAutoSnapshots);
        // default maxRegionVolume should be 5000000
        assertEquals(5000000, config.maxRegionVolume);
    }
}
