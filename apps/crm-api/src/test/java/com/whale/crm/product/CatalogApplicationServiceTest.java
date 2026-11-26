package com.whale.crm.product;

import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.product.application.CatalogApplicationService;
import com.whale.crm.product.domain.CatalogRepository;
import com.whale.crm.product.domain.Sku;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatalogApplicationServiceTest {
    private final CatalogRepository repository = mock(CatalogRepository.class);
    private final CatalogApplicationService service = new CatalogApplicationService(
            repository, mock(AuditService.class));

    @Test
    void skusByIdsFiltersInvalidIdsAndRespectsTenantAndInstitutionScope() {
        CurrentUser employee = new CurrentUser(2L, 1L, "manager1", "管家", "EMPLOYEE", 1L,
                Set.of("MANAGER"), Set.of("order:read"), Set.of());
        Set<Long> mixedIds = new LinkedHashSet<>();
        mixedIds.add(null);
        mixedIds.add(0L);
        mixedIds.add(-5L);
        mixedIds.add(301L);
        Sku sku301 = new Sku(301L, "SKU-301", 301L, 201L, "光子水光囤货卡", 1, "FIXED", "3次",
                181100L, "ACTIVE", null, null, null, 0L);
        when(repository.findSkusByIds(1L, Set.of(301L), null)).thenReturn(List.of(sku301));

        assertThat(service.skusByIds(1L, employee, mixedIds)).containsExactly(sku301);
        verify(repository).findSkusByIds(1L, Set.of(301L), null);

        CurrentUser institutionUser = new CurrentUser(9L, 1L, "op1", "机构操作员", "INSTITUTION", null,
                Set.of("INSTITUTION_OPERATOR"), Set.of("order:read"), Set.of(5L));
        when(repository.findSkusByIds(1L, Set.of(301L), Set.of(5L))).thenReturn(List.of());
        assertThat(service.skusByIds(1L, institutionUser, Set.of(301L))).isEmpty();
        verify(repository).findSkusByIds(1L, Set.of(301L), Set.of(5L));

        CurrentUser emptyInstitutionUser = new CurrentUser(10L, 1L, "op2", "无机构操作员", "INSTITUTION", null,
                Set.of("INSTITUTION_OPERATOR"), Set.of("order:read"), Set.of());
        CatalogRepository freshRepo = mock(CatalogRepository.class);
        CatalogApplicationService freshService = new CatalogApplicationService(freshRepo, mock(AuditService.class));
        assertThat(freshService.skusByIds(1L, emptyInstitutionUser, Set.of(301L))).isEmpty();
        assertThat(freshService.skusByIds(1L, employee, Set.of())).isEmpty();
        assertThat(freshService.skusByIds(1L, employee, null)).isEmpty();
        verifyNoInteractions(freshRepo);
    }
}
