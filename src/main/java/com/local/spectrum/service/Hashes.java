package com.local.spectrum.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class Hashes {
  private Hashes() {
  }

  static String sha256(String prefix, Object value, ObjectMapper objectMapper) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(objectMapper.writeValueAsBytes(value));
      return prefix + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
      throw new IllegalStateException("Deterministic fingerprint unavailable", exception);
    }
  }

  static String sha256Bytes(String prefix, byte[] value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
      return prefix + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  static String canonicalKey(String prefix, String left, String right) {
    int compared = left.compareTo(right);
    String first = compared <= 0 ? left : right;
    String second = compared <= 0 ? right : left;
    return sha256Bytes(prefix, (first + " " + second).getBytes(StandardCharsets.UTF_8));
  }
}
