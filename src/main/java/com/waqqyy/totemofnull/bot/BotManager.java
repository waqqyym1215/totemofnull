package com.waqqyy.totemofnull.bot;

import com.waqqyy.totemofnull.ArmorKit;
import com.waqqyy.totemofnull.NameGenerator;
import com.waqqyy.totemofnull.TotemOfNullPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Spawns, drives and despawns the swarm of fake-player minions.
 *
 * <p>All NMS is reached through {@link PaperNmsBridge}; everything else is plain Bukkit code.</p>
 */
public class BotManager {

	private final TotemOfNullPlugin plugin;
	private final List<NullBot> bots = new ArrayList<>();
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
		return isBot(player.getUniqueId());
	}

	/** Spawns the swarm around the holder. */
	public void summon(Player holder) {
		clearAll();
		this.holder = holder;
		this.holderActivatedAt = System.currentTimeMillis();
		this.active = true;

		World world = holder.getWorld();
		Location base = holder.getLocation();
		Set<String> taken = new HashSet<>();
		for (Player online : Bukkit.getOnlinePlayers()) {
			taken.add(online.getName().toLowerCase());
		}
		for (NullBot bot : bots) {
			taken.add(bot.name().toLowerCase());
		}

		int count = plugin.botCount();
		for (int i = 0; i < count; i++) {
			String name = NameGenerator.uniqueName(plugin.botNameLength(), taken);
			taken.add(name.toLowerCase());

			Location spawn = findSpawnLocation(base, i, count);
			var server = PaperNmsBridge.nmsServer(plugin.getServer());
			var level = PaperNmsBridge.nmsLevel(spawn.getWorld());
			FakePlayer result = PaperNmsBridge.create(server, level, name);
			if (!result.ok()) {
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

		// Lifetime: bots die after the configured number of minutes.
		long lifetimeTicks = plugin.botLifetimeMinutes() * 60L * 20L;
		if (holder != null && holder.isOnline() && now - ticksAt(holderActivatedAt) > lifetimeTicks) {
			plugin.getLogger().info("The swarm of " + holder.getName() + " fades away.");
			clearAll();
			holder = null;
			active = false;
			return;
		}

		List<NullBot> dead = new ArrayList<>();
		for (NullBot bot : bots) {
			if (bot.isDead() || bot.isRemoved()) {
				dead.add(bot);
				continue;
			}
			tickBot(bot, now);
		}
		if (!dead.isEmpty()) {
			bots.removeAll(dead);
		}

		// The holder can summon again once the swarm is gone.
		if (bots.isEmpty()) {
			active = false;
		}
	}

	private long ticksAt(long epochMillis) {
		// Approximation is fine for a lifetime check; the exact value only shifts the deadline
		// by a fraction of a tick.
		return Math.round((System.currentTimeMillis() - epochMillis) / 50.0) + plugin.getServer().getCurrentTick();
	}

	private void tickBot(NullBot bot, long now) {
		Player bukkit = bot.bukkit();
		if (bukkit == null || !bukkit.isOnline()) {
			return;
		}

		// 1. Golden apples: eat one when low.
		eatIfLow(bot);

		// 2. Acquire a target if we have none (or it is gone).
		Player target = bot.target();
		if (target == null || !target.isOnline() || target.isDead() || NullBot.isExempt(target)) {
			target = findTarget(bot);
			bot.setTarget(target);
		}
		if (target == null) {
			// Nothing to hunt: stand down and lower the shield.
			return;
		}

		// 3. Shield: raise when attacked or stared at while the viewer holds a weapon.
		updateShield(bot, now);

		// 4. Move toward the target and attack when in reach.
		moveToward(bot, target);
		attackIfInRange(bot, target, now);
	}

	private void eatIfLow(NullBot bot) {
		Player bukkit = bot.bukkit();
		double health = bukkit.getHealth();
		if (health > plugin.botEatThreshold()) {
			return;
		}
		if (bot.goldenApples() <= 0) {
			return;
		}
		PlayerInventory inv = bukkit.getInventory();
		int slot = findGoldenAppleSlot(inv);
		if (slot < 0) {
			return;
		}
		ItemStack apples = inv.getItem(slot);
		if (apples == null || apples.getType() != Material.GOLDEN_APPLE) {
			return;
		}
		if (bukkit.hasCooldown(Material.GOLDEN_APPLE)) {
			return;
		}

		inv.setItem(slot, apples.subtract(1));
		bot.setGoldenApples(bot.goldenApples() - 1);
		// Apply the golden apple effect directly: food and the regeneration/absorption effects.
		bukkit.setHealth(Math.min(bukkit.getAttribute(Attribute.MAX_HEALTH).getValue(), health + 4.0));
		bukkit.setAbsorptionAmount(Math.min(8.0, bukkit.getAbsorptionAmount() + 4.0));
		bukkit.getWorld().playSound(bukkit.getLocation(), Sound.ENTITY_GENERIC_EAT, SoundCategory.PLAYERS, 1f, 1f);
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

	private Player findTarget(NullBot bot) {
		Location origin = bot.bukkit().getLocation();
		Player best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Player candidate : Bukkit.getOnlinePlayers()) {
			if (candidate.getUniqueId().equals(bot.uuid())) {
				continue;
			}
			if (candidate.getGameMode() == GameMode.SPECTATOR || candidate.getGameMode() == GameMode.CREATIVE) {
				continue;
			}
			if (candidate.isDead() || !candidate.isOnline()) {
				continue;
			}
			if (NullBot.isExempt(candidate)) {
				continue;
			}
			if (holder != null && candidate.equals(holder)) {
				continue;
			}
			if (!candidate.getWorld().equals(origin.getWorld())) {
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

	private void updateShield(NullBot bot, long now) {
		Player bukkit = bot.bukkit();
		boolean raise = false;

		// Raised recently after being damaged.
		if (bot.isShieldRaised(now)) {
			raise = true;
		} else {
			// Or a nearby player is looking straight at the bot while holding a weapon.
			for (Player viewer : Bukkit.getOnlinePlayers()) {
				if (viewer.getUniqueId().equals(bot.uuid())) {
					continue;
				}
				if (!viewer.getWorld().equals(bukkit.getWorld())) {
					continue;
				}
				Location viewerEye = viewer.getEyeLocation();
				if (viewerEye.distanceSquared(bukkit.getEyeLocation()) > 100) {
					continue;
				}
				if (!viewer.hasLineOfSight(bukkit)) {
					continue;
				}
				if (!holdsWeapon(viewer)) {
					continue;
				}
				if (isLookingAt(viewerEye, bukkit.getEyeLocation(), 12.0)) {
					raise = true;
					break;
				}
			}
		}

		if (raise && !bot.isShieldRaised(now)) {
			int min = plugin.shieldRaiseMinSeconds() * 20;
			int max = Math.max(plugin.shieldRaiseMaxSeconds() * 20, min);
			int ticks = min + (int) (Math.random() * (max - min + 1));
			bot.raiseShield(now + ticks);
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
		Vector toPoint = point.toVector().subtract(eye.toVector()).normalize();
		return direction.angle(toPoint) <= Math.toRadians(toleranceDegrees);
	}

	private void moveToward(NullBot bot, Player target) {
		Player bukkit = bot.bukkit();
		Location from = bukkit.getLocation();
		Location to = target.getLocation();
		if (!from.getWorld().equals(to.getWorld())) {
			return;
		}
		double distance = from.distance(to);
		if (distance <= 3.0) {
			return;
		}
		if (distance > 64.0) {
			// Too far: give up rather than make bots teleport across the map.
			bot.setTarget(null);
			return;
		}

		Vector direction = to.toVector().subtract(from.toVector()).setY(0).normalize().multiply(0.55);
		Location next = from.clone().add(direction);
		// Step up onto a block if the path is blocked.
		if (isSolid(next.getBlock().getType())) {
			next.add(0, 1, 0);
		}
		bukkit.teleport(next);
		faceToward(bukkit, to);
	}

	private void faceToward(Player bukkit, Location target) {
		Location from = bukkit.getLocation();
		double dx = target.getX() - from.getX();
		double dz = target.getZ() - from.getZ();
		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		bukkit.getPlayer().setRotation(yaw, from.getPitch());
	}

	private void attackIfInRange(NullBot bot, Player target, long now) {
		Player bukkit = bot.bukkit();
		if (bukkit.getLocation().distance(target.getLocation()) > 3.5) {
			return;
		}
		// Respect a rough attack cooldown so bots do not hit every tick.
		if (now - bot.lastAttackTick() < 10) {
			return;
		}
		bot.setLastAttackTick(now);
		// Bukkit's LivingEntity#attack goes through the NMS damage path, which respects armor.
		bukkit.attack(target);
	}

	private boolean isSolid(Material material) {
		return material.isSolid() && material.isOccluding();
	}

	/** Removes every bot immediately. */
	public void clearAll() {
		for (NullBot bot : bots) {
			PaperNmsBridge.remove(bot.player());
		}
		bots.clear();
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
