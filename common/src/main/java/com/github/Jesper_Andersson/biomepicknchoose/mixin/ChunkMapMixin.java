package com.github.Jesper_Andersson.biomepicknchoose.mixin;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeSources;
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

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Unique private static final Logger bpnc$LOGGER = LogUtils.getLogger();
    @Unique private static boolean bpnc$warnedUnmarked;

    @Shadow @Final ServerLevel level;

    @Shadow protected abstract ChunkGenerator generator();

    // Mark the overworld biome source, so disabled biomes are replaced in it
    // Wrapping sources call through to the marked source, so mark the multi-noise source inside them
    @Inject(method = "<init>", at = @At("TAIL"))
    private void bpnc$markOverworld(CallbackInfo ci) {
        if (level.dimension() != Level.OVERWORLD) return;
        BiomeSource biomeSource = BiomeSources.root(generator().getBiomeSource());
        if (biomeSource instanceof BiomeToggleSource source) {
            source.bpnc$setOverworld();
        } else if (BiomeToggles.anyDisabled() && !bpnc$warnedUnmarked) {
            bpnc$warnedUnmarked = true;
            bpnc$LOGGER.warn("Overworld biome source {} is not a multi-noise source, disabled biomes will still generate",
                    biomeSource.getClass().getName());
        }
    }
}
