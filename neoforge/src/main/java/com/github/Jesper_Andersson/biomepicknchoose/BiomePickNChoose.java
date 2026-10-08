package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.github.Jesper_Andersson.biomepicknchoose.common.SmokeTest;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.InterModComms;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.InterModProcessEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Mod(Constants.MOD_ID)
public class BiomePickNChoose {
    private static final Logger LOGGER = LogUtils.getLogger();

    public BiomePickNChoose(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, NeoForgeConfig.SPEC);
        modEventBus.addListener(BiomePickNChoose::onInterModProcess);
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> BiomeToggles.onServerAboutToStart());
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> BiomeToggles.onServerStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> SmokeTest.onServerStopped());
    }

    private static void onInterModProcess(InterModProcessEvent event) {
        Set<ResourceLocation> ids = new HashSet<>();
        InterModComms.getMessages(Constants.MOD_ID, BiomeToggles.REGISTER_BIOMES::equals).forEach(message -> {
            if (message.messageSupplier().get() instanceof Collection<?> biomes) {
                for (Object biome : biomes) {
                    ResourceLocation id = toId(biome);
                    if (id != null) ids.add(id);
                    else LOGGER.warn("Ignoring biome {} from mod {}, expected a ResourceLocation, ResourceKey or String", biome, message.senderModId());
                }
            } else {
                LOGGER.warn("Ignoring {} message from mod {}, expected a Collection", BiomeToggles.REGISTER_BIOMES, message.senderModId());
            }
        });
        BiomeToggles.setRegistered(ids);
    }

    private static ResourceLocation toId(Object biome) {
        if (biome instanceof ResourceLocation location) return location;
        if (biome instanceof ResourceKey<?> key) return key.location();
        if (biome instanceof String string) return ResourceLocation.tryParse(string);
        return null;
    }
}
