package com.whale.crm.integration.wecom.infrastructure;

import com.whale.crm.integration.wecom.domain.WecomContactProfile;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRepository;
import com.whale.crm.integration.wecom.domain.WecomContactSyncRun;
import com.whale.crm.integration.wecom.domain.WecomStaffAccount;
import com.whale.crm.integration.wecom.infrastructure.mapper.WecomContactSyncMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisWecomContactSyncRepository implements WecomContactSyncRepository {
    private final WecomContactSyncMapper mapper;

    public MybatisWecomContactSyncRepository(WecomContactSyncMapper mapper) { this.mapper = mapper; }
    public List<WecomStaffAccount> enabledStaff(long t, String c) { return mapper.selectEnabledStaff(t, c); }
    public Optional<WecomStaffAccount> staff(long t, String c, String u) { return Optional.ofNullable(mapper.selectStaff(t, c, u)); }
    public long startRun(long t, String c, String type, Long actor, Instant now) {
        try {
            if (mapper.insertRun(t, c, type, actor, now) != 1) {
                return 0;
            }
            return mapper.lastInsertId();
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return 0;
        }
    }
    public void finishRun(long t, long id, String s, String c, int r, int u, int d, String e, Instant f) { mapper.finishRun(t, id, s, c, r, u, d, e, f); }
    public List<WecomContactSyncRun> runs(long t, String c) { return mapper.selectRuns(t, c); }
    public void upsertContact(long t, long staff, long run, Instant now, WecomContactProfile p) { mapper.upsertContact(t, staff, run, now, p); }
    public int deactivateMissing(long t, String c, long run) { return mapper.deactivateMissing(t, c, run); }
    public void updateFollowStatus(long t, String c, String e, long staff, String s) { mapper.updateFollowStatus(t, c, e, staff, s); }
}
