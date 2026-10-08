package com.github.Jesper_Andersson.biomepicknchoose.platform.services;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface IPlatformHelper {
    Path getConfigDir();

    /** The mod's display name, or empty if no mod with this id is loaded. */
    Optional<String> getModName(String modId);

    /** Biome ids from the config that won't generate. */
    List<String> getDisabledBiomes();

    /** Replaces the disabled biomes in the config and saves it. */
    void setDisabledBiomes(List<String> biomes);
}
