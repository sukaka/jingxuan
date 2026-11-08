package com.whale.crm.integration.wecom;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.integration.wecom.application.WecomCallbackCryptoService;
import com.whale.crm.platform.web.BusinessException;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WecomCallbackCryptoServiceTest {
    private static final String CORP = "ww-test-corp";
    private static final String TOKEN = "test-token";
    private static final String AES_KEY = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";
    private final WecomCallbackCryptoService service = new WecomCallbackCryptoService(
            new WecomOfficialProperties(true, CORP, "secret", "http://localhost", TOKEN, AES_KEY,
                    1000, 1000, 100, 10));

    @Test
    void decryptsOfficialEnvelopeAndRejectsBadSignature() throws Exception {
        String encrypted = encrypt("<xml><Event>change_external_contact</Event></xml>");
        String signature = signature("1", "nonce", encrypted);
        assertThat(service.decrypt(signature, "1", "nonce", encrypted)).contains("change_external_contact");
        assertThatThrownBy(() -> service.decrypt("bad", "1", "nonce", encrypted))
                .isInstanceOf(BusinessException.class).hasMessageContaining("签名");
    }

    @Test
    void urlVerificationReturnsPlaintextWithoutEnvelope() throws Exception {
        String encrypted = encrypt("echo-value");
        assertThat(service.verifyUrl(signature("2", "n", encrypted), "2", "n", encrypted)).isEqualTo("echo-value");
    }

    private String encrypt(String message) throws Exception {
        byte[] key = Base64.getDecoder().decode(AES_KEY + "=");
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        byte[] random = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] receive = CORP.getBytes(StandardCharsets.UTF_8);
        ByteBuffer plain = ByteBuffer.allocate(20 + body.length + receive.length);
        plain.put(random).putInt(body.length).put(body).put(receive);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.array()));
    }

    private String signature(String timestamp, String nonce, String encrypted) throws Exception {
        List<String> values = new ArrayList<>(List.of(TOKEN, timestamp, nonce, encrypted));
        Collections.sort(values);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(String.join("", values).getBytes(StandardCharsets.UTF_8)));
    }
}
