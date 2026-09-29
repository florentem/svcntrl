package com.svcntrl.util;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;

public class BlockUtils {
    /**
     * Applies block state properties from NBT to a BlockState.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockState applyProperties(BlockState state, NbtCompound propsNbt) {
        for (String key : propsNbt.getKeys()) {
            net.minecraft.state.property.Property property = state.getBlock().getStateManager().getProperty(key);
            if (property != null) {
                String value = propsNbt.getString(key, "");
                java.util.Optional<?> parsed = property.parse(value);
                if (parsed.isPresent()) {
                    state = state.with(property, (Comparable) parsed.get());
                }
            }
        }
        return state;
    }
}
