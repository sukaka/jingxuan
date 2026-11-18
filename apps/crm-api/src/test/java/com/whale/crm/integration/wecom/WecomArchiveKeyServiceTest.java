package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.adapter.WecomArchiveKeyService;
import com.whale.crm.integration.wecom.adapter.WecomArchiveOfficialProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WecomArchiveKeyServiceTest {
    @TempDir
    Path directory;

    @Test
    void decryptsOfficialPkcs1RandomKeyWithPkcs8PrivateKey() throws Exception {
        java.security.KeyPair pair = keyPair();
        writePem(3, "PRIVATE KEY", pair.getPrivate().getEncoded());
        WecomArchiveKeyService service = service();

        String encrypted = encrypt(pair, "synthetic-random-key");
        assertThat(service.decryptRandomKey(encrypted, 3)).isEqualTo("synthetic-random-key");
        assertThatThrownBy(() -> service.decryptRandomKey(encrypted, 4)).hasMessageContaining("私钥不可用");
    }

    @Test
    void supportsOfficialPkcs1PrivateKeyWithoutLoggingKeyMaterial() throws Exception {
        java.security.KeyPair pair = keyPair();
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) pair.getPrivate();
        org.bouncycastle.asn1.pkcs.RSAPrivateKey encoded = new org.bouncycastle.asn1.pkcs.RSAPrivateKey(
                key.getModulus(), key.getPublicExponent(), key.getPrivateExponent(), key.getPrimeP(), key.getPrimeQ(),
                key.getPrimeExponentP(), key.getPrimeExponentQ(), key.getCrtCoefficient());
        writePem(7, "RSA PRIVATE KEY", encoded.getEncoded());
        WecomArchiveKeyService service = service();

        String encrypted = encrypt(pair, "another-synthetic-key");
        assertThat(service.decryptRandomKey(encrypted, 7)).isEqualTo("another-synthetic-key");
    }

    private WecomArchiveKeyService service() {
        return new WecomArchiveKeyService(new WecomArchiveOfficialProperties(1L, "corp", "synthetic-secret",
                "sdk", directory.toString(), "", "", 15));
    }

    private java.security.KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String encrypt(java.security.KeyPair pair, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pair.getPublic());
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void writePem(int version, String type, byte[] content) throws Exception {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(content);
        Files.writeString(directory.resolve("private-" + version + ".pem"),
                "-----BEGIN " + type + "-----\n" + encoded + "\n-----END " + type + "-----\n", StandardCharsets.UTF_8);
    }
}
