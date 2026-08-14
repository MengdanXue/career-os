package com.careeros.crawler;

import com.careeros.crawler.service.PdfGuidePipeline;

import java.nio.file.Path;

public final class PdfGuideApp {
    private PdfGuideApp() {}

    public static void main(String[] args) throws Exception {
        Path pdf = args.length >= 1 ? Path.of(args[0])
                : Path.of("..", "..", "output", "career-os-samples", "raw", "08-zj-2025-applicant-guide.pdf");
        Path schema = args.length >= 2 ? Path.of(args[1])
                : Path.of("..", "..", "output", "career-os-samples", "recruitment-rule.schema.json");
        Path output = args.length >= 3 ? Path.of(args[2])
                : Path.of("..", "..", "output", "career-os-samples", "actual", "s08");
        if (args.length > 3) {
            System.err.println("Usage: PdfGuideApp [guide.pdf] [recruitment-rule.schema.json] [output-dir]");
            System.exit(2);
        }
        PdfGuidePipeline.Result result = new PdfGuidePipeline().run(pdf, schema, output);
        System.out.printf("Parsed %d pages, %d sections and %d questions.%nOutput: %s%n",
                result.document().source().pages(), result.document().sections().size(),
                result.document().extraction().extractedQuestions(), result.summaryFile().toAbsolutePath());
    }
}
