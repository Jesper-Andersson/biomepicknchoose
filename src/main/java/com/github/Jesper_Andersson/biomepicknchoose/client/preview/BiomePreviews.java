package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.github.Jesper_Andersson.biomepicknchoose.BiomePickNChoose;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Biome preview pictures for the biome menu: shipped with the mod first, then captured by the player with
 * {@code /biomepick_preview capture}, otherwise none.
 */
public final class BiomePreviews {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceLocation, Optional<Preview>> CACHE = new HashMap<>();
    private static final List<ResourceLocation> DYNAMIC = new ArrayList<>();

    /** A loaded picture; captured is true when it was read from a {@code /biomepick_preview capture} file. */
    private record Preview(ResourceLocation texture, boolean captured) {}

    private BiomePreviews() {}

    /** Where {@code /biomepick_preview capture} writes its pictures. */
    public static Path captureDir() {
        return FMLPaths.CONFIGDIR.get().resolve(BiomePickNChoose.MODID).resolve("biome_previews");
    }

    public static Path captureFile(ResourceLocation biome) {
        return captureDir().resolve(biome.getNamespace()).resolve(biome.getPath() + ".png");
    }

    @Nullable
    public static ResourceLocation textureFor(ResourceLocation biome) {
        return CACHE.computeIfAbsent(biome, BiomePreviews::load).map(Preview::texture).orElse(null);
    }

    /** Whether the picture shown for this biome comes from a captured file, so removing it would change anything. */
    public static boolean isCaptured(ResourceLocation biome) {
        return CACHE.computeIfAbsent(biome, BiomePreviews::load).map(Preview::captured).orElse(false);
    }

    /** Whether a shipped or captured picture exists, without loading it. */
    public static boolean hasPicture(ResourceLocation biome) {
        return Minecraft.getInstance().getResourceManager().getResource(shippedLocation(biome)).isPresent()
                || Files.isRegularFile(captureFile(biome));
    }

    /** Frees this biome's captured texture and deletes its file. */
    public static void delete(ResourceLocation biome) {
        ResourceLocation location = dynamicLocation(biome);
        if (DYNAMIC.remove(location)) Minecraft.getInstance().getTextureManager().release(location);
        CACHE.remove(biome);
        Path file = captureFile(biome);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Couldn't delete biome preview {}", file, e);
        }
    }

    /** Frees the textures loaded from captured files and forgets every lookup. */
    public static void release() {
        Minecraft minecraft = Minecraft.getInstance();
        DYNAMIC.forEach(minecraft.getTextureManager()::release);
        DYNAMIC.clear();
        CACHE.clear();
    }

    private static ResourceLocation shippedLocation(ResourceLocation biome) {
        return ResourceLocation.fromNamespaceAndPath(BiomePickNChoose.MODID,
                "textures/gui/biome_preview/" + biome.getNamespace() + "/" + biome.getPath() + ".png");
    }

    private static ResourceLocation dynamicLocation(ResourceLocation biome) {
        return ResourceLocation.fromNamespaceAndPath(BiomePickNChoose.MODID,
                "dynamic/biome_preview/" + biome.getNamespace() + "/" + biome.getPath());
    }

    private static Optional<Preview> load(ResourceLocation biome) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation shipped = shippedLocation(biome);
        if (minecraft.getResourceManager().getResource(shipped).isPresent()) return Optional.of(new Preview(shipped, false));

        Path file = captureFile(biome);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            ResourceLocation location = dynamicLocation(biome);
            minecraft.getTextureManager().register(location, new DynamicTexture(image));
            DYNAMIC.add(location);
            return Optional.of(new Preview(location, true));
        } catch (IOException e) {
            LOGGER.warn("Couldn't read biome preview {}", file, e);
            return Optional.empty();
        }
    }
}
