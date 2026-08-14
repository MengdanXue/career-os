package com.careeros.crawler;

import com.careeros.crawler.service.S07Pipeline;

import java.nio.file.Path;

public final class App {
    private App() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 3) {
            System.err.println("Usage: java -jar crawler-service.jar [input.docx] [job.schema.json] [output-dir]");
            System.exit(2);
        }
        Path document = args.length >= 1
                ? Path.of(args[0])
                : Path.of("..", "..", "output", "career-os-samples", "raw", "07-hdu-2026-dispatch-plan.docx");
        Path schema = args.length >= 2
                ? Path.of(args[1])
                : Path.of("..", "..", "output", "career-os-samples", "job.schema.json");
        Path output = args.length >= 3 ? Path.of(args[2]) : Path.of("target", "s07-output");

        S07Pipeline.Result result = new S07Pipeline().run(document, schema, output);
        System.out.printf(
                "Parsed %d jobs; IT candidates %d (auto %d, review %d).%nOutput: %s%n",
                result.parsedJobs(), result.candidates(), result.autoRelated(), result.needsReview(),
                result.summaryFile().toAbsolutePath()
        );
    }
}
