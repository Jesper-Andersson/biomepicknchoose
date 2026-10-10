package com.github.Jesper_Andersson.biomepicknchoose.mixin;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeDimension;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeSources;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggleSource;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Unique private static final Logger bpnc$LOGGER = LogUtils.getLogger();
    // Dimensions already warned about, once per game
    @Unique private static final Set<BiomeDimension> bpnc$warned = ConcurrentHashMap.newKeySet();

    @Shadow @Final ServerLevel level;

    @Shadow protected abstract ChunkGenerator generator();

    // Activate the biome source of every dimension, also ones mods add, so disabled biomes are replaced in it
    // Wrapping sources call through to the activated source, so activate the source inside them
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bpnc$activate(CallbackInfo ci) {
        BiomeDimension dimension = BiomeDimension.of(level.dimension());
        BiomeSource biomeSource = BiomeSources.root(generator().getBiomeSource());
        if (biomeSource instanceof BiomeToggleSource source) {
            source.bpnc$activate();
        } else if (generator().getBiomeSource().possibleBiomes().stream().anyMatch(BiomeToggles::isDisabled)
                && bpnc$warned.add(dimension)) {
            // Only when it matters: many mod dimensions use a single fixed biome, or a biome source of their own
            bpnc$LOGGER.warn("The {} biome source {} is not supported, disabled biomes will still generate there",
                    dimension.id(), biomeSource.getClass().getName());
        }
    }
}
