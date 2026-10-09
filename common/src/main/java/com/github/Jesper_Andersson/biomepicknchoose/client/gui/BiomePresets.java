package com.github.Jesper_Andersson.biomepicknchoose.client.gui;

import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Util;
import net.minecraft.resources.Identifier;
import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Named sets of biome on/off settings in {@code config/biomepicknchoose/presets}. Each file maps biome ids to true for enabled,
 * and keeps ids from mods that aren't installed right now. The same format as the config, see {@link BiomeConfig}.
 */
public final class BiomePresets {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String EXTENSION = ".json";

    private BiomePresets() {}

    public static Path dir() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID).resolve("presets");
    }

    /** The preset name as stored on disk, or null for a blank name. */
    @Nullable
    public static String fileName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.replaceAll("[^A-Za-z0-9 _.-]", "_");
    }

    public static List<String> list() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(file -> file.endsWith(EXTENSION))
                    .map(file -> file.substring(0, file.length() - EXTENSION.length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            LOGGER.warn("Couldn't list biome presets in {}", dir, e);
            return List.of();
        }
    }

    public static boolean exists(String name) {
        return Files.isRegularFile(file(name));
    }

    public static Map<Identifier, Boolean> load(String name) {
        return BiomeConfig.read(file(name));
    }

    /** Writes the preset, keeping entries already in the file for biomes that aren't in values. */
    public static void save(String name, Map<Identifier, Boolean> values) {
        BiomeConfig.write(file(name), values);
    }

    public static void delete(String name) {
        Path file = file(name);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Couldn't delete biome preset {}", file, e);
        }
    }

    /** Opens the presets folder in the system file browser, creating it first if needed. */
    public static void openDir() {
        Path dir = dir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("Couldn't create biome preset folder {}", dir, e);
        }
        Util.getPlatform().openPath(dir);
    }

    private static Path file(String name) {
        return dir().resolve(name + EXTENSION);
    }
}
