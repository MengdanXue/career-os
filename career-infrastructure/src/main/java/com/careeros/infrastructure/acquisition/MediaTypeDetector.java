package com.careeros.infrastructure.acquisition;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;

public final class MediaTypeDetector {
    public static final String HTML = "text/html";
    public static final String PDF = "application/pdf";
    public static final String XLS = "application/vnd.ms-excel";
    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public String detect(URI uri, String header, byte[] content) {
        if (startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return PDF;
        if (startsWith(content, new byte[] {(byte)0xD0,(byte)0xCF,0x11,(byte)0xE0,(byte)0xA1,(byte)0xB1,0x1A,(byte)0xE1})) return XLS;
        if (looksLikeHtml(content)) return HTML;
        if (isXlsx(content)) return XLSX;
        String normalizedHeader = header == null ? "" : header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (Set.of(HTML, "application/xhtml+xml", PDF, XLS, XLSX).contains(normalizedHeader)) return normalizedHeader;
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (path.endsWith(".pdf")) return PDF;
        if (path.endsWith(".xls")) return XLS;
        if (path.endsWith(".xlsx")) return XLSX;
        return normalizedHeader.isBlank() ? "application/octet-stream" : normalizedHeader;
    }

    private static boolean looksLikeHtml(byte[] content) {
        String prefix = new String(content, 0, Math.min(content.length, 256), StandardCharsets.UTF_8)
            .replace("\uFEFF", "").stripLeading().toLowerCase(Locale.ROOT);
        return prefix.startsWith("<html") || prefix.startsWith("<!doctype html")
            || prefix.startsWith("<?xml") || prefix.startsWith("<head") || prefix.startsWith("<body");
    }

    private static boolean isXlsx(byte[] content) {
        if (!startsWith(content, new byte[] {'P','K'})) return false;
        Set<String> names = new HashSet<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
                if (names.contains("[Content_Types].xml") && names.contains("xl/workbook.xml")) return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value == null || value.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (value[i] != prefix[i]) return false;
        return true;
    }
}
