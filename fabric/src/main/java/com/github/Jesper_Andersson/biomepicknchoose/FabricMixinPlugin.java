package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.compat.CompatMixinPlugin;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

// Checks the version here instead of with "breaks" in fabric.mod.json: mc-publish uploads "breaks" to Modrinth as
// incompatible with every version, since Modrinth dependencies have no version range
public class FabricMixinPlugin extends CompatMixinPlugin {
    @Override
    protected boolean isModLoaded(String modId, String minVersion) {
        return FabricLoader.getInstance().getModContainer(modId).map(mod -> {
            try {
                return mod.getMetadata().getVersion().compareTo(Version.parse(minVersion)) >= 0;
            } catch (VersionParsingException e) {
                return false;
            }
        }).orElse(false);
    }
}
