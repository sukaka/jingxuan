package com.whale.crm.platform.idempotency;

import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private IdempotencyMapper mapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void lookupAndRecordSuccess_scopeByAuthenticatedCurrentUserId() {
        IdempotencyService service = new IdempotencyService(mapper);
        CurrentUser userTen = new CurrentUser(10L, 1L, "u10", "U10", "EMPLOYEE",
                null, Set.of(), Set.of(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userTen, null, List.of()));

        assertThat(service.lookup(1L, "order.create", "shared-key", "{}")).isEmpty();
        verify(mapper).selectByKey(1L, "order.create", 10L, "shared-key");

        service.recordSuccess(1L, "order.create", "shared-key", "ORDER", "101", "{}");
        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(mapper).insertRecord(captor.capture());
        assertThat(captor.getValue().getActorUserId()).isEqualTo(10L);
    }

    @Test
    void unauthenticatedAndExplicitZeroActor_useSystemActorZeroAndDetectPayloadConflict() {
        IdempotencyService service = new IdempotencyService(mapper);
        CurrentUser userTen = new CurrentUser(10L, 1L, "u10", "U10", "EMPLOYEE",
                null, Set.of(), Set.of(), Set.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userTen, null, List.of()));

        IdempotencyRecord existing = new IdempotencyRecord();
        existing.setActorUserId(0L);
        existing.setRequestHash(IdempotencyService.sha256("{\"a\":1}"));
        when(mapper.selectByKey(1L, "webhook", 0L, "cb-1")).thenReturn(existing);

        assertThat(service.lookup(1L, "webhook", 0L, "cb-1", "{\"a\":1}")).contains(existing);
        assertThatThrownBy(() -> service.lookup(1L, "webhook", 0L, "cb-1", "{\"a\":2}"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        SecurityContextHolder.clearContext();
        service.recordSuccess(1L, "webhook", "cb-2", "CALLBACK", "202", "{}");
        ArgumentCaptor<IdempotencyRecord> captor = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(mapper).insertRecord(captor.capture());
        assertThat(captor.getValue().getActorUserId()).isZero();
    }
}
