package com.github.Jesper_Andersson.biomepicknchoose.common;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * A dimension whose biomes can be turned off: the overworld, the Nether, the End, or a dimension added by a mod or
 * datapack. Sorted with the vanilla dimensions first, then the others by id.
 */
public record BiomeDimension(ResourceLocation id) implements Comparable<BiomeDimension> {
    public static final BiomeDimension OVERWORLD = new BiomeDimension(Level.OVERWORLD.location());
    public static final BiomeDimension NETHER = new BiomeDimension(Level.NETHER.location());
    public static final BiomeDimension END = new BiomeDimension(Level.END.location());
    /** The vanilla dimensions, in the order the menu shows them. */
    public static final List<BiomeDimension> VANILLA = List.of(OVERWORLD, NETHER, END);
    // Keys of the vanilla dimensions in the known biomes file and in network payloads, from before other dimensions
    private static final List<String> VANILLA_KEYS = List.of("overworld", "nether", "end");

    public ResourceKey<Level> level() {
        return ResourceKey.create(Registries.DIMENSION, id);
    }

    public boolean isVanilla() {
        return VANILLA.contains(this);
    }

    /** Name in the known biomes file and in network payloads: a short name for vanilla, otherwise the id. */
    public String key() {
        int index = VANILLA.indexOf(this);
        return index >= 0 ? VANILLA_KEYS.get(index) : id.toString();
    }

    public Component displayName() {
        int index = VANILLA.indexOf(this);
        if (index >= 0) return Component.translatable("biomepicknchoose.configuration.dimension." + VANILLA_KEYS.get(index));
        // Few mods name their dimensions in the language files, so fall back to the path in title case
        return Component.translatableWithFallback(id.toLanguageKey("dimension"), titleCase(id.getPath()));
    }

    /** The vanilla biomes of this dimension, without a world. Empty for other dimensions. */
    public Set<ResourceLocation> vanilla() {
        Stream<ResourceKey<Biome>> keys;
        if (equals(OVERWORLD)) keys = MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD.usedBiomes();
        else if (equals(NETHER)) keys = MultiNoiseBiomeSourceParameterList.Preset.NETHER.usedBiomes();
        else if (equals(END)) keys = Stream.of(Biomes.THE_END, Biomes.END_HIGHLANDS, Biomes.END_MIDLANDS, Biomes.SMALL_END_ISLANDS, Biomes.END_BARRENS);
        else keys = Stream.empty();
        Set<ResourceLocation> ids = new TreeSet<>();
        keys.forEach(biome -> ids.add(biome.location()));
        return ids;
    }

    public static BiomeDimension of(ResourceKey<Level> level) {
        return new BiomeDimension(level.location());
    }

    /** The dimension a key from {@link #key()} names, or null if it names none, like the cave biome list. */
    @Nullable
    public static BiomeDimension byKey(String key) {
        int index = VANILLA_KEYS.indexOf(key);
        if (index >= 0) return VANILLA.get(index);
        // Other dimensions are named by their id, which always has a namespace
        if (!key.contains(":")) return null;
        ResourceLocation id = ResourceLocation.tryParse(key);
        return id == null ? null : new BiomeDimension(id);
    }

    @Override
    public int compareTo(BiomeDimension other) {
        int index = VANILLA.indexOf(this);
        int otherIndex = VANILLA.indexOf(other);
        if (index >= 0 || otherIndex >= 0) {
            if (index < 0) return 1;
            if (otherIndex < 0) return -1;
            return Integer.compare(index, otherIndex);
        }
        return id.compareTo(other.id);
    }

    private static String titleCase(String path) {
        StringBuilder name = new StringBuilder();
        for (String word : path.substring(path.lastIndexOf('/') + 1).split("_")) {
            if (word.isEmpty()) continue;
            if (!name.isEmpty()) name.append(' ');
            name.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return name.toString();
    }
}
