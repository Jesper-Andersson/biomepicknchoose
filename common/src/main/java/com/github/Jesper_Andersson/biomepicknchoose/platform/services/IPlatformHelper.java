package com.github.Jesper_Andersson.biomepicknchoose.platform.services;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface IPlatformHelper {
    Path getConfigDir();

    /** The mod's display name, or empty if no mod with this id is loaded. */
    Optional<String> getModName(String modId);

    /**
     * Biome ids disabled in this loader's config from before the shared {@code biomepicknchoose.json}, renaming that
     * file to {@code .old} so it is only read once. Empty if there is none.
     */
    List<String> migrateLegacyConfig();

    /** The root folder of each loaded mod's files, to find the biomes they define, see ModBiomeScan. */
    List<Path> getModRoots();

    /** Whether the player's client has this mod, so it accepts the payload. */
    boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);
}
