package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.biome.BiomeSource;
import org.slf4j.Logger;

import java.lang.reflect.Field;

public final class BiomeSources {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String INJECTOR_SOURCE = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";

    private BiomeSources() {}

    /**
     * Unwraps Lithostitched's InjectorBiomeSource (added when any datapack has a biome injector) and Blueprint's
     * ModdedBiomeSource, like Lithostitched's own InjectorBiomeSource.getRootSource. Both by reflection, so neither mod
     * is needed at runtime.
     */
    public static BiomeSource root(BiomeSource biomeSource) {
        while (true) {
            Class<?> type = biomeSource.getClass();
            try {
                if (type.getName().equals(INJECTOR_SOURCE)) {
                    // A private field in the versions for 1.21.11
                    Field field = type.getDeclaredField("rootDelegate");
                    field.setAccessible(true);
                    biomeSource = (BiomeSource) field.get(biomeSource);
                } else if (type.getSimpleName().equals("ModdedBiomeSource")) {
                    Field field = type.getDeclaredField("originalSource");
                    field.setAccessible(true);
                    biomeSource = (BiomeSource) field.get(biomeSource);
                } else {
                    return biomeSource;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("Could not unwrap biome source {}", type.getName(), e);
                return biomeSource;
            }
        }
    }
}
