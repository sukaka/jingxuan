package com.whale.crm.integration.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.wecom.adapter.OfficialWecomArchiveAdapter;
import com.whale.crm.integration.wecom.adapter.WecomArchiveKeyService;
import com.whale.crm.integration.wecom.adapter.WecomArchiveNativeClient;
import com.whale.crm.integration.wecom.adapter.WecomArchiveOfficialProperties;
import com.whale.crm.integration.wecom.adapter.WecomArchivePage;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomStaffAccount;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficialWecomArchiveAdapterTest {
    private WecomArchiveNativeClient nativeClient;
    private WecomArchiveNativeClient.NativeSession session;
    private WecomArchiveKeyService keyService;
    private WecomContactSyncRepository contacts;
    private OfficialWecomArchiveAdapter adapter;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        nativeClient = mock(WecomArchiveNativeClient.class);
        session = mock(WecomArchiveNativeClient.NativeSession.class);
        keyService = mock(WecomArchiveKeyService.class);
        contacts = mock(WecomContactSyncRepository.class);
        WecomArchiveOfficialProperties properties = new WecomArchiveOfficialProperties(1L, "ww-test", "synthetic-secret",
                "/sdk/lib.so", "/keys", "", "", 15);
        adapter = new OfficialWecomArchiveAdapter(properties, nativeClient, keyService, contacts, mapper);
        when(nativeClient.open(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenReturn(session);
        when(keyService.decryptRandomKey(anyString(), org.mockito.ArgumentMatchers.eq(3))).thenReturn("synthetic-aes-key");
    }

    @Test
    void decryptsBothDirectionsAndMapsSingleChatIdentity() throws Exception {
        String first = plaintext("msg-1", "CUSTOMER");
        String second = plaintext("msg-2", "EMPLOYEE");
        when(session.pullChatData(10, 10)).thenReturn(raw(List.of(encrypted(11, "msg-1"), encrypted(12, "msg-2"))));
        when(session.decrypt("synthetic-aes-key", "cipher-msg-1")).thenReturn(first);
        when(session.decrypt("synthetic-aes-key", "cipher-msg-2")).thenReturn(second);
        when(contacts.staff(1L, "ww-test", "staff-2"))
                .thenReturn(Optional.of(new WecomStaffAccount(1L, "ww-test", "staff-2", 2L, true)));

        WecomArchivePage page = adapter.pullArchive("ww-test", 10, 10);

        assertThat(page.nextSeq()).isEqualTo(12);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.messages()).hasSize(2);
        assertThat(page.messages().get(0).senderType()).isEqualTo("CUSTOMER");
        assertThat(page.messages().get(0).staffUserId()).isEqualTo(2);
        assertThat(page.messages().get(0).externalUserid()).isEqualTo("wm-customer");
        assertThat(page.messages().get(0).text()).isEqualTo("想预约");
        assertThat(page.messages().get(0).processingBlockReason()).isNull();
        assertThat(page.messages().get(1).senderType()).isEqualTo("EMPLOYEE");
        assertThat(page.messages().get(1).text()).isEqualTo("可以预约");
        verify(session).close();
        verify(keyService).decryptRandomKey("random-msg-1", 3);
    }

    @Test
    void roomRetainsActualSenderAndMediaMetadataIsRecursivelyRegistered() throws Exception {
        String room = plaintext("msg-room", "CUSTOMER").replace("\"roomid\":\"\"", "\"roomid\":\"wr-room\"");
        String media = "{\"msgid\":\"msg-media\",\"action\":\"send\",\"from\":\"wm-customer\",\"tolist\":[\"staff-2\"],"
                + "\"roomid\":\"\",\"msgtime\":1700000000000,\"msgtype\":\"image\",\"image\":{\"sdkfileid\":\"synthetic-file\"}}";
        when(session.pullChatData(0, 10)).thenReturn(raw(List.of(encrypted(1, "msg-room"), encrypted(2, "msg-media"))));
        when(session.decrypt("synthetic-aes-key", "cipher-msg-room")).thenReturn(room);
        when(session.decrypt("synthetic-aes-key", "cipher-msg-media")).thenReturn(media);
        when(contacts.staff(1L, "ww-test", "staff-2"))
                .thenReturn(Optional.of(new WecomStaffAccount(1L, "ww-test", "staff-2", 2L, true)));

        WecomArchivePage page = adapter.pullArchive("ww-test", 0, 10);

        assertThat(page.messages().stream().map(item -> item.processingBlockReason())).containsExactly(null, null);
        assertThat(page.messages().stream().map(item -> item.text())).containsExactly("想预约", "[图片]");
        assertThat(page.messages().getFirst().conversationType()).isEqualTo("ROOM");
        assertThat(page.messages().getFirst().conversationId()).isEqualTo("wr-room");
        assertThat(page.messages().getFirst().externalUserid()).isNull();
        assertThat(page.messages().getFirst().senderWecomUserid()).isEqualTo("wm-customer");
        assertThat(page.messages().getFirst().staffUserId()).isNull();
        assertThat(page.messages().get(1).media()).hasSize(1);
        assertThat(page.messages().get(1).media().getFirst().sdkFileId()).isEqualTo("synthetic-file");
        assertThat(page.messages().get(1).media().getFirst().mediaKind()).isEqualTo("image");
        assertThat(page.nextSeq()).isEqualTo(2);
        verify(session).close();
    }

    @Test
    void nonIncreasingSequenceFailsAndConfiguredCorpIsRequired() throws Exception {
        when(session.pullChatData(0, 10)).thenReturn(raw(List.of(encrypted(1, "msg-1"), encrypted(1, "msg-2"))));
        when(session.decrypt("synthetic-aes-key", "cipher-msg-1")).thenReturn(plaintext("msg-1", "CUSTOMER"));
        assertThatThrownBy(() -> adapter.pullArchive("ww-test", 0, 10))
                .hasMessageContaining("严格递增");
        verify(session).close();
        org.mockito.Mockito.clearInvocations(nativeClient);
        assertThatThrownBy(() -> adapter.pullArchive("other-corp", 0, 10))
                .hasMessageContaining("尚未完整配置");
        verify(nativeClient, never()).open(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt());
        assertThat(adapter.pullContacts("ww-test")).isEmpty();
    }

    @Test
    void bracketEmojiAndUnstructuredJsonInTextDoNotCrashOrInjectMedia() throws Exception {
        String textEmoji = "{\"msgid\":\"msg-emoji\",\"action\":\"send\",\"from\":\"wm-customer\","
                + "\"tolist\":[\"staff-2\"],\"roomid\":\"\",\"msgtime\":1700000000000,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"[微笑] {not-json [通知]\"}}";
        String injectedText = "{\"msgid\":\"msg-inject\",\"action\":\"send\",\"from\":\"wm-customer\","
                + "\"tolist\":[\"staff-2\"],\"roomid\":\"\",\"msgtime\":1700000001000,\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"{\\\"sdkfileid\\\":\\\"evil-id\\\",\\\"filename\\\":\\\"a.jpg\\\"}\"}}";
        String mixedEmojiAndInject = mapper.writeValueAsString(Map.of(
                "msgid", "msg-mixed-safe",
                "action", "send",
                "from", "wm-customer",
                "tolist", List.of("staff-2"),
                "roomid", "",
                "msgtime", 1700000002000L,
                "msgtype", "mixed",
                "mixed", Map.of("item", List.of(
                        Map.of("type", "text", "content", "{\"content\":\"[微笑]\"}"),
                        Map.of("type", "text", "content", "[通知] {broken"),
                        Map.of("type", "text", "content", "{\"sdkfileid\":\"evil-mixed\"}"),
                        Map.of("type", "image", "content", "{\"sdkfileid\":\"real-mixed-img\",\"md5sum\":\"abc\"}")
                ))));
        String chatrecordEmoji = mapper.writeValueAsString(Map.of(
                "msgid", "msg-chatrecord-safe",
                "action", "send",
                "from", "wm-customer",
                "tolist", List.of("staff-2"),
                "roomid", "",
                "msgtime", 1700000003000L,
                "msgtype", "chatrecord",
                "chatrecord", Map.of("item", List.of(
                        Map.of("type", "ChatRecordText", "content", "{\"content\":\"[微笑] 咨询\"}"),
                        Map.of("type", "ChatRecordFile", "content", "{\"filename\":\"plan.pdf\",\"sdkfileid\":\"real-chat-file\"}")
                ))));

        when(session.pullChatData(0, 10)).thenReturn(raw(List.of(
                encrypted(1, "msg-emoji"),
                encrypted(2, "msg-inject"),
                encrypted(3, "msg-mixed-safe"),
                encrypted(4, "msg-chatrecord-safe"))));
        when(session.decrypt("synthetic-aes-key", "cipher-msg-emoji")).thenReturn(textEmoji);
        when(session.decrypt("synthetic-aes-key", "cipher-msg-inject")).thenReturn(injectedText);
        when(session.decrypt("synthetic-aes-key", "cipher-msg-mixed-safe")).thenReturn(mixedEmojiAndInject);
        when(session.decrypt("synthetic-aes-key", "cipher-msg-chatrecord-safe")).thenReturn(chatrecordEmoji);
        when(contacts.staff(1L, "ww-test", "staff-2"))
                .thenReturn(Optional.of(new WecomStaffAccount(1L, "ww-test", "staff-2", 2L, true)));

        WecomArchivePage page = adapter.pullArchive("ww-test", 0, 10);

        assertThat(page.nextSeq()).isEqualTo(4);
        assertThat(page.messages()).hasSize(4);
        assertThat(page.messages().get(0).processingBlockReason()).isNull();
        assertThat(page.messages().get(0).text()).isEqualTo("[微笑] {not-json [通知]");
        assertThat(page.messages().get(0).media()).isEmpty();
        assertThat(page.messages().get(1).processingBlockReason()).isNull();
        assertThat(page.messages().get(1).media()).isEmpty();
        assertThat(page.messages().get(2).processingBlockReason()).isNull();
        assertThat(page.messages().get(2).text()).contains("[微笑]", "[通知] {broken");
        assertThat(page.messages().get(2).media()).hasSize(1);
        assertThat(page.messages().get(2).media().getFirst().sdkFileId()).isEqualTo("real-mixed-img");
        assertThat(page.messages().get(3).processingBlockReason()).isNull();
        assertThat(page.messages().get(3).text()).contains("[微笑] 咨询");
        assertThat(page.messages().get(3).media()).hasSize(1);
        assertThat(page.messages().get(3).media().getFirst().sdkFileId()).isEqualTo("real-chat-file");
        assertThat(page.messages().get(3).media().getFirst().mediaKind()).isEqualTo("chatrecordfile");
    }

    @Test
    void poisonPillLimitsAndMalformedPayloadBlockOnlyBadMessageWhileSiblingsAdvanceSeq() throws Exception {
        String deeplyNested = "{\"item\":[{\"type\":\"text\",\"content\":\"leaf\"}]}";
        for (int i = 0; i < 10; i++) {
            deeplyNested = mapper.writeValueAsString(Map.of("item", List.of(
                    Map.of("type", "mixed", "content", deeplyNested))));
        }
        String tooDeep = mapper.writeValueAsString(Map.of(
                "msgid", "msg-deep", "action", "send", "from", "wm-customer",
                "tolist", List.of("staff-2"), "roomid", "", "msgtime", 1700000000000L,
                "msgtype", "mixed", "mixed", mapper.readTree(deeplyNested)));

        java.util.ArrayList<Map<String, Object>> manyMediaItems = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            manyMediaItems.add(Map.of("type", "image", "content", "{\"sdkfileid\":\"img-" + i + "\"}"));
        }
        String tooManyMedia = mapper.writeValueAsString(Map.of(
                "msgid", "msg-many", "action", "send", "from", "wm-customer",
                "tolist", List.of("staff-2"), "roomid", "", "msgtime", 1700000001000L,
                "msgtype", "mixed", "mixed", Map.of("item", manyMediaItems)));

        when(session.pullChatData(0, 10)).thenReturn(raw(List.of(
                encrypted(1, "msg-bad-json"),
                encrypted(2, "msg-deep"),
                encrypted(3, "msg-many"),
                encrypted(4, "msg-good"))));
        when(session.decrypt("synthetic-aes-key", "cipher-msg-bad-json")).thenReturn("{not-valid-json");
        when(session.decrypt("synthetic-aes-key", "cipher-msg-deep")).thenReturn(tooDeep);
        when(session.decrypt("synthetic-aes-key", "cipher-msg-many")).thenReturn(tooManyMedia);
        when(session.decrypt("synthetic-aes-key", "cipher-msg-good")).thenReturn(plaintext("msg-good", "CUSTOMER"));
        when(contacts.staff(1L, "ww-test", "staff-2"))
                .thenReturn(Optional.of(new WecomStaffAccount(1L, "ww-test", "staff-2", 2L, true)));

        WecomArchivePage page = adapter.pullArchive("ww-test", 0, 10);

        assertThat(page.nextSeq()).isEqualTo(4);
        assertThat(page.messages().get(0).processingBlockReason()).isEqualTo("MESSAGE_PAYLOAD_INVALID");
        assertThat(page.messages().get(1).processingBlockReason()).isEqualTo("MESSAGE_PAYLOAD_INVALID");
        assertThat(page.messages().get(2).processingBlockReason()).isEqualTo("MESSAGE_PAYLOAD_INVALID");
        assertThat(page.messages().get(3).processingBlockReason()).isNull();
        assertThat(page.messages().get(3).text()).isEqualTo("想预约");
    }

    @Test
    void missingPrivateKeyBusinessExceptionIsPropagatedNotSwallowedAsMessageGap() throws Exception {
        when(session.pullChatData(0, 10)).thenReturn(raw(List.of(encrypted(1, "msg-key-missing"))));
        when(keyService.decryptRandomKey("random-msg-key-missing", 3))
                .thenThrow(new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "对应公钥版本的归档 RSA 私钥不可用"));

        assertThatThrownBy(() -> adapter.pullArchive("ww-test", 0, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("私钥不可用");
    }

    private Map<String, Object> encrypted(long seq, String msgId) {
        return Map.of("seq", seq, "msgid", msgId, "publickey_ver", 3,
                "encrypt_random_key", "random-" + msgId, "encrypt_chat_msg", "cipher-" + msgId);
    }

    private String plaintext(String msgId, String sender) {
        String from = "CUSTOMER".equals(sender) ? "wm-customer" : "staff-2";
        String to = "CUSTOMER".equals(sender) ? "staff-2" : "wm-customer";
        String content = "CUSTOMER".equals(sender) ? "想预约" : "可以预约";
        return "{\"msgid\":\"" + msgId + "\",\"action\":\"send\",\"from\":\"" + from + "\","
                + "\"tolist\":[\"" + to + "\"],\"roomid\":\"\",\"msgtime\":1700000000000,"
                + "\"msgtype\":\"text\",\"text\":{\"content\":\"" + content + "\"}}";
    }

    private String raw(List<Map<String, Object>> messages) throws Exception {
        return mapper.writeValueAsString(Map.of("errcode", 0, "errmsg", "ok", "chatdata", messages));
    }
}
