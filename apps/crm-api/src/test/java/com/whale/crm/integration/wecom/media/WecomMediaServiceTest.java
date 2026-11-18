package com.whale.crm.integration.wecom.media;

import com.whale.crm.file.infrastructure.PrivateObjectStorage;
import com.whale.crm.integration.wecom.adapter.WecomArchiveNativeClient;
import com.whale.crm.integration.wecom.adapter.WecomArchiveOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomMediaChunk;
import com.whale.crm.integration.wecom.application.WecomArchiveService;
import com.whale.crm.integration.wecom.media.adapter.WecomMediaProperties;
import com.whale.crm.integration.wecom.media.application.WecomMediaService;
import com.whale.crm.integration.wecom.media.domain.WecomMediaAsset;
import com.whale.crm.integration.wecom.media.domain.WecomMediaRepository;
import com.whale.crm.platform.audit.application.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WecomMediaServiceTest {
    private WecomMediaRepository repository;
    private WecomArchiveNativeClient nativeClient;
    private WecomArchiveNativeClient.NativeSession session;
    private PrivateObjectStorage storage;
    private WecomMediaService service;
    private WecomMediaAsset asset;
    private final AtomicReference<byte[]> storedContent = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        repository = mock(WecomMediaRepository.class);
        nativeClient = mock(WecomArchiveNativeClient.class);
        session = mock(WecomArchiveNativeClient.NativeSession.class);
        storage = mock(PrivateObjectStorage.class);
        asset = new WecomMediaAsset(9L, 1L, 8L, 0, "sdk-file", "file", md5("hello world"),
                11L, "test.txt", "text/plain", "DOWNLOADING", null, null, null,
                null, 1, null, "worker", null, null, null, 1);
        when(repository.findById(1L, 9L)).thenReturn(java.util.Optional.of(asset));
        when(nativeClient.open(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenReturn(session);
        when(repository.markStored(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyLong(), any())).thenReturn(1);
        ObjectProvider<PrivateObjectStorage> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(storage);
        WecomArchiveOfficialProperties archive = new WecomArchiveOfficialProperties(1L, "corp", "synthetic",
                "sdk", "keys", "", "", 15);
        WecomMediaProperties properties = new WecomMediaProperties(true, 10, 30000,
                5, 1024 * 1024, 10, 0);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new WecomMediaService(repository, nativeClient, archive, properties, provider,
                mock(WecomArchiveService.class), mock(AuditService.class), manager);
        storedContent.set(null);
    }

    @Test
    void downloadsMultipleChunksVerifiesIntegrityAndStoresPrivateObject() {
        when(session.downloadMedia("sdk-file", "")).thenReturn(new WecomMediaChunk("hello ".getBytes(), "next-1", false));
        when(session.downloadMedia("sdk-file", "next-1")).thenReturn(new WecomMediaChunk("world".getBytes(), "", true));

        service.download(1L, 9L, "worker");

        verify(storage).putObject(org.mockito.ArgumentMatchers.eq("wecom-media/1/8/9"), any(byte[].class),
                org.mockito.ArgumentMatchers.eq("text/plain"));
        verify(repository).markStored(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.eq("worker"),
                org.mockito.ArgumentMatchers.eq("wecom-media/1/8/9"), org.mockito.ArgumentMatchers.eq(sha256("hello world")),
                org.mockito.ArgumentMatchers.eq(11L), any());
        verify(session).close();
    }

    @Test
    void md5MismatchNeverStoresOrMarksAssetAvailable() {
        asset = new WecomMediaAsset(9L, 1L, 8L, 0, "sdk-file", "file", md5("different"),
                11L, "test.txt", "text/plain", "DOWNLOADING", null, null, null,
                null, 1, null, "worker", null, null, null, 1);
        when(repository.findById(1L, 9L)).thenReturn(java.util.Optional.of(asset));
        when(session.downloadMedia("sdk-file", "")).thenReturn(new WecomMediaChunk("hello world".getBytes(), "", true));

        assertThatThrownBy(() -> service.download(1L, 9L, "worker")).hasMessageContaining("MD5");
        verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
        verify(repository, never()).markStored(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(),
                anyLong(), any());
        verify(session).close();
    }

    @Test
    void repeatedChunkIndexFailsInsteadOfLoopingForever() {
        when(session.downloadMedia(anyString(), anyString()))
                .thenReturn(new WecomMediaChunk("hello".getBytes(), "same", false));

        assertThatThrownBy(() -> service.download(1L, 9L, "worker")).hasMessageContaining("游标未推进");
        verify(storage, never()).putObject(anyString(), any(byte[].class), anyString());
        verify(session).close();
    }

    private static String md5(String value) {
        return digest("MD5", value);
    }

    private static String sha256(String value) {
        return digest("SHA-256", value);
    }

    private static String digest(String algorithm, String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value.getBytes()));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
