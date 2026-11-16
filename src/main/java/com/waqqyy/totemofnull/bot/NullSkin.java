package com.waqqyy.totemofnull.bot;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.util.UUID;

/**
 * The shared "Null" skin: fully black, used by the fake-player minions and the totem holder.
 *
 * <p>The client only shows a player skin whose textures property is signed by Mojang and points
 * at textures.minecraft.net, so this is a real signed property for an all-black 64x64 skin
 * (every pixel is opaque 0,0,0, both layers). The signature does not expire.</p>
 */
public final class NullSkin {

	/** Texture: http://textures.minecraft.net/texture/eaccb07f0536dd817c1525a3a3f97e428095c9b298460bc7a3a3e1b8b9fc71fa */
	private static final String BLACK_SKIN_VALUE =
			"ewogICJ0aW1lc3RhbXAiIDogMTYyMzAzMjA4MTQ3NiwKICAicHJvZmlsZUlkIiA6ICJkZGVkNTZlMWVmOGI0MGZlOGFkMTYy" +
			"OTIwZjdhZWNkYSIsCiAgInByb2ZpbGVOYW1lIiA6ICJEaXNjb3JkQXBwIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1" +
			"ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNy" +
			"YWZ0Lm5ldC90ZXh0dXJlL2VhY2NiMDdmMDUzNmRkODE3YzE1MjVhM2EzZjk3ZTQyODA5NWM5YjI5ODQ2MGJjN2EzYTNlMWI4" +
			"YjlmYzcxZmEiCiAgICB9CiAgfQp9";

	private static final String BLACK_SKIN_SIGNATURE =
			"mxwj4Y2K3DI5cF+VNSWSntMa13cXV6SbuAVMgpsY2zrc3UWJrd9Mxy1qYefNfHL1bJMDrwZoyyTrlU3EDisoGPd1xckfOXIK" +
			"ThDQQVLHvhEqYjj5AAZY/9ljh0cxhDGMq/eT4Xs6vMH25jD/dCgGibcgvzeKoqk41j1v4qZXsn5tCSzXJLnVK/GjdjUSOnRs" +
			"/PIjERRhuq0We9fmOEBzKA3FVHk6O1g+iIsSYDJaw2XIALcYOQlz4p0PYqctFaEGEPeM5TlZDgrSVdTEjYlENq4ktp7yIBAR" +
			"Kh0DS5x/lnM1PY0UJ7JNLqvGczi5SyzlcoEhfqWInz97k/YHSPSMtXq3Ny57qCBnq34mXm4PBXjCjIbBo0YkL62CWHrF3HfF" +
			"lTtwzkX/Ck9ehYZphXZX3d8ZS1rn+nxn/cnRa4bX8x1c+3vemZtUQpDPHfHho23qG22QtGv8kvnE+Si6WioxAkbGDx1uK0Ua" +
			"fxeuV3KzqGX4P34InwMMB+DUxJR1ddEtPjbizCLtzCkomjXHBrhfA+TaoKzE2WAsUKKEgV17IV0YOGrkM1IOElmlyFKBCny/" +
			"6Pbq473lkZH4kGRjvhLjpKeHlRuicUK4d1x/mb1Wn5rJnfOH36lUXZwrQcp4DZFnbrfoisoetelV0XA9l5FHBO86KYBnvZS9" +
			"hJcM37Z28B4=";

	private NullSkin() {
	}

	/** The signed "textures" property for the black skin, as a Paper profile property. */
	public static ProfileProperty blackProfileProperty() {
		return new ProfileProperty("textures", BLACK_SKIN_VALUE, BLACK_SKIN_SIGNATURE);
	}

	/** An authlib {@link GameProfile} carrying the black skin, with the given name. */
	public static GameProfile blackGameProfile(String name) {
		// PropertyMap copies whatever it is given into an immutable multimap, so the property must
		// be added to the backing multimap BEFORE wrapping it. Putting it on the PropertyMap
		// afterwards (or using the 2-arg GameProfile constructor, which uses PropertyMap.EMPTY)
		// throws UnsupportedOperationException.
		var backing = com.google.common.collect.HashMultimap.<String, Property>create();
		backing.put("textures", new Property("textures", BLACK_SKIN_VALUE, BLACK_SKIN_SIGNATURE));
		return new GameProfile(UUID.randomUUID(), name, new PropertyMap(backing));
	}

	/** True if the given NMS profile carries the black skin. */
	public static boolean isBlackSkin(GameProfile profile) {
		if (profile == null) {
			return false;
		}
		for (var property : profile.properties().get("textures")) {
			if (BLACK_SKIN_VALUE.equals(property.value())) {
				return true;
			}
		}
		return false;
	}
}
