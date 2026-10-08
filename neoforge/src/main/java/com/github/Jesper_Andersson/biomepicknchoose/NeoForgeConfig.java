package com.github.Jesper_Andersson.biomepicknchoose;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class NeoForgeConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Biomes listed here are replaced by the nearest enabled biome by climate in newly generated overworld chunks.
    // Read when a world starts, see BiomeToggles
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_BIOMES = BUILDER
            .comment("Overworld biomes that won't generate, e.g. [\"minecraft:plains\", \"minecraft:dark_forest\"].",
                    "Each is replaced by the nearest enabled biome by climate. Applies to newly generated chunks on the next world load.")
            .defineListAllowEmpty("disabledBiomes", List.of(), () -> "", o -> o instanceof String s && ResourceLocation.tryParse(s) != null);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private NeoForgeConfig() {}
}
