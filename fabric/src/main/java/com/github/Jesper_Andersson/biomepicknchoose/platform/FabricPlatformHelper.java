package com.github.Jesper_Andersson.biomepicknchoose.platform;

import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.github.Jesper_Andersson.biomepicknchoose.platform.services.IPlatformHelper;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FabricPlatformHelper implements IPlatformHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Optional<String> getModName(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(container -> container.getMetadata().getName());
    }

    // The old Fabric config was {"disabledBiomes": ["minecraft:plains"]} in biomepicknchoose-common.json
    @Override
    public List<String> migrateLegacyConfig() {
        Path file = getConfigDir().resolve(Constants.MOD_ID + "-common.json");
        if (!Files.isRegularFile(file)) return List.of();
        List<String> biomes = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement list = JsonParser.parseReader(reader).getAsJsonObject().get("disabledBiomes");
            if (list != null) list.getAsJsonArray().forEach(element -> biomes.add(element.getAsString()));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read the old config {}", file, e);
        }
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".old"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Couldn't rename the old config {}", file, e);
        }
        return biomes;
    }

    @Override
    public List<Path> getModRoots() {
        return FabricLoader.getInstance().getAllMods().stream().flatMap(mod -> mod.getRootPaths().stream()).toList();
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }
}
