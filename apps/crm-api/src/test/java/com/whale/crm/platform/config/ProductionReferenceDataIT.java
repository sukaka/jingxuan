package com.whale.crm.platform.config;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReferenceDataIT {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("medical_crm")
            .withUrlParam("connectionTimeZone", "UTC")
            .withCommand("--log-bin-trust-function-creators=1");

    static {
        MYSQL.start();
    }

    @Test
    void cleanProductionHasCoreReferenceDataAndRepeatSeedPreservesOperationsChanges() {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl());
        dataSource.setUser("root");
        dataSource.setPassword(MYSQL.getPassword());
        productionFlyway(dataSource).migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<String> options = jdbc.queryForList("SELECT CONCAT(t.dict_code,':',i.option_code) "
                + "FROM sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                + "AND t.tenant_id=i.tenant_id WHERE i.tenant_id=1 AND i.status='ACTIVE'", String.class);
        assertThat(options).contains("orderTypes:REGULAR", "orderTypes:FLASH", "orderTypes:PROMOTION",
                "institutionBusinessTypes:MED_AESTHETIC", "institutionOwnerships:PRIVATE", "merchantGrades:B",
                "settlementTypes:MONTHLY", "settlementTypes:PER_REDEMPTION");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class)).isZero();

        jdbc.update("UPDATE sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                + "SET i.option_label='运营自定义订单',i.sort_no=88,i.status='INACTIVE' "
                + "WHERE i.tenant_id=1 AND t.tenant_id=1 AND t.dict_code='orderTypes' AND i.option_code='REGULAR'");
        productionFlyway(dataSource).migrate();

        Map<String, Object> customized = jdbc.queryForMap("SELECT i.option_label,i.sort_no,i.status "
                + "FROM sys_dictionary_item i JOIN sys_dictionary_type t ON t.id=i.dict_type_id "
                + "WHERE i.tenant_id=1 AND t.tenant_id=1 AND t.dict_code='orderTypes' AND i.option_code='REGULAR'");
        assertThat(customized).containsEntry("option_label", "运营自定义订单")
                .containsEntry("sort_no", 88).containsEntry("status", "INACTIVE");
    }

    private Flyway productionFlyway(MysqlDataSource dataSource) {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/prod").load();
    }
}
