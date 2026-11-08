package com.whale.crm.platform.security.crypto;

import com.whale.crm.platform.security.CryptoProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 敏感字段加密与盲索引工具（纯单元测试，不依赖容器）。
 */
class AesGcmCipherTest {

    private static final String DEV_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final AesGcmCipher cipher = new AesGcmCipher(new CryptoProperties(DEV_KEY_BASE64, "pepper-a"));
    private final BlindIndex blindIndex = new BlindIndex(new CryptoProperties(DEV_KEY_BASE64, "pepper-a"));

    @Test
    void encryptDecrypt_roundTrips() {
        String plaintext = "13800001234";
        String encrypted = cipher.encrypt(plaintext);
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    void encrypt_usesRandomIvSoCiphertextsDiffer() {
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    void decrypt_tamperedCiphertextFails() {
        String encrypted = cipher.encrypt("secret");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "AAAA";
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decrypt_withDifferentKeyFails() {
        AesGcmCipher other = new AesGcmCipher(new CryptoProperties(
                "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU=", "pepper-a"));
        String encrypted = cipher.encrypt("secret");
        assertThatThrownBy(() -> other.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blindIndex_isStableAndPepperSeparated() {
        assertThat(blindIndex.hash("13800001234")).isEqualTo(blindIndex.hash("13800001234"));
        BlindIndex otherPepper = new BlindIndex(new CryptoProperties(DEV_KEY_BASE64, "pepper-b"));
        assertThat(otherPepper.hash("13800001234")).isNotEqualTo(blindIndex.hash("13800001234"));
        assertThat(blindIndex.hash(null)).isNull();
    }

    @Test
    void invalidKeyMaterial_isRejected() {
        assertThatThrownBy(() -> new AesGcmCipher(new CryptoProperties("!!!not-base64!!!", "p")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmCipher(new CryptoProperties("YWJj", "p")))
                .isInstanceOf(IllegalStateException.class);
    }
}
