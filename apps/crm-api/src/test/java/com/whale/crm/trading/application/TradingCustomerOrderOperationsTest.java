package com.whale.crm.trading.application;

import com.whale.crm.customer.application.CustomerApplicationService;
import com.whale.crm.customer.application.MergeOrderResourceSnapshot;
import com.whale.crm.trading.domain.TradingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingCustomerOrderOperationsTest {

    @Test
    void snapshotResourcesQueriesOrderAndEntitlementIdsWithoutPerRowLocking() {
        TradingRepository repository = mock(TradingRepository.class);
        TradingCustomerOrderOperations operations = new TradingCustomerOrderOperations(
                repository, mock(CustomerApplicationService.class));
        when(repository.findOrderIdsByCustomer(1L, 200L)).thenReturn(List.of(10L, 20L));
        when(repository.findEntitlementIdsByCustomer(1L, 200L)).thenReturn(List.of(100L, 200L));

        MergeOrderResourceSnapshot snapshot = operations.snapshotResources(1L, 200L);

        assertThat(snapshot.orderIds()).containsExactly(10L, 20L);
        assertThat(snapshot.entitlementIds()).containsExactly(100L, 200L);
        verify(repository, never()).findOrderForUpdate(anyLong(), anyLong());
        verify(repository, never()).findEntitlementForUpdate(anyLong(), anyLong());
    }

    @Test
    void lockResourcesBatchLocksOrdersBeforeEntitlementsAndSkipsEmptyLists() {
        TradingRepository repository = mock(TradingRepository.class);
        TradingCustomerOrderOperations operations = new TradingCustomerOrderOperations(
                repository, mock(CustomerApplicationService.class));

        operations.lockResources(1L, List.of(20L, 10L), List.of(200L, 100L));

        InOrder lockOrder = inOrder(repository);
        lockOrder.verify(repository).findOrdersForUpdate(1L, List.of(20L, 10L));
        lockOrder.verify(repository).findEntitlementsForUpdate(1L, List.of(200L, 100L));
        verify(repository, never()).findOrderForUpdate(anyLong(), anyLong());
        verify(repository, never()).findEntitlementForUpdate(anyLong(), anyLong());

        TradingRepository emptyRepo = mock(TradingRepository.class);
        TradingCustomerOrderOperations emptyOps = new TradingCustomerOrderOperations(
                emptyRepo, mock(CustomerApplicationService.class));
        emptyOps.lockResources(1L, null, List.of());
        verify(emptyRepo, never()).findOrdersForUpdate(anyLong(), any());
        verify(emptyRepo, never()).findEntitlementsForUpdate(anyLong(), any());
    }

    @Test
    void reassignSpecificDelegatesOnlyNonEmptyListsAndHandlesNullSafely() {
        TradingRepository repository = mock(TradingRepository.class);
        TradingCustomerOrderOperations operations = new TradingCustomerOrderOperations(
                repository, mock(CustomerApplicationService.class));

        operations.reassignSpecific(1L, List.of(10L, 20L), List.of(100L), 200L, 100L);
        verify(repository).reassignSpecificOrders(1L, List.of(10L, 20L), 200L, 100L);
        verify(repository).reassignSpecificEntitlements(1L, List.of(100L), 200L, 100L);

        TradingRepository emptyRepo = mock(TradingRepository.class);
        TradingCustomerOrderOperations emptyOps = new TradingCustomerOrderOperations(
                emptyRepo, mock(CustomerApplicationService.class));
        emptyOps.reassignSpecific(1L, null, List.of(), 200L, 100L);
        emptyOps.reassignSpecific(1L, List.of(), null, 200L, 100L);
        verify(emptyRepo, never()).reassignSpecificOrders(anyLong(), any(), anyLong(), anyLong());
        verify(emptyRepo, never()).reassignSpecificEntitlements(anyLong(), any(), anyLong(), anyLong());
    }
}
