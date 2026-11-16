package com.waqqyy.totemofnull.bot;

import com.waqqyy.totemofnull.NameGenerator;
import com.waqqyy.totemofnull.TotemOfNullPlugin;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spawns, drives and despawns the swarm of fake-player minions.
 *
 * <p>Bots are moved like real players: each tick the manager sets their movement input
 * (facing, forward, sprint, jump) and then runs the player tick, so gravity, collisions and
 * knockback all apply.</p>
 */
public class BotManager {

	private static final long DEAD_BOT_LINGER_TICKS = 20L;
	/** Ticks a player needs to eat a golden apple. */
	private static final int EAT_TICKS = 32;
	private static final double ATTACK_REACH = 3.0;
	private static final int PATH_NODE_BUDGET = 600;
	private static final long DIG_BLOCKED_TICKS = 100L;

	private final TotemOfNullPlugin plugin;
	private final List<NullBot> bots = new ArrayList<>();
	/** Names of live bots. Filled before the fake player joins so the join event can see it. */
	private final Set<String> botNames = new HashSet<>();
	private Player holder;
	private long holderActivatedAt;
	private boolean active;

	public BotManager(TotemOfNullPlugin plugin) {
		this.plugin = plugin;
	}

	/** True if a swarm is currently hunting for the given player. */
	public boolean isActive(Player player) {
		return active && player.equals(holder) && !bots.isEmpty();
	}

	public Optional<String> getHolderName() {
		return Optional.ofNullable(holder).map(Player::getName);
	}

	public int activeCount() {
		return (int) bots.stream().filter(b -> !b.isDead() && !b.isRemoved()).count();
	}

	public boolean isBot(UUID uuid) {
		return bots.stream().anyMatch(b -> b.uuid().equals(uuid));
	}

	public boolean isBot(Player player) {
		return botNames.contains(player.getName()) || isBot(player.getUniqueId());
	}

	/** Spawns the swarm around the holder. */
	public void summon(Player holder) {
		clearAll();
		this.holder = holder;
		this.holderActivatedAt = System.currentTimeMillis();
		this.active = true;

		Location base = holder.getLocation();
		Set<String> taken = new HashSet<>();
		for (Player online : Bukkit.getOnlinePlayers()) {
			taken.add(online.getName().toLowerCase());
		}

		int count = plugin.botCount();
		for (int i = 0; i < count; i++) {
			String name = NameGenerator.uniqueName(plugin.botNameLength(), taken);
			taken.add(name.toLowerCase());

			Location spawn = findSpawnLocation(base, i, count);
			var server = PaperNmsBridge.nmsServer(plugin.getServer());
			var level = PaperNmsBridge.nmsLevel(spawn.getWorld());
			botNames.add(name);
			FakePlayer result = PaperNmsBridge.create(server, level, name);
			if (!result.ok()) {
				botNames.remove(name);
				plugin.getLogger().warning("Failed to spawn bot " + name + ": " + result.error());
				continue;
			}

			NullBot bot = new NullBot(result.player(), name, holderActivatedAt, plugin.botGoldenApples());
			// Move onto solid ground near the holder.
			result.player().teleportTo(spawn.getX(), spawn.getY(), spawn.getZ());
			bot.equip(plugin.swordSharpness());
			bot.giveGoldenApples(plugin.botGoldenApples());
			bots.add(bot);
		}

		plugin.getLogger().info("Summoned " + activeCount() + " minions for " + holder.getName());
	}

	/** Picks a spawn point in a ring around the holder, nudging up/down to find ground. */
	private Location findSpawnLocation(Location base, int index, int total) {
		double angle = 2 * Math.PI * index / Math.max(1, total);
		double radius = 4.0 + (index % 3);
		double x = base.getX() + radius * Math.cos(angle);
		double z = base.getZ() + radius * Math.sin(angle);
		World world = base.getWorld();

		int highest = world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z));
		double y = Math.min(highest + 1, world.getMaxHeight() - 2);
		return new Location(world, x, y, z, base.getYaw(), base.getPitch());
	}

	/** Called every tick by the plugin's repeating task. */
	public void tick() {
		if (!active) {
			return;
		}
		long now = plugin.getServer().getCurrentTick();

		// Lifetime: the swarm fades after the configured number of minutes.
		long lifetimeMillis = plugin.botLifetimeMinutes() * 60_000L;
		if (System.currentTimeMillis() - holderActivatedAt > lifetimeMillis) {
			plugin.getLogger().info("The swarm" + (holder != null ? " of " + holder.getName() : "") + " fades away.");
			clearAll();
			holder = null;
			active = false;
			return;
		}

		List<NullBot> gone = new ArrayList<>();
		for (NullBot bot : bots) {
			if (bot.isRemoved()) {
				gone.add(bot);
				continue;
			}
			if (bot.isDead()) {
				// A dead fake player stays in the server's player list until it is
				// disconnected. Leave it a second for the death animation, then drop it.
				if (bot.deadSinceTick < 0) {
					bot.deadSinceTick = now;
				} else if (now - bot.deadSinceTick >= DEAD_BOT_LINGER_TICKS) {
					gone.add(bot);
				}
				continue;
			}
			tickBot(bot, now);
			// The server only calls ServerPlayer.doTick() from a real network connection's
			// tick, and a fake player has none. doTick() is what runs Player.tick(): movement
			// input, gravity, velocity (knockback), effects, hurt timers and attack cooldown.
			bot.player().doTick();
		}
		for (NullBot bot : gone) {
			bots.remove(bot);
			removeBot(bot);
		}

		// The holder can summon again once the swarm is gone.
		if (bots.isEmpty()) {
			active = false;
		}
	}

	private void tickBot(NullBot bot, long now) {
		Player bukkit = bot.bukkit();
		ServerPlayer nms = bot.player();

		// Movement input is re-decided every tick; default to standing still.
		nms.zza = 0f;
		nms.xxa = 0f;
		nms.setJumping(false);
		nms.setSprinting(false);
		if (bukkit == null || !bukkit.isOnline()) {
			return;
		}

		// 1. Golden apples: eating takes as long as it does for a player.
		tickEating(bot, now);
		boolean eating = bot.eatingUntilTick >= 0;

		// 2. Shield: decide whether it should be up, then actually raise or lower it.
		updateShield(bot, now);
		applyShield(bot, now, eating);

		// 3. Acquire a target if we have none (or it is no longer valid).
		Player target = bot.target();
		if (!isValidTarget(bot, target)) {
			target = findTarget(bot);
			bot.setTarget(target);
			bot.path = null;
		}
		if (target == null) {
			stopDigging(bot);
			holdSword(bot);
			return;
		}

		double distance = bukkit.getLocation().distance(target.getLocation());
		if (distance > 64.0) {
			// Too far: give up rather than chase across the map.
			bot.setTarget(null);
			bot.path = null;
			return;
		}

		// 4. In reach with a clear line: fight.
		if (distance <= ATTACK_REACH && bukkit.hasLineOfSight(target)) {
			stopDigging(bot);
			holdSword(bot);
			Location aim = target.getEyeLocation();
			face(bukkit, aim.getX(), aim.getY(), aim.getZ());
			if (distance > 2.0) {
				nms.zza = eating ? 0.2f : 1.0f;
			}
			if (!eating && now - bot.lastAttackTick() >= 10) {
				bot.setLastAttackTick(now);
				// Bukkit's attack goes through the NMS damage path, which respects armor.
				bukkit.attack(target);
			}
			return;
		}

		// 5. Otherwise walk there, digging down if the target is below and unreachable.
		navigate(bot, target, now, eating);
	}

	// ---------------------------------------------------------------------------
	// Eating
	// ---------------------------------------------------------------------------

	private void tickEating(NullBot bot, long now) {
		Player bukkit = bot.bukkit();
		PlayerInventory inv = bukkit.getInventory();

		if (bot.eatingUntilTick >= 0) {
			if (now < bot.eatingUntilTick) {
				if (now % 4 == 0) {
					bukkit.getWorld().playSound(bukkit.getLocation(), Sound.ENTITY_GENERIC_EAT, SoundCategory.PLAYERS, 1f, 1f);
				}
				return;
			}
			bot.eatingUntilTick = -1;
			int slot = findGoldenAppleSlot(inv);
			if (slot < 0) {
				return;
			}
			ItemStack apples = inv.getItem(slot);
			inv.setItem(slot, apples.subtract(1));
			bot.setGoldenApples(bot.goldenApples() - 1);
			// The same effects a golden apple gives a player.
			bukkit.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
			bukkit.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
			bukkit.setFoodLevel(Math.min(20, bukkit.getFoodLevel() + 4));
			bukkit.getWorld().playSound(bukkit.getLocation(), Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 1f, 1f);
			return;
		}

		if (bukkit.getHealth() > plugin.botEatThreshold()) {
			return;
		}
		if (bot.goldenApples() <= 0 || findGoldenAppleSlot(inv) < 0) {
			return;
		}
		bot.eatingUntilTick = now + EAT_TICKS;
	}

	private int findGoldenAppleSlot(PlayerInventory inv) {
		for (int i = 0; i < inv.getSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack != null && stack.getType() == Material.GOLDEN_APPLE) {
				return i;
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------------------
	// Targeting
	// ---------------------------------------------------------------------------

	private boolean isValidTarget(NullBot bot, Player candidate) {
		if (candidate == null || !candidate.isOnline() || candidate.isDead()) {
			return false;
		}
		if (isBot(candidate) || candidate.equals(holder)) {
			return false;
		}
		if (candidate.getGameMode() == GameMode.SPECTATOR || candidate.getGameMode() == GameMode.CREATIVE) {
			return false;
		}
		if (!candidate.getWorld().equals(bot.bukkit().getWorld())) {
			return false;
		}
		return !NullBot.isExempt(candidate);
	}

	private Player findTarget(NullBot bot) {
		Location origin = bot.bukkit().getLocation();
		Player best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Player candidate : Bukkit.getOnlinePlayers()) {
			if (!isValidTarget(bot, candidate)) {
				continue;
			}
			double distance = candidate.getLocation().distanceSquared(origin);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = candidate;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------------------
	// Shield
	// ---------------------------------------------------------------------------

	/** Raises the shield when a nearby player stares at the bot while holding a weapon. */
	private void updateShield(NullBot bot, long now) {
		if (bot.isShieldRaised(now)) {
			return;
		}
		Player bukkit = bot.bukkit();
		for (Player viewer : Bukkit.getOnlinePlayers()) {
			if (isBot(viewer) || viewer.equals(holder)) {
				continue;
			}
			if (!viewer.getWorld().equals(bukkit.getWorld())) {
				continue;
			}
			Location viewerEye = viewer.getEyeLocation();
			if (viewerEye.distanceSquared(bukkit.getEyeLocation()) > 100) {
				continue;
			}
			if (!holdsWeapon(viewer) || !viewer.hasLineOfSight(bukkit)) {
				continue;
			}
			if (isLookingAt(viewerEye, bukkit.getEyeLocation(), 12.0)) {
				bot.raiseShield(now + randomShieldTicks());
				return;
			}
		}
	}

	/** Makes the bot's actual blocking state match its shield timer. */
	private void applyShield(NullBot bot, long now, boolean eating) {
		ServerPlayer nms = bot.player();
		boolean want = bot.isShieldRaised(now) && !eating;
		if (want && !nms.isUsingItem()) {
			// An axe hit puts the shield on cooldown; respect that like a real player must.
			if (!bot.bukkit().hasCooldown(Material.SHIELD)) {
				nms.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
			}
		} else if (!want && nms.isUsingItem()) {
			nms.stopUsingItem();
		}
	}

	private long randomShieldTicks() {
		int min = plugin.shieldRaiseMinSeconds() * 20;
		int max = Math.max(plugin.shieldRaiseMaxSeconds() * 20, min);
		return min + ThreadLocalRandom.current().nextInt(max - min + 1);
	}

	/** Called when a real player attacks a bot: the bot raises its shield. */
	public void onAttackedByPlayer(Player botPlayer) {
		long now = plugin.getServer().getCurrentTick();
		for (NullBot bot : bots) {
			if (bot.uuid().equals(botPlayer.getUniqueId())) {
				bot.raiseShield(now + randomShieldTicks());
				return;
			}
		}
	}

	private boolean holdsWeapon(Player player) {
		ItemStack stack = player.getInventory().getItemInMainHand();
		if (stack == null || stack.getType().isAir()) {
			return false;
		}
		Material type = stack.getType();
		String name = type.name();
		return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_TRIDENT")
				|| name.endsWith("_MACE") || type == Material.BOW || type == Material.CROSSBOW;
	}

	/** True if the viewer's eye ray points at the given point within {@code toleranceDegrees}. */
	private boolean isLookingAt(Location eye, Location point, double toleranceDegrees) {
		Vector direction = eye.getDirection().normalize();
		Vector toPoint = point.toVector().subtract(eye.toVector());
		if (toPoint.lengthSquared() < 1.0e-6) {
			return true;
		}
		return direction.angle(toPoint.normalize()) <= Math.toRadians(toleranceDegrees);
	}

	// ---------------------------------------------------------------------------
	// Movement
	// ---------------------------------------------------------------------------

	private void navigate(NullBot bot, Player target, long now, boolean eating) {
		Player bukkit = bot.bukkit();
		ServerPlayer nms = bot.player();
		Location loc = bukkit.getLocation();
		Location goal = target.getLocation();

		if (bot.path == null || now >= bot.nextRepathTick) {
			BotNavigator.Result result = BotNavigator.findPath(loc, goal, PATH_NODE_BUDGET);
			bot.path = result.nodes();
			bot.pathIndex = 0;
			bot.pathComplete = result.complete();
			// A failed search costs the whole node budget, so retry those less often.
			int interval = result.complete() ? 10 : 30;
			bot.nextRepathTick = now + interval + ThreadLocalRandom.current().nextInt(10);
		}

		BotNavigator.Node waypoint = nextWaypoint(bot, loc);

		// As close as walking gets, and the target is below: dig.
		boolean targetBelow = goal.getY() < loc.getY() - 1.5;
		if (!bot.pathComplete && waypoint == null && targetBelow && now >= bot.digBlockedUntilTick) {
			if (dig(bot, target, now)) {
				return;
			}
		} else {
			stopDigging(bot);
		}
		holdSword(bot);

		double tx;
		double tz;
		boolean stepUp = false;
		if (waypoint != null) {
			tx = waypoint.x() + 0.5;
			tz = waypoint.z() + 0.5;
			stepUp = waypoint.y() > loc.getY() + 0.5;
		} else {
			tx = goal.getX();
			tz = goal.getZ();
		}
		face(bukkit, tx, bukkit.getEyeLocation().getY(), tz);
		nms.zza = eating ? 0.2f : 1.0f;
		nms.setSprinting(!eating);
		nms.setJumping(nms.isInWater() || (nms.onGround() && (stepUp || nms.horizontalCollision)));
	}

	/** The next path node the bot has not reached yet, or null when the path is used up. */
	private BotNavigator.Node nextWaypoint(NullBot bot, Location loc) {
		List<BotNavigator.Node> path = bot.path;
		if (path == null) {
			return null;
		}
		while (bot.pathIndex < path.size()) {
			BotNavigator.Node node = path.get(bot.pathIndex);
			double dx = node.x() + 0.5 - loc.getX();
			double dz = node.z() + 0.5 - loc.getZ();
			if (dx * dx + dz * dz < 0.25 && Math.abs(loc.getY() - node.y()) < 1.0) {
				bot.pathIndex++;
			} else {
				return node;
			}
		}
		return null;
	}

	/** Turns the bot to look at a point. */
	private void face(Player bukkit, double x, double y, double z) {
		Location eye = bukkit.getEyeLocation();
		double dx = x - eye.getX();
		double dy = y - eye.getY();
		double dz = z - eye.getZ();
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 1.0e-4 && Math.abs(dy) < 1.0e-4) {
			return;
		}
		float yaw = horizontal < 1.0e-4 ? eye.getYaw() : (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
		bukkit.setRotation(yaw, pitch);
	}

	private void holdSword(NullBot bot) {
		PlayerInventory inv = bot.bukkit().getInventory();
		if (inv.getHeldItemSlot() != NullBot.SWORD_SLOT) {
			inv.setHeldItemSlot(NullBot.SWORD_SLOT);
		}
	}

	// ---------------------------------------------------------------------------
	// Digging
	// ---------------------------------------------------------------------------

	/**
	 * Mines toward a target that is below the bot. Returns true while the bot is busy digging
	 * (it stands still), false if there is nothing it can dig.
	 */
	private boolean dig(NullBot bot, Player target, long now) {
		Player bukkit = bot.bukkit();
		ServerPlayer nms = bot.player();
		if (!nms.onGround()) {
			return true; // still falling into the hole it just made
		}
		Block block = pickDigBlock(bot, target);
		if (block == null) {
			stopDigging(bot);
			return false;
		}
		PlayerInventory inv = bukkit.getInventory();
		if (inv.getHeldItemSlot() != NullBot.PICK_SLOT) {
			inv.setHeldItemSlot(NullBot.PICK_SLOT);
		}
		// Progress per tick with the held pickaxe, exactly as for a player. 0 = unbreakable.
		float speed = block.getBreakSpeed(bukkit);
		if (speed <= 0f) {
			stopDigging(bot);
			bot.digBlockedUntilTick = now + DIG_BLOCKED_TICKS;
			return false;
		}
		if (!block.equals(bot.digBlock)) {
			bot.digBlock = block;
			bot.digProgress = 0f;
		}
		Location centre = block.getLocation().add(0.5, 0.5, 0.5);
		face(bukkit, centre.getX(), centre.getY(), centre.getZ());
		bot.digProgress += speed;
		if (now % 4 == 0) {
			bukkit.swingMainHand();
		}
		showCracks(bukkit, block, Math.min(1f, bot.digProgress));

		if (bot.digProgress >= 1f) {
			// Fires BlockBreakEvent, so protection plugins can refuse.
			boolean broken = bukkit.breakBlock(block);
			stopDigging(bot);
			bot.nextRepathTick = 0;
			if (!broken) {
				bot.digBlockedUntilTick = now + DIG_BLOCKED_TICKS;
			}
		}
		return true;
	}

	/** Straight down when above the target, otherwise the next step of a staircase toward it. */
	private Block pickDigBlock(NullBot bot, Player target) {
		Player bukkit = bot.bukkit();
		Location loc = bukkit.getLocation();
		Location goal = target.getLocation();
		World world = loc.getWorld();
		double dx = goal.getX() - loc.getX();
		double dz = goal.getZ() - loc.getZ();

		if (Math.abs(dx) < 1.5 && Math.abs(dz) < 1.5) {
			var on = bot.player().getOnPos();
			Block below = world.getBlockAt(on.getX(), on.getY(), on.getZ());
			return below.isPassable() ? null : below;
		}

		int sx = Math.abs(dx) >= Math.abs(dz) ? (int) Math.signum(dx) : 0;
		int sz = sx == 0 ? (int) Math.signum(dz) : 0;
		int x = loc.getBlockX() + sx;
		int y = loc.getBlockY();
		int z = loc.getBlockZ() + sz;
		// Head room first, then the step itself, then the block that becomes the lower step.
		for (int dy : new int[] { 1, 0, -1 }) {
			Block block = world.getBlockAt(x, y + dy, z);
			if (!block.isPassable()) {
				return block;
			}
		}
		return null;
	}

	private void showCracks(Player bot, Block block, float progress) {
		Location at = block.getLocation();
		for (Player viewer : at.getWorld().getPlayers()) {
			if (!isBot(viewer) && viewer.getLocation().distanceSquared(at) < 32 * 32) {
				viewer.sendBlockDamage(at, progress, bot);
			}
		}
	}

	private void stopDigging(NullBot bot) {
		bot.digBlock = null;
		bot.digProgress = 0f;
	}

	// ---------------------------------------------------------------------------
	// Removal
	// ---------------------------------------------------------------------------

	private void removeBot(NullBot bot) {
		PaperNmsBridge.remove(bot.player());
		// After the disconnect, so the quit event still recognises the bot by name.
		botNames.remove(bot.name());
	}

	/** Removes every bot immediately. */
	public void clearAll() {
		List<NullBot> all = new ArrayList<>(bots);
		bots.clear();
		for (NullBot bot : all) {
			removeBot(bot);
		}
	}

	/** Removes the swarm belonging to the given player (they left or died). */
	public void deactivate(Player player) {
		if (holder == null || !holder.equals(player)) {
			return;
		}
		clearAll();
		holder = null;
		active = false;
	}

	public void shutdown() {
		clearAll();
		holder = null;
		active = false;
	}
}
