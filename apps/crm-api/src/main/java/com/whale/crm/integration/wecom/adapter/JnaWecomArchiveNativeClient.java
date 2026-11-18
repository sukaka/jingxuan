package com.whale.crm.integration.wecom.adapter;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class JnaWecomArchiveNativeClient implements WecomArchiveNativeClient {
    private static final int MAX_SLICE_BYTES = 16 * 1024 * 1024;

    @Override
    public NativeSession open(String corpId, String secret, String sdkLibraryPath, String proxy,
                              String proxyPassword, int timeoutSeconds) {
        try {
            NativeLibrary library = Native.load(sdkLibraryPath, NativeLibrary.class);
            Pointer sdk = library.NewSdk();
            if (sdk == null) {
                throw new IllegalStateException("官方归档 SDK 无法创建会话");
            }
            int result = library.Init(sdk, corpId, secret);
            if (result != 0) {
                library.DestroySdk(sdk);
                throw new IllegalStateException("官方归档 SDK 初始化失败，错误码=" + result);
            }
            return new JnaSession(library, sdk, proxy == null ? "" : proxy,
                    proxyPassword == null ? "" : proxyPassword, timeoutSeconds);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception | LinkageError ex) {
            throw new IllegalStateException("官方归档原生库不可用，请检查系统架构与 SDK 配置", ex);
        }
    }

    private static final class JnaSession implements NativeSession {
        private final NativeLibrary library;
        private final Pointer sdk;
        private final String proxy;
        private final String proxyPassword;
        private final int timeoutSeconds;
        private boolean closed;

        private JnaSession(NativeLibrary library, Pointer sdk, String proxy, String proxyPassword, int timeoutSeconds) {
            this.library = library;
            this.sdk = sdk;
            this.proxy = proxy;
            this.proxyPassword = proxyPassword;
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public String pullChatData(long afterSeq, int limit) {
            Pointer slice = library.NewSlice();
            if (slice == null) {
                throw new IllegalStateException("官方归档 SDK 无法分配响应缓冲区");
            }
            try {
                int result = library.GetChatData(sdk, afterSeq, limit, proxy, proxyPassword, timeoutSeconds, slice);
                requireSuccess("拉取会话", result);
                return content(slice);
            } finally {
                library.FreeSlice(slice);
            }
        }

        @Override
        public String decrypt(String randomKey, String encryptedMessage) {
            Pointer slice = library.NewSlice();
            if (slice == null) {
                throw new IllegalStateException("官方归档 SDK 无法分配解密缓冲区");
            }
            try {
                requireSuccess("解密会话", library.DecryptData(randomKey, encryptedMessage, slice));
                return content(slice);
            } finally {
                library.FreeSlice(slice);
            }
        }

        @Override
        public WecomMediaChunk downloadMedia(String sdkFileId, String nextIndex) {
            Pointer media = library.NewMediaData();
            if (media == null) {
                throw new IllegalStateException("官方归档 SDK 无法分配媒体缓冲区");
            }
            try {
                requireSuccess("下载媒体", library.GetMediaData(sdk, nextIndex == null ? "" : nextIndex,
                        sdkFileId, proxy, proxyPassword, timeoutSeconds, media));
                byte[] data = library.GetData(media);
                String next = library.GetOutIndexBuf(media);
                return new WecomMediaChunk(data, next, library.IsMediaDataFinish(media) == 1);
            } finally {
                library.FreeMediaData(media);
            }
        }

        @Override
        public void close() {
            if (!closed) {
                library.DestroySdk(sdk);
                closed = true;
            }
        }

        private String content(Pointer pointer) {
            int length = pointer.getInt(Native.POINTER_SIZE);
            Pointer buffer = pointer.getPointer(0);
            if (length < 0 || length > MAX_SLICE_BYTES || length > 0 && buffer == null) {
                throw new IllegalStateException("官方归档 SDK 返回内容长度无效");
            }
            return length == 0 ? "" : new String(buffer.getByteArray(0, length), StandardCharsets.UTF_8);
        }

        private void requireSuccess(String operation, int result) {
            if (result != 0) {
                throw new IllegalStateException("官方归档 SDK " + operation + "失败，错误码=" + result);
            }
        }
    }

    private interface NativeLibrary extends Library {
        Pointer NewSdk();

        int Init(Pointer sdk, String corpId, String secret);

        int GetChatData(Pointer sdk, long afterSeq, int limit, String proxy, String proxyPassword,
                        int timeoutSeconds, Pointer chatData);

        int DecryptData(String randomKey, String encryptedMessage, Pointer message);

        void DestroySdk(Pointer sdk);

        Pointer NewSlice();

        void FreeSlice(Pointer slice);

        Pointer NewMediaData();

        int GetMediaData(Pointer sdk, String nextIndex, String sdkFileId, String proxy, String proxyPassword,
                         int timeoutSeconds, Pointer mediaData);

        byte[] GetData(Pointer mediaData);

        String GetOutIndexBuf(Pointer mediaData);

        int IsMediaDataFinish(Pointer mediaData);

        void FreeMediaData(Pointer mediaData);
    }
}
