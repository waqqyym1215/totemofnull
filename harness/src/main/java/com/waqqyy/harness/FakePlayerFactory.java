package com.waqqyy.harness;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.craftbukkit.CraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates fake {@link ServerPlayer} instances using an in-memory Netty channel.
 *
 * <p>This is the mechanism we will use for the Totem of Null bots. The harness exists to prove
 * it works on a real server before we build the plugin around it.</p>
 */
public final class FakePlayerFactory {

	private FakePlayerFactory() {
	}

	public record Result(ServerPlayer player, Connection connection, ServerGamePacketListenerImpl listener,
	                     Throwable error) {
	}

	/**
	 * Creates a fake player that is a real entity in the world but is never added to the
	 * tab list (no player-info packet is sent).
	 */
	public static Result create(MinecraftServer server, ServerLevel level, String name) {
		try {
			var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
			var connection = new Connection(PacketFlow.SERVERBOUND);

			// An embedded channel makes Connection.isConnected() return true, which is required
			// for the listener to accept packets and for the server to treat it as a live player.
			var channel = new io.netty.channel.embedded.EmbeddedChannel();
			channel.pipeline().addLast("packet_handler", connection);
			connection.channel = channel;
			connection.setListenerForServerboundHandshake(
					new net.minecraft.server.network.MemoryServerHandshakePacketListenerImpl(server, connection));

			var player = new ServerPlayer(server, level, profile,
					net.minecraft.server.level.ClientInformation.createDefault());
			var cookie = CommonListenerCookie.createInitial(profile, false);
			var listener = new ServerGamePacketListenerImpl(server, connection, player, cookie);
			player.connection = listener;

			// Unlist the bot on every existing player BEFORE placeNewPlayer(). placeNewPlayer()
			// checks each viewer's CraftPlayer.isListed(bot) at broadcast time, so unlisting
			// first suppresses the initial player-info packet entirely rather than having to
			// retract it afterwards.
			//
			// Both directions are needed: when bots are spawned one at a time, a bot created
			// earlier must also be hidden from bots created later.
			var craftPlayer = player.getBukkitEntity();
			for (var online : server.getPlayerList().getPlayers()) {
				if (online != player) {
					online.getBukkitEntity().unlistPlayer(craftPlayer);
					craftPlayer.unlistPlayer(online.getBukkitEntity());
				}
			}

			// Register with the player list so the entity is real (tickable, damageable, ...).
			// NOTE: placeNewPlayer() constructs its OWN ServerGamePacketListenerImpl and assigns
			// it to player.connection, overwriting the listener we built above. Its constructor
			// starts a 60-tick client-load timer (restartClientLoadTimerAfterRespawn), so we must
			// use player.connection (the live listener) for everything below.
			server.getPlayerList().placeNewPlayer(connection, player, cookie);
			var liveListener = player.connection;

			// A real client sends ServerboundPlayerLoadedPacket once it finishes loading the
			// world. Without this, ServerPlayer.isInvulnerableTo() always returns true because
			// ServerGamePacketListenerImpl.hasClientLoaded() is false, so the bot cannot be
			// damaged or killed.
			liveListener.handleAcceptPlayerLoad(null);

			// Fake players are constructed with creative-mode abilities AND Entity.invulnerable
			// set. ServerPlayer.isInvulnerableTo() walks Avatar -> LivingEntity ->
			// Entity.isInvulnerableToBase(), which checks Entity.invulnerable. setGameMode()
			// does not clear either flag, so hurtServer() rejects all damage and the bot can
			// never die. Clear both explicitly.
			player.getAbilities().invulnerable = false;
			player.setInvulnerable(false);
			player.onUpdateAbilities();

			// Put the bot in survival so it takes damage, starves, and dies like a real player.
			player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

			return new Result(player, connection, listener, null);
		} catch (Throwable t) {
			return new Result(null, null, null, t);
		}
	}

	/** Removes a fake player cleanly. */
	public static void remove(ServerPlayer player) {
		if (player == null) {
			return;
		}
		try {
			player.connection.disconnect(net.kyori.adventure.text.Component.empty());
		} catch (Throwable ignored) {
			// best effort
		}
	}

	public static MinecraftServer nmsServer(org.bukkit.Server server) {
		return ((CraftServer) server).getServer();
	}

	public static ServerLevel nmsLevel(org.bukkit.World world) {
		return ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
	}
}
