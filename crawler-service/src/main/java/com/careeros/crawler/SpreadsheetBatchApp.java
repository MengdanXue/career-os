package com.careeros.crawler;

import com.careeros.crawler.parser.SpreadsheetCatalog;
import com.careeros.crawler.service.SpreadsheetBatchPipeline;

import java.nio.file.Path;

public final class SpreadsheetBatchApp {
    private SpreadsheetBatchApp() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 3) {
            System.err.println("Usage: SpreadsheetBatchApp [samples-dir] [job.schema.json] [output-dir]");
            System.exit(2);
        }
        Path samples = args.length >= 1 ? Path.of(args[0])
                : Path.of("..", "..", "output", "career-os-samples", "raw");
        Path schema = args.length >= 2 ? Path.of(args[1])
                : Path.of("..", "..", "output", "career-os-samples", "job.schema.json");
        Path output = args.length >= 3 ? Path.of(args[2])
                : Path.of("..", "..", "output", "career-os-samples", "actual", "spreadsheets");

        SpreadsheetBatchPipeline.Result result = new SpreadsheetBatchPipeline().run(
                SpreadsheetCatalog.defaultEntries(samples), schema, output
        );
        System.out.printf(
                "Parsed %d spreadsheet jobs; IT candidates %d (auto %d, review %d).%nOutput: %s%n",
                result.jobs().size(), result.candidates().size(), result.autoRelated(),
                result.candidates().size() - result.autoRelated(), result.summaryFile().toAbsolutePath()
        );
    }
}
