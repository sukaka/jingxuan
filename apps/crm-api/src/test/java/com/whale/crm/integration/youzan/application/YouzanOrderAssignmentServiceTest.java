package com.whale.crm.integration.youzan.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.StewardDirectory;
import com.whale.crm.integration.youzan.api.dto.YouzanOrderAssignRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardAliasRequest;
import com.whale.crm.integration.youzan.api.dto.YouzanStewardAliasSaveResponse;
import com.whale.crm.integration.youzan.domain.YouzanOrderAssignment;
import com.whale.crm.integration.youzan.domain.YouzanOrderAssignmentRepository;
import com.whale.crm.integration.youzan.domain.YouzanStewardAlias;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.user.domain.SysUser;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class YouzanOrderAssignmentServiceTest {

    private static final long TENANT_ID = 1L;
    private static final String SHOP_ID = "92010731";
    private static final Instant FIXED_NOW = Instant.parse("2026-11-27T07:00:00Z");

    private final YouzanOrderAssignmentRepository repository = mock(YouzanOrderAssignmentRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final StewardDirectory stewardDirectory = mock(StewardDirectory.class);
    private final IdempotencyService idempotencyService = mock(IdempotencyService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private YouzanOrderAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new YouzanOrderAssignmentService(
                repository,
                new YouzanTradeMemoParser(objectMapper),
                userRepository,
                stewardDirectory,
                idempotencyService,
                auditService,
                objectMapper,
                new SimpleTxManager(),
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    void autoAssignsByActiveAliasBeforeDisplayName() {
        SysUser steward = activeEmployee(11L, 101L, "林管家");
        when(repository.findByOrder(TENANT_ID, SHOP_ID, "E-ALIAS-1")).thenReturn(Optional.empty());
        when(repository.findAliasByKey(TENANT_ID, "紫紫模拟")).thenReturn(Optional.of(
                new YouzanStewardAlias(1L, TENANT_ID, "紫紫模拟", "紫紫模拟", 11L, "ACTIVE",
                        null, 1L, 1L, FIXED_NOW, FIXED_NOW, 0L)));
        when(userRepository.findById(TENANT_ID, 11L)).thenReturn(Optional.of(steward));
        when(stewardDirectory.isEligibleSteward(TENANT_ID, steward)).thenReturn(true);

        service.syncFromProviderPayload(TENANT_ID, SHOP_ID, "E-ALIAS-1", 501L, payloadWithMemo("账号：紫紫模拟，到院：示范院"));

        ArgumentCaptor<YouzanOrderAssignment> captor = ArgumentCaptor.forClass(YouzanOrderAssignment.class);
        verify(repository).upsertOrderAssignment(eq(TENANT_ID), captor.capture());
        YouzanOrderAssignment saved = captor.getValue();
        assertThat(saved.assignedStewardId()).isEqualTo(11L);
        assertThat(saved.assignedTeamId()).isEqualTo(101L);
        assertThat(saved.assignmentStatus()).isEqualTo(YouzanOrderAssignmentService.STATUS_ASSIGNED);
        assertThat(saved.assignmentSource()).isEqualTo(YouzanOrderAssignmentService.SOURCE_AUTO_ALIAS);
        assertThat(saved.memoAccountName()).isEqualTo("紫紫模拟");
        assertThat(saved.memoInstitutionName()).isEqualTo("示范院");
    }

    @Test
    void fallsBackToExactDisplayNameAndFlagsAmbiguousOrUnmatched() {
        when(repository.findByOrder(eq(TENANT_ID), eq(SHOP_ID), any())).thenReturn(Optional.empty());
        when(repository.findAliasByKey(eq(TENANT_ID), any())).thenReturn(Optional.empty());
        when(userRepository.findStewardOptions(TENANT_ID, StewardDirectory.STEWARD_ROLE_CODES, null, null))
                .thenReturn(List.of(
                        new UserRepository.StewardOptionRow(21L, "苏唯一", 101L, "一组"),
                        new UserRepository.StewardOptionRow(22L, "王重名", 101L, "一组"),
                        new UserRepository.StewardOptionRow(23L, "王重名", 102L, "二组")));

        service.syncFromProviderPayload(TENANT_ID, SHOP_ID, "E-NAME-1", 502L, payloadWithMemo("账号：苏唯一"));
        service.syncFromProviderPayload(TENANT_ID, SHOP_ID, "E-AMB-1", 503L, payloadWithMemo("账号：王重名"));
        service.syncFromProviderPayload(TENANT_ID, SHOP_ID, "E-MISS-1", 504L, payloadWithMemo("账号：不存在的人"));

        ArgumentCaptor<YouzanOrderAssignment> captor = ArgumentCaptor.forClass(YouzanOrderAssignment.class);
        verify(repository, org.mockito.Mockito.times(3)).upsertOrderAssignment(eq(TENANT_ID), captor.capture());

        YouzanOrderAssignment matched = captor.getAllValues().get(0);
        assertThat(matched.assignedStewardId()).isEqualTo(21L);
        assertThat(matched.assignmentStatus()).isEqualTo(YouzanOrderAssignmentService.STATUS_ASSIGNED);
        assertThat(matched.assignmentSource()).isEqualTo(YouzanOrderAssignmentService.SOURCE_AUTO_DISPLAY_NAME);

        YouzanOrderAssignment ambiguous = captor.getAllValues().get(1);
        assertThat(ambiguous.assignedStewardId()).isNull();
        assertThat(ambiguous.assignmentStatus()).isEqualTo(YouzanOrderAssignmentService.STATUS_AMBIGUOUS);

        YouzanOrderAssignment unmatched = captor.getAllValues().get(2);
        assertThat(unmatched.assignedStewardId()).isNull();
        assertThat(unmatched.assignmentStatus()).isEqualTo(YouzanOrderAssignmentService.STATUS_UNMATCHED);
    }

    @Test
    void preservesManualAssignmentWhenSubsequentSyncArrives() {
        YouzanOrderAssignment manualExisting = new YouzanOrderAssignment(
                7L, TENANT_ID, SHOP_ID, "E-MANUAL-1", 500L,
                "WAIT_SELLER_SEND_GOODS", "待发货", "微信支付", null, null, null, null,
                "账号：旧账号", null, null, null, null, "旧账号", "旧账号", null, null,
                31L, 101L, YouzanOrderAssignmentService.STATUS_ASSIGNED,
                YouzanOrderAssignmentService.SOURCE_MANUAL, "主管手工指派", 1L, FIXED_NOW,
                FIXED_NOW, FIXED_NOW, 1L);
        when(repository.findByOrder(TENANT_ID, SHOP_ID, "E-MANUAL-1")).thenReturn(Optional.of(manualExisting));

        service.syncFromProviderPayload(TENANT_ID, SHOP_ID, "E-MANUAL-1", 509L,
                payloadWithMemo("账号：新账号，到院：新机构"));

        ArgumentCaptor<YouzanOrderAssignment> captor = ArgumentCaptor.forClass(YouzanOrderAssignment.class);
        verify(repository).upsertOrderAssignment(eq(TENANT_ID), captor.capture());
        YouzanOrderAssignment saved = captor.getValue();
        assertThat(saved.assignedStewardId()).isEqualTo(31L);
        assertThat(saved.assignmentSource()).isEqualTo(YouzanOrderAssignmentService.SOURCE_MANUAL);
        assertThat(saved.assignmentReason()).isEqualTo("主管手工指派");
        assertThat(saved.memoAccountName()).isEqualTo("新账号");
        assertThat(saved.memoInstitutionName()).isEqualTo("新机构");
        verify(repository, never()).findAliasByKey(anyLong(), any());
    }

    @Test
    void manualAssignWithSaveAliasUpdatesAssignmentSavesAliasAndBackfillsPending() {
        CurrentUser admin = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("integration:youzan:manage", "order:read"), Set.of());
        SysUser targetSteward = activeEmployee(12L, 102L, "周管家");
        YouzanOrderAssignment existing = new YouzanOrderAssignment(
                9L, TENANT_ID, SHOP_ID, "E-ASSIGN-1", 600L,
                "WAIT_BUYER_CONFIRM_GOODS", "已发货", "微信支付", null, null, null, null,
                "账号：紫紫模拟", null, null, null, null, "紫紫模拟", "紫紫模拟", null, null,
                null, null, YouzanOrderAssignmentService.STATUS_UNMATCHED,
                YouzanOrderAssignmentService.SOURCE_NONE, "未匹配", null, null,
                FIXED_NOW, FIXED_NOW, 0L);
        YouzanOrderAssignment afterAssign = new YouzanOrderAssignment(
                9L, TENANT_ID, SHOP_ID, "E-ASSIGN-1", 600L,
                "WAIT_BUYER_CONFIRM_GOODS", "已发货", "微信支付", null, null, null, null,
                "账号：紫紫模拟", null, null, null, null, "紫紫模拟", "紫紫模拟", null, null,
                12L, 102L, YouzanOrderAssignmentService.STATUS_ASSIGNED,
                YouzanOrderAssignmentService.SOURCE_MANUAL, "确认归属周管家", 1L, FIXED_NOW,
                FIXED_NOW, FIXED_NOW, 1L);
        YouzanStewardAlias savedAlias = new YouzanStewardAlias(
                88L, TENANT_ID, "紫紫模拟", "紫紫模拟", 12L, "ACTIVE", "auto", 1L, 1L, FIXED_NOW, FIXED_NOW, 0L);

        when(userRepository.findById(TENANT_ID, 12L)).thenReturn(Optional.of(targetSteward));
        when(stewardDirectory.isEligibleSteward(TENANT_ID, targetSteward)).thenReturn(true);
        when(stewardDirectory.canAssignTo(admin, targetSteward)).thenReturn(true);
        when(idempotencyService.lookup(eq(TENANT_ID), any(), eq(1L), eq("assign-key-1"), any()))
                .thenReturn(Optional.empty());
        when(repository.findByOrderForUpdate(TENANT_ID, SHOP_ID, "E-ASSIGN-1")).thenReturn(Optional.of(existing));
        when(repository.updateManualAssignment(eq(TENANT_ID), eq(9L), eq(0L), eq(12L), eq(102L),
                eq("ASSIGNED"), eq("MANUAL"), eq("确认归属周管家"), eq(1L), eq(FIXED_NOW))).thenReturn(1);
        when(repository.findByOrder(TENANT_ID, SHOP_ID, "E-ASSIGN-1")).thenReturn(Optional.of(afterAssign));
        when(repository.findAliasByKeyForUpdate(TENANT_ID, "紫紫模拟")).thenReturn(Optional.empty());
        when(repository.findAliasByKey(TENANT_ID, "紫紫模拟")).thenReturn(Optional.of(savedAlias));
        when(repository.backfillPendingByAccountKey(eq(TENANT_ID), eq("紫紫模拟"), eq(12L), eq(102L), any(), eq(FIXED_NOW)))
                .thenReturn(3);

        YouzanOrderAssignmentService.ManualAssignResult result = service.assignOrderSteward(
                admin, SHOP_ID, "E-ASSIGN-1", 600L,
                new YouzanOrderAssignRequest(12L, "确认归属周管家", 0L, true, null),
                "assign-key-1");

        assertThat(result.assignment().assignedStewardId()).isEqualTo(12L);
        assertThat(result.backfilledAliasOrderCount()).isEqualTo(3);
        verify(repository).insertAlias(eq(TENANT_ID), any(YouzanStewardAlias.class));
        verify(auditService).record(eq(TENANT_ID), eq(1L), eq(AuditService.ACTION_YOUZAN_ORDER_STEWARD_ASSIGN),
                eq("YOUZAN_ORDER"), eq(SHOP_ID + ":E-ASSIGN-1"), any(), any(), eq("确认归属周管家"));
    }

    @Test
    void saveAliasValidatesEligibilityAndBackfillsPendingOrders() {
        CurrentUser admin = new CurrentUser(1L, TENANT_ID, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("integration:youzan:manage"), Set.of());
        SysUser target = activeEmployee(15L, 105L, "郑管家");
        YouzanStewardAlias saved = new YouzanStewardAlias(
                99L, TENANT_ID, "小郑花名", "小郑花名", 15L, "ACTIVE", "花名映射", 1L, 1L, FIXED_NOW, FIXED_NOW, 0L);

        when(userRepository.findById(TENANT_ID, 15L)).thenReturn(Optional.of(target));
        when(stewardDirectory.isEligibleSteward(TENANT_ID, target)).thenReturn(true);
        when(idempotencyService.lookup(eq(TENANT_ID), any(), eq(1L), eq("alias-key-1"), any()))
                .thenReturn(Optional.empty());
        when(repository.findAliasByKeyForUpdate(TENANT_ID, "小郑花名")).thenReturn(Optional.empty());
        when(repository.findAliasByKey(TENANT_ID, "小郑花名")).thenReturn(Optional.of(saved));
        when(repository.backfillPendingByAccountKey(eq(TENANT_ID), eq("小郑花名"), eq(15L), eq(105L), any(), eq(FIXED_NOW)))
                .thenReturn(2);
        when(userRepository.findStaffProfilesByIds(TENANT_ID, List.of(15L)))
                .thenReturn(List.of(new UserRepository.StewardOptionRow(15L, "郑管家", 105L, "五组")));

        YouzanStewardAliasSaveResponse response = service.saveAlias(
                admin, new YouzanStewardAliasRequest("小郑花名", 15L, "ACTIVE", "花名映射", null, true), "alias-key-1");

        assertThat(response.backfilledOrderCount()).isEqualTo(2);
        assertThat(response.alias().aliasName()).isEqualTo("小郑花名");
        assertThat(response.alias().stewardName()).isEqualTo("郑管家");
        assertThat(response.alias().teamName()).isEqualTo("五组");
    }

    @Test
    void rejectsInstitutionUsersAndOutOfScopeAssignment() {
        CurrentUser institution = new CurrentUser(9L, TENANT_ID, "op1", "机构", "INSTITUTION", null,
                Set.of("INSTITUTION_OPERATOR"), Set.of("order:read"), Set.of(5L));
        assertThatThrownBy(() -> service.stewardOptions(TENANT_ID, institution))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        CurrentUser lead = new CurrentUser(2L, TENANT_ID, "lead1", "主管1", "EMPLOYEE", 101L,
                Set.of("MANAGER_LEAD"), Set.of("customer:assign"), Set.of());
        SysUser otherTeamSteward = activeEmployee(20L, 202L, "跨团队管家");
        when(userRepository.findById(TENANT_ID, 20L)).thenReturn(Optional.of(otherTeamSteward));
        when(stewardDirectory.isEligibleSteward(TENANT_ID, otherTeamSteward)).thenReturn(true);
        when(stewardDirectory.canAssignTo(lead, otherTeamSteward)).thenReturn(false);

        assertThatThrownBy(() -> service.assignOrderSteward(
                lead, SHOP_ID, "E-CROSS-1", 700L,
                new YouzanOrderAssignRequest(20L, "越权分配", 0L, false, null), "key-cross"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private static SysUser activeEmployee(long id, Long teamId, String displayName) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setTenantId(TENANT_ID);
        user.setTeamId(teamId);
        user.setDisplayName(displayName);
        user.setUserType("EMPLOYEE");
        user.setStatus(SysUser.STATUS_ACTIVE);
        return user;
    }

    private static String payloadWithMemo(String tradeMemo) {
        return """
                {"code":200,"success":true,"data":{"full_order_info":{
                  "remark_info":{"trade_memo":"%s"},
                  "pay_info":{"pay_way_desc":"微信支付","payment":"100.00"},
                  "order_info":{"tid":"E-UNIT","status":"WAIT_BUYER_CONFIRM_GOODS","status_str":"已发货"}
                }}}
                """.formatted(tradeMemo);
    }

    private static final class SimpleTxManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
