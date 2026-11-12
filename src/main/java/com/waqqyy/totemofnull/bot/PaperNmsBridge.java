package com.waqqyy.totemofnull.bot;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.MemoryServerHandshakePacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.UUID;

/**
 * Creates fake {@link ServerPlayer} instances using an in-memory Netty channel.
 *
 * <p>This is the proven mechanism from the verification harness. A bot is a real entity in the world
 * (tickable, damageable, can hold items) but never appears in the tab list.</p>
 */
public final class PaperNmsBridge {

	private PaperNmsBridge() {
	}

	public static MinecraftServer nmsServer(org.bukkit.Server server) {
		return ((CraftServer) server).getServer();
	}

	public static ServerLevel nmsLevel(org.bukkit.World world) {
		return ((CraftWorld) world).getHandle();
	}

	/**
	 * Creates a fake player that is a real entity in the world but is never added to the
	 * tab list (no player-info packet is sent).
	 */
	public static FakePlayer create(MinecraftServer server, ServerLevel level, String name) {
		try {
			GameProfile profile = new GameProfile(UUID.randomUUID(), name);
			Connection connection = new Connection(PacketFlow.SERVERBOUND);

			// An embedded channel makes Connection.isConnected() return true, which is required
			// for the listener to accept packets and for the server to treat it as a live player.
			var channel = new io.netty.channel.embedded.EmbeddedChannel();
			channel.pipeline().addLast("packet_handler", connection);
			connection.channel = channel;
			connection.setListenerForServerboundHandshake(
					new MemoryServerHandshakePacketListenerImpl(server, connection));

			var player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
			var cookie = CommonListenerCookie.createInitial(profile, false);
			var listener = new ServerGamePacketListenerImpl(server, connection, player, cookie);
			player.connection = listener;

			// Unlist the bot on every existing player BEFORE placeNewPlayer(). placeNewPlayer()
			// checks each viewer's CraftPlayer.isListed(bot) at broadcast time, so unlisting
			// first suppresses the initial player-info packet entirely rather than having to
			// retract it afterwards. Both directions are needed so bots created later do not
			// see bots created earlier.
			var craftPlayer = player.getBukkitEntity();
			for (var online : server.getPlayerList().getPlayers()) {
				if (online != player) {
					online.getBukkitEntity().unlistPlayer(craftPlayer);
					craftPlayer.unlistPlayer(online.getBukkitEntity());
				}
			}

			// Register with the player list so the entity is real. This REPLACES player.connection
			// with a listener it constructs itself, so re-read it afterwards.
			server.getPlayerList().placeNewPlayer(connection, player, cookie);
			var liveListener = player.connection;

			// A real client sends ServerboundPlayerLoadedPacket once it finishes loading the
			// world. Without this, ServerPlayer.isInvulnerableTo() always returns true because
			// hasClientLoaded() is false, so the bot can never be damaged or killed.
			liveListener.handleAcceptPlayerLoad(null);

			// Fake players are constructed with creative-mode abilities AND Entity.invulnerable
			// set. ServerPlayer.isInvulnerableTo() checks Entity.invulnerable and
			// Player.hurtServer() checks Abilities.invulnerable; setGameMode() clears neither,
			// so clear both explicitly.
			player.getAbilities().invulnerable = false;
			player.setInvulnerable(false);
			player.onUpdateAbilities();
			player.setGameMode(GameType.SURVIVAL);

			return new FakePlayer(player, connection, liveListener, null);
		} catch (Throwable t) {
			return new FakePlayer(null, null, null, t);
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
}
