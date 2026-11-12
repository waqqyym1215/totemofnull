package com.waqqyy.totemofnull.bot;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.bukkit.Bukkit;

import java.util.UUID;

/**
 * The shared "Null" identity: a fully black skin plus the hidden name.
 *
 * <p>Both the fake-player minions and the totem holder use this skin. The texture is embedded as a
 * base64 data URI so the plugin needs no network access or external host.</p>
 */
public final class NullSkin {

	/** Base64 of the unsigned texture property JSON for a fully black classic-model skin. */
	private static final String BLACK_SKIN_PROPERTY =
				"eyJ0aW1lc3RhbXAiOjAsInByb2ZpbGVJZCI6IjAwMDAwMDAwLTAwMDAtMDAwMC0wMDAwLTAwMDAwMDAwMDAwMCIsInByb2Zp" +
				"bGVOYW1lIjoibnVsbCIsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiZGF0YTppbWFnZS9wbmc7YmFzZTY0LGlWQk9SdzBL" +
				"R2dvQUFBQU5TVWhFVWdBQUFFQUFBQUJBQ0FZQUFBQ3FhWEhlQUFBQVlrbEVRVlI0MnUzUU1SRUFBQWdBb2U5ZlduTjRNbENB" +
				"cW5sT2dBQUJBZ1FJRUNCQWdBQUJBZ1FJRUNCQWdBQUJBZ1FJRUNCQWdBQUJBZ1FJRUNCQWdBQUJBZ1FJRUNCQWdBQUJBZ1FJ" +
				"RUNCQWdBQUJBZ1FJRUNCQWdBQUJBZ1FJdUc0QldIenc0dUpSUUdvQUFBQUFTVVZPUks1Q1lJST0iLCJtZXRhZGF0YSI6eyJt" +
				"b2RlbCI6ImNsYXNzaWMifX19fQ==";

	private NullSkin() {
	}

	/** The base64 "textures" property value for the fully black skin. */
	public static String blackTextureProperty() {
		return BLACK_SKIN_PROPERTY;
	}

	/** An authlib {@link GameProfile} carrying the black skin, with the given name. */
	public static GameProfile blackGameProfile(String name) {
		// The 2-arg constructor uses PropertyMap.EMPTY, which is immutable; put() would throw
		// UnsupportedOperationException. Build a mutable map and use the 3-arg constructor.
		var properties = new com.mojang.authlib.properties.PropertyMap(
				com.google.common.collect.HashMultimap.create());
		properties.put("textures", new Property("textures", BLACK_SKIN_PROPERTY));
		return new GameProfile(UUID.randomUUID(), name, properties);
	}

	/** A fresh Paper {@link PlayerProfile} carrying the fully black skin. */
	public static com.destroystokyo.paper.profile.PlayerProfile blackProfile(String name) {
		com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(name);
		profile.setProperty(new ProfileProperty("textures", BLACK_SKIN_PROPERTY));
		return profile;
	}

	/**
	 * Applies the black skin to an existing profile, preserving its name and UUID.
	 *
	 * @return the same profile, mutated
	 */
	public static PlayerProfile applyBlackSkin(PlayerProfile profile) {
		profile.setProperty(new ProfileProperty("textures", BLACK_SKIN_PROPERTY));
		return profile;
	}

	/** True if the given NMS profile already carries the black skin. */
	public static boolean isBlackSkin(GameProfile profile) {
		if (profile == null) {
			return false;
		}
		for (var property : profile.properties().get("textures")) {
			if (BLACK_SKIN_PROPERTY.equals(property.value())) {
				return true;
			}
		}
		return false;
	}
}
