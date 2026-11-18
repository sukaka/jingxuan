package com.whale.crm.integration.wecom.adapter;

public interface WecomArchiveNativeClient {
    NativeSession open(String corpId, String secret, String sdkLibraryPath, String proxy, String proxyPassword,
                       int timeoutSeconds);

    interface NativeSession extends AutoCloseable {
        String pullChatData(long afterSeq, int limit);

        String decrypt(String randomKey, String encryptedMessage);

        WecomMediaChunk downloadMedia(String sdkFileId, String nextIndex);

        @Override
        void close();
    }
}
