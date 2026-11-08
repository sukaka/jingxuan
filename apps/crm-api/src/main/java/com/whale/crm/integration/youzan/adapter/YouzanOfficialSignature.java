package com.whale.crm.integration.youzan.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class YouzanOfficialSignature {
    private YouzanOfficialSignature() {
    }

    public static boolean valid(String clientId, String clientSecret, String rawEntity, String expected) {
        if (clientId == null || clientSecret == null || rawEntity == null || expected == null) {
            return false;
        }
        try {
            // nosemgrep: java.lang.security.audit.crypto.use-of-md5.use-of-md5 -- 有赞官方回调验签协议固定 MD5，不用于口令散列
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] value = digest.digest((clientId + rawEntity + clientSecret).getBytes(StandardCharsets.UTF_8));
            String actual = HexFormat.of().formatHex(value);
            return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
