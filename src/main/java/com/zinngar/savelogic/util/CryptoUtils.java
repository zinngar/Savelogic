package com.zinngar.savelogic.util;

import com.zinngar.savelogic.SaveLogic;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Keeps the token out of plain sight in savelogic.json. The key is a random value stored in
 * config/savelogic.key, so this protects against casually sharing or screenshotting the config,
 * not against someone who can read every file on the computer.
 */
public class CryptoUtils {

    private static final String PREFIX = "enc:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int KEY_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static SecretKey secretKey;

    private static synchronized SecretKey getKey() throws IOException {
        if (secretKey == null) {
            Path keyFile = FabricLoader.getInstance().getConfigDir().resolve("savelogic.key");
            byte[] key;
            if (Files.exists(keyFile)) {
                key = Base64.getDecoder().decode(Files.readString(keyFile).trim());
            } else {
                key = new byte[KEY_BYTES];
                RANDOM.nextBytes(key);
                Files.createDirectories(keyFile.getParent());
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
            }
            secretKey = new SecretKeySpec(key, "AES");
        }
        return secretKey;
    }

    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length);
            buffer.put(iv).put(cipherText);
            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            SaveLogic.LOGGER.error("Failed to encrypt token; it will not be saved", e);
            return "";
        }
    }

    /** Values without the "enc:" prefix are treated as plain text, so a token can be pasted straight into the file. */
    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(stored.substring(PREFIX.length())));
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SaveLogic.LOGGER.error("Could not decrypt the saved GitHub token; please enter it again in SaveLogic settings", e);
            return "";
        }
    }
}
