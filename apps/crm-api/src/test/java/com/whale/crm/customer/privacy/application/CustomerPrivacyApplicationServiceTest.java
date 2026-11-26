package com.whale.crm.customer.privacy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whale.crm.customer.application.CustomerPrivacyFieldSupport;
import com.whale.crm.customer.application.CustomerScopeSupport;
import com.whale.crm.customer.application.MobileSupport;
import com.whale.crm.customer.domain.Customer;
import com.whale.crm.customer.domain.CustomerRepository;
import com.whale.crm.customer.privacy.api.dto.ApprovePrivacyRequest;
import com.whale.crm.customer.privacy.api.dto.RevealCustomerPrivacyRequest;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyAccessLog;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyRepository;
import com.whale.crm.customer.privacy.domain.CustomerPrivacyRequest;
import com.whale.crm.notification.application.NotificationRequestService;
import com.whale.crm.platform.audit.application.AuditService;
import com.whale.crm.platform.idempotency.IdempotencyService;
import com.whale.crm.platform.security.CryptoProperties;
import com.whale.crm.platform.security.CurrentUser;
import com.whale.crm.platform.security.crypto.AesGcmCipher;
import com.whale.crm.platform.security.crypto.BlindIndex;
import com.whale.crm.platform.user.domain.UserRepository;
import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerPrivacyApplicationServiceTest {

    @Mock
    private CustomerPrivacyRepository privacyRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private CustomerScopeSupport scopeSupport;
    @Mock
    private CustomerPrivacyApproverResolver approverResolver;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditService auditService;
    @Mock
    private IdempotencyService idempotencyService;
    @Mock
    private NotificationRequestService notificationRequestService;

    private CustomerPrivacyFieldSupport fieldSupport;
    private CustomerPrivacyApplicationService service;
    private Customer customer;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        CryptoProperties props = new CryptoProperties(key, key);
        AesGcmCipher cipher = new AesGcmCipher(props);
        BlindIndex blindIndex = new BlindIndex(props);
        fieldSupport = new CustomerPrivacyFieldSupport(new MobileSupport(cipher, blindIndex), cipher, blindIndex);
        service = new CustomerPrivacyApplicationService(privacyRepository, customerRepository, scopeSupport,
                fieldSupport, approverResolver, userRepository, auditService, idempotencyService,
                notificationRequestService, new ObjectMapper());

        customer = new Customer();
        customer.setId(50L);
        customer.setTenantId(1L);
        customer.setCustomerNo("C0000000050");
        customer.setOwnerStaffId(2L);
        customer.setTeamId(1L);
        customer.setMobileCipher(fieldSupport.encryptMobile("13800138000"));
        customer.setEmailCipher(fieldSupport.encrypt("vip@example.com"));
        customer.setWechatIdCipher(fieldSupport.encrypt("wx_vip_9988"));
    }

    @Test
    void adminDirectReveal_requiresReasonAndWritesImmutableAccessLogWithoutPlaintext() {
        CurrentUser admin = new CurrentUser(1L, 1L, "admin", "管理员", "EMPLOYEE", null,
                Set.of("ADMIN"), Set.of("customer:read", "customer:privacy:admin_reveal"), Set.of());
        when(scopeSupport.requireVisible(1L, 50L, admin)).thenReturn(customer);

        assertThatThrownBy(() -> service.reveal(1L, admin, 50L,
                new RevealCustomerPrivacyRequest(List.of("MOBILE"), "   ", null, "ADMIN_DIRECT"),
                "127.0.0.1", "JUnit"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        doAnswer(inv -> {
            inv.getArgument(0, CustomerPrivacyAccessLog.class).setId(901L);
            return null;
        }).when(privacyRepository).insertAccessLog(any(CustomerPrivacyAccessLog.class));

        var res = service.reveal(1L, admin, 50L,
                new RevealCustomerPrivacyRequest(List.of("MOBILE", "EMAIL"), "核实客户投诉回访电话", null, "ADMIN_DIRECT"),
                "10.0.0.5", "Mozilla/5.0");

        assertThat(res.accessMode()).isEqualTo(CustomerPrivacyAccessLog.ACCESS_MODE_ADMIN_DIRECT);
        assertThat(res.values()).containsEntry("MOBILE", "13800138000")
                .containsEntry("EMAIL", "vip@example.com")
                .doesNotContainKey("WECHAT_ID");
        assertThat(res.accessLogId()).isEqualTo(901L);

        ArgumentCaptor<CustomerPrivacyAccessLog> logCaptor = ArgumentCaptor.forClass(CustomerPrivacyAccessLog.class);
        verify(privacyRepository).insertAccessLog(logCaptor.capture());
        assertThat(logCaptor.getValue().getMaskedValuesSnapshotJson())
                .contains("138****8000")
                .doesNotContain("13800138000")
                .doesNotContain("vip@example.com");
        verify(auditService).record(eq(1L), eq(1L), eq(AuditService.ACTION_CUSTOMER_PRIVACY_ADMIN_REVEAL),
                eq("CUSTOMER"), eq("50"), eq(null), any(), eq("核实客户投诉回访电话"));
    }

    @Test
    void nonAdminReveal_blocksWithoutGrantAndBlocksUnapprovedField() {
        CurrentUser manager = new CurrentUser(2L, 1L, "manager1", "王丽", "EMPLOYEE", 1L,
                Set.of("MANAGER"), Set.of("customer:read", "customer:privacy:request"), Set.of());
        when(scopeSupport.requireVisible(1L, 50L, manager)).thenReturn(customer);
        when(privacyRepository.findLatestActiveGrant(eq(1L), eq(2L), eq(50L), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reveal(1L, manager, 50L,
                new RevealCustomerPrivacyRequest(List.of("MOBILE"), null, null, null),
                "127.0.0.1", "JUnit"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(auditService).recordIndependent(eq(1L), eq(2L), eq(AuditService.ACTION_CUSTOMER_PRIVACY_REVEAL_DENIED),
                eq("CUSTOMER"), eq("50"), eq(null), any(), any());

        CustomerPrivacyRequest grant = new CustomerPrivacyRequest();
        grant.setId(88L);
        grant.setRequestNo("PR0000000088");
        grant.setCustomerId(50L);
        grant.setApplicantUserId(2L);
        grant.setStatus(CustomerPrivacyRequest.STATUS_APPROVED);
        grant.setApprovedFieldsJson("[\"MOBILE\"]");
        grant.setReason("回访预约");
        grant.setGrantStartsAt(Instant.now().minusSeconds(60));
        grant.setGrantExpiresAt(Instant.now().plusSeconds(1800));
        when(privacyRepository.findLatestActiveGrant(eq(1L), eq(2L), eq(50L), any())).thenReturn(Optional.of(grant));

        assertThatThrownBy(() -> service.reveal(1L, manager, 50L,
                new RevealCustomerPrivacyRequest(List.of("MOBILE", "EMAIL"), null, null, null),
                "127.0.0.1", "JUnit"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void approveRequest_strictlyForbidsSelfApproval() {
        CurrentUser lead = new CurrentUser(4L, 1L, "lead1", "张敏", "EMPLOYEE", 1L,
                Set.of("MANAGER_LEAD"), Set.of("customer:privacy:request", "customer:privacy:approve"), Set.of());
        CustomerPrivacyRequest pending = new CustomerPrivacyRequest();
        pending.setId(77L);
        pending.setTenantId(1L);
        pending.setCustomerId(50L);
        pending.setApplicantUserId(4L);
        pending.setAssignedApproverId(1L);
        pending.setStatus(CustomerPrivacyRequest.STATUS_PENDING);
        pending.setVersion(0L);
        when(privacyRepository.findRequestByIdForUpdate(1L, 77L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.approveRequest(1L, lead, 77L,
                new ApprovePrivacyRequest(List.of("MOBILE"), 30, "自审尝试", 0L)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).errorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(auditService).recordIndependent(eq(1L), eq(4L), eq(AuditService.ACTION_CUSTOMER_PRIVACY_REVEAL_DENIED),
                eq("CUSTOMER_PRIVACY_REQUEST"), eq("77"), eq(null), any(), any());
    }
}
