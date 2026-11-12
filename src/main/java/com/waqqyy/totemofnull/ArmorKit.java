package com.waqqyy.totemofnull;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

/**
 * Builds the "Null" armor/weapon kit: netherite with the Flow trim,
 * Protection IV, Mending, Unbreaking III and Curse of Vanishing.
 */
public final class ArmorKit {

	private ArmorKit() {
	}

	private static final TrimPattern FLOW = TrimPattern.FLOW;
	private static final TrimMaterial TRIM_MATERIAL = TrimMaterial.NETHERITE;

	/** The four armor pieces, in helmet/chestplate/leggings/boots order. */
	public static ItemStack[] armorSet() {
		return new ItemStack[] { helmet(), chestplate(), leggings(), boots() };
	}

	public static ItemStack helmet() {
		return armor(Material.NETHERITE_HELMET);
	}

	public static ItemStack chestplate() {
		return armor(Material.NETHERITE_CHESTPLATE);
	}

	public static ItemStack leggings() {
		return armor(Material.NETHERITE_LEGGINGS);
	}

	public static ItemStack boots() {
		return armor(Material.NETHERITE_BOOTS);
	}

	private static ItemStack armor(Material material) {
		ItemStack stack = new ItemStack(material);
		ArmorMeta meta = (ArmorMeta) stack.getItemMeta();
		meta.setTrim(new ArmorTrim(TRIM_MATERIAL, FLOW));
		meta.addEnchant(Enchantment.PROTECTION, 4, true);
		meta.addEnchant(Enchantment.UNBREAKING, 3, true);
		meta.addEnchant(Enchantment.MENDING, 1, true);
		meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true);
		stack.setItemMeta(meta);
		return stack;
	}

	/** Netherite sword: Sharpness V, Unbreaking III, Curse of Vanishing. */
	public static ItemStack sword(int sharpnessLevel) {
		ItemStack stack = new ItemStack(Material.NETHERITE_SWORD);
		var meta = stack.getItemMeta();
		if (sharpnessLevel > 0) {
			meta.addEnchant(Enchantment.SHARPNESS, sharpnessLevel, true);
		}
		meta.addEnchant(Enchantment.UNBREAKING, 3, true);
		meta.addEnchant(Enchantment.VANISHING_CURSE, 1, true);
		stack.setItemMeta(meta);
		return stack;
	}

	/** Shield: Unbreaking III. */
	public static ItemStack shield() {
		ItemStack stack = new ItemStack(Material.SHIELD);
		var meta = stack.getItemMeta();
		meta.addEnchant(Enchantment.UNBREAKING, 3, true);
		stack.setItemMeta(meta);
		return stack;
	}
}
