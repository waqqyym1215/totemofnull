package com.waqqyy.totemofnull.bot;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * Result of creating a fake player, plus the live listener.
 *
 * <p>{@code listener} is the one {@code player.connection} points at. {@code PlayerList.placeNewPlayer}
 * constructs its own {@code ServerGamePacketListenerImpl} and assigns it to the player, overwriting the
 * one built locally, so callers must use this reference for anything that touches the listener
 * (e.g. {@code handleAcceptPlayerLoad}).</p>
 */
public record FakePlayer(ServerPlayer player, Connection connection,
                         ServerGamePacketListenerImpl listener, Throwable error) {

	public boolean ok() {
		return error == null && player != null;
	}
}
