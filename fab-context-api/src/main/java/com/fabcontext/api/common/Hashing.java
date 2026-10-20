package com.fabcontext.api.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {

	private Hashing() {
	}

	public static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 키 순서를 정렬한 JSON으로 해시해 같은 내용이면 같은 값을 만든다. */
	public static String canonicalSha256(Object value) {
		return "sha256:" + sha256(Json.canonical(value));
	}
}
