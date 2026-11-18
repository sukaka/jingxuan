package com.whale.crm.platform.security;

import com.whale.crm.platform.user.domain.DataScopeRepository;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DataScopeServiceTest {
    @Test
    void operationLeadHasCompanyScopeWithoutDependingOnLocalSeedRows() {
        DataScopeRepository repository = mock(DataScopeRepository.class);
        DataScopeService service = new DataScopeService(repository);
        CurrentUser lead = new CurrentUser(1L, 1L, "operationlead", "运营主管", "EMPLOYEE", null,
                Set.of("OPERATION_LEAD"), Set.of("report:read"), Set.of());

        DataScope scope = service.scopeFor(lead, DataScopeService.RESOURCE_CUSTOMER);

        assertThat(scope.type()).isEqualTo(DataScope.ScopeType.ALL);
        verifyNoInteractions(repository);
    }
}
