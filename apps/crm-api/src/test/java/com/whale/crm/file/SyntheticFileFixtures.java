package com.whale.crm.file;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SyntheticFileFixtures {
    static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private SyntheticFileFixtures() {
    }

    static byte[] cleanPdf() {
        return pdfWithComment(null);
    }

    static byte[] eicarPdf() {
        return pdfWithContent(EICAR);
    }

    static byte[] pdfWithComment(String comment) {
        return pdfWithContent(comment);
    }

    static byte[] pdfWithContent(String content) {
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Contents 4 0 R >>",
                "<< /Length " + (content == null ? 0 : bytes(content).length) + " >>\nstream\n"
                        + (content == null ? "" : content) + "\nendstream"
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write(output, "%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(output.size());
            write(output, (index + 1) + " 0 obj\n" + objects.get(index) + "\nendobj\n");
        }
        int xrefOffset = output.size();
        write(output, "xref\n0 " + (objects.size() + 1) + "\n");
        write(output, "0000000000 65535 f \n");
        for (int offset : offsets) {
            write(output, "%010d 00000 n \n".formatted(offset));
        }
        write(output, "trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\n");
        write(output, "startxref\n" + xrefOffset + "\n%%EOF\n");
        return output.toByteArray();
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void write(ByteArrayOutputStream output, String value) {
        output.writeBytes(bytes(value));
    }
}
