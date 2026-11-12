package com.waqqyy.totemofnull;

import java.security.SecureRandom;
import java.util.Set;

/**
 * Generates bot names like {@code Vj2j34lkBJEKkls}: mixed-case letters plus digits.
 */
public final class NameGenerator {

	private NameGenerator() {
	}

	private static final String ALPHABET =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
	private static final SecureRandom RANDOM = new SecureRandom();

	/**
	 * @param length desired name length
	 * @param taken  names already in use (case-insensitive), never returned
	 */
	public static String uniqueName(int length, Set<String> taken) {
		StringBuilder name = new StringBuilder(length);
		while (true) {
			name.setLength(0);
			for (int i = 0; i < length; i++) {
				name.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
			}
			String candidate = name.toString();
			if (!taken.contains(candidate.toLowerCase())) {
				return candidate;
			}
		}
	}
}
