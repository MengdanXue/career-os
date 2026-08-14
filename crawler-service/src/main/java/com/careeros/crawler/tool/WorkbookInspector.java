package com.careeros.crawler.tool;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Read-only workbook probe used when onboarding a new official source. */
public final class WorkbookInspector {
    private static final int MAX_ROWS = 15;
    private static final int MAX_COLUMNS = 16;
    private static final int MAX_CELL_LENGTH = 100;

    private WorkbookInspector() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: WorkbookInspector <xls-or-xlsx> [...]");
            System.exit(2);
        }
        DataFormatter formatter = new DataFormatter();
        for (String argument : args) {
            Path path = Path.of(argument);
            System.out.println("FILE\t" + path.toAbsolutePath());
            try (InputStream input = Files.newInputStream(path);
                 Workbook workbook = WorkbookFactory.create(input)) {
                System.out.println("TYPE\t" + workbook.getClass().getSimpleName() + "\tSHEETS\t" + workbook.getNumberOfSheets());
                for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                    Sheet sheet = workbook.getSheetAt(sheetIndex);
                    System.out.printf("SHEET\t%d\t%s\tROWS\t%d%n", sheetIndex, sheet.getSheetName(), sheet.getLastRowNum() + 1);
                    int emitted = 0;
                    for (Row row : sheet) {
                        List<String> values = rowValues(row, formatter);
                        if (values.stream().allMatch(String::isBlank)) continue;
                        System.out.printf("ROW\t%d\t%s%n", row.getRowNum() + 1, String.join(" | ", values));
                        if (++emitted >= MAX_ROWS) break;
                    }
                }
            }
        }
    }

    private static List<String> rowValues(Row row, DataFormatter formatter) {
        int last = Math.min(Math.max(row.getLastCellNum(), 0), MAX_COLUMNS);
        List<String> values = new ArrayList<>(last);
        for (int column = 0; column < last; column++) {
            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String value = cell == null ? "" : formatter.formatCellValue(cell).replaceAll("\\s+", " ").trim();
            if (value.length() > MAX_CELL_LENGTH) value = value.substring(0, MAX_CELL_LENGTH) + "…";
            values.add(value);
        }
        while (!values.isEmpty() && values.get(values.size() - 1).isBlank()) values.remove(values.size() - 1);
        return values;
    }
}
