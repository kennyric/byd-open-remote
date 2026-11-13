package com.overdrive.app.byd.cloud.crypto;

import com.overdrive.app.byd.cloud.CloudLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Protects sensitive credential fields at rest.
 */
public final class CredentialCipher {

    private static final String TAG = "CredentialCipher";
    private static final CloudLog logger = CloudLog.getInstance(TAG);

    private static final String ENC_PREFIX = "ENC:";
    private static final String CIPHER_ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final String KD_SALT = "overdrive-byd-cred-v1";
    private static final String DID_PATH = new File(System.getProperty("java.io.tmpdir", "/tmp"), ".byd_device_id").getAbsolutePath();

    private CredentialCipher() {}

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    /**
     * Encrypt a plaintext credential for storage.
     * Returns the original value if encryption fails.
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        if (isEncrypted(plaintext)) return plaintext;

        try {
            byte[] key = deriveKey(/*includeFingerprint=*/false);
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));

            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);

            return ENC_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            logger.warn("Credential protection failed: " + e.getMessage());
            return plaintext;
        }
    }

    /**
     * Decrypt a stored credential.
     * Values without the encrypted marker are returned as-is (legacy support).
     * Returns empty string on decryption failure (forces re-setup).
     */
    public static String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return stored;
        if (!isEncrypted(stored)) return stored;

        final byte[] combined;
        try {
            combined = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
        } catch (Exception e) {
            logger.error("Credential recovery failed (base64): " + e.getMessage());
            return "";
        }

        if (combined.length < IV_LEN + 1) return "";

        byte[] iv = new byte[IV_LEN];
        System.arraycopy(combined, 0, iv, 0, IV_LEN);
        byte[] ct = new byte[combined.length - IV_LEN];
        System.arraycopy(combined, IV_LEN, ct, 0, ct.length);

        String plain = tryDecrypt(iv, ct, /*includeFingerprint=*/false);
        if (plain != null) return plain;
        plain = tryDecrypt(iv, ct, /*includeFingerprint=*/true);
        if (plain != null) return plain;

        logger.error("Credential recovery failed: no key could decrypt");
        return "";
    }

    private static String tryDecrypt(byte[] iv, byte[] ct, boolean includeFingerprint) {
        try {
            byte[] key = deriveKey(includeFingerprint);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGO);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean needsStableReEncrypt(String stored) {
        if (!isEncrypted(stored)) return false;
        final byte[] combined;
        try {
            combined = Base64.getDecoder().decode(stored.substring(ENC_PREFIX.length()));
        } catch (Exception e) {
            return false;
        }
        if (combined.length < IV_LEN + 1) return false;
        byte[] iv = new byte[IV_LEN];
        System.arraycopy(combined, 0, iv, 0, IV_LEN);
        byte[] ct = new byte[combined.length - IV_LEN];
        System.arraycopy(combined, IV_LEN, ct, 0, ct.length);
        return tryDecrypt(iv, ct, false) == null && tryDecrypt(iv, ct, true) != null;
    }

    public static String upgradeToStableOrNull(String stored) {
        if (!needsStableReEncrypt(stored)) return null;
        String plain = decrypt(stored);
        if (plain == null || plain.isEmpty()) return null;
        String reEncrypted = encrypt(plain);
        if (!isEncrypted(reEncrypted)) return null;
        if (!plain.equals(decrypt(reEncrypted))) return null;
        return reEncrypted;
    }

    private static byte[] deriveKey(boolean includeFingerprint) throws Exception {
        String did = readDid();
        if (did == null) {
            throw new IllegalStateException("device id unavailable at " + DID_PATH);
        }
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        String material = KD_SALT + ":" + did;
        if (includeFingerprint) {
            String bp;
            try {
                bp = android.os.Build.FINGERPRINT;
            } catch (Throwable e) {
                bp = "unknown";
            }
            material = material + ":" + bp;
        }
        return d.digest(material.getBytes(StandardCharsets.UTF_8));
    }

    private static synchronized String readDid() {
        try {
            File f = new File(DID_PATH);
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String id = r.readLine();
                    if (id != null && !id.trim().isEmpty()) return id.trim();
                }
            }
            // Seed a new device ID if missing
            String newId = UUID.randomUUID().toString();
            try (FileWriter w = new FileWriter(f)) {
                w.write(newId);
            }
            return newId;
        } catch (Exception ignored) {
            return "byd_companion_device_id";
        }
    }
}
