package com.github.Jesper_Andersson.biomepicknchoose.platform;

import com.github.Jesper_Andersson.biomepicknchoose.NeoForgeConfig;
import com.github.Jesper_Andersson.biomepicknchoose.platform.services.IPlatformHelper;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class NeoForgePlatformHelper implements IPlatformHelper {
    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Optional<String> getModName(String modId) {
        return ModList.get().getModContainerById(modId).map(container -> container.getModInfo().getDisplayName());
    }

    @Override
    public List<String> getDisabledBiomes() {
        return List.copyOf(NeoForgeConfig.DISABLED_BIOMES.get());
    }

    @Override
    public void setDisabledBiomes(List<String> biomes) {
        NeoForgeConfig.DISABLED_BIOMES.set(biomes);
        NeoForgeConfig.SPEC.save();
    }
}
