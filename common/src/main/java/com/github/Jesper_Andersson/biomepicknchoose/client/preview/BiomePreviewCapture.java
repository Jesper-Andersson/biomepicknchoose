package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeCatalog;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeDimension;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.github.Jesper_Andersson.biomepicknchoose.common.CaveBiomes;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ServerLevelData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Photographs overworld, Nether and End biomes one at a time: locate a spot on a background thread, teleport there in
 * spectator mode, wait for the chunks to render, then screenshot without the HUD. Cave biomes and Nether biomes are
 * photographed from inside a cave room. Singleplayer only, since it drives the integrated server.
 */
public final class BiomePreviewCapture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int WIDTH = 480;
    private static final int HEIGHT = 270;
    private static final int SETTLE_TICKS = 20;
    private static final int TIMEOUT_TICKS = 15 * 20;
    private static final long NOON = 6000;
    private static final int CAPTURE_RENDER_DISTANCE = 8;
    // Around a cave spot, where to look for a cave room: blocks to each side, above and below, and between the blocks tried
    private static final int CAVE_SEARCH_RADIUS = 24;
    private static final int CAVE_SEARCH_HEIGHT = 16;
    private static final int CAVE_SEARCH_STEP = 2;
    // How far a cave camera looks for open space in each direction
    private static final int CAVE_VIEW_DISTANCE = 32;
    private static final float CAVE_PITCH = 10;
    // A cave camera looks in this many directions, and picks the spot where the longest view, counted this many
    // times, plus the views in the other directions is largest. So a room beats the end of a long tunnel
    private static final int VIEW_DIRECTIONS = 8;
    private static final int LONGEST_VIEW_WEIGHT = 4;
    // How far below a cave camera, and below the space it looks into, there must be solid ground
    private static final int CAMERA_GROUND_DEPTH = 6;
    private static final int VIEW_GROUND_DEPTH = 12;

    @Nullable
    private static BiomePreviewCapture active;

    private static final int STATUS_INTERVAL = 5;

    // CAVE finds a cave room around a cave spot once its chunks exist, then waits again there
    private enum Step { PREPARE, LOCATE, WAIT, CAVE, CAPTURE }

    private enum WaitKind { MENU, TELEPORT, CHUNKS, RENDER, SETTLE }

    /** What WAIT is held up by; loaded and total are only set for CHUNKS. */
    private record WaitStatus(WaitKind kind, int loaded, int total) {
        boolean ready() {
            return kind == WaitKind.SETTLE;
        }
    }

    /** A biome to photograph, the dimension to look for it in, and whether to look underground. */
    private record Target(Holder<Biome> biome, BiomeDimension dimension, boolean cave) {}

    /** Where a cave camera could stand: how much open space it sees, and the direction it sees the most in. */
    private record Viewpoint(int score, float yaw) {}

    private record Saved(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch,
                         GameType gameMode, boolean daylight, boolean weatherCycle, long dayTime,
                         int clearTime, int rainTime, boolean raining, boolean thundering,
                         @Nullable MobEffectInstance nightVision) {}

    private final Minecraft minecraft;
    private final MinecraftServer server;
    private final UUID player;
    private final List<Target> targets;
    private final boolean hideGui;
    private final boolean pauseOnLostFocus;
    private final int renderDistance;
    // Half the cores, leaving the rest to the integrated server's chunk generation
    private final ExecutorService locator = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2), runnable -> {
                Thread thread = new Thread(runnable, "Biome Pick'n'Choose preview locator");
                thread.setDaemon(true);
                return thread;
            });
    // One scan per dimension with biomes to photograph
    private final Map<BiomeDimension, AtomicInteger> scanProgress = new HashMap<>();
    private final Map<BiomeDimension, CompletableFuture<BiomeScan>> scans = new HashMap<>();
    // Indexed like targets, null for disabled biomes
    private final List<CompletableFuture<BiomeSpotFinder.Result>> searches = new ArrayList<>();
    private final List<Identifier> captured = new ArrayList<>();
    private final List<Component> skipped = new ArrayList<>();
    private final List<Identifier> timedOut = new ArrayList<>();
    // How long each captured biome took, for the time left estimate
    private final List<Long> durations = new ArrayList<>();

    private Step step = Step.PREPARE;
    private int index = -1;
    private CompletableFuture<Saved> prepare;
    @Nullable
    private Saved saved;
    private CompletableFuture<BiomeSpotFinder.Result> locate;
    private BiomeSpotFinder.Spot spot;
    @Nullable
    private CompletableFuture<BiomeSpotFinder.Spot> caveRoom;
    private int waited;
    private int settled;
    private int frames;
    private long runStart;
    private long biomeStart;
    private long stepStart;
    // Restarts on step changes and on WAIT status changes, for the status line
    private long phaseStart;
    private long locateTime;
    private long waitTime;
    @Nullable
    private WaitStatus waitStatus;
    private int ticks;

    private BiomePreviewCapture(Minecraft minecraft, MinecraftServer server, List<Target> targets) {
        this.minecraft = minecraft;
        this.server = server;
        this.player = minecraft.player.getUUID();
        this.targets = targets;
        this.hideGui = minecraft.options.hideGui;
        this.pauseOnLostFocus = minecraft.options.pauseOnLostFocus;
        this.renderDistance = minecraft.options.renderDistance().get();
    }

    /** Starts a run, or returns why it can't. With missingOnly, biomes that already have a picture are left out. */
    @Nullable
    static Component start(@Nullable String namespace, boolean missingOnly) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) return Component.translatable("biomepicknchoose.command.preview.singleplayer_only");
        if (active != null) return Component.translatable("biomepicknchoose.command.preview.running");

        List<Target> targets = collectTargets(server, namespace, missingOnly);
        if (targets.isEmpty()) {
            return Component.translatable(missingOnly ? "biomepicknchoose.command.preview.none_missing" : "biomepicknchoose.command.preview.none");
        }

        BiomePreviewCapture capture = new BiomePreviewCapture(minecraft, server, targets);
        capture.runStart = Util.getMillis();
        capture.setStep(Step.PREPARE);
        capture.prepare = server.submit(capture::prepareServer);
        // Scan the biomes around spawn once per dimension, then search every spot from it up front so later spots are
        // ready while earlier chunks load
        for (Target target : targets) {
            ServerLevel level = server.getLevel(target.dimension().level());
            CompletableFuture<BiomeScan> scan = capture.scans.computeIfAbsent(target.dimension(), dimension -> capture.startScan(level, dimension));
            capture.searches.add(BiomeToggles.isDisabled(target.biome()) ? null
                    : scan.thenApplyAsync(done -> BiomeSpotFinder.find(level, target.biome(), target.cave(), done), capture.locator));
        }
        // Fewer chunks to generate and render per spot; the integrated server follows the client's view distance
        if (capture.renderDistance > CAPTURE_RENDER_DISTANCE) minecraft.options.renderDistance().set(CAPTURE_RENDER_DISTANCE);
        // An unfocused window would pause the integrated server and stall chunk generation
        minecraft.options.pauseOnLostFocus = false;
        active = capture;
        return null;
    }

    // The biomes to photograph, each dimension in turn. A biome placed in several dimensions is only taken in the first
    private static List<Target> collectTargets(MinecraftServer server, @Nullable String namespace, boolean missingOnly) {
        Set<Identifier> caves = CaveBiomes.fromServer(server);
        Set<Identifier> taken = new HashSet<>();
        List<Target> targets = new ArrayList<>();
        for (BiomeDimension dimension : BiomeCatalog.serverDimensions(server)) {
            ServerLevel level = server.getLevel(dimension.level());
            if (level == null) continue;
            Map<Identifier, Holder<Biome>> biomes = new TreeMap<>();
            for (Holder<Biome> biome : level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes()) {
                biome.unwrapKey().ifPresent(key -> biomes.put(key.identifier(), biome));
            }
            biomes.forEach((id, biome) -> {
                if (namespace != null && !id.getNamespace().equals(namespace)) return;
                if (missingOnly && BiomePreviews.hasPicture(id)) return;
                if (!taken.add(id)) return;
                // Every Nether biome is underground, below the bedrock roof
                boolean underground = dimension.equals(BiomeDimension.NETHER) || caves.contains(id);
                targets.add(new Target(biome, dimension, underground));
            });
        }
        return targets;
    }

    private CompletableFuture<BiomeScan> startScan(ServerLevel level, BiomeDimension dimension) {
        AtomicInteger progress = new AtomicInteger();
        scanProgress.put(dimension, progress);
        long scanStart = Util.getMillis();
        CompletableFuture<BiomeScan> scan = BiomeScan.run(level, dimension, locator,
                // Rows finish on several threads, so a lower percentage can arrive after a higher one
                percent -> progress.accumulateAndGet(percent, Math::max));
        scan.thenRun(() -> LOGGER.info("Biome scan of the {}: {} columns in {}", dimension.key(), BiomeScan.columns(),
                formatDuration(Util.getMillis() - scanStart)));
        return scan;
    }

    static boolean cancel() {
        BiomePreviewCapture capture = active;
        if (capture == null) return false;
        capture.end();
        capture.message(Component.translatable("biomepicknchoose.command.preview.cancelled"));
        return true;
    }

    /** Called by each loader at the end of every client tick. */
    public static void onClientTick() {
        BiomePreviewCapture capture = active;
        if (capture == null) return;
        if (capture.minecraft.level == null || capture.minecraft.getSingleplayerServer() != capture.server) {
            // World closed mid-run: nothing to restore on the server side any more
            capture.locator.shutdownNow();
            capture.restoreClient();
            active = null;
            return;
        }
        capture.tick();
    }

    /** Called by each loader after every rendered frame. */
    public static void onRenderFrame() {
        BiomePreviewCapture capture = active;
        if (capture != null && capture.step == Step.CAPTURE) capture.frame();
    }

    private void tick() {
        tickStep();
        if (active == this && step != Step.CAPTURE && ++ticks % STATUS_INTERVAL == 0) showStatus();
    }

    private void tickStep() {
        switch (step) {
            case PREPARE -> tickPrepare();
            case LOCATE -> tickLocate();
            case WAIT -> tickWait();
            case CAVE -> tickCave();
            case CAPTURE -> {}
        }
    }

    private void tickPrepare() {
        if (!prepare.isDone()) return;
        saved = prepare.join();
        next();
    }

    // Once the spot search for the biome finished, teleport there, or skip the biome if there is no spot
    private void tickLocate() {
        if (!locate.isDone()) return;
        locateTime = Util.getMillis() - stepStart;
        BiomeSpotFinder.Result result;
        try {
            result = locate.join();
        } catch (CompletionException e) {
            LOGGER.warn("Couldn't locate a spot for biome preview {}", currentId(), e.getCause());
            skipAndContinue("not_found");
            return;
        }
        if (result.spot() == null) {
            skipAndContinue(result.failure() == BiomeSpotFinder.Failure.UNDERGROUND ? "underground" : "not_found");
            return;
        }
        spot = result.spot();
        caveRoom = null;
        moveTo(spot);
    }

    // Waits for the chunks around the camera to load and render, then takes the picture
    private void tickWait() {
        waited++;
        WaitStatus status = waitStatus();
        settled = status.ready() ? settled + 1 : 0;
        boolean changed = waitStatus == null || waitStatus.kind() != status.kind();
        waitStatus = status;
        if (changed) {
            phaseStart = Util.getMillis();
            showStatus();
        }
        boolean chunksGenerated = status.kind() == WaitKind.RENDER || status.ready();
        if (spot.cave() && caveRoom == null && chunksGenerated) {
            findCaveRoomLater();
        } else if (settled >= SETTLE_TICKS) {
            shoot();
        } else if (waited >= TIMEOUT_TICKS) {
            LOGGER.warn("Biome preview for {} timed out, capturing anyway", currentId());
            timedOut.add(currentId());
            shoot();
        }
    }

    // A cave spot is only somewhere inside the biome, often in solid rock. Now that its chunks exist on the server,
    // look for a room in them to take the picture from
    private void findCaveRoomLater() {
        ResourceKey<Biome> key = current().biome().unwrapKey().orElseThrow();
        ServerLevel level = currentLevel();
        BiomeSpotFinder.Spot around = spot;
        caveRoom = server.submit(() -> findCaveRoom(level, around, key));
        setStep(Step.CAVE);
    }

    // Once the cave room search finished, move the camera into the room, or skip the biome if there is none
    private void tickCave() {
        if (!caveRoom.isDone()) return;
        BiomeSpotFinder.Spot room;
        try {
            room = caveRoom.join();
        } catch (CompletionException e) {
            LOGGER.warn("Couldn't find a cave room for biome preview {}", currentId(), e.getCause());
            room = null;
        }
        if (room == null) {
            skipAndContinue("no_cave");
            return;
        }
        spot = room;
        moveTo(spot);
    }

    private void skipAndContinue(String reason) {
        skip(reason);
        next();
    }

    private void moveTo(BiomeSpotFinder.Spot target) {
        ServerLevel level = currentLevel();
        server.execute(() -> teleportServer(level, target));
        waited = 0;
        settled = 0;
        waitStatus = null;
        setStep(Step.WAIT);
    }

    private Target current() {
        return targets.get(index);
    }

    private ServerLevel currentLevel() {
        return server.getLevel(current().dimension().level());
    }

    private void next() {
        index++;
        if (index >= targets.size()) {
            finish();
            return;
        }
        locate = searches.get(index);
        if (locate == null) {
            locateTime = 0;
            skip("disabled");
            next();
            return;
        }
        biomeStart = Util.getMillis();
        setStep(Step.LOCATE);
    }

    private void setStep(Step step) {
        this.step = step;
        stepStart = Util.getMillis();
        phaseStart = stepStart;
        if (step != Step.CAPTURE) showStatus();
    }

    private void skip(String reason) {
        LOGGER.info("Biome preview {}: skipped ({}), locate {}", currentId(), reason, formatDuration(locateTime));
        skipped.add(Component.translatable("biomepicknchoose.command.preview.reason." + reason, currentId().toString()));
    }

    private WaitStatus waitStatus() {
        var localPlayer = minecraft.player;
        if (minecraft.screen != null) return new WaitStatus(WaitKind.MENU, 0, 0);
        if (localPlayer == null || minecraft.level == null || minecraft.level.dimension() != current().dimension().level()
                || localPlayer.distanceToSqr(spot.x(), spot.y(), spot.z()) > 4) {
            return new WaitStatus(WaitKind.TELEPORT, 0, 0);
        }
        int radius = Math.max(2, minecraft.options.getEffectiveRenderDistance() - 1);
        int cx = localPlayer.chunkPosition().x;
        int cz = localPlayer.chunkPosition().z;
        int loaded = 0;
        int total = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                total++;
                if (minecraft.level.getChunkSource().hasChunk(cx + dx, cz + dz)) loaded++;
            }
        }
        if (loaded < total) return new WaitStatus(WaitKind.CHUNKS, loaded, total);
        if (!minecraft.levelRenderer.hasRenderedAllSections()) return new WaitStatus(WaitKind.RENDER, 0, 0);
        return new WaitStatus(WaitKind.SETTLE, 0, 0);
    }

    private void shoot() {
        waitTime = Util.getMillis() - stepStart;
        minecraft.options.hideGui = true;
        frames = 0;
        setStep(Step.CAPTURE);
    }

    private void showStatus() {
        long now = Util.getMillis();
        String biome = index >= 0 && index < targets.size() ? currentId().toString() : "-";
        message(Component.translatable("biomepicknchoose.command.preview.status", Math.max(index + 1, 0), targets.size(), biome,
                stepText(), formatDuration(now - phaseStart), eta(now)), true);
    }

    private Component stepText() {
        return switch (step) {
            case PREPARE -> Component.translatable("biomepicknchoose.command.preview.step.prepare");
            case LOCATE -> scans.get(current().dimension()).isDone() ? Component.translatable("biomepicknchoose.command.preview.step.locate")
                    : Component.translatable("biomepicknchoose.command.preview.step.scan", scanProgress.get(current().dimension()).get());
            case CAPTURE -> Component.translatable("biomepicknchoose.command.preview.step.capture");
            case CAVE -> Component.translatable("biomepicknchoose.command.preview.step.cave");
            case WAIT -> {
                WaitStatus status = waitStatus;
                if (status == null) yield Component.translatable("biomepicknchoose.command.preview.step.teleport");
                yield switch (status.kind()) {
                    case MENU -> Component.translatable("biomepicknchoose.command.preview.step.menu");
                    case TELEPORT -> Component.translatable("biomepicknchoose.command.preview.step.teleport");
                    case CHUNKS -> Component.translatable("biomepicknchoose.command.preview.step.chunks", status.loaded(), status.total());
                    case RENDER -> Component.translatable("biomepicknchoose.command.preview.step.render");
                    case SETTLE -> Component.translatable("biomepicknchoose.command.preview.step.settle", settled, SETTLE_TICKS);
                };
            }
        };
    }

    // Average capture time for each biome still to come, plus whatever is left of the average for the current one
    private Component eta(long now) {
        if (durations.isEmpty()) return Component.translatable("biomepicknchoose.command.preview.eta_unknown");
        long average = durations.stream().mapToLong(Long::longValue).sum() / durations.size();
        int left = 0;
        for (int i = Math.max(index + 1, 0); i < searches.size(); i++) {
            if (searches.get(i) != null) left++;
        }
        long current = Math.max(0, average - (now - biomeStart));
        return Component.translatable("biomepicknchoose.command.preview.eta", formatDuration(average * left + current));
    }

    private static String formatDuration(long ms) {
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        long seconds = ms / 1000;
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
    }

    // The first frame after hiding the HUD may have started before the tick that hid it
    private void frame() {
        if (++frames < 2) return;
        Identifier id = currentId();
        Path file = BiomePreviews.captureFile(id);
        // The frame is copied now, but read back from the GPU later, so the picture is made in the callback
        Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), screen -> {
            NativeImage preview = new NativeImage(WIDTH, HEIGHT, false);
            int w = screen.getWidth();
            int h = screen.getHeight();
            int cropW = Math.min(w, h * 16 / 9);
            int cropH = Math.min(h, w * 9 / 16);
            screen.resizeSubRectTo((w - cropW) / 2, (h - cropH) / 2, cropW, cropH, preview);
            screen.close();
            // The shared IO pool, which must not be closed
            //noinspection resource
            Util.ioPool().execute(() -> {
                try {
                    Files.createDirectories(file.getParent());
                    preview.writeToFile(file);
                } catch (IOException e) {
                    LOGGER.warn("Couldn't write biome preview {}", file, e);
                } finally {
                    preview.close();
                }
            });
        });
        minecraft.options.hideGui = false;
        captured.add(id);
        durations.add(Util.getMillis() - biomeStart);
        LOGGER.info("Biome preview {}: locate {}, wait {}", id, formatDuration(locateTime), formatDuration(waitTime));
        next();
    }

    private void finish() {
        end();
        Path dir = BiomePreviews.captureDir();
        Component folder = Component.literal(dir.toString()).withStyle(style -> style
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenFile(dir.toAbsolutePath().toString())));
        message(Component.translatable("biomepicknchoose.command.preview.done", captured.size(), targets.size(), folder,
                formatDuration(Util.getMillis() - runStart)));
        if (!skipped.isEmpty()) {
            message(Component.translatable("biomepicknchoose.command.preview.skipped",
                    ComponentUtils.formatList(skipped, Component.literal(", "))).withStyle(ChatFormatting.GRAY));
        }
        if (!timedOut.isEmpty()) {
            message(Component.translatable("biomepicknchoose.command.preview.timed_out",
                    ComponentUtils.formatList(timedOut, id -> Component.literal(id.toString()))).withStyle(ChatFormatting.YELLOW));
        }
    }

    private void end() {
        active = null;
        locator.shutdownNow();
        restoreClient();
        BiomePreviews.release();
        Saved state = saved;
        if (state != null) {
            server.execute(() -> restoreServer(state));
        } else if (prepare != null) {
            // Cancelled before the server saved its state: restore once it has
            prepare.thenAccept(state2 -> server.execute(() -> restoreServer(state2)));
        }
    }

    private void restoreClient() {
        minecraft.options.hideGui = hideGui;
        minecraft.options.pauseOnLostFocus = pauseOnLostFocus;
        minecraft.options.renderDistance().set(renderDistance);
    }

    private Identifier currentId() {
        return current().biome().unwrapKey().orElseThrow().identifier();
    }

    private void message(Component component) {
        message(component, false);
    }

    private void message(Component component, boolean actionBar) {
        if (minecraft.player != null) minecraft.player.displayClientMessage(component, actionBar);
    }

    // Server thread

    private Saved prepareServer() {
        ServerLevel overworld = server.overworld();
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player);
        GameRules rules = overworld.getGameRules();
        ServerLevelData data = server.getWorldData().overworldData();
        MobEffectInstance nightVision = serverPlayer.getEffect(MobEffects.NIGHT_VISION);
        // The level is only read, it belongs to the server
        //noinspection resource
        Saved state = new Saved(serverPlayer.level().dimension(), serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                serverPlayer.getYRot(), serverPlayer.getXRot(), serverPlayer.gameMode.getGameModeForPlayer(),
                rules.get(GameRules.ADVANCE_TIME), rules.get(GameRules.ADVANCE_WEATHER), overworld.getDayTime(),
                data.getClearWeatherTime(), data.getRainTime(), data.isRaining(), data.isThundering(),
                nightVision == null ? null : new MobEffectInstance(nightVision));
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        overworld.setWeatherParameters(0, 0, false, false);
        overworld.setDayTime(NOON);
        return state;
    }

    private void teleportServer(ServerLevel level, BiomeSpotFinder.Spot target) {
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player);
        if (serverPlayer == null) return;
        // The time of day and the weather are the overworld's, also when the picture is taken in another dimension
        ServerLevel overworld = server.overworld();
        serverPlayer.setGameMode(GameType.SPECTATOR);
        serverPlayer.teleportTo(level, target.x(), target.y(), target.z(), Set.of(), target.yaw(), target.pitch(), true);
        // Caves are dark, even at noon. Hidden, so it doesn't show in the picture
        serverPlayer.removeEffect(MobEffects.NIGHT_VISION);
        if (target.cave()) {
            serverPlayer.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
        }
        overworld.setDayTime(NOON);
        overworld.setWeatherParameters(0, 0, false, false);
    }

    private void restoreServer(Saved state) {
        ServerLevel overworld = server.overworld();
        GameRules rules = overworld.getGameRules();
        rules.set(GameRules.ADVANCE_TIME, state.daylight(), server);
        rules.set(GameRules.ADVANCE_WEATHER, state.weatherCycle(), server);
        overworld.setDayTime(state.dayTime());
        overworld.setWeatherParameters(state.clearTime(), state.rainTime(), state.raining(), state.thundering());
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player);
        ServerLevel level = server.getLevel(state.dimension());
        if (serverPlayer == null || level == null) return;
        serverPlayer.teleportTo(level, state.x(), state.y(), state.z(), Set.of(), state.yaw(), state.pitch(), true);
        serverPlayer.setGameMode(state.gameMode());
        serverPlayer.removeEffect(MobEffects.NIGHT_VISION);
        if (state.nightVision() != null) serverPlayer.addEffect(new MobEffectInstance(state.nightVision()));
    }

    /**
     * The open spot in the biome around a cave spot with the most open space to look into, facing the direction with the
     * longest view that stays in the biome. Null if there is no open space in the biome there.
     */
    @Nullable
    private BiomeSpotFinder.Spot findCaveRoom(ServerLevel level, BiomeSpotFinder.Spot around, ResourceKey<Biome> key) {
        BlockPos center = BlockPos.containing(around.x(), around.y(), around.z());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        float bestYaw = 0;
        int bestScore = 0;
        for (int dy = -CAVE_SEARCH_HEIGHT; dy <= CAVE_SEARCH_HEIGHT; dy += CAVE_SEARCH_STEP) {
            for (int dx = -CAVE_SEARCH_RADIUS; dx <= CAVE_SEARCH_RADIUS; dx += CAVE_SEARCH_STEP) {
                for (int dz = -CAVE_SEARCH_RADIUS; dz <= CAVE_SEARCH_RADIUS; dz += CAVE_SEARCH_STEP) {
                    pos.setWithOffset(center, dx, dy, dz);
                    if (!level.isLoaded(pos) || !level.getBlockState(pos).isAir() || !level.getBiome(pos).is(key)) continue;
                    // Close above solid ground, not hanging over a lava or water sea
                    if (!hasGround(level, pos, CAMERA_GROUND_DEPTH)) continue;
                    Viewpoint viewpoint = viewpoint(level, pos, key);
                    if (viewpoint.score() > bestScore) {
                        bestScore = viewpoint.score();
                        best = pos.immutable();
                        bestYaw = viewpoint.yaw();
                    }
                }
            }
        }
        if (best == null) return null;
        return new BiomeSpotFinder.Spot(best.getX() + 0.5, best.getY() + 0.5, best.getZ() + 0.5, bestYaw, CAVE_PITCH, true);
    }

    private static Viewpoint viewpoint(ServerLevel level, BlockPos pos, ResourceKey<Biome> key) {
        int total = 0;
        int longest = -1;
        float longestYaw = 0;
        for (int direction = 0; direction < VIEW_DIRECTIONS; direction++) {
            float yaw = direction * 360f / VIEW_DIRECTIONS;
            int view = caveView(level, pos, yaw, key);
            total += view;
            if (view > longest) {
                longest = view;
                longestYaw = yaw;
            }
        }
        return new Viewpoint(longest * LONGEST_VIEW_WEIGHT + total, longestYaw);
    }

    // Blocks of open space in the biome before something solid, looking along the yaw. Only counts open space with
    // ground under it, so a view out over the Nether's lava sea, or into a ravine, scores low
    private static int caveView(ServerLevel level, BlockPos from, float yaw, ResourceKey<Biome> key) {
        double dx = -Mth.sin(yaw * Mth.DEG_TO_RAD);
        double dz = Mth.cos(yaw * Mth.DEG_TO_RAD);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int view = 0;
        for (int d = 1; d <= CAVE_VIEW_DISTANCE; d++) {
            pos.set(from.getX() + Mth.floor(dx * d + 0.5), from.getY(), from.getZ() + Mth.floor(dz * d + 0.5));
            if (!level.isLoaded(pos) || level.getBlockState(pos).blocksMotion()) break;
            if (level.getBiome(pos).is(key) && hasGround(level, pos, VIEW_GROUND_DEPTH)) view++;
        }
        return view;
    }

    // Whether the first block below that isn't air is solid ground within depth blocks, not a fluid like lava
    private static boolean hasGround(ServerLevel level, BlockPos from, int depth) {
        BlockPos.MutableBlockPos pos = from.mutable();
        for (int d = 1; d <= depth; d++) {
            pos.move(0, -1, 0);
            if (!level.isLoaded(pos)) return false;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            return state.getFluidState().isEmpty();
        }
        return false;
    }
}
