package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
    private static final Map<ResourceLocation, Optional<Preview>> CACHE = new HashMap<>();
    private static final List<ResourceLocation> DYNAMIC = new ArrayList<>();
    // Small copies for the rows of the biome list, decoded off the render thread. Empty when there is no picture
    public static final int THUMBNAIL_WIDTH = 128;
    public static final int THUMBNAIL_HEIGHT = 72;
    private static final Map<ResourceLocation, Optional<ResourceLocation>> THUMBNAILS = new HashMap<>();
    private static final Set<ResourceLocation> LOADING = new HashSet<>();
    // Bumped by release, so thumbnails still loading from before are dropped
    private static int generation;

    /** A loaded picture; captured is true when it was read from a {@code /biomepick_preview capture} file. */
    private record Preview(ResourceLocation texture, boolean captured) {}

    private BiomePreviews() {}

    /** Where {@code /biomepick_preview capture} writes its pictures. */
    public static Path captureDir() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID).resolve("biome_previews");
    }

    public static Path captureFile(ResourceLocation biome) {
        return captureDir().resolve(biome.getNamespace()).resolve(biome.getPath() + ".png");
    }

    @Nullable
    public static ResourceLocation textureFor(ResourceLocation biome) {
        return CACHE.computeIfAbsent(biome, BiomePreviews::load).map(Preview::texture).orElse(null);
    }

    /**
     * The thumbnail for the biome list, or null while it is loading or if there is no picture. Starts loading it on
     * the first call.
     */
    @Nullable
    public static ResourceLocation thumbnailFor(ResourceLocation biome) {
        Optional<ResourceLocation> thumbnail = THUMBNAILS.get(biome);
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
                ResourceLocation location = thumbnailLocation(biome);
                minecraft.getTextureManager().register(location, new DynamicTexture(image));
                DYNAMIC.add(location);
                THUMBNAILS.put(biome, Optional.of(location));
            }, minecraft);
        }
        return null;
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

    /** Frees this biome's texture and deletes its captured file. */
    public static void delete(ResourceLocation biome) {
        ResourceLocation location = dynamicLocation(biome);
        if (DYNAMIC.remove(location)) Minecraft.getInstance().getTextureManager().release(location);
        ResourceLocation thumbnail = thumbnailLocation(biome);
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

    private static ResourceLocation shippedLocation(ResourceLocation biome) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
                "textures/gui/biome_preview/" + biome.getNamespace() + "/" + biome.getPath() + ".jpg");
    }

    private static ResourceLocation dynamicLocation(ResourceLocation biome) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
                "dynamic/biome_preview/" + biome.getNamespace() + "/" + biome.getPath());
    }

    private static ResourceLocation thumbnailLocation(ResourceLocation biome) {
        return ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID,
                "dynamic/biome_thumbnail/" + biome.getNamespace() + "/" + biome.getPath());
    }

    // Runs on a background thread
    @Nullable
    private static NativeImage readThumbnail(ResourceLocation biome) {
        try (NativeImage image = read(biome)) {
            if (image == null) return null;
            NativeImage thumbnail = new NativeImage(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT, false);
            image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), thumbnail);
            return thumbnail;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read biome preview for {}", biome, e);
            return null;
        }
    }

    private static Optional<Preview> load(ResourceLocation biome) {
        try {
            NativeImage image = read(biome);
            if (image == null) return Optional.empty();
            ResourceLocation location = dynamicLocation(biome);
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
            DYNAMIC.add(location);
            return Optional.of(new Preview(location, !hasShipped(biome)));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read biome preview for {}", biome, e);
            return Optional.empty();
        }
    }

    private static boolean hasShipped(ResourceLocation biome) {
        return Minecraft.getInstance().getResourceManager().getResource(shippedLocation(biome)).isPresent();
    }

    /**
     * The shipped picture, else the captured one, else null. Always RGBA, which resizeSubRectTo needs for the
     * thumbnail, since captured pictures can be RGB.
     */
    @Nullable
    private static NativeImage read(ResourceLocation biome) throws IOException {
        Optional<Resource> shipped = Minecraft.getInstance().getResourceManager().getResource(shippedLocation(biome));
        if (shipped.isPresent()) {
            try (InputStream in = shipped.get().open()) {
                return readJpeg(in);
            }
        }
        Path file = captureFile(biome);
        if (!Files.isRegularFile(file)) return null;
        try (InputStream in = Files.newInputStream(file)) {
            return NativeImage.read(NativeImage.Format.RGBA, in);
        }
    }

    // Shipped pictures are JPEG to keep the jar small, which NativeImage can't read
    private static NativeImage readJpeg(InputStream in) throws IOException {
        BufferedImage picture = ImageIO.read(in);
        if (picture == null) throw new IOException("Not a readable JPEG");
        int width = picture.getWidth();
        int height = picture.getHeight();
        int[] argb = picture.getRGB(0, 0, width, height, null, 0, width);
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                // setPixelRGBA takes ABGR: swap red and blue, and make it opaque
                image.setPixelRGBA(x, y, 0xFF000000 | (pixel & 0xFF00) | (pixel >> 16 & 0xFF) | (pixel & 0xFF) << 16);
            }
        }
        return image;
    }
}
