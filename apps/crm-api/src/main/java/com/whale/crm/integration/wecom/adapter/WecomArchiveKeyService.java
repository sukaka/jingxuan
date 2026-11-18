package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WecomArchiveKeyService {
    private static final int MAX_KEY_BYTES = 128 * 1024;
    private final WecomArchiveOfficialProperties properties;
    private final Map<Integer, PrivateKey> keys = new ConcurrentHashMap<>();

    public WecomArchiveKeyService(WecomArchiveOfficialProperties properties) {
        this.properties = properties;
    }

    public String decryptRandomKey(String encryptedRandomKey, int publicKeyVersion) {
        try {
            // 企微官方仅对单次随机密钥使用 RSA PKCS1；此处不是通用 ECB 块加密。
            // nosemgrep: java.lang.security.audit.crypto.ecb-cipher.ecb-cipher
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey(publicKeyVersion));
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedRandomKey.strip()));
            String value = new String(decrypted, StandardCharsets.UTF_8).strip();
            if (value.isEmpty()) {
                throw new IllegalStateException("归档随机密钥为空");
            }
            return value;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("归档随机密钥解密失败，请检查公钥版本与私钥是否匹配", ex);
        }
    }

    private PrivateKey privateKey(int version) {
        return keys.computeIfAbsent(version, this::loadPrivateKey);
    }

    private PrivateKey loadPrivateKey(int version) {
        try {
            if (properties.privateKeyDirectory() == null || properties.privateKeyDirectory().isBlank()) {
                throw unavailable();
            }
            Path directory = Path.of(properties.privateKeyDirectory()).toAbsolutePath().normalize();
            Path path = directory.resolve("private-" + version + ".pem").normalize();
            if (!path.startsWith(directory) || !Files.isRegularFile(path) || Files.size(path) > MAX_KEY_BYTES) {
                throw unavailable();
            }
            try (var reader = new PemReader(new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {
                PemObject pem = reader.readPemObject();
                if (pem == null) {
                    throw unavailable();
                }
                if ("PRIVATE KEY".equals(pem.getType())) {
                    PrivateKeyInfo info = PrivateKeyInfo.getInstance(pem.getContent());
                    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(info.getEncoded()));
                }
                if (!"RSA PRIVATE KEY".equals(pem.getType())) {
                    throw unavailable();
                }
                org.bouncycastle.asn1.pkcs.RSAPrivateKey key = org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(pem.getContent());
                if (key.getModulus().bitLength() < 2048) {
                    throw new IllegalStateException("归档 RSA 私钥长度至少为 2048 位");
                }
                return KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(key.getModulus(),
                        key.getPublicExponent(), key.getPrivateExponent(), key.getPrime1(), key.getPrime2(),
                        key.getExponent1(), key.getExponent2(), key.getCoefficient()));
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("归档私钥读取失败", ex);
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "对应公钥版本的归档 RSA 私钥不可用");
    }
}
