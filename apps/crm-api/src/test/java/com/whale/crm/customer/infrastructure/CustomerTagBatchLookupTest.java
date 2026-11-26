package com.whale.crm.customer.infrastructure;

import com.whale.crm.customer.api.dto.CustomerSegmentCriteria;
import com.whale.crm.customer.domain.CustomerListRow;
import com.whale.crm.customer.domain.CustomerTagRepository;
import com.whale.crm.customer.infrastructure.mapper.CustomerMapper;
import com.whale.crm.customer.infrastructure.mapper.CustomerSegmentMapper;
import com.whale.crm.customer.infrastructure.mapper.CustomerTagMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomerTagBatchLookupTest {

    @Test
    void tagRepositorySkipsSqlWhenCustomerIdsEmptyOrNull() {
        CustomerTagMapper mapper = mock(CustomerTagMapper.class);
        MybatisCustomerTagRepository repository = new MybatisCustomerTagRepository(mapper);

        assertThat(repository.findTagCodesByCustomerIds(1L, null)).isEmpty();
        assertThat(repository.findTagCodesByCustomerIds(1L, List.of())).isEmpty();
        assertThat(repository.findTagCodesByCustomerIds(1L, Arrays.asList(null, null))).isEmpty();
        verifyNoInteractions(mapper);
    }

    @Test
    void tagRepositoryDeduplicatesChunksAndPreservesOrder() {
        CustomerTagMapper mapper = mock(CustomerTagMapper.class);
        MybatisCustomerTagRepository repository = new MybatisCustomerTagRepository(mapper);
        when(mapper.selectTagCodesByCustomerIds(1L, List.of(10L, 20L))).thenReturn(List.of(
                new CustomerTagMapper.CustomerTagRow(10L, "ALPHA"),
                new CustomerTagMapper.CustomerTagRow(10L, "BETA"),
                new CustomerTagMapper.CustomerTagRow(20L, "VIP")));

        Map<Long, List<String>> tags = repository.findTagCodesByCustomerIds(1L, List.of(10L, 20L, 10L));

        assertThat(tags).containsEntry(10L, List.of("ALPHA", "BETA")).containsEntry(20L, List.of("VIP"));
        verify(mapper).selectTagCodesByCustomerIds(1L, List.of(10L, 20L));

        List<Long> manyIds = new ArrayList<>();
        for (long i = 1; i <= 501; i++) {
            manyIds.add(i);
        }
        when(mapper.selectTagCodesByCustomerIds(eq(1L), any())).thenReturn(List.of());
        repository.findTagCodesByCustomerIds(1L, manyIds);
        verify(mapper, times(3)).selectTagCodesByCustomerIds(eq(1L), any());
    }

    @Test
    void customerRepositorySkipsTagQueryWhenPageEmptyAndAttachesBatchTagsWhenNonEmpty() {
        CustomerMapper mapper = mock(CustomerMapper.class);
        CustomerTagRepository tagRepository = mock(CustomerTagRepository.class);
        MybatisCustomerRepository repository = new MybatisCustomerRepository(mapper, tagRepository);

        when(mapper.search(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        assertThat(repository.search(1L, "ALL", List.of(), 1L, null, null, null, null, null, null,
                null, null, null, 0, 20)).isEmpty();
        verifyNoInteractions(tagRepository);

        CustomerListRow first = row(11L);
        CustomerListRow second = row(12L);
        when(mapper.search(anyLong(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of(first, second));
        when(tagRepository.findTagCodesByCustomerIds(1L, List.of(11L, 12L)))
                .thenReturn(Map.of(11L, List.of("A_TAG", "Z_TAG")));

        List<CustomerListRow> rows = repository.search(1L, "ALL", List.of(), 1L, null, null, null, null, null, null,
                null, null, null, 0, 20);

        assertThat(rows.get(0).getTagCodes()).containsExactly("A_TAG", "Z_TAG");
        assertThat(rows.get(1).getTagCodes()).isEmpty();
    }

    @Test
    void segmentRepositorySkipsTagQueryWhenPageEmptyAndAttachesBatchTagsWhenNonEmpty() {
        CustomerSegmentMapper mapper = mock(CustomerSegmentMapper.class);
        CustomerTagRepository tagRepository = mock(CustomerTagRepository.class);
        MybatisCustomerSegmentRepository repository = new MybatisCustomerSegmentRepository(mapper, tagRepository);

        when(mapper.searchMembers(anyLong(), any(), any(), anyLong(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        assertThat(repository.searchMembers(1L, "ALL", List.of(), 1L, CustomerSegmentCriteria.empty(), 0, 20))
                .isEmpty();
        verifyNoInteractions(tagRepository);

        CustomerListRow first = row(21L);
        CustomerListRow second = row(22L);
        when(mapper.searchMembers(anyLong(), any(), any(), anyLong(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(first, second));
        when(tagRepository.findTagCodesByCustomerIds(1L, List.of(21L, 22L)))
                .thenReturn(Map.of(22L, List.of("HIGH_INTENT", "VIP")));

        List<CustomerListRow> rows = repository.searchMembers(1L, "ALL", List.of(), 1L,
                CustomerSegmentCriteria.empty(), 0, 20);

        assertThat(rows.get(0).getTagCodes()).isEmpty();
        assertThat(rows.get(1).getTagCodes()).containsExactly("HIGH_INTENT", "VIP");
    }

    private CustomerListRow row(long id) {
        CustomerListRow row = new CustomerListRow();
        row.setId(id);
        return row;
    }
}
