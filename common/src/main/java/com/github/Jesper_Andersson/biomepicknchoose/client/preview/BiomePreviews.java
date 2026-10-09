package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Biome preview pictures for the biome menu: shipped with the mod first, then captured by the player with
 * {@code /biomepick_preview capture}, otherwise none.
 */
public final class BiomePreviews {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Identifier, Optional<Preview>> CACHE = new HashMap<>();
    private static final List<Identifier> DYNAMIC = new ArrayList<>();
    // Small copies for the rows of the biome list, decoded off the render thread. Empty when there is no picture
    public static final int THUMBNAIL_WIDTH = 128;
    public static final int THUMBNAIL_HEIGHT = 72;
    private static final Map<Identifier, Optional<Identifier>> THUMBNAILS = new HashMap<>();
    private static final Set<Identifier> LOADING = new HashSet<>();
    // Bumped by release, so thumbnails still loading from before are dropped
    private static int generation;

    /** A loaded picture; captured is true when it was read from a {@code /biomepick_preview capture} file. */
    private record Preview(Identifier texture, boolean captured) {}

    private BiomePreviews() {}

    /** Where {@code /biomepick_preview capture} writes its pictures. */
    public static Path captureDir() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID).resolve("biome_previews");
    }

    public static Path captureFile(Identifier biome) {
        return captureDir().resolve(biome.getNamespace()).resolve(biome.getPath() + ".png");
    }

    @Nullable
    public static Identifier textureFor(Identifier biome) {
        return CACHE.computeIfAbsent(biome, BiomePreviews::load).map(Preview::texture).orElse(null);
    }

    /**
     * The thumbnail for the biome list, or null while it is loading or if there is no picture. Starts loading it on
     * the first call.
     */
    @Nullable
    public static Identifier thumbnailFor(Identifier biome) {
        Optional<Identifier> thumbnail = THUMBNAILS.get(biome);
        if (thumbnail != null) return thumbnail.orElse(null);
        if (LOADING.add(biome)) {
            Minecraft minecraft = Minecraft.getInstance();
            int started = generation;
            CompletableFuture.supplyAsync(() -> readThumbnail(biome), Util.backgroundExecutor()).thenAcceptAsync(image -> {
                if (started != generation) {
                    if (image != null) image.close();
                    return;
                }
                LOADING.remove(biome);
                if (image == null) {
                    THUMBNAILS.put(biome, Optional.empty());
                    return;
                }
                Identifier location = thumbnailLocation(biome);
                minecraft.getTextureManager().register(location, new DynamicTexture(location::toString, image));
                DYNAMIC.add(location);
                THUMBNAILS.put(biome, Optional.of(location));
            }, minecraft);
        }
        return null;
    }

    /** Whether the picture shown for this biome comes from a captured file, so removing it would change anything. */
    public static boolean isCaptured(Identifier biome) {
        return CACHE.computeIfAbsent(biome, BiomePreviews::load).map(Preview::captured).orElse(false);
    }

    /** Whether a shipped or captured picture exists, without loading it. */
    public static boolean hasPicture(Identifier biome) {
        return Minecraft.getInstance().getResourceManager().getResource(shippedLocation(biome)).isPresent()
                || Files.isRegularFile(captureFile(biome));
    }

    /** Frees this biome's captured texture and deletes its file. */
    public static void delete(Identifier biome) {
        Identifier location = dynamicLocation(biome);
        if (DYNAMIC.remove(location)) Minecraft.getInstance().getTextureManager().release(location);
        Identifier thumbnail = thumbnailLocation(biome);
        if (DYNAMIC.remove(thumbnail)) Minecraft.getInstance().getTextureManager().release(thumbnail);
        CACHE.remove(biome);
        THUMBNAILS.remove(biome);
        // Drop thumbnails still loading, in case this one was, so they are loaded again
        LOADING.clear();
        generation++;
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
        THUMBNAILS.clear();
        LOADING.clear();
        generation++;
    }

    private static Identifier shippedLocation(Identifier biome) {
        return Identifier.fromNamespaceAndPath(Constants.MOD_ID,
                "textures/gui/biome_preview/" + biome.getNamespace() + "/" + biome.getPath() + ".png");
    }

    private static Identifier dynamicLocation(Identifier biome) {
        return Identifier.fromNamespaceAndPath(Constants.MOD_ID,
                "dynamic/biome_preview/" + biome.getNamespace() + "/" + biome.getPath());
    }

    private static Identifier thumbnailLocation(Identifier biome) {
        return Identifier.fromNamespaceAndPath(Constants.MOD_ID,
                "dynamic/biome_thumbnail/" + biome.getNamespace() + "/" + biome.getPath());
    }

    // Runs on a background thread. The shipped picture first, like load
    @Nullable
    private static NativeImage readThumbnail(Identifier biome) {
        Optional<Resource> shipped = Minecraft.getInstance().getResourceManager().getResource(shippedLocation(biome));
        Path file = captureFile(biome);
        if (shipped.isEmpty() && !Files.isRegularFile(file)) return null;
        try (InputStream in = shipped.isPresent() ? shipped.get().open() : Files.newInputStream(file);
             // RGBA like the thumbnail, since resizeSubRectTo needs both in the same format and pictures can be RGB
             NativeImage image = NativeImage.read(NativeImage.Format.RGBA, in)) {
            NativeImage thumbnail = new NativeImage(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, false);
            image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), thumbnail);
            return thumbnail;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read biome preview for {}", biome, e);
            return null;
        }
    }

    private static Optional<Preview> load(Identifier biome) {
        Minecraft minecraft = Minecraft.getInstance();
        Identifier shipped = shippedLocation(biome);
        if (minecraft.getResourceManager().getResource(shipped).isPresent()) return Optional.of(new Preview(shipped, false));

        Path file = captureFile(biome);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            Identifier location = dynamicLocation(biome);
            minecraft.getTextureManager().register(location, new DynamicTexture(location::toString, image));
            DYNAMIC.add(location);
            return Optional.of(new Preview(location, true));
        } catch (IOException e) {
            LOGGER.warn("Couldn't read biome preview {}", file, e);
            return Optional.empty();
        }
    }
}
