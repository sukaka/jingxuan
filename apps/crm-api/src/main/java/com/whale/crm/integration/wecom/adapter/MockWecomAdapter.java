package com.whale.crm.integration.wecom.adapter;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("local")
public class MockWecomAdapter implements WecomAdapter {
    public static final String CORP_ID = "MOCK-CORP-001";
    public static final String SCOPE = "MOCK_ARCHIVE";
    private final AtomicBoolean failNextPull = new AtomicBoolean();

    @Override
    public String name() {
        return "MOCK_DEV_ONLY";
    }

    @Override
    public List<WecomContactInput> pullContacts(String corpId) {
        requireFixtureCorp(corpId);
        return List.of(
                new WecomContactInput("WECOM-EXT-1010", 2, "GRANTED", "mock://consent/1010"),
                new WecomContactInput("WECOM-EXT-UNMATCHED", 2, "GRANTED", "mock://consent/unmatched"),
                new WecomContactInput("WECOM-EXT-REVOKED", 3, "REVOKED", "mock://consent/revoked"));
    }

    @Override
    public WecomArchivePage pullArchive(String corpId, long afterSeq, int limit) {
        requireFixtureCorp(corpId);
        if (failNextPull.compareAndSet(true, false)) {
            throw new IllegalStateException("Mock归档拉取失败");
        }
        List<WecomArchiveMessage> fixtures = List.of(
                new WecomArchiveMessage("WECOM-MSG-001", 101, "WECOM-EXT-1010", 2,
                        "MOCK-CONV-1010", "TEXT", "CUSTOMER", Instant.parse("2026-10-25T01:00:00Z"),
                        "虚构Mock客户：想预约基础水光项目"),
                new WecomArchiveMessage("WECOM-MSG-002", 102, "WECOM-EXT-UNMATCHED", 2,
                        "MOCK-CONV-UNMATCHED", "TEXT", "CUSTOMER", Instant.parse("2026-10-25T01:01:00Z"),
                        "虚构未关联客户：想咨询套餐"),
                new WecomArchiveMessage("WECOM-MSG-003", 103, "WECOM-EXT-REVOKED", 3,
                        "MOCK-CONV-REVOKED", "TEXT", "CUSTOMER", Instant.parse("2026-10-25T01:02:00Z"),
                        "虚构撤回同意消息：不得归档"));
        List<WecomArchiveMessage> remaining = fixtures.stream().filter(item -> item.seq() > afterSeq).toList();
        int size = Math.max(1, Math.min(limit, 100));
        List<WecomArchiveMessage> page = remaining.stream().limit(size).toList();
        long next = page.isEmpty() ? afterSeq : page.get(page.size() - 1).seq();
        return new WecomArchivePage(page, next, next < 103);
    }

    public void failNextPull() {
        failNextPull.set(true);
    }

    private void requireFixtureCorp(String corpId) {
        if (!CORP_ID.equals(corpId)) {
            throw new IllegalArgumentException("未知Mock企业");
        }
    }
}
