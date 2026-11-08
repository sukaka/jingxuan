package com.whale.crm.integration.wecom.infrastructure;

import com.whale.crm.integration.wecom.domain.WecomContact;
import com.whale.crm.integration.wecom.domain.WecomCursor;
import com.whale.crm.integration.wecom.domain.WecomDataGap;
import com.whale.crm.integration.wecom.domain.WecomMessage;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.integration.wecom.infrastructure.mapper.WecomMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class MybatisWecomRepository implements WecomRepository {
    private final WecomMapper mapper;

    public MybatisWecomRepository(WecomMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<WecomContact> findContact(long t, String c, String e, long s) { return Optional.ofNullable(mapper.selectContact(t, c, e, s)); }
    public Optional<WecomContact> findContactById(long t, long id) { return Optional.ofNullable(mapper.selectContactById(t, id)); }
    public void insertContact(long t, WecomContact e) { mapper.insertContact(t, e); }
    public int updateContactConsent(long t, long id, long v, String s, String e, Instant at) { return mapper.updateContactConsent(t, id, v, s, e, at); }
    public int linkContact(long t, long id, long v, long c, String r, long u) { return mapper.linkContact(t, id, v, c, r, u); }
    public List<WecomContact> listContacts(long t, String s, List<Long> ids, long u) { return mapper.selectContacts(t, s, ids, u); }
    public boolean archiveMemberEnabled(long t, String c, long s) { return mapper.countEnabledMember(t, c, s) == 1; }
    public boolean staffVisible(long t, long staff, String s, List<Long> ids, long u) { return mapper.countVisibleStaff(t, staff, s, ids, u) == 1; }
    public Optional<WecomMessage> findMessage(long t, String c, String m) { return Optional.ofNullable(mapper.selectMessage(t, c, m)); }
    public Optional<WecomMessage> findMessageById(long t, long id) { return Optional.ofNullable(mapper.selectMessageById(t, id)); }
    public Optional<WecomMessage> findReadableMessage(long t, long id, String s, List<Long> ids, long u) { return Optional.ofNullable(mapper.selectReadableMessage(t, id, s, ids, u)); }
    public void insertMessage(long t, WecomMessage e) { mapper.insertMessage(t, e); }
    public int linkMessages(long t, String c, String e, long s, long customer) { return mapper.linkMessages(t, c, e, s, customer); }
    public List<WecomMessage> listMessages(long t, Long c, String s, List<Long> ids, long u, int o, int z) { return mapper.selectMessages(t, c, s, ids, u, o, z); }
    public long countMessages(long t, Long c, String s, List<Long> ids, long u) { return mapper.countScopedMessages(t, c, s, ids, u); }
    public long countAllMessages(long t, String c) { return mapper.countAllMessages(t, c); }
    public Optional<WecomCursor> findCursorForUpdate(long t, String c, String s) { return Optional.ofNullable(mapper.selectCursorForUpdate(t, c, s)); }
    public Optional<WecomCursor> findCursor(long t, String c, String s) { return Optional.ofNullable(mapper.selectCursor(t, c, s)); }
    public void insertCursor(long t, WecomCursor e) { mapper.insertCursor(t, e); }
    public int updateCursor(long t, long id, long v, long seq, Instant success, Instant attempt, String status, int failures, String error, boolean gap, String reason) { return mapper.updateCursor(t, id, v, seq, success, attempt, status, failures, error, gap, reason); }
    public void recordGap(long t, String c, String s, String type, Long from, Long to, String detail, Instant now) { mapper.insertGap(t, c, s, type, from, to, detail, now); }
    public int resolveCollectorGaps(long t, String c, String s, Instant at) { return mapper.resolveCollectorGaps(t, c, s, at); }
    public List<WecomDataGap> findOpenGaps(long t, String c, String s) { return mapper.selectOpenGaps(t, c, s); }
    public long countResolvedGaps(long t, String c, String s) { return mapper.countResolvedGaps(t, c, s); }
}
