package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Fabric counterpart of the NeoForge common config, in {@code config/biomepicknchoose-common.json}. Read again on
 * every access, so edits made by hand apply on the next world load like on NeoForge.
 */
public final class FabricConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String COMMENT = "Overworld biomes that won't generate, e.g. [\"minecraft:plains\", \"minecraft:dark_forest\"]. "
            + "Each is replaced by the nearest enabled biome by climate. Applies to newly generated chunks on the next world load.";

    private FabricConfig() {}

    public static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + "-common.json");
    }

    /** Writes the default config if there is none yet, so it can be found and edited. */
    public static synchronized void load() {
        if (!Files.exists(file())) save(List.of());
    }

    public static synchronized List<String> disabledBiomes() {
        Path file = file();
        if (!Files.isRegularFile(file)) return List.of();
        List<String> biomes = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement list = JsonParser.parseReader(reader).getAsJsonObject().get("disabledBiomes");
            if (list != null) {
                for (JsonElement element : list.getAsJsonArray()) {
                    String id = element.getAsString();
                    if (ResourceLocation.tryParse(id) != null) biomes.add(id);
                    else LOGGER.warn("Ignoring invalid biome id {} in {}", id, file);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}", file, e);
        }
        return List.copyOf(biomes);
    }

    public static synchronized void save(List<String> disabledBiomes) {
        JsonArray list = new JsonArray();
        disabledBiomes.forEach(list::add);
        JsonObject root = new JsonObject();
        root.addProperty("_comment", COMMENT);
        root.add("disabledBiomes", list);
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't write {}", file, e);
        }
    }
}
