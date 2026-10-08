package dev.jetoptimizer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class RecipeCacheHash {
    private RecipeCacheHash() {}

    static String strings(List<String> fields) {
        MessageDigest digest = digest();
        byte[] buffer = new byte[4096];
        fields.forEach(field -> {
            digest.update((byte) (field.length() >>> 24));
            digest.update((byte) (field.length() >>> 16));
            digest.update((byte) (field.length() >>> 8));
            digest.update((byte) field.length());
            // Exact UTF-16 avoids merging unpaired surrogates into UTF-8 replacement characters.
            int offset = 0;
            for (int index = 0; index < field.length(); index++) {
                char value = field.charAt(index);
                buffer[offset++] = (byte) (value >>> 8); buffer[offset++] = (byte) value;
                if (offset == buffer.length) { digest.update(buffer); offset = 0; }
            }
            digest.update(buffer, 0, offset);
        });
        return HexFormat.of().formatHex(digest.digest());
    }

    static String file(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
