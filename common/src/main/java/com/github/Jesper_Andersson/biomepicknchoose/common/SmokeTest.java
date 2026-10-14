package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Headless check that a disabled biome doesn't generate, run by the {@code runSmokeTest} Gradle tasks. Only active
 * when the {@value #PROPERTY} system property names the biome that the run's config disables. Once the server has
 * started, compares the biomes of the new world with what vanilla would place there. Then it turns that biome back
 * on and disables {@link #RELOAD_BIOME} in the config, runs {@code /reload}, and checks the same for that biome in
 * chunks generated after the reload. Then it does the same for {@link #NETHER_BIOME}, {@link #END_BIOME} and
 * {@link #CUSTOM_BIOME}, sampling the biome sources of the Nether, the End and {@link #CUSTOM_DIMENSION} only. That
 * dimension comes from a datapack that the Gradle task puts in the world folder. Finally, it stops the server and exits with 0 if all passed and 1 otherwise.
 */
public final class SmokeTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String PROPERTY = "biomepicknchoose.smoketest";
    // In the game folder
    private static final String RESULT_FILE = "smoketest-result.txt";
    private static final ResourceLocation RELOAD_BIOME = ResourceLocation.withDefaultNamespace("forest");
    private static final ResourceLocation NETHER_BIOME = ResourceLocation.withDefaultNamespace("soul_sand_valley");
    private static final ResourceLocation END_BIOME = ResourceLocation.withDefaultNamespace("end_highlands");
    private static final ResourceKey<Level> CUSTOM_DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("bpnc_test", "plains_world"));
    private static final ResourceLocation CUSTOM_BIOME = ResourceLocation.withDefaultNamespace("cherry_grove");
    // The biome source is sampled every 64 blocks out to this distance from spawn, without generating chunks
    // Then chunks are generated up to their biomes around the spot nearest to spawn where vanilla would place the biome,
    // and read back from the world
    private static final int SAMPLE_RADIUS = 4096;
    private static final int SAMPLE_STEP = 64;
    private static final int SAMPLE_Y = 64;
    private static final int CHUNK_RADIUS = 8;
    // Fails the test if it hasn't finished by then, for example when the reload never completes
    private static final long TIMEOUT_MILLIS = 5 * 60 * 1000;

    private static volatile Boolean passed;
    // Center of the chunks generated before the reload, which keep their biomes, so the second check avoids them
    @Nullable
    private static BlockPos firstCenter;
    /** Which /reload the test is waiting for. */
    private enum PendingReload { NONE, OVERWORLD, OTHER_DIMENSIONS }

    private static PendingReload pendingReload = PendingReload.NONE;
    // The biome disabled at the start, set once the property was parsed
    private static ResourceLocation firstBiome;

    private SmokeTest() {}

    public static boolean enabled() {
        return System.getProperty(PROPERTY) != null;
    }

    /** Called from {@link BiomeToggles#onServerStarted}. */
    static void onServerStarted(MinecraftServer server) {
        if (!enabled()) return;
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(TIMEOUT_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            LOGGER.error("Smoke test FAILED: timed out");
            exit(false);
        }, "Smoke test watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        ResourceLocation id = ResourceLocation.tryParse(System.getProperty(PROPERTY));
        if (id == null) {
            LOGGER.error("Smoke test FAILED: invalid biome id {}", System.getProperty(PROPERTY));
            finish(server, false);
            return;
        }
        firstBiome = id;
        if (!run(server, () -> check(server, id, null))) return;

        // Swap which biome is disabled, then apply it with /reload, see onReload
        BiomeConfig.save(Set.of(id), List.of(RELOAD_BIOME.toString()));
        pendingReload = PendingReload.OVERWORLD;
        LOGGER.info("Smoke test: enabled {} and disabled {} in the config, running /reload", id, RELOAD_BIOME);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
    }

    /** Called from {@link BiomeToggles#onReload}. */
    static void onReload(MinecraftServer server) {
        if (!enabled() || pendingReload == PendingReload.NONE) return;
        PendingReload reload = pendingReload;
        pendingReload = PendingReload.NONE;
        if (reload == PendingReload.OTHER_DIMENSIONS) {
            if (run(server, () -> checkSource(server, Level.NETHER, NETHER_BIOME) && checkSource(server, Level.END, END_BIOME)
                    && checkSource(server, CUSTOM_DIMENSION, CUSTOM_BIOME))) {
                finish(server, true);
            }
            return;
        }
        ResourceLocation first = firstBiome;
        if (BiomeToggles.isDisabled(ResourceKey.create(Registries.BIOME, first))) {
            LOGGER.error("Smoke test FAILED: {} is still disabled after /reload", first);
            finish(server, false);
            return;
        }
        if (!run(server, () -> check(server, RELOAD_BIOME, firstCenter))) return;

        BiomeConfig.save(Set.of(RELOAD_BIOME), List.of(NETHER_BIOME.toString(), END_BIOME.toString(), CUSTOM_BIOME.toString()));
        pendingReload = PendingReload.OTHER_DIMENSIONS;
        LOGGER.info("Smoke test: enabled {} and disabled {}, {} and {} in the config, running /reload", RELOAD_BIOME,
                NETHER_BIOME, END_BIOME, CUSTOM_BIOME);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
    }

    // Samples the dimension's biome source around the origin, against a vanilla source of the same kind that was never
    // activated, so it shows where the biome would have generated
    private static boolean checkSource(MinecraftServer server, ResourceKey<Level> dimension, ResourceLocation id) {
        ResourceKey<Biome> target = ResourceKey.create(Registries.BIOME, id);
        if (!BiomeToggles.isDisabled(target)) {
            LOGGER.error("Smoke test FAILED: {} isn't disabled after /reload", id);
            return false;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            LOGGER.error("Smoke test FAILED: the server has no {}", dimension.location());
            return false;
        }
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        BiomeSource vanilla;
        if (dimension == Level.END) {
            vanilla = TheEndBiomeSource.create(server.registryAccess().lookupOrThrow(Registries.BIOME));
        } else if (BiomeSources.root(source) instanceof BiomeToggleSource root && root.bpnc$parameters() != null) {
            // The same parameters, with the disabled biomes, in a source that was never activated
            vanilla = MultiNoiseBiomeSource.createFromList(root.bpnc$parameters());
        } else {
            LOGGER.error("Smoke test FAILED: the {} biome source {} isn't supported", dimension.location(), source.getClass().getName());
            return false;
        }
        int quartY = QuartPos.fromBlock(SAMPLE_Y);
        int expected = 0, found = 0;
        for (int x = -SAMPLE_RADIUS; x <= SAMPLE_RADIUS; x += SAMPLE_STEP) {
            for (int z = -SAMPLE_RADIUS; z <= SAMPLE_RADIUS; z += SAMPLE_STEP) {
                int qx = QuartPos.fromBlock(x), qz = QuartPos.fromBlock(z);
                if (vanilla.getNoiseBiome(qx, quartY, qz, sampler).is(target)) expected++;
                if (source.getNoiseBiome(qx, quartY, qz, sampler).is(target)) found++;
            }
        }
        LOGGER.info("Smoke test: {} in sampled {} biome source: {} (vanilla would have {})", id, dimension.location(), found, expected);
        if (expected == 0) {
            LOGGER.error("Smoke test FAILED: vanilla wouldn't place {} near the origin either", id);
            return false;
        }
        if (found > 0) {
            LOGGER.error("Smoke test FAILED: {} still generates", id);
            return false;
        }
        LOGGER.info("Smoke test: {} doesn't generate", id);
        return true;
    }

    /** Called by each loader once a server has stopped. Exits, as the smoke test is all this server was started for. */
    public static void onServerStopped() {
        if (!enabled()) return;
        if (passed == null) LOGGER.error("Smoke test FAILED: the server stopped before the checks finished");
        exit(Boolean.TRUE.equals(passed));
    }

    // Runs one check, and stops the server unless it passed
    private static boolean run(MinecraftServer server, BooleanSupplier check) {
        boolean result;
        try {
            result = check.getAsBoolean();
        } catch (RuntimeException e) {
            LOGGER.error("Smoke test crashed", e);
            result = false;
        }
        if (!result) finish(server, false);
        return result;
    }

    private static void finish(MinecraftServer server, boolean result) {
        passed = result;
        if (result) LOGGER.info("Smoke test PASSED");
        server.halt(false);
    }

    private static void exit(boolean result) {
        // Checked by the Gradle task, since the exit code is 0 when the game fails before this code runs
        try {
            Files.writeString(Path.of(RESULT_FILE), result ? "PASSED" : "FAILED");
        } catch (IOException e) {
            LOGGER.error("Couldn't write {}", RESULT_FILE, e);
        }
        LOGGER.info("Smoke test exiting");
        LogManager.shutdown();
        Runtime.getRuntime().halt(result ? 0 : 1);
    }

    private static boolean check(MinecraftServer server, ResourceLocation id, @Nullable BlockPos exclude) {
        ResourceKey<Biome> target = ResourceKey.create(Registries.BIOME, id);
        if (!BiomeToggles.isDisabled(target)) {
            LOGGER.error("Smoke test FAILED: {} isn't disabled, check the run's config", id);
            return false;
        }

        ServerLevel overworld = server.overworld();
        BiomeSource source = overworld.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = overworld.getChunkSource().randomState().sampler();
        // The vanilla overworld parameters, without the mod, to show the biome would have generated at all
        Climate.ParameterList<Holder<Biome>> vanilla = server.registryAccess()
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD).value().parameters();
        BlockPos spawn = overworld.getSharedSpawnPos();
        int quartY = QuartPos.fromBlock(SAMPLE_Y);

        // The biome source over a wide area, remembering where vanilla would place the biome nearest to spawn
        int sampleExpected = 0, sampleFound = 0;
        BlockPos nearest = null;
        for (int x = -SAMPLE_RADIUS; x <= SAMPLE_RADIUS; x += SAMPLE_STEP) {
            for (int z = -SAMPLE_RADIUS; z <= SAMPLE_RADIUS; z += SAMPLE_STEP) {
                BlockPos pos = new BlockPos(spawn.getX() + x, SAMPLE_Y, spawn.getZ() + z);
                int qx = QuartPos.fromBlock(pos.getX()), qz = QuartPos.fromBlock(pos.getZ());
                if (vanilla.findValue(sampler.sample(qx, quartY, qz)).is(target)) {
                    sampleExpected++;
                    boolean free = exclude == null || Math.max(Math.abs((pos.getX() >> 4) - (exclude.getX() >> 4)),
                            Math.abs((pos.getZ() >> 4) - (exclude.getZ() >> 4))) > 2 * CHUNK_RADIUS + 1;
                    if (free && (nearest == null || pos.distManhattan(spawn) < nearest.distManhattan(spawn))) nearest = pos;
                }
                if (source.getNoiseBiome(qx, quartY, qz, sampler).is(target)) sampleFound++;
            }
        }
        if (nearest == null) {
            LOGGER.error("Smoke test FAILED: vanilla wouldn't place {} near spawn either, pick another biome or seed", id);
            return false;
        }
        if (exclude == null) firstCenter = nearest;

        // Biomes stored in newly generated chunks around that spot
        int chunkExpected = 0, chunkFound = 0;
        int centerX = nearest.getX() >> 4, centerZ = nearest.getZ() >> 4;
        for (int cx = centerX - CHUNK_RADIUS; cx <= centerX + CHUNK_RADIUS; cx++) {
            for (int cz = centerZ - CHUNK_RADIUS; cz <= centerZ + CHUNK_RADIUS; cz++) {
                ChunkAccess chunk = overworld.getChunk(cx, cz, ChunkStatus.BIOMES, true);
                for (int qx = QuartPos.fromSection(cx); qx < QuartPos.fromSection(cx + 1); qx++) {
                    for (int qz = QuartPos.fromSection(cz); qz < QuartPos.fromSection(cz + 1); qz++) {
                        if (vanilla.findValue(sampler.sample(qx, quartY, qz)).is(target)) chunkExpected++;
                        if (chunk.getNoiseBiome(qx, quartY, qz).is(target)) chunkFound++;
                    }
                }
            }
        }

        LOGGER.info("Smoke test: {} in sampled biome source: {} (vanilla would have {}), in chunks generated around {}: {} (vanilla would have {})",
                id, sampleFound, sampleExpected, nearest.toShortString(), chunkFound, chunkExpected);
        if (chunkFound > 0 || sampleFound > 0) {
            LOGGER.error("Smoke test FAILED: {} still generates", id);
            return false;
        }
        LOGGER.info("Smoke test: {} doesn't generate", id);
        return true;
    }
}
