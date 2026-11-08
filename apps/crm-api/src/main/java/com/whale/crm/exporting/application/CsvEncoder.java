package com.whale.crm.exporting.application;

import com.whale.crm.exporting.domain.ExportColumn;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.sql.Timestamp;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.Map;

@Component
public class CsvEncoder {
    public void writeHeader(Writer output, List<ExportColumn> columns) throws IOException {
        writeValues(output, columns.stream().map(ExportColumn::label).toList());
    }

    public void writeRow(Writer output, List<ExportColumn> columns, Map<String, Object> row) throws IOException {
        writeValues(output, columns.stream().map(column -> row.get(column.code())).toList());
    }

    public String encode(Object value) {
        if (value == null) {
            return "";
        }
        String text = switch (value) {
            case Timestamp timestamp -> timestamp.toInstant().toString();
            case java.sql.Date date -> date.toLocalDate().toString();
            case TemporalAccessor temporal -> temporal.toString();
            default -> String.valueOf(value);
        };
        if (formulaLike(text)) {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private void writeValues(Writer output, List<?> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                output.write(',');
            }
            output.write(encode(values.get(index)));
        }
        output.write("\r\n");
    }

    private boolean formulaLike(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        if (first == '\t' || first == '\r' || first == '\n') {
            return true;
        }
        String stripped = value.stripLeading();
        if (stripped.isEmpty()) {
            return false;
        }
        char candidate = stripped.charAt(0);
        return candidate == '=' || candidate == '+' || candidate == '-' || candidate == '@';
    }
}
