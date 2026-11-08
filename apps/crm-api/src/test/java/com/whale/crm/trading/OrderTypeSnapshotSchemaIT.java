package com.whale.crm.trading;

import com.mysql.cj.jdbc.MysqlDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTypeSnapshotSchemaIT {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("medical_crm")
            .withUrlParam("connectionTimeZone", "UTC")
            .withCommand("--log-bin-trust-function-creators=1");

    static {
        MYSQL.start();
    }

    @Test
    void v18DoesNotBackfillAndRollbackOnlyRemovesV18Artifacts() throws Exception {
        MysqlDataSource dataSource = new MysqlDataSource();
        dataSource.setURL(MYSQL.getJdbcUrl());
        dataSource.setUser("root");
        dataSource.setPassword(MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .baselineOnMigrate(true).target("17").load().migrate();
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update("INSERT INTO crm_customer (id,tenant_id,owner_staff_id,customer_no,nickname) "
                + "VALUES (1,1,1,'V18-CUSTOMER','V18历史订单fixture')");
        jdbcTemplate.update("INSERT INTO crm_order (tenant_id,order_no,customer_id,sold_by_user_id,owner_at_sale_id,"
                + "source_system,order_type_code,status,receiver_type,list_amount_fen,discount_amount_fen,"
                + "payable_fen,paid_fen,refunded_fen) VALUES (1,'V18-LEGACY',1,1,1,'CRM','REGULAR',"
                + "'DRAFT','PLATFORM',0,0,0,0,0)");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .baselineOnMigrate(true).load().migrate();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_type_label_snapshot FROM crm_order WHERE order_no='V18-LEGACY'", String.class)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() AND trigger_name='trg_order_type_label_snapshot_no_update'",
                Long.class)).isEqualTo(1L);

        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                    "db/rollback/V18__p2_order_type_snapshot_rollback.sql"));
        }

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='crm_order' "
                + "AND column_name='order_type_label_snapshot'", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() AND trigger_name='trg_order_type_label_snapshot_no_update'",
                Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='crm_order' "
                + "AND column_name='campaign_code_snapshot'", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() AND trigger_name='trg_order_sale_snapshot_no_update'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM crm_order WHERE order_no='V18-LEGACY'", Long.class)).isEqualTo(1L);
    }
}
