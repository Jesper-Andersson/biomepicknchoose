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
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;

/**
 * Headless check that a disabled biome doesn't generate, run by the {@code runSmokeTest} Gradle tasks. Only active
 * when the {@value #PROPERTY} system property names the biome that the run's config disables. Once the server has
 * started, compares the biomes of the new world with what vanilla would place there, then stops the server and exits
 * with 0 if the biome never appeared and 1 otherwise.
 */
public final class SmokeTest {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String PROPERTY = "biomepicknchoose.smoketest";
    // The biome source is sampled every 64 blocks out to this distance from spawn, without generating chunks
    // Then chunks are generated up to their biomes around the spot nearest to spawn where vanilla would place the biome,
    // and read back from the world
    private static final int SAMPLE_RADIUS = 4096;
    private static final int SAMPLE_STEP = 64;
    private static final int SAMPLE_Y = 64;
    private static final int CHUNK_RADIUS = 8;

    private static volatile Boolean passed;

    private SmokeTest() {}

    public static boolean enabled() {
        return System.getProperty(PROPERTY) != null;
    }

    /** Called from {@link BiomeToggles#onServerStarted}. */
    static void onServerStarted(MinecraftServer server) {
        if (!enabled()) return;
        try {
            passed = check(server);
        } catch (RuntimeException e) {
            LOGGER.error("Smoke test crashed", e);
            passed = false;
        }
        server.halt(false);
    }

    /** Called by each loader once a server has stopped. Exits, as the smoke test is all this server was started for. */
    public static void onServerStopped() {
        if (!enabled()) return;
        if (passed == null) LOGGER.error("Smoke test FAILED: the server stopped before the check ran");
        LOGGER.info("Smoke test exiting");
        LogManager.shutdown();
        Runtime.getRuntime().halt(Boolean.TRUE.equals(passed) ? 0 : 1);
    }

    private static boolean check(MinecraftServer server) {
        ResourceLocation id = ResourceLocation.tryParse(System.getProperty(PROPERTY));
        if (id == null) {
            LOGGER.error("Smoke test FAILED: invalid biome id {}", System.getProperty(PROPERTY));
            return false;
        }
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
                    if (nearest == null || pos.distManhattan(spawn) < nearest.distManhattan(spawn)) nearest = pos;
                }
                if (source.getNoiseBiome(qx, quartY, qz, sampler).is(target)) sampleFound++;
            }
        }
        if (nearest == null) {
            LOGGER.error("Smoke test FAILED: vanilla wouldn't place {} near spawn either, pick another biome or seed", id);
            return false;
        }

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
        LOGGER.info("Smoke test PASSED: {} doesn't generate", id);
        return true;
    }
}
