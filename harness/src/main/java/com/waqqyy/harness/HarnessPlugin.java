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
		getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
			@org.bukkit.event.EventHandler
			public void onVelocity(org.bukkit.event.player.PlayerVelocityEvent event) {
				if (keepVelocity.contains(event.getPlayer().getUniqueId())) {
					event.setCancelled(true);
				}
			}
		}, this);
		// The local console is not scriptable, so allow the knockback check to run unattended.
		if (Boolean.getBoolean("harness.knockback")) {
			Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> knockback(), 40L);
		}
		if (Boolean.getBoolean("harness.walk")) {
			Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> walk(), 40L);
		}
		if (Boolean.getBoolean("harness.e2e")) {
			Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> e2e(), 40L);
		}
		if (Boolean.getBoolean("harness.shield")) {
			Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> shield(), 40L);
		}
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
				case "knockback" -> knockback();
				case "shield" -> shield();
				case "e2e" -> e2e();
				case "walk" -> walk();
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
			var botLevel = bot.level();
			var source = botLevel.damageSources().genericKill();
			boolean invulnTo = bot.isInvulnerableTo(botLevel, source);
			boolean invulnToBase = bot.isInvulnerableToBase(source);
			boolean immune = net.minecraft.world.item.enchantment.EnchantmentHelper
					.isImmuneToDamage(botLevel, bot, source);
			boolean deadOrDying = bot.isDeadOrDying();
			boolean hasLoaded = bot.connection.hasClientLoaded();
			boolean changingDim = bot.isChangingDimension();
			boolean spectator = bot.isSpectator();
			var gm = bot.gameMode.getGameModeForPlayer();
			boolean creative = gm == net.minecraft.world.level.GameType.CREATIVE;
			boolean hurt = bot.hurtServer(botLevel, source, 1000f);
			check("Bot takes damage and dies", hurt && (bot.isDeadOrDying() || bot.getHealth() < before),
					"before=" + before + " after=" + bot.getHealth()
							+ " hurt=" + hurt + " invulnerable=" + invuln
							+ " abilitiesInvuln=" + abilitiesInvuln
							+ " removed=" + removed + " reason=" + removalReason
							+ " invulnTo=" + invulnTo + " invulnToBase=" + invulnToBase
							+ " enchantImmune=" + immune + " deadOrDying=" + deadOrDying
							+ " health=" + bot.getHealth() + " maxHealth=" + bot.getMaxHealth()
							+ " hasClientLoaded=" + hasLoaded + " changingDim=" + changingDim
							+ " spectator=" + spectator + " gameMode=" + gm + " creative=" + creative);
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

	private final java.util.Set<java.util.UUID> keepVelocity = new java.util.HashSet<>();

	/**
	 * Knockback check. Pair A gets the candidate fix (doTick() every tick plus a cancelled
	 * PlayerVelocityEvent); pair B is the control, ticked only by the level like the plugin's
	 * bots were. Each attacker hits its victim with the real Player.attack() path.
	 */
	private void knockback() {
		clearAll();
		var world = getServer().getWorlds().get(0);
		var spawn = world.getSpawnLocation();
		world.getChunkAt(spawn).addPluginChunkTicket(this);
		var server = FakePlayerFactory.nmsServer(getServer());
		var level = FakePlayerFactory.nmsLevel(world);
		double x = spawn.getBlockX() + 0.5;
		double z = spawn.getBlockZ() + 0.5;
		double y = world.getHighestBlockYAt(spawn) + 1.0;

		var victimA = FakePlayerFactory.create(server, level, "KbVictimA");
		var attackerA = FakePlayerFactory.create(server, level, "KbAttackA");
		var victimB = FakePlayerFactory.create(server, level, "KbVictimB");
		var attackerB = FakePlayerFactory.create(server, level, "KbAttackB");
		for (var r : List.of(victimA, attackerA, victimB, attackerB)) {
			if (r.error() != null) {
				check("Knockback spawn", false, String.valueOf(r.error()));
				return;
			}
			bots.add(r);
		}
		var vA = victimA.player();
		var aA = attackerA.player();
		var vB = victimB.player();
		var aB = attackerB.player();
		vA.teleportTo(x, y, z);
		aA.teleportTo(x + 1.5, y, z);
		vB.teleportTo(x, y, z + 3.0);
		aB.teleportTo(x + 1.5, y, z + 3.0);
		keepVelocity.add(vA.getUUID());

		int[] tick = {0};
		net.minecraft.world.phys.Vec3[] before = new net.minecraft.world.phys.Vec3[2];
		Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
			tick[0]++;
			// Attackers always get doTick() so their attack cooldown charges.
			vA.doTick();
			aA.doTick();
			aB.doTick();
			if (tick[0] == 30) {
				before[0] = vA.position();
				before[1] = vB.position();
				aA.attack(vA);
				aB.attack(vB);
				getLogger().info("[KB] hit: healthA=" + vA.getHealth() + " deltaA=" + vA.getDeltaMovement()
						+ " healthB=" + vB.getHealth() + " deltaB=" + vB.getDeltaMovement());
			}
			if (tick[0] == 50) {
				double movedA = vA.position().distanceTo(before[0]);
				double movedB = vB.position().distanceTo(before[1]);
				getLogger().info("[KB] before=" + before[0] + " afterA=" + vA.position()
						+ " afterB=" + vB.position());
				check("Knockback with doTick fix", movedA > 0.3, "moved=" + movedA);
				check("Control without fix does not move", movedB < 0.01, "moved=" + movedB);
				task.cancel();
			}
		}, 1L, 1L);
	}

	/**
	 * Shield check. Victim A raises its offhand shield with startUsingItem() and faces the
	 * attacker; victim B holds a shield but does not raise it. Everyone gets doTick().
	 */
	private void shield() {
		clearAll();
		var world = getServer().getWorlds().get(0);
		var spawn = world.getSpawnLocation();
		world.getChunkAt(spawn).addPluginChunkTicket(this);
		var server = FakePlayerFactory.nmsServer(getServer());
		var level = FakePlayerFactory.nmsLevel(world);
		double x = spawn.getBlockX() + 0.5;
		double z = spawn.getBlockZ() + 0.5;
		double y = world.getHighestBlockYAt(spawn) + 1.0;

		var rs = List.of(FakePlayerFactory.create(server, level, "ShVictimA"),
				FakePlayerFactory.create(server, level, "ShAttackA"),
				FakePlayerFactory.create(server, level, "ShVictimB"),
				FakePlayerFactory.create(server, level, "ShAttackB"));
		for (var r : rs) {
			if (r.error() != null) {
				check("Shield spawn", false, String.valueOf(r.error()));
				return;
			}
			bots.add(r);
		}
		var vA = rs.get(0).player();
		var aA = rs.get(1).player();
		var vB = rs.get(2).player();
		var aB = rs.get(3).player();
		vA.teleportTo(x, y, z);
		aA.teleportTo(x + 1.5, y, z);
		vB.teleportTo(x, y, z + 3.0);
		aB.teleportTo(x + 1.5, y, z + 3.0);
		for (var v : List.of(vA, vB)) {
			v.getBukkitEntity().getInventory().setItemInOffHand(
					new org.bukkit.inventory.ItemStack(org.bukkit.Material.SHIELD));
			// Face +x, toward the attacker.
			v.getBukkitEntity().setRotation(-90f, 0f);
		}

		int[] tick = {0};
		float[] before = new float[2];
		Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
			tick[0]++;
			for (var p : List.of(vA, aA, vB, aB)) {
				p.doTick();
			}
			if (tick[0] == 10) {
				vA.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
			}
			if (tick[0] == 30) {
				before[0] = vA.getHealth();
				before[1] = vB.getHealth();
				getLogger().info("[SH] pre-hit: usingA=" + vA.isUsingItem() + " blockingA=" + vA.isBlocking()
						+ " usingB=" + vB.isUsingItem());
				aA.attack(vA);
				aB.attack(vB);
				check("Raised shield blocks the hit", vA.getHealth() == before[0],
						"before=" + before[0] + " after=" + vA.getHealth());
				check("Control without raised shield takes damage", vB.getHealth() < before[1],
						"before=" + before[1] + " after=" + vB.getHealth());
				vA.stopUsingItem();
				getLogger().info("[SH] lowered: usingA=" + vA.isUsingItem() + " blockingA=" + vA.isBlocking());
				task.cancel();
			}
		}, 1L, 1L);
	}

	/**
	 * Walking check: can a fake player be driven with real movement input (zza + yaw + doTick)
	 * instead of teleports? Bot A walks on flat ground; bot B has to jump a one-block step.
	 */
	private void walk() {
		clearAll();
		var world = getServer().getWorlds().get(0);
		world.setDifficulty(org.bukkit.Difficulty.PEACEFUL);
		var spawn = world.getSpawnLocation();
		world.getChunkAt(spawn).addPluginChunkTicket(this);
		var server = FakePlayerFactory.nmsServer(getServer());
		var level = FakePlayerFactory.nmsLevel(world);
		int bx = spawn.getBlockX();
		int bz = spawn.getBlockZ();
		int by = world.getHighestBlockYAt(spawn) + 1;

		// A one-block step in bot B's lane.
		for (int i = 3; i <= 9; i++) {
			world.getBlockAt(bx + i, by, bz + 4).setType(org.bukkit.Material.STONE);
		}

		var ra = FakePlayerFactory.create(server, level, "WalkA");
		var rb = FakePlayerFactory.create(server, level, "WalkB");
		if (ra.error() != null || rb.error() != null) {
			check("Walk spawn", false, ra.error() + " / " + rb.error());
			return;
		}
		bots.add(ra);
		bots.add(rb);
		var a = ra.player();
		var b = rb.player();
		a.teleportTo(bx + 0.5, by, bz + 0.5);
		b.teleportTo(bx + 0.5, by, bz + 4.5);
		var startA = a.position();
		var startB = b.position();

		int[] tick = {0};
		Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
			tick[0]++;
			for (var p : List.of(a, b)) {
				p.getBukkitEntity().setRotation(-90f, 0f);
				p.zza = 1.0f;
				p.setSprinting(true);
				p.setJumping(p.horizontalCollision && p.onGround());
				p.doTick();
			}
			if (tick[0] == 40) {
				double dxA = a.position().x - startA.x;
				double dxB = b.position().x - startB.x;
				double dyB = b.position().y - startB.y;
				check("Walks with movement input", dxA > 5.0, "dx=" + dxA + " in 40 ticks");
				check("Jumps a one-block step", dxB > 4.0 && dyB > 0.9, "dx=" + dxB + " dy=" + dyB);
				for (int i = 3; i <= 9; i++) {
					world.getBlockAt(bx + i, by, bz + 4).setType(org.bukkit.Material.AIR);
				}
				task.cancel();
			}
		}, 1L, 1L);
	}

	/**
	 * End-to-end check of the real TotemOfNull plugin (reached by reflection, so the harness
	 * has no compile-time dependency on it). A fake holder summons the swarm against a fake
	 * target: first behind a wall, then in a sealed room underground.
	 */
	private void e2e() {
		clearAll();
		var totem = getServer().getPluginManager().getPlugin("TotemOfNull");
		if (totem == null) {
			check("TotemOfNull present", false, "plugin not loaded");
			return;
		}
		final Object botManager;
		final Object holderState;
		try {
			botManager = totem.getClass().getMethod("getBotManager").invoke(totem);
			holderState = totem.getClass().getMethod("getHolderState").invoke(totem);
		} catch (ReflectiveOperationException e) {
			check("TotemOfNull API", false, e.toString());
			return;
		}

		var world = getServer().getWorlds().get(0);
		world.setDifficulty(org.bukkit.Difficulty.PEACEFUL);
		world.setTime(1000L);
		var spawn = world.getSpawnLocation();
		for (int cx = -2; cx <= 2; cx++) {
			for (int cz = -2; cz <= 2; cz++) {
				world.getChunkAt(spawn.getChunk().getX() + cx, spawn.getChunk().getZ() + cz).addPluginChunkTicket(this);
			}
		}
		var server = FakePlayerFactory.nmsServer(getServer());
		var level = FakePlayerFactory.nmsLevel(world);
		int bx = spawn.getBlockX();
		int bz = spawn.getBlockZ();
		int by = world.getHighestBlockYAt(spawn) + 1;

		Runnable restoreTerrain = () -> {
			for (int x = bx - 12; x <= bx + 20; x++) {
				for (int z = bz - 12; z <= bz + 12; z++) {
					for (int y = by; y <= by + 3; y++) {
						world.getBlockAt(x, y, z).setType(org.bukkit.Material.AIR);
					}
					world.getBlockAt(x, by - 1, z).setType(org.bukkit.Material.GRASS_BLOCK);
					world.getBlockAt(x, by - 2, z).setType(org.bukkit.Material.DIRT);
					world.getBlockAt(x, by - 3, z).setType(org.bukkit.Material.DIRT);
				}
			}
		};
		restoreTerrain.run();
		getLogger().info("[E2E] column below surface: " + world.getBlockAt(bx, by - 1, bz).getType() + ", "
				+ world.getBlockAt(bx, by - 2, bz).getType() + ", " + world.getBlockAt(bx, by - 3, bz).getType()
				+ ", " + world.getBlockAt(bx, by - 4, bz).getType());

		// Phase A terrain: a wall between the swarm and the target, open at both far ends.
		for (int z = bz - 10; z <= bz + 10; z++) {
			for (int y = by; y <= by + 2; y++) {
				world.getBlockAt(bx + 8, y, z).setType(org.bukkit.Material.STONE);
			}
		}

		var holderResult = FakePlayerFactory.create(server, level, "E2eHolder");
		var targetResult = FakePlayerFactory.create(server, level, "E2eTarget");
		if (holderResult.error() != null || targetResult.error() != null) {
			check("E2E spawn", false, holderResult.error() + " / " + targetResult.error());
			return;
		}
		bots.add(holderResult);
		bots.add(targetResult);
		var holder = holderResult.player();
		holder.teleportTo(bx + 0.5, by, bz + 0.5);
		var holderBukkit = (Player) holder.getBukkitEntity();
		net.minecraft.server.level.ServerPlayer[] target = { targetResult.player() };
		target[0].teleportTo(bx + 14.5, by, bz + 0.5);

		java.util.function.Supplier<List<Player>> swarm = () -> {
			List<Player> out = new ArrayList<>();
			for (Player online : getServer().getOnlinePlayers()) {
				if (!online.getName().startsWith("E2e")) {
					out.add(online);
				}
			}
			return out;
		};
		java.util.function.Consumer<String> summon = label -> {
			try {
				botManager.getClass().getMethod("summon", Player.class).invoke(botManager, holderBukkit);
			} catch (ReflectiveOperationException e) {
				check(label, false, String.valueOf(e.getCause() != null ? e.getCause() : e));
			}
		};

		summon.accept("Summon (wall phase)");

		// phase: 0 = wall chase, 1 = dead-bot removal, 2 = dig, 3 = done
		int[] phase = {0};
		int[] tick = {0};
		int[] phaseStart = {0};
		Player[] killed = {null};
		Player[] struck = {null};
		Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
			tick[0]++;
			int elapsed = tick[0] - phaseStart[0];
			try {
				if (target[0] != null && target[0].isAlive()) {
					target[0].doTick();
				}
				if (phase[0] == 0) {
					if (elapsed == 5) {
						var first = swarm.get();
						check("Swarm spawned", !first.isEmpty(), "bots=" + first.size());
					}
					boolean hit = target[0].getHealth() < 20.0f || !target[0].isAlive();
					if (hit || elapsed >= 400) {
						double nearest = Double.MAX_VALUE;
						for (Player bot : swarm.get()) {
							nearest = Math.min(nearest, bot.getLocation().distance(target[0].getBukkitEntity().getLocation()));
						}
						check("Bots walk around a wall and hit the target", hit,
								"ticks=" + elapsed + " nearestBot=" + nearest + " targetHealth=" + target[0].getHealth());
						// The target (a "real" player as far as the plugin knows) swings at the
						// nearest bot, which should raise its shield.
						Player nearestBot = null;
						for (Player bot : swarm.get()) {
							if (nearestBot == null || bot.getLocation().distanceSquared(target[0].getBukkitEntity().getLocation())
									< nearestBot.getLocation().distanceSquared(target[0].getBukkitEntity().getLocation())) {
								nearestBot = bot;
							}
						}
						if (nearestBot != null && target[0].isAlive()) {
							struck[0] = nearestBot;
							target[0].attack(((org.bukkit.craftbukkit.entity.CraftPlayer) nearestBot).getHandle());
						}
						// Kill one bot and make sure it leaves the player list.
						var all = swarm.get();
						if (!all.isEmpty()) {
							killed[0] = all.get(all.size() - 1) == struck[0] ? all.get(0) : all.get(all.size() - 1);
							killed[0].setHealth(0.0);
						}
						phase[0] = 1;
						phaseStart[0] = tick[0];
					}
				} else if (phase[0] == 1) {
					if (elapsed == 8) {
						boolean blocking = struck[0] != null
								&& ((org.bukkit.craftbukkit.entity.CraftPlayer) struck[0]).getHandle().isBlocking();
						check("Bot raises its shield when a player attacks it", blocking,
								"bot=" + (struck[0] != null ? struck[0].getName() : null) + " blocking=" + blocking);
					}
					if (elapsed == 60) {
						boolean stillOnline = killed[0] != null && getServer().getPlayer(killed[0].getUniqueId()) != null;
						check("Dead bot is dropped from the player list", killed[0] != null && !stillOnline,
								"killed=" + (killed[0] != null ? killed[0].getName() : null) + " stillOnline=" + stillOnline);

						// Phase C: sealed room under the surface with a fresh target in it.
						botManager.getClass().getMethod("clearAll").invoke(botManager);
						FakePlayerFactory.remove(target[0]);
						restoreTerrain.run();
						for (int x = bx + 6; x <= bx + 8; x++) {
							for (int z = bz - 1; z <= bz + 1; z++) {
								world.getBlockAt(x, by - 2, z).setType(org.bukkit.Material.AIR);
								world.getBlockAt(x, by - 3, z).setType(org.bukkit.Material.AIR);
							}
						}
						var deep = FakePlayerFactory.create(server, level, "E2eDeep");
						bots.add(deep);
						target[0] = deep.player();
						target[0].teleportTo(bx + 7.5, by - 3, bz + 0.5);
						summon.accept("Summon (dig phase)");
						phase[0] = 2;
						phaseStart[0] = tick[0];
					}
				} else if (phase[0] == 2) {
					boolean hit = target[0].getHealth() < 20.0f || !target[0].isAlive();
					if (hit || elapsed >= 600) {
						double lowest = Double.MAX_VALUE;
						for (Player bot : swarm.get()) {
							lowest = Math.min(lowest, bot.getLocation().getY());
						}
						check("Bots dig down to a target below", hit,
								"ticks=" + elapsed + " surfaceY=" + by + " lowestBotY=" + lowest
										+ " targetHealth=" + target[0].getHealth());

						// Identity: hide and restore the holder with real viewers present.
						var hs = holderState.getClass();
						hs.getMethod("applyHidden", Player.class).invoke(holderState, holderBukkit);
						boolean hiddenNow = (boolean) hs.getMethod("isHidden", Player.class).invoke(holderState, holderBukkit);
						boolean hasSkin = holderBukkit.getPlayerProfile().hasProperty("textures");
						String listName = holderBukkit.getPlayerListName();
						hs.getMethod("restore", Player.class).invoke(holderState, holderBukkit);
						boolean hiddenAfter = (boolean) hs.getMethod("isHidden", Player.class).invoke(holderState, holderBukkit);
						boolean skinAfter = holderBukkit.getPlayerProfile().hasProperty("textures");
						check("Holder identity hides and restores", hiddenNow && hasSkin && !hiddenAfter && !skinAfter
								&& holderBukkit.getPlayerListName().equals(holderBukkit.getName()),
								"hidden=" + hiddenNow + " skin=" + hasSkin + " listName=" + listName
										+ " afterHidden=" + hiddenAfter + " afterSkin=" + skinAfter);

						botManager.getClass().getMethod("clearAll").invoke(botManager);
						restoreTerrain.run();
						check("Swarm cleared", swarm.get().isEmpty(), "left=" + swarm.get().size());
						getLogger().info("[E2E] === complete ===");
						phase[0] = 3;
						task.cancel();
					}
				}
			} catch (Throwable t) {
				check("E2E run", false, String.valueOf(t.getCause() != null ? t.getCause() : t));
				t.printStackTrace();
				task.cancel();
			}
		}, 1L, 1L);
	}
}
