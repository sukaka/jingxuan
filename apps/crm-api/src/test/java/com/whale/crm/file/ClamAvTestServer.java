package com.whale.crm.file;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ClamAvTestServer implements AutoCloseable {
    private static final int MAX_TEST_CHUNK_BYTES = 16 * 1024 * 1024;

    private final ServerSocket serverSocket;
    private final String response;
    private final CompletableFuture<Receipt> receipt = new CompletableFuture<>();
    private final Thread worker;

    ClamAvTestServer(String response) throws IOException {
        this.response = response;
        this.serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        this.serverSocket.setSoTimeout(5000);
        this.worker = new Thread(this::serve, "clamav-test-server");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    String host() {
        return serverSocket.getInetAddress().getHostAddress();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    Receipt awaitReceipt() throws Exception {
        try {
            return receipt.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw ex;
        } catch (TimeoutException ex) {
            throw new AssertionError("ClamAV test server did not receive a complete INSTREAM request", ex);
        }
    }

    @Override
    public void close() throws Exception {
        serverSocket.close();
        worker.join(Duration.ofSeconds(5));
    }

    private void serve() {
        try (Socket socket = serverSocket.accept()) {
            socket.setSoTimeout(5000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            String command = readCommand(input);
            List<Integer> chunkSizes = new ArrayList<>();
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            while (true) {
                int size = input.readInt();
                if (size < 0 || size > MAX_TEST_CHUNK_BYTES) {
                    throw new IOException("Invalid test chunk size: " + size);
                }
                if (size == 0) {
                    break;
                }
                chunkSizes.add(size);
                byte[] chunk = input.readNBytes(size);
                if (chunk.length != size) {
                    throw new EOFException("Chunk length does not match its INSTREAM header");
                }
                content.writeBytes(chunk);
            }
            socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            receipt.complete(new Receipt(command, List.copyOf(chunkSizes), content.toByteArray()));
        } catch (Throwable ex) {
            receipt.completeExceptionally(ex);
        }
    }

    private static String readCommand(DataInputStream input) throws IOException {
        ByteArrayOutputStream command = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next < 0) {
                throw new EOFException("Missing NUL after ClamAV command");
            }
            if (next == 0) {
                return command.toString(StandardCharsets.US_ASCII);
            }
            command.write(next);
            if (command.size() > 64) {
                throw new IOException("ClamAV command is too long");
            }
        }
    }

    record Receipt(String command, List<Integer> chunkSizes, byte[] content) {
    }
}
