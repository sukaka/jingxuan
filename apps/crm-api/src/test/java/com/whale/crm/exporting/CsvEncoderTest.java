package com.whale.crm.exporting;

import com.whale.crm.exporting.application.CsvEncoder;
import com.whale.crm.exporting.domain.ExportColumn;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsvEncoderTest {
    private final CsvEncoder encoder = new CsvEncoder();

    @Test
    void escapesCsvAndNeutralizesSpreadsheetFormulas() {
        assertThat(encoder.encode("plain")).isEqualTo("plain");
        assertThat(encoder.encode("a,b")).isEqualTo("\"a,b\"");
        assertThat(encoder.encode("a\"b")).isEqualTo("\"a\"\"b\"");
        assertThat(encoder.encode("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(encoder.encode("=1+1")).isEqualTo("'=1+1");
        assertThat(encoder.encode("+cmd")).isEqualTo("'+cmd");
        assertThat(encoder.encode("-2+3")).isEqualTo("'-2+3");
        assertThat(encoder.encode("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(encoder.encode("  =1+1")).isEqualTo("'  =1+1");
        assertThat(encoder.encode(12345L)).isEqualTo("12345");
    }

    @Test
    void writesOnlyWhitelistedColumnsInDeclaredOrder() throws Exception {
        List<ExportColumn> columns = List.of(
                new ExportColumn("customerNo", "客户编号"),
                new ExportColumn("remark", "备注"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("remark", "需要,复核");
        row.put("customerNo", "C-X01");
        row.put("mobileCipher", "must-not-leak");
        StringWriter output = new StringWriter();

        encoder.writeHeader(output, columns);
        encoder.writeRow(output, columns, row);

        assertThat(output.toString()).isEqualTo("客户编号,备注\r\nC-X01,\"需要,复核\"\r\n");
        assertThat(output.toString()).doesNotContain("must-not-leak");
    }
}
