package com.waqqyy.totemofnull;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Optional;

/**
 * Handles totem activation, owner anonymity and kit grants.
 */
public class TotemListener implements Listener {

	private final TotemOfNullPlugin plugin;
	private final com.waqqyy.totemofnull.bot.BotManager botManager;

	public TotemListener(TotemOfNullPlugin plugin, com.waqqyy.totemofnull.bot.BotManager botManager) {
		this.plugin = plugin;
		this.botManager = botManager;
	}

	/** True if the given stack is the Totem of Null. */
	public static boolean isTotem(ItemStack stack) {
		if (stack == null || !stack.hasItemMeta()) {
			return false;
		}
		ItemMeta meta = stack.getItemMeta();
		String name = meta.getDisplayName();
		return name != null && name.equals(ChatColor.DARK_PURPLE + "Totem of Null");
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		ItemStack stack = event.getItem();
		if (!isTotem(stack)) {
			return;
		}
		Player player = event.getPlayer();

		// The totem only works via the keybind; right-click is the fallback trigger.
		// A real keybind is client-side; see README "Known Limitations".
		if (botManager.isActive(player)) {
			player.sendMessage(ChatColor.DARK_PURPLE + "The swarm is already hunting. It cannot be summoned again.");
			return;
		}
		activate(player);
	}

	private void activate(Player player) {
		// Give the owner their kit.
		if (plugin.giveKitOnActivate()) {
			giveKit(player);
		}
		// Hide the owner's name.
		player.setPlayerListName(TotemOfNullPlugin.HIDDEN_NAME);
		player.setDisplayName(TotemOfNullPlugin.HIDDEN_NAME);
		// Summon the swarm.
		botManager.summon(player);
		player.sendMessage(ChatColor.DARK_PURPLE + "The swarm awakens. " + plugin.botCount()
				+ " minions rise to hunt the untrimmed.");
	}

	private void giveKit(Player player) {
		var kit = ArmorKit.armorSet();
		player.getInventory().setHelmet(kit[0]);
		player.getInventory().setChestplate(kit[1]);
		player.getInventory().setLeggings(kit[2]);
		player.getInventory().setBoots(kit[3]);
		player.getInventory().setItemInMainHand(ArmorKit.sword(plugin.swordSharpness()));
	}

	@EventHandler
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		if (!plugin.giveKitOnRespawn()) {
			return;
		}
		boolean hasTotem = hasTotem(player);
		if (!hasTotem) {
			return;
		}
		// Restore kit and anonymity on the next tick (inventory is reset on respawn).
		plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
			giveKit(player);
			player.setPlayerListName(TotemOfNullPlugin.HIDDEN_NAME);
			player.setDisplayName(TotemOfNullPlugin.HIDDEN_NAME);
		}, 1L);
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		botManager.deactivate(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		if (!botManager.isBot(player)) {
			return;
		}
		// Bots drop everything except armor, sword and shield.
		event.getDrops().removeIf(stack -> {
			Material type = stack.getType();
			return type == Material.NETHERITE_HELMET
					|| type == Material.NETHERITE_CHESTPLATE
					|| type == Material.NETHERITE_LEGGINGS
					|| type == Material.NETHERITE_BOOTS
					|| type == Material.NETHERITE_SWORD
					|| type == Material.SHIELD;
		});
	}

	private boolean hasTotem(Player player) {
		for (ItemStack stack : player.getInventory().getContents()) {
			if (isTotem(stack)) {
				return true;
			}
		}
		return false;
	}

	/** Holder name, if any (for admin status). */
	public Optional<String> holderName() {
		return botManager.getHolderName();
	}
}
