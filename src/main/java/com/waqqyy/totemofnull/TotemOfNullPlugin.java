package com.waqqyy.totemofnull;

import com.waqqyy.totemofnull.bot.BotManager;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Totem of Null - a Paper plugin for Minecraft 26.2.
 *
 * <p>Summons 50 fake-player minions that attack anyone not wearing full
 * netherite armor with the Flow trim.</p>
 */
public class TotemOfNullPlugin extends JavaPlugin {

	/** Namespaced key for the custom Totem of Null item. */
	public static final NamespacedKey TOTEM_KEY = new NamespacedKey("totemofnull", "totem_of_null");

	/** The hidden name shown for the totem holder. */
	public static final String HIDDEN_NAME = "________";

	private BotManager botManager;

	/** The bot manager, for other listeners that need holder information. */
	public BotManager getBotManager() {
		return botManager;
	}

	@Override
	public void onEnable() {
		saveDefaultConfig();

		botManager = new BotManager(this);
		HolderState holderState = new HolderState(this);
		TotemListener listener = new TotemListener(this, botManager, holderState);

		getServer().getPluginManager().registerEvents(listener, this);
		getServer().getPluginManager().registerEvents(holderState, this);

		getCommand("ton").setExecutor(new GiveCommand());
		getCommand("toadmin").setExecutor(new AdminCommand());

		registerRecipe();

		getServer().getGlobalRegionScheduler().runAtFixedRate(this, task -> botManager.tick(), 1L, 1L);

		getLogger().info("Totem of Null enabled. Summon " + botCount() + " bots per activation.");
	}

	@Override
	public void onDisable() {
		if (botManager != null) {
			botManager.shutdown();
		}
	}

	/** Number of bots spawned per activation. */
	public int botCount() {
		return Math.max(1, getConfig().getInt("bot-count", 15));
	}

	/** Minutes a bot lives before automatically dying. */
	public int botLifetimeMinutes() {
		return Math.max(1, getConfig().getInt("bot-lifetime-minutes", 15));
	}

	/** Health (hearts) below which a bot eats a golden apple. */
	public double botEatThreshold() {
		return Math.max(0.0, getConfig().getDouble("bot-eat-threshold", 4.0));
	}

	/** Golden apples each bot starts with. */
	public int botGoldenApples() {
		return Math.max(0, getConfig().getInt("bot-golden-apples", 64));
	}

	/** Minimum seconds a bot keeps its shield raised. */
	public int shieldRaiseMinSeconds() {
		return Math.max(1, getConfig().getInt("shield-raise-min-seconds", 1));
	}

	/** Maximum seconds a bot keeps its shield raised. */
	public int shieldRaiseMaxSeconds() {
		return Math.max(shieldRaiseMinSeconds(), getConfig().getInt("shield-raise-max-seconds", 3));
	}

	/** Bot name length. */
	public int botNameLength() {
		return Math.max(4, getConfig().getInt("bot-name-length", 14));
	}

	/** Sharpness level for bot swords. */
	public int swordSharpness() {
		return Math.max(0, getConfig().getInt("sword-sharpness", 5));
	}

	/** Whether the owner receives their kit on activation. */
	public boolean giveKitOnActivate() {
		return getConfig().getBoolean("give-kit-on-activate", true);
	}

	/** Whether the owner receives their kit on respawn. */
	public boolean giveKitOnRespawn() {
		return getConfig().getBoolean("give-kit-on-respawn", true);
	}

	/** The configured activation key (Minecraft key name, e.g. {@code U}). */
	public String keybind() {
		return getConfig().getString("keybind", "U").toUpperCase(Locale.ROOT);
	}

	/** True if the plugin should hide the holder's join/quit/advancement messages. */
	public boolean hideMessages() {
		return getConfig().getBoolean("hide-messages", true);
	}

	/**
	 * Creates the Totem of Null item.
	 */
	public static ItemStack totemItem() {
		ItemStack stack = new ItemStack(Material.TOTEM_OF_UNDYING);
		var meta = stack.getItemMeta();
		meta.setMaxStackSize(1);
		meta.setDisplayName(ChatColor.DARK_PURPLE + "Totem of Null");
		meta.setLore(java.util.List.of(
				ChatColor.GRAY + "A single totem of terrible power.",
				ChatColor.GRAY + "Press " + ChatColor.LIGHT_PURPLE + "U" + ChatColor.GRAY + " while holding to summon the swarm.",
				ChatColor.DARK_GRAY + "They will not stop until all who lack the Flow are slain."
		));
		stack.setItemMeta(meta);
		return stack;
	}

	/** Shaped recipe for the Totem of Null. */
	public void registerRecipe() {
		ShapedRecipe recipe = new ShapedRecipe(TOTEM_KEY, totemItem());
		recipe.shape("TNT", "NWN", "TNT");
		recipe.setIngredient('T', Material.TOTEM_OF_UNDYING);
		recipe.setIngredient('N', Material.NETHERITE_BLOCK);
		recipe.setIngredient('W', Material.NETHER_STAR);
		getServer().addRecipe(recipe);
	}

	private class GiveCommand implements CommandExecutor {
		@Override
		public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
		                        @NotNull String label, @NotNull String[] args) {
			if (!(sender instanceof Player player)) {
				sender.sendMessage(ChatColor.RED + "Only players can use this command.");
				return true;
			}
			player.getInventory().addItem(totemItem());
			player.sendMessage(ChatColor.DARK_PURPLE + "You received the Totem of Null.");
			return true;
		}
	}

	private class AdminCommand implements CommandExecutor {
		@Override
		public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
		                        @NotNull String label, @NotNull String[] args) {
			if (args.length == 0) {
				sender.sendMessage(ChatColor.RED + "Usage: /toadmin <status|clear|reload>");
				return true;
			}
			switch (args[0].toLowerCase(Locale.ROOT)) {
				case "status" -> {
					sender.sendMessage(ChatColor.GOLD + "Active bots: " + botManager.activeCount());
					sender.sendMessage(ChatColor.GOLD + "Holder: " + botManager.getHolderName()
							.map(n -> ChatColor.GREEN + n).orElse(ChatColor.GRAY + "none"));
				}
				case "clear" -> {
					botManager.clearAll();
					sender.sendMessage(ChatColor.GREEN + "Cleared all bots.");
				}
				case "reload" -> {
					reloadConfig();
					sender.sendMessage(ChatColor.GREEN + "Configuration reloaded.");
				}
				default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + args[0]);
			}
			return true;
		}
	}
}
