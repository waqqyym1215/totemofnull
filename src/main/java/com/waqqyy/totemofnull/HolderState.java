package com.waqqyy.totemofnull;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.waqqyy.totemofnull.bot.NullSkin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Keeps the holder's hidden identity (black skin, {@code ________} name) in sync with the
 * Totem of Null being in their inventory or offhand, and keeps the totem from being lost or
 * moved to other containers.
 *
 * <p>The totem is the only source of the hidden identity: if it is gone, the holder's name and
 * skin are restored.</p>
 */
public class HolderState implements Listener {

	private final TotemOfNullPlugin plugin;

	/** Players currently hidden by this plugin, with the skin property they had before (if any). */
	private final java.util.Map<java.util.UUID, java.util.Optional<ProfileProperty>> hidden = new java.util.HashMap<>();

	public HolderState(TotemOfNullPlugin plugin) {
		this.plugin = plugin;
	}

	/** True if the given stack is the Totem of Null. */
	public static boolean isTotem(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta()) {
			return false;
		}
		var meta = stack.getItemMeta();
		String name = meta.getDisplayName();
		return name != null && name.equals(ChatColor.DARK_PURPLE + "Totem of Null");
	}

	/** True if the player holds the totem in their main hand, offhand, or anywhere in the inventory. */
	public boolean hasTotem(Player player) {
		PlayerInventory inv = player.getInventory();
		if (isTotem(inv.getItemInMainHand()) || isTotem(inv.getItemInOffHand())) {
			return true;
		}
		for (ItemStack stack : inv.getContents()) {
			if (isTotem(stack)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Applies the hidden identity: the {@code ________} name and the fully black skin.
	 *
	 * <p>Only players recorded in {@link #hidden} are ever touched again, so regular players
	 * keep their own skin and name.</p>
	 */
	public void applyHidden(Player player) {
		if (hidden.containsKey(player.getUniqueId())) {
			return;
		}
		PlayerProfile profile = player.getPlayerProfile();
		ProfileProperty original = null;
		for (ProfileProperty property : profile.getProperties()) {
			if (property.getName().equals("textures")) {
				original = property;
			}
		}
		hidden.put(player.getUniqueId(), java.util.Optional.ofNullable(original));

		profile.setProperty(NullSkin.blackProfileProperty());
		showProfile(player, profile, true);
		player.setPlayerListName(TotemOfNullPlugin.HIDDEN_NAME);
		player.setDisplayName(TotemOfNullPlugin.HIDDEN_NAME);
	}

	/** Restores the real name and skin of a player this plugin hid. Others are left alone. */
	public void restore(Player player) {
		var original = hidden.remove(player.getUniqueId());
		if (original == null) {
			return;
		}
		player.setPlayerListName(player.getName());
		player.setDisplayName(player.getName());
		PlayerProfile profile = player.getPlayerProfile();
		if (original.isPresent()) {
			profile.setProperty(original.get());
		} else {
			profile.removeProperty("textures");
		}
		showProfile(player, profile, false);
	}

	/** True if this plugin is currently hiding the player's identity. */
	public boolean isHidden(Player player) {
		return hidden.containsKey(player.getUniqueId());
	}

	/**
	 * Swaps the player's profile and re-shows them to everyone who can see them.
	 *
	 * <p>{@link Player#setPlayerProfile} is deliberately not used: it also sends the player a
	 * respawn packet to refresh their own view, which flashes the world-loading screen.</p>
	 *
	 * @param hideName show the name tag above the player's head as {@code ________}
	 */
	private void showProfile(Player player, PlayerProfile profile, boolean hideName) {
		var handle = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle();
		handle.gameProfile = com.destroystokyo.paper.profile.CraftPlayerProfile.asAuthlibCopy(profile);
		for (Player viewer : Bukkit.getOnlinePlayers()) {
			reshow(player, viewer, hideName);
		}
	}

	/**
	 * Re-sends {@code player} to one viewer.
	 *
	 * <p>The name tag above a player's head is the profile name in the player-info packet. The
	 * server-side profile keeps the real name (commands, lookups and saves depend on it), so
	 * the hidden name is swapped in only while that packet is built.</p>
	 */
	private void reshow(Player player, Player viewer, boolean hideName) {
		if (viewer.equals(player) || plugin.getBotManager().isBot(viewer) || !viewer.canSee(player)) {
			return;
		}
		var handle = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle();
		var real = handle.gameProfile;
		viewer.hidePlayer(plugin, player);
		try {
			if (hideName) {
				handle.gameProfile = new com.mojang.authlib.GameProfile(
						real.id(), TotemOfNullPlugin.HIDDEN_NAME, real.properties());
			}
			viewer.showPlayer(plugin, player);
		} finally {
			handle.gameProfile = real;
		}
	}

	/** Re-applies or restores the identity, whichever the current inventory state requires. */
	public void sync(Player player) {
		if (hasTotem(player)) {
			applyHidden(player);
		} else {
			restore(player);
		}
	}

	// ---------------------------------------------------------------------------
	// Identity sync
	// ---------------------------------------------------------------------------

	@EventHandler(priority = EventPriority.MONITOR)
	public void onHeld(PlayerItemHeldEvent event) {
		sync(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(PlayerJoinEvent event) {
		// A player who joins is sent everyone's real profile name; fix up the hidden ones.
		Player joined = event.getPlayer();
		if (!hidden.isEmpty() && !plugin.getBotManager().isBot(joined)) {
			plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
				if (!joined.isOnline()) {
					return;
				}
				for (java.util.UUID id : hidden.keySet()) {
					Player holder = Bukkit.getPlayer(id);
					if (holder != null) {
						reshow(holder, joined, true);
					}
				}
			}, 1L);
		}
		if (hasTotem(event.getPlayer())) {
			if (plugin.hideMessages()) {
				event.joinMessage(null);
			}
			applyHidden(event.getPlayer());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		if (hasTotem(event.getPlayer()) && plugin.hideMessages()) {
			event.quitMessage(null);
		}
		// The profile dies with the session; just forget the saved state.
		hidden.remove(event.getPlayer().getUniqueId());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRespawn(PlayerRespawnEvent event) {
		// The inventory is reset on respawn; re-check and re-apply on the next tick.
		plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
			Player player = event.getPlayer();
			if (!player.isOnline()) {
				return;
			}
			sync(player);
		}, 1L);
	}

	// ---------------------------------------------------------------------------
	// Totem persistence
	// ---------------------------------------------------------------------------

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onInventoryClick(InventoryClickEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!hasTotem(player)) {
			return;
		}
		Inventory clicked = event.getClickedInventory();
		if (clicked != null && clicked.equals(player.getInventory())) {
			return;
		}
		ItemStack current = event.getCurrentItem();
		if (current != null && isTotem(current)) {
			event.setCancelled(true);
			return;
		}
		ItemStack cursor = event.getCursor();
		if (cursor != null && isTotem(cursor)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onInventoryDrag(InventoryDragEvent event) {
		if (!(event.getWhoClicked() instanceof Player player)) {
			return;
		}
		if (!hasTotem(player)) {
			return;
		}
		ItemStack cursor = event.getOldCursor();
		if (cursor != null && isTotem(cursor)) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDrop(PlayerDropItemEvent event) {
		if (isTotem(event.getItemDrop().getItemStack())) {
			event.setCancelled(true);
			sync(event.getPlayer());
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onAttemptPickup(PlayerAttemptPickupItemEvent event) {
		if (isTotem(event.getItem().getItemStack()) && !isOwner(event.getPlayer())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPickup(EntityPickupItemEvent event) {
		if (isTotem(event.getItem().getItemStack())
				&& !(event.getEntity() instanceof Player player && isOwner(player))) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onConsume(PlayerItemConsumeEvent event) {
		if (isTotem(event.getItem())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		if (!hasTotem(player)) {
			return;
		}
		event.getDrops().removeIf(HolderState::isTotem);
		if (plugin.hideMessages()) {
			event.deathMessage(null);
		}
	}

	private boolean isOwner(Player player) {
		return plugin.getBotManager() != null && plugin.getBotManager().getHolderName()
				.map(name -> name.equals(player.getName())).orElse(false);
	}

	// ---------------------------------------------------------------------------
	// Advancement hiding
	// ---------------------------------------------------------------------------

	@EventHandler(priority = EventPriority.MONITOR)
	public void onAdvancement(PlayerAdvancementDoneEvent event) {
		if (!hasTotem(event.getPlayer()) || !plugin.hideMessages()) {
			return;
		}
		event.message(null);
	}
}
