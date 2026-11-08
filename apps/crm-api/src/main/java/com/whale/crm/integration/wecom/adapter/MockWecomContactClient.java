package com.whale.crm.integration.wecom.adapter;

import com.whale.crm.integration.wecom.domain.WecomContactProfile;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@Profile("local")
public class MockWecomContactClient implements WecomContactClient {
    @Override
    public String name() { return "MOCK_CONTACT_DEV_ONLY"; }

    @Override
    public String corpId() { return MockWecomAdapter.CORP_ID; }

    @Override
    public WecomContactBatchPage batchGetByUser(List<String> wecomUserids, String cursor, int limit) {
        int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
        List<WecomContactProfile> all = wecomUserids.stream().flatMap(user -> fixtures().stream()
                .filter(item -> item.wecomUserid().equals(user))).toList();
        List<WecomContactProfile> page = all.stream().skip(offset).limit(Math.max(1, Math.min(limit, 100))).toList();
        String next = offset + page.size() < all.size() ? Integer.toString(offset + page.size()) : "";
        return new WecomContactBatchPage(page, next);
    }

    @Override
    public List<WecomContactProfile> getContact(String externalUserid) {
        return fixtures().stream().filter(item -> item.externalUserid().equals(externalUserid)).toList();
    }

    private List<WecomContactProfile> fixtures() {
        return List.of(
                profile("WECOM-EXT-1010", "staff-2", "虚构企微客户甲", "人工备注甲"),
                profile("WECOM-EXT-UNMATCHED", "staff-2", "虚构企微客户乙", "待人工匹配"),
                profile("WECOM-EXT-REVOKED", "staff-3", "虚构撤回同意客户", "仅验证资料同步"));
    }

    private WecomContactProfile profile(String external, String staff, String name, String remark) {
        return new WecomContactProfile(corpId(), external, staff, name, null, 1, 0, null, remark,
                "虚构Mock客户资料", 1, "mock-channel", Instant.parse("2026-10-25T01:00:00Z"),
                "[{\"group_name\":\"虚构分组\",\"tag_name\":\"企微客户\",\"tag_id\":\"mock-tag\",\"type\":1}]");
    }
}
