package com.whale.crm.integration.wecom;

import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.integration.wecom.domain.WecomRepository;
import com.whale.crm.integration.wecom.h5card.api.dto.CreateWecomH5CardRequest;
import com.whale.crm.integration.wecom.h5card.api.dto.PublicWecomH5CardResponse;
import com.whale.crm.integration.wecom.h5card.api.dto.WecomH5CardResponse;
import com.whale.crm.integration.wecom.h5card.application.WecomH5CardService;
import com.whale.crm.integration.wecom.h5card.domain.WecomH5Card;
import com.whale.crm.integration.wecom.h5card.domain.WecomH5CardRepository;
import com.whale.crm.integration.wecom.sidebar.adapter.WecomSidebarProperties;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.Project;
import com.whale.crm.quickreply.application.QuickReplyTextMasker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WecomH5CardServiceTest {

    @Test
    void defaultCoverImageUsesPngForBothCreatedCardAndPublicView() {
        WecomH5CardRepository cardRepo = mock(WecomH5CardRepository.class);
        WecomRepository wecomRepo = mock(WecomRepository.class);
        CatalogApplicationService catalog = mock(CatalogApplicationService.class);
        CustomerScopeSupport scopeSupport = mock(CustomerScopeSupport.class);
        WecomSidebarProperties props = new WecomSidebarProperties(true, 10001L,
                "https://open.weixin.qq.com/connect/oauth2/authorize",
                "https://crm.example.com/api/v1/wecom/oauth/callback",
                "https://crm.example.com/wecom/sidebar",
                List.of("https://crm.example.com"), 300, 60);
        IdempotencyService idempotency = mock(IdempotencyService.class);
        AuditService audit = mock(AuditService.class);

        WecomH5CardService service = new WecomH5CardService(cardRepo, wecomRepo, catalog, scopeSupport,
                props, new QuickReplyTextMasker(), idempotency, audit);

        CurrentUser steward = new CurrentUser(2L, 1L, "manager1", "管家一", "EMPLOYEE", 1L,
                Set.of("MANAGER"), Set.of("wecom:sidebar:view"), Set.of());
        when(idempotency.lookup(anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(wecomRepo.contactMapped(1L, "wo-1", 1010L)).thenReturn(true);
        when(catalog.activeProjectForSharing(1L, 101L)).thenReturn(
                new Project(101L, "PRJ-101", "光子嫩肤", "SKIN", 198000L, "ACTIVE", Instant.now(), 0L));

        AtomicReference<WecomH5Card> saved = new AtomicReference<>();
        doAnswer(inv -> {
            WecomH5Card input = inv.getArgument(0);
            saved.set(new WecomH5Card(99L, input.tenantId(), input.projectId(), input.projectNameSnapshot(),
                    input.customerId(), input.employeeId(), input.externalUserid(), input.title(),
                    input.summary(), input.content(), input.shareKey(), "ACTIVE", null, null, null,
                    Instant.now(), Instant.now(), 0L));
            return null;
        }).when(cardRepo).insert(any());
        when(cardRepo.findByShareKey(anyString())).thenAnswer(inv -> Optional.ofNullable(saved.get()));

        WecomH5CardResponse created = service.create(steward,
                new CreateWecomH5CardRequest(1010L, 101L, "wo-1", "光子嫩肤介绍", "温和护理方案", "公开项目说明"),
                "idem-png-1");
        assertThat(created.imageUrl()).isEqualTo("https://crm.example.com/h5-card-cover.png");
        assertThat(created.imageUrl()).doesNotContain(".svg");

        String shareKey = saved.get().shareKey();
        PublicWecomH5CardResponse publicView = service.publicView(shareKey);
        assertThat(publicView.imageUrl()).isEqualTo("https://crm.example.com/h5-card-cover.png");
        assertThat(publicView.imageUrl()).doesNotContain(".svg");
    }
}
