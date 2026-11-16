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
import org.bukkit.event.player.PlayerVelocityEvent;
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

	/**
	 * Gives the kit without destroying anything: an armor piece is only swapped in when it is
	 * stronger than what is worn (the old piece goes back to the inventory), and the sword is
	 * added to the inventory rather than replacing the held item, which is the totem.
	 */
	private void giveKit(Player player) {
		var inv = player.getInventory();
		ItemStack[] kit = ArmorKit.armorSet();
		ItemStack[] worn = { inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots() };
		for (int i = 0; i < kit.length; i++) {
			if (!ArmorKit.isWeaker(worn[i], kit[i])) {
				continue;
			}
			switch (i) {
				case 0 -> inv.setHelmet(kit[i]);
				case 1 -> inv.setChestplate(kit[i]);
				case 2 -> inv.setLeggings(kit[i]);
				default -> inv.setBoots(kit[i]);
			}
			if (worn[i] != null && !worn[i].getType().isAir()) {
				giveOrDrop(player, worn[i]);
			}
		}

		int sharpness = plugin.swordSharpness();
		boolean hasSword = false;
		for (ItemStack stack : inv.getContents()) {
			if (stack != null && stack.getType() == Material.NETHERITE_SWORD
					&& stack.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS) >= sharpness) {
				hasSword = true;
				break;
			}
		}
		if (!hasSword) {
			giveOrDrop(player, ArmorKit.sword(sharpness));
		}
	}

	private void giveOrDrop(Player player, ItemStack stack) {
		for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
			player.getWorld().dropItemNaturally(player.getLocation(), leftover);
		}
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

	/**
	 * When a player is hit, the server sends the knockback to that player's client and then
	 * restores the old velocity, expecting the client to move itself. A bot has no client, so
	 * cancel the event: that skips both the packet and the restore, leaving the knockback on
	 * the server where BotManager's doTick() applies it.
	 */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBotVelocity(PlayerVelocityEvent event) {
		if (botManager.isBot(event.getPlayer())) {
			event.setCancelled(true);
		}
	}

	/** A real player swings at a bot: the bot raises its shield. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerAttacksBot(io.papermc.paper.event.player.PrePlayerAttackEntityEvent event) {
		if (event.getAttacked() instanceof Player victim && botManager.isBot(victim)
				&& !botManager.isBot(event.getPlayer())) {
			botManager.onAttackedByPlayer(victim);
		}
	}

	/** Same for a real player's projectiles, which do not go through the melee event. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onProjectileHitsBot(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
		if (!(event.getEntity() instanceof Player victim) || !botManager.isBot(victim)) {
			return;
		}
		if (event.getDamager() instanceof org.bukkit.entity.Projectile projectile
				&& projectile.getShooter() instanceof Player shooter && !botManager.isBot(shooter)) {
			botManager.onAttackedByPlayer(victim);
		}
	}

	// Bots are never announced: no join, quit, death or advancement messages.

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onBotJoin(org.bukkit.event.player.PlayerJoinEvent event) {
		if (botManager.isBot(event.getPlayer())) {
			event.joinMessage(null);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onBotQuit(PlayerQuitEvent event) {
		if (botManager.isBot(event.getPlayer())) {
			event.quitMessage(null);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onBotAdvancement(org.bukkit.event.player.PlayerAdvancementDoneEvent event) {
		if (botManager.isBot(event.getPlayer())) {
			event.message(null);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		if (!botManager.isBot(player)) {
			return;
		}
		event.deathMessage(null);
		// Bots drop everything except armor, sword, pickaxe and shield.
		event.getDrops().removeIf(stack -> {
			Material type = stack.getType();
			return type == Material.NETHERITE_HELMET
					|| type == Material.NETHERITE_CHESTPLATE
					|| type == Material.NETHERITE_LEGGINGS
					|| type == Material.NETHERITE_BOOTS
					|| type == Material.NETHERITE_SWORD
					|| type == Material.NETHERITE_PICKAXE
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
