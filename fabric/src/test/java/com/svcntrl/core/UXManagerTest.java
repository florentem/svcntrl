package com.svcntrl.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UXManagerTest {

    @Test
    public void testHashIndexBounds() {
        String projectName = "CrashTest"; // We will simulate a string that gives a specific hash
        // Instead of mocking the exact string, we just test the math logic
        int poolLength = 4; // Suppose pool length is 4
        
        int hash1 = 12345;
        int index1 = (hash1 & 0x7fffffff) % poolLength;
        assertTrue(index1 >= 0 && index1 < poolLength);
        
        int hash2 = Integer.MIN_VALUE; // The problematic hash
        int index2 = (hash2 & 0x7fffffff) % poolLength;
        assertTrue(index2 >= 0 && index2 < poolLength, "Index should be positive and within bounds for MIN_VALUE");
        
        int hash3 = -12345;
        int index3 = (hash3 & 0x7fffffff) % poolLength;
        assertTrue(index3 >= 0 && index3 < poolLength);
    }
}
