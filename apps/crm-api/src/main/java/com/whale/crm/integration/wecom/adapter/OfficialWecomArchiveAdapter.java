package com.whale.crm.integration.wecom.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomStaffAccount;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class OfficialWecomArchiveAdapter implements WecomAdapter {
    private static final int MAX_NESTED_DEPTH = 8;
    private static final int MAX_MEDIA_PER_MESSAGE = 50;
    private static final Set<String> DIRECT_MEDIA_TYPES = Set.of(
            "IMAGE", "VOICE", "VIDEO", "EMOTION", "FILE", "MEETING_VOICE_CALL", "VOIP_DOC_SHARE");
    private static final Set<String> NESTED_MEDIA_ITEM_TYPES = Set.of(
            "image", "voice", "video", "emotion", "file",
            "chatrecordimage", "chatrecordvoice", "chatrecordvideo", "chatrecordemotion", "chatrecordfile");
    private static final Set<String> NESTED_CONTAINER_ITEM_TYPES = Set.of(
            "mixed", "chatrecordmixed", "chatrecord", "chatrecordchatrecord");

    private final WecomArchiveOfficialProperties properties;
    private final WecomArchiveNativeClient nativeClient;
    private final WecomArchiveKeyService keyService;
    private final WecomContactSyncRepository contactRepository;
    private final ObjectMapper objectMapper;

    public OfficialWecomArchiveAdapter(WecomArchiveOfficialProperties properties, WecomArchiveNativeClient nativeClient,
                                       WecomArchiveKeyService keyService, WecomContactSyncRepository contactRepository,
                                       ObjectMapper objectMapper) {
        this.properties = properties;
        this.nativeClient = nativeClient;
        this.keyService = keyService;
        this.contactRepository = contactRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "OFFICIAL_SDK";
    }

    @Override
    public List<WecomContactInput> pullContacts(String corpId) {
        return List.of();
    }

    @Override
    public WecomArchivePage pullArchive(String corpId, long afterSeq, int limit) {
        requireConfigured(corpId);
        if (afterSeq < 0 || limit < 1 || limit > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "归档游标或每页数量无效");
        }
        try (WecomArchiveNativeClient.NativeSession session = nativeClient.open(properties.corpId(), properties.secret(),
                properties.sdkLibraryPath(), properties.proxy(), properties.proxyPassword(), properties.timeoutSeconds())) {
            JsonNode response = objectMapper.readTree(session.pullChatData(afterSeq, limit));
            if (response.path("errcode").asInt() != 0 || !response.path("chatdata").isArray()) {
                throw new IllegalStateException("官方归档响应错误，错误码=" + response.path("errcode").asInt(-1));
            }
            java.util.ArrayList<WecomArchiveMessage> messages = new java.util.ArrayList<>();
            Set<String> messageIds = new HashSet<>();
            long previous = afterSeq;
            for (JsonNode encrypted : response.path("chatdata")) {
                long seq = requiredLong(encrypted, "seq");
                if (seq <= previous) {
                    throw new IllegalStateException("官方归档序号必须严格递增");
                }
                previous = seq;
                String msgId = requiredText(encrypted, "msgid");
                if (!messageIds.add(msgId)) {
                    throw new IllegalStateException("官方归档消息 ID 重复");
                }
                try {
                    String randomKey = keyService.decryptRandomKey(requiredText(encrypted, "encrypt_random_key"),
                            encrypted.path("publickey_ver").asInt(-1));
                    JsonNode plaintext = objectMapper.readTree(session.decrypt(randomKey,
                            requiredText(encrypted, "encrypt_chat_msg")));
                    if (plaintext.has("msgid") && !msgId.equals(plaintext.path("msgid").asText())) {
                        throw new IllegalStateException("官方归档内外消息 ID 不一致");
                    }
                    messages.add(normalize(msgId, seq, plaintext));
                } catch (BusinessException | DataAccessException ex) {
                    throw ex;
                } catch (Exception ex) {
                    messages.add(blocked(msgId, seq, "UNKNOWN", "", Instant.EPOCH, "MESSAGE_PAYLOAD_INVALID"));
                }
            }
            return new WecomArchivePage(List.copyOf(messages), previous, messages.size() == limit);
        } catch (BusinessException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("官方归档消息解析失败", ex);
        }
    }

    private WecomArchiveMessage normalize(String msgId, long seq, JsonNode message) {
        long msgTime = requiredLong(message, "msgtime");
        if (msgTime <= 0) {
            throw new IllegalStateException("官方归档消息时间无效");
        }
        Instant sentAt = Instant.ofEpochMilli(msgTime);
        String type = message.path("msgtype").asText("").toUpperCase(Locale.ROOT);
        String roomId = message.path("roomid").asText("");
        String from = message.path("from").asText("");
        String action = message.path("action").asText();
        if (!"send".equals(action) && !"recall".equals(action)) {
            return blocked(msgId, seq, type, from, sentAt, "ACTION_NOT_SUPPORTED");
        }
        boolean recalled = "recall".equals(action);
        if (recalled && !"REVOKE".equals(type)) {
            throw new IllegalStateException("撤回控制消息类型必须为 revoke");
        }
        String previousMessageId = recalled ? requiredText(message.path("revoke"), "pre_msgid") : null;
        String content = recalled ? "" : contentText(message, type);
        List<com.whale.crm.integration.wecom.media.domain.WecomMediaReference> media = recalled ? List.of()
                : mediaReferences(message, type);
        var fromStaff = staff(from);
        if (!roomId.isBlank()) {
            String senderType = fromStaff.isPresent() ? "EMPLOYEE" : isExternalUserid(from) ? "CUSTOMER" : "UNKNOWN";
            return WecomArchiveMessage.room(msgId, seq, roomId, type, senderType, sentAt, content, from,
                    fromStaff.map(WecomStaffAccount::staffUserId).orElse(null), media, previousMessageId);
        }
        java.util.ArrayList<String> recipientValues = new java.util.ArrayList<>();
        if (message.path("tolist").isArray()) {
            message.path("tolist").forEach(item -> {
                if (item.isTextual() && !item.asText().isBlank()) {
                    recipientValues.add(item.asText());
                }
            });
        }
        List<String> recipients = List.copyOf(recipientValues);
        if (fromStaff.isPresent()) {
            Long staffId = fromStaff.orElseThrow().staffUserId();
            List<String> external = recipients.stream().filter(this::isExternalUserid).distinct().toList();
            if (external.size() != 1) {
                return blocked(msgId, seq, type, from, sentAt, "IDENTITY_UNRESOLVED");
            }
            return new WecomArchiveMessage(msgId, seq, external.getFirst(), staffId, external.getFirst(), type,
                    "EMPLOYEE", sentAt, content, null, media, previousMessageId);
        }
        List<Long> staffRecipients = recipients.stream().map(this::staff).flatMap(java.util.Optional::stream)
                .map(WecomStaffAccount::staffUserId).distinct().toList();
        if (!isExternalUserid(from) || staffRecipients.size() != 1) {
            return blocked(msgId, seq, type, from, sentAt, "IDENTITY_UNRESOLVED");
        }
        return new WecomArchiveMessage(msgId, seq, from, staffRecipients.getFirst(), from, type,
                "CUSTOMER", sentAt, content, null, media, previousMessageId);
    }

    private String contentText(JsonNode message, String type) {
        return switch (type) {
            case "TEXT" -> message.path("text").path("content").asText();
            case "MARKDOWN" -> message.path("info").path("content").asText();
            case "LINK" -> joined(message.path("link"), "title", "description", "link_url");
            case "WEAPP" -> joined(message.path("weapp"), "title", "description");
            case "CARD" -> joined(message.path("card"), "corpname", "userid");
            case "LOCATION" -> joined(message.path("location"), "title", "address");
            case "FILE" -> message.path("file").path("filename").asText();
            case "IMAGE" -> "[图片]";
            case "VOICE" -> "[语音]";
            case "VIDEO" -> "[视频]";
            case "EMOTION" -> "[表情]";
            case "MEETING" -> joined(message.path("meeting"), "topic", "address", "remarks");
            case "MIXED" -> nestedText(message.path("mixed").path("item"));
            case "CHATRECORD" -> nestedText(message.path("chatrecord").path("item"));
            default -> {
                String value = message.path(type.toLowerCase(Locale.ROOT)).path("content").asText("");
                if (value.isBlank()) {
                    value = message.path("info").path("content").asText("");
                }
                if (value.isBlank()) {
                    value = message.path(type.toLowerCase(Locale.ROOT)).path("title").asText("");
                }
                yield value.isBlank() ? "[" + (type.isBlank() ? "未知" : type) + " 消息]" : value;
            }
        };
    }

    private String joined(JsonNode node, String... fields) {
        return java.util.Arrays.stream(fields).map(field -> node.path(field).asText(""))
                .filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.joining("\n"));
    }

    private String nestedText(JsonNode itemsNode) {
        java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        collectText(itemsNode, texts, 0);
        return String.join("\n", texts);
    }

    private void collectText(JsonNode itemsNode, List<String> texts, int depth) {
        if (depth > MAX_NESTED_DEPTH) {
            throw new IllegalStateException("嵌套消息层级超过上限");
        }
        if (!itemsNode.isArray()) {
            return;
        }
        for (JsonNode item : itemsNode) {
            String itemType = item.path("type").asText("").toLowerCase(Locale.ROOT);
            JsonNode contentNode = item.path("content");
            if (contentNode.isObject()) {
                extractTextFromObject(contentNode, texts, depth);
                continue;
            }
            if (!contentNode.isTextual()) {
                continue;
            }
            String raw = contentNode.asText();
            if ("text".equals(itemType) || "chatrecordtext".equals(itemType)) {
                addNestedPlainText(raw, texts);
                continue;
            }
            String stripped = raw.strip();
            if (NESTED_CONTAINER_ITEM_TYPES.contains(itemType) || stripped.startsWith("{") || stripped.startsWith("[")) {
                JsonNode inner = parseJson(raw, "嵌套消息内容不是有效 JSON");
                if (inner.isObject()) {
                    extractTextFromObject(inner, texts, depth);
                } else if (inner.isArray()) {
                    collectText(inner, texts, depth + 1);
                }
            } else if (!raw.isBlank()) {
                texts.add(raw);
            }
        }
    }

    private void extractTextFromObject(JsonNode inner, List<String> texts, int depth) {
        if (inner.path("item").isArray()) {
            collectText(inner.path("item"), texts, depth + 1);
            return;
        }
        if (inner.path("content").isTextual()) {
            String value = inner.path("content").asText();
            if (!value.isBlank()) {
                texts.add(value);
            }
            return;
        }
        String display = firstText(inner, "title", "filename");
        if (display != null && !display.isBlank()) {
            texts.add(display);
        }
    }

    private void addNestedPlainText(String raw, List<String> texts) {
        String stripped = raw.strip();
        if (stripped.startsWith("{")) {
            try {
                JsonNode inner = objectMapper.readTree(raw);
                if (inner.isObject() && inner.path("content").isTextual()) {
                    String text = inner.path("content").asText();
                    if (!text.isBlank()) {
                        texts.add(text);
                    }
                    return;
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // 文本项中以 '{' 开头的普通聊天内容按原文保留，不作为结构化 JSON 拒绝
            }
        }
        if (!raw.isBlank()) {
            texts.add(raw);
        }
    }

    private List<com.whale.crm.integration.wecom.media.domain.WecomMediaReference> mediaReferences(
            JsonNode message, String type) {
        java.util.ArrayList<com.whale.crm.integration.wecom.media.domain.WecomMediaReference> result =
                new java.util.ArrayList<>();
        if (DIRECT_MEDIA_TYPES.contains(type)) {
            String field = type.toLowerCase(Locale.ROOT);
            addDirectMedia(message.path(field), field, result);
        } else if ("MIXED".equals(type)) {
            collectNestedMedia(message.path("mixed").path("item"), result, 0);
        } else if ("CHATRECORD".equals(type)) {
            collectNestedMedia(message.path("chatrecord").path("item"), result, 0);
        }
        return List.copyOf(result);
    }

    private void collectNestedMedia(JsonNode itemsNode,
                                    List<com.whale.crm.integration.wecom.media.domain.WecomMediaReference> result,
                                    int depth) {
        if (depth > MAX_NESTED_DEPTH) {
            throw new IllegalStateException("嵌套消息层级超过上限");
        }
        if (!itemsNode.isArray()) {
            return;
        }
        for (JsonNode item : itemsNode) {
            String itemType = item.path("type").asText("").toLowerCase(Locale.ROOT);
            boolean isMedia = NESTED_MEDIA_ITEM_TYPES.contains(itemType);
            boolean isContainer = NESTED_CONTAINER_ITEM_TYPES.contains(itemType);
            if (!isMedia && !isContainer) {
                continue;
            }
            JsonNode payload = item.path("content");
            if (payload.isTextual()) {
                payload = parseJson(payload.asText(), "嵌套媒体内容不是有效 JSON");
            }
            if (isMedia) {
                addDirectMedia(payload, itemType, result);
            } else if (payload.isObject()) {
                collectNestedMedia(payload.path("item"), result, depth + 1);
            } else if (payload.isArray()) {
                collectNestedMedia(payload, result, depth + 1);
            }
        }
    }

    private void addDirectMedia(JsonNode node, String kind,
                                List<com.whale.crm.integration.wecom.media.domain.WecomMediaReference> result) {
        if (!node.isObject()) {
            return;
        }
        String sdkFileId = node.path("sdkfileid").asText("");
        if (sdkFileId.isBlank()) {
            return;
        }
        if (sdkFileId.length() > 16384) {
            throw new IllegalStateException("媒体文件 ID 超过允许长度");
        }
        if (result.size() >= MAX_MEDIA_PER_MESSAGE) {
            throw new IllegalStateException("单条消息媒体附件数量超过上限");
        }
        String fileName = firstText(node, "filename", "title");
        result.add(new com.whale.crm.integration.wecom.media.domain.WecomMediaReference(result.size(),
                sdkFileId, kind, node.path("md5sum").asText(null), mediaSize(node), fileName,
                mediaContentType(node, fileName)));
    }

    private JsonNode parseJson(String raw, String message) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalStateException(message, ex);
        }
    }

    private Long mediaSize(JsonNode node) {
        for (String field : List.of("filesize", "voice_size", "imagesize")) {
            if (node.path(field).isNumber() && node.path(field).longValue() >= 0) {
                return node.path(field).longValue();
            }
        }
        return null;
    }

    private String firstText(JsonNode node, String... fields) {
        return java.util.Arrays.stream(fields).map(field -> node.path(field).asText(""))
                .filter(value -> !value.isBlank()).findFirst().orElse(null);
    }

    private String mediaContentType(JsonNode node, String fileName) {
        String extension = node.path("fileext").asText("");
        String candidate = fileName == null || fileName.isBlank() ? "file." + extension : fileName;
        String value = java.net.URLConnection.guessContentTypeFromName(candidate);
        return value == null ? "application/octet-stream" : value;
    }

    private WecomArchiveMessage blocked(String msgId, long seq, String type, String conversationId,
                                        Instant sentAt, String reason) {
        return new WecomArchiveMessage(msgId, seq, "", 0L, conversationId, type.isBlank() ? "UNKNOWN" : type,
                "UNKNOWN", sentAt, "", reason);
    }

    private java.util.Optional<WecomStaffAccount> staff(String wecomUserid) {
        if (wecomUserid == null || wecomUserid.isBlank()) {
            return java.util.Optional.empty();
        }
        return contactRepository.staff(properties.allowedTenantId(), properties.corpId(), wecomUserid)
                .filter(WecomStaffAccount::enabled).filter(item -> item.staffUserId() != null);
    }

    private boolean isExternalUserid(String value) {
        return value != null && (value.startsWith("wo") || value.startsWith("wm"));
    }

    private long requiredLong(JsonNode value, String field) {
        if (!value.path(field).isNumber()) {
            throw new IllegalStateException("官方归档字段缺失：" + field);
        }
        return value.path(field).longValue();
    }

    private String requiredText(JsonNode value, String field) {
        String text = value.path(field).asText("");
        if (text.isBlank()) {
            throw new IllegalStateException("官方归档字段缺失：" + field);
        }
        return text;
    }

    private void requireConfigured(String corpId) {
        if (properties.secret() == null || properties.secret().isBlank() || properties.corpId() == null
                || !properties.corpId().equals(corpId) || properties.sdkLibraryPath() == null
                || properties.sdkLibraryPath().isBlank() || properties.privateKeyDirectory() == null
                || properties.privateKeyDirectory().isBlank() || properties.timeoutSeconds() < 1
                || properties.timeoutSeconds() > 120) {
            throw new BusinessException(ErrorCode.PENDING_BUSINESS_RULE, "当前企业的官方归档 SDK、Secret 或私钥尚未完整配置");
        }
    }
}
