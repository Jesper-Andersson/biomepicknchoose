package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.compat.CompatMixinPlugin;
import net.neoforged.fml.loading.FMLLoader;

public class NeoForgeMixinPlugin extends CompatMixinPlugin {
    // The version range in neoforge.mods.toml already stops older versions from loading
    @Override
    protected boolean isModLoaded(String modId, String minVersion) {
        return FMLLoader.getCurrent().getLoadingModList().getModFileById(modId) != null;
    }
}
