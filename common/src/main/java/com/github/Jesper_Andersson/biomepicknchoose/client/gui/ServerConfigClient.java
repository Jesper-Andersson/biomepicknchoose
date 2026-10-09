package com.github.Jesper_Andersson.biomepicknchoose.client.gui;

import com.github.Jesper_Andersson.biomepicknchoose.common.ServerConfigEditing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** The client side of {@link ServerConfigEditing}. Only loaded on clients. */
public final class ServerConfigClient {
    private ServerConfigClient() {}

    /** Called on the client thread when the server sends its config. */
    public static void open(ServerConfigEditing.OpenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        Set<Identifier> known = parse(payload.known());
        Set<Identifier> caves = parse(payload.caves());
        minecraft.setScreen(BiomeToggleScreen.forServer(minecraft.screen, known, payload.disabled(), caves, disabled -> {
            ClientPacketListener connection = minecraft.getConnection();
            if (connection != null) connection.send(new ServerboundCustomPayloadPacket(new ServerConfigEditing.SavePayload(disabled)));
        }));
    }

    private static Set<Identifier> parse(List<String> ids) {
        return ids.stream()
                .map(Identifier::tryParse)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
