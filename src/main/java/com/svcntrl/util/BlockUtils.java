package com.svcntrl.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

public class BlockUtils {
    /**
     * Applies block state properties from NBT to a BlockState.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static BlockState applyProperties(BlockState state, CompoundTag propsNbt) {
        for (String key : propsNbt.keySet()) {
            net.minecraft.world.level.block.state.properties.Property property = state.getBlock().getStateDefinition().getProperty(key);
            if (property != null) {
                String value = propsNbt.getStringOr(key, "");
                java.util.Optional<?> parsed = property.getValue(value);
                if (parsed.isPresent()) {
                    state = state.setValue(property, (Comparable) parsed.get());
                }
            }
        }
        return state;
    }
}
