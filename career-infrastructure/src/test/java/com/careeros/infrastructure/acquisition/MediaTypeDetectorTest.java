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

    @Test void detectsDocxFromOfficeZipEntries() throws Exception {
        assertThat(detector.detect(URI.create("https://host/file"), "application/octet-stream", docxBytes()))
            .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    @Test void detectsGenericZipWhenItIsNotAnOfficeDocument() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("附件".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThat(detector.detect(URI.create("https://host/attachments"), "application/octet-stream", bytes.toByteArray()))
            .isEqualTo("application/zip");
    }

    @Test void htmlErrorPageNamedXlsxRemainsHtml() {
        byte[] html = "<!doctype html><title>error</title>".getBytes(StandardCharsets.UTF_8);
        assertThat(detector.detect(URI.create("https://host/jobs.xlsx"), "application/octet-stream", html))
            .isEqualTo("text/html");
    }

    @Test void detectsPngByMagicBytesWhenTheOfficialEndpointHasNoFileExtension() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x01};

        assertThat(detector.detect(URI.create("https://host/image?id=42"),
            "application/octet-stream", png)).isEqualTo("image/png");
    }

    @Test void detectsJpegByMagicBytesInsteadOfTrustingAnOctetStreamHeader() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};

        assertThat(detector.detect(URI.create("https://host/download"),
            "application/octet-stream", jpeg)).isEqualTo("image/jpeg");
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

    private static byte[] docxBytes() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("types".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("document".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }
}
