package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class MediaTypeDetectorTest {
    private final MediaTypeDetector detector = new MediaTypeDetector();

    @Test void detectsPdfByMagicBytes() {
        assertThat(detector.detect(URI.create("https://host/file"), "application/octet-stream",
            "%PDF-1.7".getBytes(StandardCharsets.US_ASCII))).isEqualTo("application/pdf");
    }

    @Test void detectsLegacyExcelByOleHeader() {
        byte[] bytes = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        assertThat(detector.detect(URI.create("https://host/file"), null, bytes))
            .isEqualTo("application/vnd.ms-excel");
    }

    @Test void detectsXlsxFromZipEntries() throws Exception {
        assertThat(detector.detect(URI.create("https://host/file.zip"), "application/zip", xlsxBytes()))
            .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    }

    @Test void htmlErrorPageNamedXlsxRemainsHtml() {
        byte[] html = "<!doctype html><title>error</title>".getBytes(StandardCharsets.UTF_8);
        assertThat(detector.detect(URI.create("https://host/jobs.xlsx"), "application/octet-stream", html))
            .isEqualTo("text/html");
    }

    private static byte[] xlsxBytes() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("types".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write("workbook".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
