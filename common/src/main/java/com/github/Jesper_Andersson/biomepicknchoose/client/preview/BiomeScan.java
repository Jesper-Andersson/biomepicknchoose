package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * A coarse map of the biomes around spawn in one dimension, sampled once per run so each biome's spot search can start
 * from known patches instead of searching outward from spawn on its own. The Nether and the End are scanned around
 * their origin. Only reads the biome source, so it is as thread safe as {@link BiomeSpotFinder}.
 */
final class BiomeScan {
    private static final int SCAN_RADIUS = 4096;
    private static final int SCAN_STEP = 32;
    private static final int SIZE = SCAN_RADIUS / SCAN_STEP * 2 + 1;
    private static final int ROWS_PER_TASK = 8;

    record Candidate(BlockPos pos, int neighbours, double distance) {}

    /**
     * The block heights relative to sea level to sample at, lowest first. The first undergroundCount of them are
     * underground, where cave biomes are looked for.
     */
    private record Layers(int[] heights, int undergroundCount) {
        // Below sea level for cave biomes, down to the deep dark, and the surface up to mountain tops
        static final Layers OVERWORLD = new Layers(new int[]{-112, -80, -48, -16, 0, 32, 64, 112}, 4);
        // Above the lava sea at 32 and below the bedrock roof at 128, all of it underground
        static final Layers NETHER = new Layers(new int[]{8, 24, 40, 56, 72}, 5);
        // The outer End biomes don't change with height
        static final Layers END = new Layers(new int[]{64}, 0);

        static Layers of(BiomeDimension dimension) {
            if (dimension.equals(BiomeDimension.NETHER)) return NETHER;
            if (dimension.equals(BiomeDimension.END)) return END;
            // Other dimensions are scanned like the overworld, since most of them have a surface
            return OVERWORLD;
        }
    }

    private final BlockPos center;
    private final int seaLevel;
    private final Layers layers;
    private final List<Holder<Biome>> biomes;
    private final Map<Holder<Biome>, Short> indices = new HashMap<>();
    // One grid per height, row major in z then x, -1 for biomes outside the source's possible biomes
    private final short[][] grid;

    private BiomeScan(ServerLevel level, BiomeDimension dimension) {
        this.center = center(level, dimension);
        this.seaLevel = level.getChunkSource().getGenerator().getSeaLevel();
        this.layers = Layers.of(dimension);
        this.grid = new short[layers.heights().length][SIZE * SIZE];
        this.biomes = List.copyOf(level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes());
        for (int i = 0; i < biomes.size(); i++) indices.put(biomes.get(i), (short) i);
    }

    static int columns() {
        return SIZE * SIZE;
    }

    BlockPos center() {
        return center;
    }

    /** Where the search starts: spawn in the overworld, the origin elsewhere. */
    static BlockPos center(ServerLevel level, BiomeDimension dimension) {
        return dimension.equals(BiomeDimension.OVERWORLD) ? level.getSharedSpawnPos() : BlockPos.ZERO;
    }

    /** Scans in row batches spread over the pool; progress gets the percentage of rows done. */
    static CompletableFuture<BiomeScan> run(ServerLevel level, BiomeDimension dimension, ExecutorService pool, IntConsumer progress) {
        BiomeScan scan = new BiomeScan(level, dimension);
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        AtomicInteger rowsDone = new AtomicInteger();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (int from = 0; from < SIZE; from += ROWS_PER_TASK) {
            int start = from;
            int end = Math.min(SIZE, from + ROWS_PER_TASK);
            tasks.add(CompletableFuture.runAsync(() -> {
                for (int row = start; row < end; row++) {
                    if (Thread.currentThread().isInterrupted()) return;
                    scan.scanRow(source, sampler, row);
                    progress.accept(rowsDone.incrementAndGet() * 100 / SIZE);
                }
            }, pool));
        }
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).thenApply(ignored -> scan);
    }

    private void scanRow(BiomeSource source, Climate.Sampler sampler, int row) {
        int quartZ = QuartPos.fromBlock(blockZ(row));
        for (int layer = 0; layer < layers.heights().length; layer++) {
            int quartY = QuartPos.fromBlock(seaLevel + layers.heights()[layer]);
            for (int column = 0; column < SIZE; column++) {
                int quartX = QuartPos.fromBlock(blockX(column));
                grid[layer][row * SIZE + column] = indices.getOrDefault(source.getNoiseBiome(quartX, quartY, quartZ, sampler), (short) -1);
            }
        }
    }

    private int blockX(int column) {
        return center.getX() - SCAN_RADIUS + column * SCAN_STEP;
    }

    private int blockZ(int row) {
        return center.getZ() - SCAN_RADIUS + row * SCAN_STEP;
    }

    /**
     * Every column where the biome was seen, most surrounded first, then nearest to spawn. Cave biomes, and every
     * Nether biome, are looked for underground, other biomes at and above sea level.
     */
    List<Candidate> candidates(ResourceKey<Biome> key, boolean cave) {
        List<Candidate> candidates = new ArrayList<>();
        short target = indexOf(key);
        if (target < 0) return candidates;
        int firstLayer = cave ? 0 : layers.undergroundCount();
        int endLayer = cave ? layers.undergroundCount() : layers.heights().length;
        for (int row = 0; row < SIZE; row++) {
            for (int column = 0; column < SIZE; column++) {
                // A column can match at several heights; keep the height where it is most surrounded
                int bestNeighbours = -1;
                int bestHeight = 0;
                for (int layer = firstLayer; layer < endLayer; layer++) {
                    if (grid[layer][row * SIZE + column] != target) continue;
                    int neighbours = countNeighbours(grid[layer], row, column, target);
                    if (neighbours > bestNeighbours) {
                        bestNeighbours = neighbours;
                        bestHeight = seaLevel + layers.heights()[layer];
                    }
                }
                if (bestNeighbours < 0) continue;
                BlockPos pos = new BlockPos(blockX(column), bestHeight, blockZ(row));
                double distance = Math.sqrt(pos.distSqr(center.atY(bestHeight)));
                candidates.add(new Candidate(pos, bestNeighbours, distance));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::neighbours).reversed().thenComparingDouble(Candidate::distance));
        return candidates;
    }

    // The biome's index in the grid, or -1 if the source can't place it
    private short indexOf(ResourceKey<Biome> key) {
        for (int i = 0; i < biomes.size(); i++) {
            if (biomes.get(i).is(key)) return (short) i;
        }
        return -1;
    }

    // How many of the 8 cells around the cell hold the target biome too
    private static int countNeighbours(short[] layerGrid, int row, int column, short target) {
        int neighbours = 0;
        for (int neighbourRow = row - 1; neighbourRow <= row + 1; neighbourRow++) {
            for (int neighbourColumn = column - 1; neighbourColumn <= column + 1; neighbourColumn++) {
                boolean isCell = neighbourRow == row && neighbourColumn == column;
                boolean inGrid = neighbourRow >= 0 && neighbourRow < SIZE && neighbourColumn >= 0 && neighbourColumn < SIZE;
                if (!isCell && inGrid && layerGrid[neighbourRow * SIZE + neighbourColumn] == target) neighbours++;
            }
        }
        return neighbours;
    }
}
