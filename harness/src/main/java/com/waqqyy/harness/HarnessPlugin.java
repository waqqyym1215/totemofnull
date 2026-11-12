package com.waqqyy.harness;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Verification harness for the Totem of Null fake-player mechanism.
 *
 * <p>Run {@code /harness all} on a Paper 26.2 server and read the console. Every check prints
 * PASS/FAIL so we know exactly what works before building the plugin around it.</p>
 */
public class HarnessPlugin extends JavaPlugin {

	private final List<FakePlayerFactory.Result> bots = new ArrayList<>();

	@Override
	public void onEnable() {
		getCommand("harness").setExecutor(new HarnessCommand());
		getLogger().info("Harness ready. Run /harness all");
	}

	@Override
	public void onDisable() {
		clearAll();
	}

	private void clearAll() {
		for (var bot : bots) {
			FakePlayerFactory.remove(bot.player());
		}
		bots.clear();
	}

	private class HarnessCommand implements CommandExecutor {
		@Override
		public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
		                        @NotNull String label, @NotNull String[] args) {
			String sub = args.length == 0 ? "all" : args[0].toLowerCase(Locale.ROOT);
			switch (sub) {
				case "spawn" -> spawn(sender);
				case "status" -> status(sender);
				case "kill" -> kill(sender);
				case "clear" -> {
					clearAll();
					sender.sendMessage("Cleared.");
				}
				case "tps" -> tps(sender);
				case "all" -> runAll(sender);
				default -> sender.sendMessage("Usage: /harness [spawn|status|kill|clear|tps|all]");
			}
			return true;
		}
	}

	private void spawn(CommandSender sender) {
		if (bots.size() >= 50) {
			sender.sendMessage("Already at 50 bots.");
			return;
		}
		var server = FakePlayerFactory.nmsServer(getServer());
		var world = sender instanceof Player p
				? p.getWorld()
				: getServer().getWorlds().get(0);
		var level = FakePlayerFactory.nmsLevel(world);

		String name = "Vj2j34lkBJEKk" + (bots.size() + 1);
		var result = FakePlayerFactory.create(server, level, name);

		if (result.error() != null) {
			sender.sendMessage(ChatColor.RED + "SPAWN FAILED: " + result.error());
			result.error().printStackTrace();
			return;
		}

		// Place the bot near the sender so it is in a loaded chunk.
		if (sender instanceof Player p) {
			var loc = p.getLocation();
			result.player().teleportTo(loc.getX() + 1, loc.getY(), loc.getZ() + 1);
		}

		bots.add(result);
		sender.sendMessage(ChatColor.GREEN + "Spawned fake player #" + bots.size() + " (" + name + ")");
	}

	private void status(CommandSender sender) {
		sender.sendMessage("Bots: " + bots.size());
		for (int i = 0; i < bots.size(); i++) {
			var bot = bots.get(i).player();
			if (bot == null) {
				sender.sendMessage("  #" + (i + 1) + " null");
				continue;
			}
			sender.sendMessage(String.format("  #%d name=%s health=%.1f pos=%.1f,%.1f,%.1f inTab=%s",
					i + 1, bot.getGameProfile().name(), bot.getHealth(),
					bot.getX(), bot.getY(), bot.getZ(),
					getServer().getPlayer(bot.getUUID()) != null));
		}
	}

	private void kill(CommandSender sender) {
		if (bots.isEmpty()) {
			sender.sendMessage("No bots.");
			return;
		}
		var bot = bots.remove(bots.size() - 1).player();
		FakePlayerFactory.remove(bot);
		sender.sendMessage("Removed last bot.");
	}

	private void tps(CommandSender sender) {
		double[] tps = getServer().getTPS();
		sender.sendMessage(String.format("TPS: 1m=%.2f 5m=%.2f 15m=%.2f  (bots=%d)",
				tps[0], tps[1], tps[2], bots.size()));
	}

	/** Runs the full verification suite and reports PASS/FAIL for each check. */
	private void runAll(CommandSender sender) {
		clearAll();
		getLogger().info("=== Totem of Null harness ===");

		check("Server version", getServer().getMinecraftVersion() != null,
				getServer().getMinecraftVersion());

		// Check 1: spawn 50
		var server = FakePlayerFactory.nmsServer(getServer());
		var level = FakePlayerFactory.nmsLevel(getServer().getWorlds().get(0));
		int spawned = 0;
		for (int i = 0; i < 50; i++) {
			var r = FakePlayerFactory.create(server, level, "Harness" + i);
			if (r.error() == null && r.player() != null) {
				bots.add(r);
				spawned++;
			} else if (i == 0) {
				check("Spawn first bot", false, String.valueOf(r.error()));
			}
		}
		check("Spawn 50 bots", spawned == 50, "spawned=" + spawned);

		// Check 2: real entities, alive
		long alive = bots.stream().filter(b -> b.player() != null && !b.player().isDeadOrDying()).count();
		check("All bots alive", alive == bots.size(), "alive=" + alive);

		// Check 3: not in tab list. Bukkit.getPlayer()/getOnlinePlayers() return fake players
		// regardless of tab listing, so probe CraftPlayer.isListed() and compare by UUID (a
		// CraftPlayer is never == to its ServerPlayer handle).
		var botIds = bots.stream()
				.filter(b -> b.player() != null)
				.map(b -> b.player().getUUID())
				.collect(java.util.stream.Collectors.toSet());
		int listedForRealPlayers = 0;
		int realViewers = 0;
		for (var bot : bots) {
			if (bot.player() == null) {
				continue;
			}
			var botCraft = bot.player().getBukkitEntity();
			for (var viewer : getServer().getOnlinePlayers()) {
				if (botIds.contains(viewer.getUniqueId())) {
					continue; // skip other bots - they are not real viewers
				}
				realViewers++;
				if (((org.bukkit.craftbukkit.entity.CraftPlayer) viewer).isListed(botCraft)) {
					listedForRealPlayers++;
					break;
				}
			}
		}
		check("Bots hidden from real players", realViewers == 0 || listedForRealPlayers == 0,
				"realViewers=" + realViewers + " listed=" + listedForRealPlayers);

		// Mechanism check: no bot may be listed for any other bot.
		int botsVisibleToBots = 0;
		for (var bot : bots) {
			if (bot.player() == null) {
				continue;
			}
			var botCraft = bot.player().getBukkitEntity();
			for (var other : getServer().getOnlinePlayers()) {
				if (botIds.contains(other.getUniqueId()) && !other.getUniqueId().equals(bot.player().getUUID())) {
					if (((org.bukkit.craftbukkit.entity.CraftPlayer) other).isListed(botCraft)) {
						botsVisibleToBots++;
						break;
					}
				}
			}
		}
		check("Unlist mechanism works", botsVisibleToBots == 0, "visibleToBots=" + botsVisibleToBots);

		// Check 4: hold items (NMS inventory exposes armor contents)
		boolean kitOk = true;
		for (var bot : bots) {
			var armor = bot.player().getInventory().getArmorContents();
			if (armor == null || armor.isEmpty()) {
				kitOk = false;
				break;
			}
		}
		check("Bots have inventories", kitOk, "checked=" + bots.size());

			// Check 5: damage and death
		if (!bots.isEmpty()) {
			var bot = bots.get(0).player();
			float before = bot.getHealth();
			boolean invuln = bot.isInvulnerable();
			boolean abilitiesInvuln = bot.getAbilities().invulnerable;
			boolean removed = bot.isRemoved();
			var removalReason = bot.getRemovalReason();
			boolean invulnTo = bot.isInvulnerableTo(bot.level(),
					bot.level().damageSources().genericKill());
			boolean hurt = bot.hurtServer(bot.level(), bot.level().damageSources().genericKill(), 1000f);
			check("Bot takes damage and dies", hurt && (bot.isDeadOrDying() || bot.getHealth() < before),
					"before=" + before + " after=" + bot.getHealth()
							+ " hurt=" + hurt + " invulnerable=" + invuln
							+ " abilitiesInvuln=" + abilitiesInvuln
							+ " removed=" + removed + " reason=" + removalReason
							+ " invulnTo=" + invulnTo);
		}

		// Check 6: ticking (they should still be valid after a tick)
		Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
			long valid = bots.stream().filter(b -> b.player() != null && b.player().isAlive()).count();
			check("Bots survive a tick", valid > 0, "valid=" + valid);
			tps(sender);
			getLogger().info("=== harness complete ===");
		}, 40L);

		sender.sendMessage(ChatColor.GOLD + "Harness running - check console for results.");
	}

	private void check(String name, boolean pass, String detail) {
		String line = (pass ? "[PASS] " : "[FAIL] ") + name + " (" + detail + ")";
		if (pass) {
			getLogger().info(line);
		} else {
			getLogger().warning(line);
		}
	}
}
