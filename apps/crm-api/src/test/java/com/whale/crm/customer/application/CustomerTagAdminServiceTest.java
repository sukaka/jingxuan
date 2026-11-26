package com.whale.crm.customer.application;

import com.whale.crm.customer.api.dto.CreateCustomerTagRequest;
import com.whale.crm.customer.api.dto.UpdateCustomerTagRequest;
import com.whale.crm.platform.dictionary.api.dto.DictionaryItemResponse;
import com.whale.crm.platform.dictionary.api.dto.UpdateDictionaryItemRequest;
import com.whale.crm.platform.dictionary.application.DictionaryApplicationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerTagAdminServiceTest {

    @Test
    void createTag_autoGeneratesImmutableCodeWhenOmitted_andEnforcesDuplicateLabelCheck() {
        DictionaryApplicationService dictionaryService = mock(DictionaryApplicationService.class);
        CustomerTagAdminService service = new CustomerTagAdminService(dictionaryService);
        DictionaryItemResponse expected = new DictionaryItemResponse(
                10L, "TAG_ABCDEF123456", "敏感肌", 5, "ACTIVE", 0L, Instant.now(), Instant.now());
        when(dictionaryService.createItemForType(eq(1L), eq(4L), eq("customerTags"), any(), eq("敏感肌"),
                eq(5), eq(true))).thenReturn(expected);

        DictionaryItemResponse actual = service.createTag(1L, 4L, new CreateCustomerTagRequest("  ", " 敏感肌 ", 5));

        assertThat(actual).isEqualTo(expected);
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(dictionaryService).createItemForType(eq(1L), eq(4L), eq("customerTags"), codeCaptor.capture(),
                eq("敏感肌"), eq(5), eq(true));
        assertThat(codeCaptor.getValue()).matches("TAG_[A-Z0-9]{12}");
    }

    @Test
    void listUpdateDisableEnable_alwaysScopesToCustomerTagsDictionary() {
        DictionaryApplicationService dictionaryService = mock(DictionaryApplicationService.class);
        CustomerTagAdminService service = new CustomerTagAdminService(dictionaryService);
        DictionaryItemResponse item = new DictionaryItemResponse(
                10L, "VIP", "核心贵宾", 1, "ACTIVE", 1L, Instant.now(), Instant.now());
        when(dictionaryService.listItemsByDictCode(1L, "customerTags")).thenReturn(List.of(item));
        when(dictionaryService.updateItemForType(eq(1L), eq(4L), eq("customerTags"), eq(10L),
                any(UpdateDictionaryItemRequest.class), eq(true))).thenReturn(item);
        when(dictionaryService.disableItemForType(1L, 4L, "customerTags", 10L)).thenReturn(item);
        when(dictionaryService.enableItemForType(1L, 4L, "customerTags", 10L)).thenReturn(item);

        assertThat(service.listTags(1L)).containsExactly(item);
        assertThat(service.updateTag(1L, 4L, 10L, new UpdateCustomerTagRequest(" 核心贵宾 ", 1, 0L))).isEqualTo(item);
        assertThat(service.disableTag(1L, 4L, 10L)).isEqualTo(item);
        assertThat(service.enableTag(1L, 4L, 10L)).isEqualTo(item);

        verify(dictionaryService).updateItemForType(1L, 4L, "customerTags", 10L,
                new UpdateDictionaryItemRequest("核心贵宾", 1, 0L), true);
    }
}
