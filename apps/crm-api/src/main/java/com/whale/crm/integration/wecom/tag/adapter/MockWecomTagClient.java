package com.whale.crm.integration.wecom.tag.adapter;

import com.whale.crm.integration.wecom.adapter.MockWecomAdapter;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地开发与集成测试专用企微标签 Mock 适配器（仅 local profile 激活，绝不进入生产）。
 * 内存维护确定性企业标签库与客户标签打标结果，便于完整往返验证。
 */
@Component
@Profile("local")
public class MockWecomTagClient implements WecomTagClient {

    public static final String ADAPTER_NAME = "MOCK_WECOM_TAG_DEV_ONLY";
    public static final String SEED_GROUP_ID = "mock-grp-1";
    public static final String SEED_GROUP_NAME = "虚构分组";
    public static final String SEED_TAG_WECOM_CUSTOMER = "mock-tag";
    public static final String SEED_TAG_VIP = "mock-tag-vip";
    public static final String SEED_TAG_HIGH_INTENT = "mock-tag-high";

    private final Map<String, WecomCorpTag> tagsById = new LinkedHashMap<>();
    private final Map<String, Set<String>> customerTagIdsByKey = new LinkedHashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(100);
    private final AtomicBoolean failNextMark = new AtomicBoolean(false);

    public MockWecomTagClient() {
        reset();
    }

    @Override
    public String name() {
        return ADAPTER_NAME;
    }

    @Override
    public String corpId() {
        return MockWecomAdapter.CORP_ID;
    }

    @Override
    public synchronized List<WecomCorpTag> listCorpTags() {
        return List.copyOf(tagsById.values());
    }

    @Override
    public synchronized WecomCorpTag addCorpTag(String groupId, String groupName, String tagName, int order) {
        if (tagName == null || tagName.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "标签名称不能为空");
        }
        String normalizedName = tagName.strip();
        String resolvedGroupId = groupId != null && !groupId.isBlank()
                ? groupId.strip() : "mock-grp-" + sequence.incrementAndGet();
        String resolvedGroupName = groupName != null && !groupName.isBlank()
                ? groupName.strip() : SEED_GROUP_NAME;
        for (WecomCorpTag existing : tagsById.values()) {
            if (existing.groupId().equals(resolvedGroupId)) {
                resolvedGroupName = existing.groupName();
                break;
            }
        }
        String tagId = "mock-tag-" + sequence.incrementAndGet();
        WecomCorpTag created = new WecomCorpTag(resolvedGroupId, resolvedGroupName, tagId,
                normalizedName, Math.max(0, order));
        tagsById.put(tagId, created);
        return created;
    }

    @Override
    public synchronized void editCorpTag(String tagId, String tagName, int order) {
        WecomCorpTag existing = tagsById.get(tagId);
        if (existing == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "企微标签不存在");
        }
        tagsById.put(tagId, new WecomCorpTag(existing.groupId(), existing.groupName(), existing.tagId(),
                tagName.strip(), Math.max(0, order)));
    }

    @Override
    public synchronized void deleteCorpTag(String tagId) {
        tagsById.remove(tagId);
        for (Set<String> assigned : customerTagIdsByKey.values()) {
            assigned.remove(tagId);
        }
    }

    @Override
    public synchronized void markCustomerTags(String wecomUserid, String externalUserid, List<String> addTagIds,
                                              List<String> removeTagIds) {
        if (failNextMark.compareAndSet(true, false)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Mock 企微客户打标暂时失败");
        }
        String key = contactKey(wecomUserid, externalUserid);
        Set<String> current = customerTagIdsByKey.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
        if (addTagIds != null) {
            for (String tagId : addTagIds) {
                if (tagId != null && !tagId.isBlank()) {
                    current.add(tagId.strip());
                }
            }
        }
        if (removeTagIds != null) {
            for (String tagId : removeTagIds) {
                if (tagId != null && !tagId.isBlank()) {
                    current.remove(tagId.strip());
                }
            }
        }
    }

    public synchronized void reset() {
        tagsById.clear();
        customerTagIdsByKey.clear();
        sequence.set(100);
        failNextMark.set(false);
        tagsById.put(SEED_TAG_WECOM_CUSTOMER,
                new WecomCorpTag(SEED_GROUP_ID, SEED_GROUP_NAME, SEED_TAG_WECOM_CUSTOMER, "企微客户", 10));
        tagsById.put(SEED_TAG_VIP,
                new WecomCorpTag(SEED_GROUP_ID, SEED_GROUP_NAME, SEED_TAG_VIP, "贵宾客户", 1));
        tagsById.put(SEED_TAG_HIGH_INTENT,
                new WecomCorpTag(SEED_GROUP_ID, SEED_GROUP_NAME, SEED_TAG_HIGH_INTENT, "高意向", 2));
        Set<String> c1010 = new LinkedHashSet<>();
        c1010.add(SEED_TAG_WECOM_CUSTOMER);
        customerTagIdsByKey.put(contactKey("staff-2", "WECOM-EXT-1010"), c1010);
    }

    public synchronized List<String> customerTagIds(String wecomUserid, String externalUserid) {
        return new ArrayList<>(customerTagIdsByKey.getOrDefault(contactKey(wecomUserid, externalUserid), Set.of()));
    }

    public synchronized void putRemoteTag(WecomCorpTag tag) {
        tagsById.put(tag.tagId(), tag);
    }

    public void failNextMark() {
        failNextMark.set(true);
    }

    private String contactKey(String wecomUserid, String externalUserid) {
        return wecomUserid + ":" + externalUserid;
    }
}
