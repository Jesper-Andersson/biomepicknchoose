package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionCheck;
import org.slf4j.Logger;

import java.util.List;

/**
 * Lets an operator edit the server's config from a client that has the mod. {@code /biomepicknchoose config} sends the
 * server's biomes and config to the client, which opens the toggle screen, and Done there sends the disabled biomes
 * back to be saved. Each loader registers the payloads and the command, and calls the handlers.
 */
public final class ServerConfigEditing {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final PermissionCheck PERMISSION = Commands.LEVEL_GAMEMASTERS;
    private static final StreamCodec<ByteBuf, List<String>> IDS = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

    private ServerConfigEditing() {}

    /** Server to client: the biomes the server knows, the ones its config disables, and its cave biomes. */
    public record OpenPayload(List<String> known, List<String> disabled, List<String> caves) implements CustomPacketPayload {
        // v2 added caves. A new id, so clients with an older version are told to update instead of failing to decode it
        public static final Type<OpenPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "open_server_config_v2"));
        public static final StreamCodec<ByteBuf, OpenPayload> CODEC = StreamCodec.composite(
                IDS, OpenPayload::known, IDS, OpenPayload::disabled, IDS, OpenPayload::caves, OpenPayload::new);

        @Override
        public Type<OpenPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: the biomes to disable in the server's config. */
    public record SavePayload(List<String> disabled) implements CustomPacketPayload {
        public static final Type<SavePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "save_server_config"));
        public static final StreamCodec<ByteBuf, SavePayload> CODEC = IDS.map(SavePayload::new, SavePayload::disabled);

        @Override
        public Type<SavePayload> type() {
            return TYPE;
        }
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Constants.MOD_ID)
                .requires(Commands.hasPermission(PERMISSION))
                .then(Commands.literal("config").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    // Plain text fallbacks, since a client without the mod has no translations for them
                    if (!Services.PLATFORM.canSend(player, OpenPayload.TYPE)) {
                        context.getSource().sendFailure(Component.translatableWithFallback("biomepicknchoose.server_config.no_mod",
                                "Install Biome Pick'n'Choose on your client to edit the server's biomes"));
                        return 0;
                    }
                    MinecraftServer server = player.level().getServer();
                    List<String> known = BiomeToggles.serverKnownBiomes(server).stream().map(Identifier::toString).toList();
                    List<String> caves = CaveBiomes.fromServer(server).stream().map(Identifier::toString).toList();
                    player.connection.send(new ClientboundCustomPayloadPacket(new OpenPayload(known, BiomeConfig.disabledBiomes(), caves)));
                    return 1;
                })));
    }

    /** Called on the server thread when a client sends the edited config. */
    public static void handleSave(ServerPlayer player, SavePayload payload) {
        // Checked again, since any client with the mod can send this
        if (!PERMISSION.check(player.permissions())) {
            LOGGER.warn("{} tried to change the biome config without permission", player.getGameProfile().name());
            return;
        }
        BiomeConfig.save(BiomeToggles.serverKnownBiomes(player.level().getServer()), payload.disabled());
        LOGGER.info("{} changed the biome config, disabled biomes: {}", player.getGameProfile().name(), payload.disabled());
        player.sendSystemMessage(Component.translatableWithFallback("biomepicknchoose.server_config.saved",
                "Saved the server's biome config. Run /reload to apply it to newly generated chunks"));
    }
}
