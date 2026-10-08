package com.github.Jesper_Andersson.biomepicknchoose.mixin;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggleSource;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(MultiNoiseBiomeSource.class)
public abstract class MultiNoiseBiomeSourceMixin implements BiomeToggleSource {
    @Shadow protected abstract Climate.ParameterList<Holder<Biome>> parameters();

    @Unique private volatile boolean bpnc$overworld;
    // Parameters without the disabled biomes, null if nothing would be left. Rebuilt when the toggles change
    @Unique private volatile Climate.ParameterList<Holder<Biome>> bpnc$filtered;
    @Unique private volatile int bpnc$filteredVersion = -1;

    @Override
    public void bpnc$setOverworld() {
        bpnc$overworld = true;
    }

    // Replace disabled biomes with the nearest enabled biome by climate. Wraps the whole method, so biomes picked by
    // TerraBlender regions are replaced too. possibleBiomes() stays unchanged
    @WrapMethod(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;")
    private Holder<Biome> bpnc$replaceDisabled(int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        Holder<Biome> biome = original.call(x, y, z, sampler);
        if (!bpnc$overworld || !BiomeToggles.isDisabled(biome)) return biome;
        return bpnc$replace(biome, sampler.sample(x, y, z));
    }

    @Override
    public Holder<Biome> bpnc$replace(Holder<Biome> biome, Climate.TargetPoint point) {
        if (!bpnc$overworld || !BiomeToggles.isDisabled(biome)) return biome;
        Climate.ParameterList<Holder<Biome>> filtered = bpnc$filtered();
        return filtered == null ? biome : filtered.findValue(point);
    }

    @Unique
    private Climate.ParameterList<Holder<Biome>> bpnc$filtered() {
        int version = BiomeToggles.version();
        if (bpnc$filteredVersion != version) {
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> values = parameters().values().stream()
                    .filter(entry -> !BiomeToggles.isDisabled(entry.getSecond()))
                    .toList();
            bpnc$filtered = values.isEmpty() ? null : new Climate.ParameterList<>(values);
            bpnc$filteredVersion = version;
        }
        return bpnc$filtered;
    }
}
