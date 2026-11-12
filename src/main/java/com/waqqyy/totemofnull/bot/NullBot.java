package com.waqqyy.totemofnull.bot;

import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.UUID;

/**
 * One fake-player minion: the NMS {@link ServerPlayer} plus the plugin-side state the swarm rules
 * need (spawn time, golden apples, shield window, current target).
 */
public class NullBot {

	private final ServerPlayer player;
	private final String name;
	private final long spawnedAt;
	private int goldenApples;
	private long shieldRaisedUntil;
	private Player target;
	private long lastAttackTick;

	public NullBot(ServerPlayer player, String name, long spawnedAt, int goldenApples) {
		this.player = player;
		this.name = name;
		this.spawnedAt = spawnedAt;
		this.goldenApples = goldenApples;
	}

	public ServerPlayer player() {
		return player;
	}

	public Player bukkit() {
		return player.getBukkitEntity();
	}

	public String name() {
		return name;
	}

	public UUID uuid() {
		return player.getUUID();
	}

	public long spawnedAt() {
		return spawnedAt;
	}

	public int goldenApples() {
		return goldenApples;
	}

	public void setGoldenApples(int count) {
		this.goldenApples = Math.max(0, count);
	}

	public Player target() {
		return target;
	}

	public void setTarget(Player target) {
		this.target = target;
	}

	public long shieldRaisedUntil() {
		return shieldRaisedUntil;
	}

	public void raiseShield(long untilTick) {
		this.shieldRaisedUntil = untilTick;
	}

	public boolean isShieldRaised(long now) {
		return now < shieldRaisedUntil;
	}

	public long lastAttackTick() {
		return lastAttackTick;
	}

	public void setLastAttackTick(long tick) {
		this.lastAttackTick = tick;
	}

	public boolean isDead() {
		return player.isDeadOrDying() || !player.isAlive();
	}

	public boolean isRemoved() {
		return player.isRemoved();
	}

	public PlayerInventory inventory() {
		return bukkit().getInventory();
	}

	/** Puts the Null armor, sword and shield on this bot. */
	public void equip(int swordSharpness) {
		PlayerInventory inv = inventory();
		ItemStack[] armor = com.waqqyy.totemofnull.ArmorKit.armorSet();
		inv.setHelmet(armor[0]);
		inv.setChestplate(armor[1]);
		inv.setLeggings(armor[2]);
		inv.setBoots(armor[3]);
		inv.setItemInMainHand(com.waqqyy.totemofnull.ArmorKit.sword(swordSharpness));
		inv.setItemInOffHand(com.waqqyy.totemofnull.ArmorKit.shield());
	}

	/** Gives this bot the configured number of golden apples in its hotbar. */
	public void giveGoldenApples(int count) {
		ItemStack apples = new ItemStack(Material.GOLDEN_APPLE, Math.max(0, count));
		inventory().addItem(apples);
	}

	/** True if this player wears a full netherite set with the Flow trim on every piece. */
	public static boolean isExempt(Player player) {
		PlayerInventory inv = player.getInventory();
		ItemStack[] pieces = { inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots() };
		for (ItemStack piece : pieces) {
			if (piece == null || !isFlowTrimmedNetherite(piece)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isFlowTrimmedNetherite(ItemStack piece) {
		if (!(piece.getItemMeta() instanceof ArmorMeta meta)) {
			return false;
		}
		if (!meta.hasTrim()) {
			return false;
		}
		var trim = meta.getTrim();
		return trim != null
				&& trim.getMaterial() == TrimMaterial.NETHERITE
				&& trim.getPattern() == TrimPattern.FLOW;
	}
}
