package com.fabcontext.api.common;

import java.security.SecureRandom;

/** prefix_xxxxxxxxxxxx 형태의 불투명 ID 생성기 */
public final class Ids {

	private static final char[] ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();

	private Ids() {
	}

	public static String next(String prefix) {
		char[] buf = new char[12];
		for (int i = 0; i < buf.length; i++) {
			buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
		}
		return prefix + "_" + new String(buf);
	}
}
