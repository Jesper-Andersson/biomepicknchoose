package com.github.Jesper_Andersson.biomepicknchoose.mixin;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggleSource;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Unique private static final Logger bpnc$LOGGER = LogUtils.getLogger();
    @Unique private static final String bpnc$INJECTOR_SOURCE = "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";
    @Unique private static boolean bpnc$warnedUnmarked;

    @Shadow @Final ServerLevel level;

    @Shadow protected abstract ChunkGenerator generator();

    // Mark the overworld biome source, so disabled biomes are replaced in it
    // Wrapping sources call through to the marked source, so mark the multi-noise source inside them
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bpnc$markOverworld(CallbackInfo ci) {
        if (level.dimension() != Level.OVERWORLD) return;
        BiomeSource biomeSource = bpnc$rootSource(generator().getBiomeSource());
        if (biomeSource instanceof BiomeToggleSource source) {
            source.bpnc$setOverworld();
        } else if (BiomeToggles.anyDisabled() && !bpnc$warnedUnmarked) {
            bpnc$warnedUnmarked = true;
            bpnc$LOGGER.warn("Overworld biome source {} is not a multi-noise source, disabled biomes will still generate",
                    biomeSource.getClass().getName());
        }
    }

    // Unwrap Lithostitched's InjectorBiomeSource (added when any datapack has a biome injector) and Blueprint's
    // ModdedBiomeSource, like Lithostitched's own InjectorBiomeSource.getRootSource. Both by reflection, so neither
    // mod is needed at runtime
    @Unique
    private static BiomeSource bpnc$rootSource(BiomeSource biomeSource) {
        while (true) {
            Class<?> type = biomeSource.getClass();
            try {
                if (type.getName().equals(bpnc$INJECTOR_SOURCE)) {
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
                bpnc$LOGGER.warn("Could not unwrap biome source {}", type.getName(), e);
                return biomeSource;
            }
        }
    }
}
