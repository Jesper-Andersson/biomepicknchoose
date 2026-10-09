package com.github.Jesper_Andersson.biomepicknchoose.platform;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.github.Jesper_Andersson.biomepicknchoose.platform.services.IPlatformHelper;
import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class NeoForgePlatformHelper implements IPlatformHelper {
    private static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Optional<String> getModName(String modId) {
        return ModList.get().getModContainerById(modId).map(container -> container.getModInfo().getDisplayName());
    }

    // The old NeoForge config was disabledBiomes = ["minecraft:plains"] in biomepicknchoose-common.toml
    @Override
    public List<String> migrateLegacyConfig() {
        Path file = getConfigDir().resolve(Constants.MOD_ID + "-common.toml");
        if (!Files.isRegularFile(file)) return List.of();
        List<String> biomes = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            Config config = new TomlParser().parse(reader);
            if (config.get("disabledBiomes") instanceof List<?> list) list.forEach(id -> biomes.add(String.valueOf(id)));
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

    // Folders as they are, and jars opened as zip file systems, which stay open since the scan only runs once
    @Override
    public List<Path> getModRoots() {
        List<Path> roots = new ArrayList<>();
        for (var file : ModList.get().getModFiles()) {
            for (Path root : file.getFile().getContents().getContentRoots()) {
                if (Files.isDirectory(root)) {
                    roots.add(root);
                } else if (Files.isRegularFile(root)) {
                    try {
                        roots.add(FileSystems.newFileSystem(root).getPath("/"));
                    } catch (IOException | RuntimeException e) {
                        LOGGER.warn("Couldn't open {}", root, e);
                    }
                }
            }
        }
        return roots;
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection.hasChannel(type);
    }
}
