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
	 * <p>The skin is applied through {@link Player#setPlayerProfile}, which re-sends the player
	 * info packet to every viewer, so the change is visible immediately.</p>
	 */
	public void applyHidden(Player player) {
		PlayerProfile profile = player.getPlayerProfile();
		boolean alreadyBlack = NullSkin.isBlackSkin(
				((com.destroystokyo.paper.profile.CraftPlayerProfile) profile).getGameProfile());
		if (alreadyBlack && player.getPlayerListName().equals(TotemOfNullPlugin.HIDDEN_NAME)) {
			return;
		}
		profile.setProperty(new ProfileProperty("textures", NullSkin.blackTextureProperty()));
		player.setPlayerProfile(profile);
		player.setPlayerListName(TotemOfNullPlugin.HIDDEN_NAME);
		player.setDisplayName(TotemOfNullPlugin.HIDDEN_NAME);
	}

	/** Restores the player's real name and skin. */
	public void restore(Player player) {
		if (player.getPlayerListName().equals(TotemOfNullPlugin.HIDDEN_NAME)) {
			player.setPlayerListName(player.getName());
			player.setDisplayName(player.getName());
		}
		PlayerProfile profile = player.getPlayerProfile();
		profile.removeProperty("textures");
		player.setPlayerProfile(profile);
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
		if (hasTotem(event.getPlayer())) {
			event.joinMessage(net.kyori.adventure.text.Component.empty());
			applyHidden(event.getPlayer());
		} else {
			restore(event.getPlayer());
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(PlayerQuitEvent event) {
		if (hasTotem(event.getPlayer())) {
			event.quitMessage(net.kyori.adventure.text.Component.empty());
		}
		restore(event.getPlayer());
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
		event.deathMessage(net.kyori.adventure.text.Component.empty());
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
		if (!hasTotem(event.getPlayer())) {
			return;
		}
		event.message(net.kyori.adventure.text.Component.empty());
	}
}
