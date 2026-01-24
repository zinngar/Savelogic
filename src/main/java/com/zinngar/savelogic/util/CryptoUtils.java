package com.zinngar.savelogic.util;

import com.zinngar.savelogic.SaveLogic;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.UUID;

public class CryptoUtils {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final String KEY_ALGORITHM = "AES";
    private static final String KEY_FACTORY_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final byte[] SALT = "savelogic-salt".getBytes(StandardCharsets.UTF_8);
    private static final int ITERATION_COUNT = 65536;
    private static final int KEY_LENGTH = 256; // bits

    private static SecretKey secretKey = null;

    private static SecretKey getKey(UUID userId) throws NoSuchAlgorithmException, InvalidKeySpecException {
        if (secretKey == null) {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_FACTORY_ALGORITHM);
            KeySpec spec = new PBEKeySpec(userId.toString().toCharArray(), SALT, ITERATION_COUNT, KEY_LENGTH);
            SecretKey tmp = factory.generateSecret(spec);
            secretKey = new SecretKeySpec(tmp.getEncoded(), KEY_ALGORITHM);
        }
        return secretKey;
    }

    public static String encrypt(String plaintext, UUID userId) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, getKey(userId), gcmParameterSpec);

            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            SaveLogic.LOGGER.error("Failed to encrypt data", e);
            return null;
        }
    }

    public static String decrypt(String base64CipherText, UUID userId) {
        if (base64CipherText == null || base64CipherText.isEmpty()) {
            return base64CipherText;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64CipherText);
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, getKey(userId), gcmParameterSpec);

            byte[] decryptedText = cipher.doFinal(cipherText);
            return new String(decryptedText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // This can happen if the data is not encrypted (e.g., old config)
            // or if decryption fails for any other reason.
            SaveLogic.LOGGER.warn("Failed to decrypt data. Assuming it's plaintext. Error: {}", e.getMessage());
            return base64CipherText; // Return original text if decryption fails
        }
    }
}
