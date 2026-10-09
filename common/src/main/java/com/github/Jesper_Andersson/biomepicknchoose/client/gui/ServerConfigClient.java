package com.github.Jesper_Andersson.biomepicknchoose.client.gui;

import com.github.Jesper_Andersson.biomepicknchoose.common.ServerConfigEditing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/** The client side of {@link ServerConfigEditing}. Only loaded on clients. */
public final class ServerConfigClient {
    private ServerConfigClient() {}

    /** Called on the client thread when the server sends its config. */
    public static void open(ServerConfigEditing.OpenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(BiomeToggleScreen.forServer(minecraft.screen, payload.catalog(), payload.disabled(), disabled -> {
            ClientPacketListener connection = minecraft.getConnection();
            if (connection != null) connection.send(new ServerboundCustomPayloadPacket(new ServerConfigEditing.SavePayload(disabled)));
        }));
    }
}
