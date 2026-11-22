package com.whale.crm.quickreply;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.quickreply.api.dto.QuickReplyTemplateResponse;
import com.whale.crm.quickreply.application.QuickReplyFactsProvider;
import com.whale.crm.quickreply.application.QuickReplyPreviewService;
import com.whale.crm.quickreply.domain.QuickReplyRepository;
import com.whale.crm.quickreply.domain.QuickReplyTemplate;
import com.whale.crm.quickreply.domain.QuickReplyUsageStat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuickReplyPreviewServiceTest {
    @Mock
    private QuickReplyRepository repository;
    @Mock
    private QuickReplyFactsProvider factsProvider;

    @Test
    void listSupportsKeywordFavoriteOnlyRecentSortAndNullsLastOrdering() {
        CurrentUser user = new CurrentUser(10L, 1L, "steward", "管家", "EMPLOYEE", 5L,
                Set.of("STEWARD"), Set.of("quickreply:view"), Set.of(100L));
        QuickReplyPreviewService service = new QuickReplyPreviewService(repository, factsProvider, List.of());

        QuickReplyTemplate neverUsed = template(1L, "首次问候", "INIT_HELLO", "您好，欢迎咨询护理服务", "INITIAL",
                "GLOBAL", null, false, false, 1);
        QuickReplyTemplate recentUsed = template(2L, "水光针预约提醒", "SGZ_APPT", "您好，请确认水光针预约时间", "APPOINTMENT",
                "GLOBAL", null, false, false, 2);
        QuickReplyTemplate olderUsed = template(3L, "术后护理指南", "POST_CARE", "水光针术后24小时请注意补水", "POSTOPERATIVE",
                "GLOBAL", null, false, false, 3);
        QuickReplyTemplate favorited = template(4L, "套餐余次说明", "PKG_LEFT", "您的套餐剩余次数如下", "PACKAGE_REMAINING",
                "GLOBAL", null, true, true, 4);
        QuickReplyTemplate otherPersonal = template(5L, "他人私密话术", "OTHER_PRIV", "水光针内部模板", "INITIAL",
                "PERSONAL", 99L, false, false, 0);
        QuickReplyTemplate disabled = new QuickReplyTemplate(6L, 1L, 1L, "停用水光针话术", "DISABLED_SGZ", "TEXT",
                "已停用", "INITIAL", "GLOBAL", null, true, false, false, false, "DISABLED", 0, 1L,
                Instant.now(), Instant.now(), 0L);

        when(repository.findTemplates(1L)).thenReturn(List.of(neverUsed, recentUsed, olderUsed, favorited,
                otherPersonal, disabled));
        when(repository.findFavoriteTemplateIds(1L, 10L)).thenReturn(List.of(4L));
        Instant newer = Instant.parse("2026-11-22T09:00:00Z");
        Instant older = Instant.parse("2026-11-21T09:00:00Z");
        when(repository.aggregateUsageStats(1L, 10L, null, null)).thenReturn(List.of(
                new QuickReplyUsageStat(2L, 3L, 1L, 2L, 0L, 0L, 0L, 1L, newer),
                new QuickReplyUsageStat(3L, 5L, 2L, 3L, 0L, 0L, 1L, 1L, older)));

        // DEFAULT sort: favorite first, then recently used (newest first), then never used (nullsLast), excluding other personal & disabled
        List<QuickReplyTemplateResponse> defaultOrder = service.list(user, null);
        assertThat(defaultOrder).extracting(QuickReplyTemplateResponse::id).containsExactly(4L, 2L, 3L, 1L);
        assertThat(defaultOrder.get(0).favorite()).isTrue();
        assertThat(defaultOrder.get(1).usageCount()).isEqualTo(3L);
        assertThat(defaultOrder.get(1).lastUsedAt()).isEqualTo(newer);

        // RECENT sort: most recently used first regardless of favorite status
        List<QuickReplyTemplateResponse> recentOrder = service.list(user, null, null, null, "RECENT");
        assertThat(recentOrder).extracting(QuickReplyTemplateResponse::id).containsExactly(2L, 3L, 4L, 1L);

        // Keyword search (matches name, content, or code case-insensitively) and still enforces visibility
        List<QuickReplyTemplateResponse> byKeyword = service.list(user, null, "水光针", null, "RECENT");
        assertThat(byKeyword).extracting(QuickReplyTemplateResponse::id).containsExactly(2L, 3L);

        List<QuickReplyTemplateResponse> byCode = service.list(user, null, "pkg_left", null, null);
        assertThat(byCode).extracting(QuickReplyTemplateResponse::id).containsExactly(4L);

        // Favorite only
        List<QuickReplyTemplateResponse> favOnly = service.list(user, null, null, true, null);
        assertThat(favOnly).extracting(QuickReplyTemplateResponse::id).containsExactly(4L);
    }

    private QuickReplyTemplate template(long id, String name, String code, String content, String scene,
                                        String scopeType, Long scopeId, boolean aiEnabled, boolean featured, int sortNo) {
        return new QuickReplyTemplate(id, 1L, 1L, name, code, "TEXT", content, scene, scopeType, scopeId,
                true, true, aiEnabled, featured, "ACTIVE", sortNo, 1L, Instant.now(), Instant.now(), 0L);
    }
}
