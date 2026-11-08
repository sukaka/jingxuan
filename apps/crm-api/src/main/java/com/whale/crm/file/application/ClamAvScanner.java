package com.whale.crm.file.application;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClamAvScanner {
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int MAX_RESPONSE_SIZE = 256;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public ClamAvScanner(String host, int port, int connectTimeoutMs, int readTimeoutMs) {
        this.host = requireLoopback(host);
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    private static String requireLoopback(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("ClamAV 主机不能为空，明文协议仅允许本机地址");
        }
        try {
            if (!InetAddress.getByName(host).isLoopbackAddress()) {
                throw new IllegalArgumentException("ClamAV 明文协议仅允许本机地址，请使用本机扫描或安全代理");
            }
            return host;
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("ClamAV 主机无法解析，明文协议仅允许本机地址", ex);
        }
    }

    public ScanResult scan(Path path) {
        // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket -- ClamAV 原生协议无 TLS，构造器已限制仅本机地址
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM".getBytes(StandardCharsets.US_ASCII));
            output.writeByte(0);
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.writeInt(read);
                    if (read > 0) {
                        output.write(buffer, 0, read);
                    }
                    output.flush();
                }
            }
            output.writeInt(0);
            output.flush();
            String response = readResponse(new DataInputStream(socket.getInputStream()));
            if ("stream: OK".equals(response)) {
                return ScanResult.CLEAN;
            }
            if (response.contains("FOUND")) {
                return ScanResult.FOUND;
            }
            return ScanResult.ERROR;
        } catch (IOException | RuntimeException ex) {
            return ScanResult.ERROR;
        }
    }

    private String readResponse(DataInputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0 || response.size() >= MAX_RESPONSE_SIZE) {
                throw new IOException("Invalid ClamAV response");
            }
            if (next == 0) {
                return response.toString(StandardCharsets.US_ASCII);
            }
            response.write(next);
        }
    }

    public enum ScanResult {
        CLEAN,
        FOUND,
        ERROR
    }
}
