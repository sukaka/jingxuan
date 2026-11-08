package com.whale.crm.integration.wecom.application;

import com.whale.crm.integration.wecom.adapter.WecomOfficialProperties;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

@Service
public class WecomCallbackCryptoService {
    private final WecomOfficialProperties properties;

    public WecomCallbackCryptoService(WecomOfficialProperties properties) { this.properties = properties; }

    public String decrypt(String signature, String timestamp, String nonce, String encrypted) {
        requireConfigured();
        verifySignature(signature, timestamp, nonce, encrypted);
        try {
            byte[] key = Base64.getDecoder().decode(properties.callbackAesKey() + "=");
            // nosemgrep: java.lang.security.audit.cbc-padding-oracle.cbc-padding-oracle -- 企微官方回调协议固定 CBC，先验签且统一拒绝解密异常
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            if (plain.length < 20) {
                throw new IllegalArgumentException();
            }
            int length = ByteBuffer.wrap(plain, 16, 4).getInt();
            if (length < 0 || 20 + length > plain.length) {
                throw new IllegalArgumentException();
            }
            String message = new String(plain, 20, length, StandardCharsets.UTF_8);
            String receiveId = new String(plain, 20 + length, plain.length - 20 - length, StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(receiveId.getBytes(StandardCharsets.UTF_8),
                    properties.corpId().getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException();
            }
            return message;
        } catch (GeneralSecurityException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "企微回调解密或企业校验失败");
        }
    }

    public String verifyUrl(String signature, String timestamp, String nonce, String echostr) {
        return decrypt(signature, timestamp, nonce, echostr);
    }

    public String encryptedElement(String xml) {
        return element(xml, "Encrypt");
    }

    public String element(String xml, String name) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = document.getDocumentElement();
            var nodes = root.getElementsByTagName(name);
            return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "企微回调XML格式无效");
        }
    }

    private void verifySignature(String signature, String timestamp, String nonce, String encrypted) {
        try {
            List<String> values = new ArrayList<>(List.of(properties.callbackToken(), timestamp, nonce, encrypted));
            Collections.sort(values);
            // nosemgrep: java.lang.security.audit.crypto.use-of-sha1.use-of-sha1 -- 企微官方回调签名协议固定 SHA-1，不用于口令散列
            String actual = hex(MessageDigest.getInstance("SHA-1").digest(String.join("", values).getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "企微回调签名无效");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "企微回调签名无效");
        }
    }

    private void requireConfigured() {
        if (properties.callbackToken() == null || properties.callbackToken().isBlank()
                || properties.callbackAesKey() == null || !properties.callbackAesKey().matches("[A-Za-z0-9]{43}")
                || properties.corpId() == null || properties.corpId().isBlank()) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "企微回调参数未配置");
        }
    }

    private String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte b : value) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
