package com.whale.crm.integration.wecom.adapter;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.whale.crm.integration.wecom.adapter.JnaWecomArchiveNativeClient.NativeLibrary;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JnaWecomArchiveNativeClientTest {

    @Test
    void nativeLibraryDeclaresPointerGetDataAndIntGetDataLenWithoutArrayReturns() throws Exception {
        for (Method method : NativeLibrary.class.getDeclaredMethods()) {
            assertThat(method.getReturnType().isArray())
                    .as("JNA NativeLibrary method %s must not return array", method.getName())
                    .isFalse();
        }
        assertThat(NativeLibrary.class.getDeclaredMethod("GetData", Pointer.class).getReturnType())
                .isEqualTo(Pointer.class);
        assertThat(NativeLibrary.class.getDeclaredMethod("GetDataLen", Pointer.class).getReturnType())
                .isEqualTo(int.class);
    }

    @Test
    void downloadMediaReadsExactByteLengthAndFreesMediaAndSdk() {
        NativeLibrary library = mock(NativeLibrary.class);
        Pointer sdk = Pointer.createConstant(101L);
        Pointer media = Pointer.createConstant(202L);
        byte[] expected = "hello-wecom-media".getBytes(StandardCharsets.UTF_8);
        Memory buffer = new Memory(expected.length);
        buffer.write(0, expected, 0, expected.length);

        when(library.NewSdk()).thenReturn(sdk);
        when(library.Init(sdk, "corp", "secret")).thenReturn(0);
        when(library.NewMediaData()).thenReturn(media);
        when(library.GetMediaData(eq(sdk), eq(""), eq("sdk-file-1"), eq(""), eq(""), eq(15), eq(media))).thenReturn(0);
        when(library.GetDataLen(media)).thenReturn(expected.length);
        when(library.GetData(media)).thenReturn(buffer);
        when(library.GetOutIndexBuf(media)).thenReturn("next-idx");
        when(library.IsMediaDataFinish(media)).thenReturn(1);

        JnaWecomArchiveNativeClient client = new JnaWecomArchiveNativeClient(path -> library);
        try (WecomArchiveNativeClient.NativeSession session = client.open("corp", "secret", "/sdk/lib.so", "", "", 15)) {
            WecomMediaChunk chunk = session.downloadMedia("sdk-file-1", "");
            assertThat(chunk.data()).isEqualTo(expected);
            assertThat(chunk.nextIndex()).isEqualTo("next-idx");
            assertThat(chunk.finished()).isTrue();
        }

        verify(library).FreeMediaData(media);
        verify(library, times(1)).DestroySdk(sdk);
    }

    @Test
    void downloadMediaHandlesZeroLengthChunkSafely() {
        NativeLibrary library = mock(NativeLibrary.class);
        Pointer sdk = Pointer.createConstant(101L);
        Pointer media = Pointer.createConstant(202L);
        when(library.NewSdk()).thenReturn(sdk);
        when(library.Init(sdk, "corp", "secret")).thenReturn(0);
        when(library.NewMediaData()).thenReturn(media);
        when(library.GetMediaData(eq(sdk), anyString(), anyString(), anyString(), anyString(), anyInt(), eq(media)))
                .thenReturn(0);
        when(library.GetDataLen(media)).thenReturn(0);
        when(library.GetData(media)).thenReturn(null);
        when(library.GetOutIndexBuf(media)).thenReturn("");
        when(library.IsMediaDataFinish(media)).thenReturn(1);

        JnaWecomArchiveNativeClient client = new JnaWecomArchiveNativeClient(path -> library);
        try (WecomArchiveNativeClient.NativeSession session = client.open("corp", "secret", "/sdk/lib.so", "", "", 15)) {
            WecomMediaChunk chunk = session.downloadMedia("sdk-file-empty", null);
            assertThat(chunk.data()).isEmpty();
            assertThat(chunk.finished()).isTrue();
        }
        verify(library).FreeMediaData(media);
    }

    @Test
    void downloadMediaRejectsInvalidLengthsNullNonEmptyBufferAndSdkErrorsWhileAlwaysFreeing() {
        NativeLibrary library = mock(NativeLibrary.class);
        Pointer sdk = Pointer.createConstant(101L);
        Pointer media = Pointer.createConstant(202L);
        when(library.NewSdk()).thenReturn(sdk);
        when(library.Init(sdk, "corp", "secret")).thenReturn(0);
        when(library.NewMediaData()).thenReturn(media);
        when(library.GetMediaData(eq(sdk), anyString(), anyString(), anyString(), anyString(), anyInt(), eq(media)))
                .thenReturn(0);

        JnaWecomArchiveNativeClient client = new JnaWecomArchiveNativeClient(path -> library);
        try (WecomArchiveNativeClient.NativeSession session = client.open("corp", "secret", "/sdk/lib.so", "", "", 15)) {
            when(library.GetDataLen(media)).thenReturn(-1);
            when(library.GetData(media)).thenReturn(Pointer.createConstant(1L));
            assertThatThrownBy(() -> session.downloadMedia("sdk-neg", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("长度无效");

            when(library.GetDataLen(media)).thenReturn(JnaWecomArchiveNativeClient.MAX_SLICE_BYTES + 1);
            assertThatThrownBy(() -> session.downloadMedia("sdk-over", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("长度无效");

            when(library.GetDataLen(media)).thenReturn(8);
            when(library.GetData(media)).thenReturn(null);
            assertThatThrownBy(() -> session.downloadMedia("sdk-null-ptr", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("长度无效");

            when(library.GetMediaData(eq(sdk), anyString(), anyString(), anyString(), anyString(), anyInt(), eq(media)))
                    .thenReturn(-10002);
            assertThatThrownBy(() -> session.downloadMedia("sdk-err", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("-10002");
        }
        verify(library, times(4)).FreeMediaData(media);
        verify(library, times(1)).DestroySdk(sdk);
    }
}
