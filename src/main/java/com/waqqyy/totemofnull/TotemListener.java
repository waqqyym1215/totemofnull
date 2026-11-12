package com.waqqyy.totemofnull;

import com.waqqyy.totemofnull.bot.BotManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/**
 * Handles totem activation, owner anonymity and kit grants.
 */
public class TotemListener implements Listener {

	private final TotemOfNullPlugin plugin;
	private final BotManager botManager;
	private final HolderState holderState;

	public TotemListener(TotemOfNullPlugin plugin, BotManager botManager, HolderState holderState) {
		this.plugin = plugin;
		this.botManager = botManager;
		this.holderState = holderState;
	}

	/** True if the given stack is the Totem of Null. */
	public static boolean isTotem(ItemStack stack) {
		return HolderState.isTotem(stack);
	}

	/**
	 * Right-click only. There is no keybind: the totem is used by right-clicking it in the
	 * main hand, and the keybind config option is ignored.
	 */
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		ItemStack stack = event.getItem();
		if (!isTotem(stack)) {
			return;
		}
		Player player = event.getPlayer();

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
		// Summon the swarm.
		botManager.summon(player);
		// The hidden identity comes from the totem itself; make sure it is applied.
		holderState.applyHidden(player);
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

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		if (!plugin.giveKitOnRespawn()) {
			return;
		}
		boolean hasTotem = holderState.hasTotem(player);
		if (!hasTotem) {
			return;
		}
		// Restore kit and anonymity on the next tick (inventory is reset on respawn).
		plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
			giveKit(player);
			holderState.sync(player);
		}, 1L);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onHeld(PlayerItemHeldEvent event) {
		holderState.sync(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
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
		return holderState.hasTotem(player);
	}

	/** Holder name, if any (for admin status). */
	public Optional<String> holderName() {
		return botManager.getHolderName();
	}
}
