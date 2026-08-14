package com.careeros.crawler.service;

import com.careeros.crawler.domain.RecruitmentRuleDocument;
import com.careeros.crawler.parser.PdfGuideParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PdfGuidePipeline {
    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public Result run(Path pdf, Path schemaPath, Path outputDirectory) throws IOException {
        RecruitmentRuleDocument document = new PdfGuideParser().parse(pdf);
        JsonSchemaContractValidator validator = new JsonSchemaContractValidator(schemaPath);
        validator.validate(mapper.writeValueAsString(document));

        Files.createDirectories(outputDirectory);
        Path rulesFile = outputDirectory.resolve("recruitment-rules.json");
        Path summaryFile = outputDirectory.resolve("summary.json");
        mapper.writeValue(rulesFile.toFile(), document);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source_sample", "S08");
        summary.put("pages", document.source().pages());
        summary.put("sections", document.sections().size());
        summary.put("questions", document.extraction().extractedQuestions());
        summary.put("schema_validated", true);
        summary.put("text_layer", document.source().textLayer());
        summary.put("file_sha256", document.source().fileSha256());
        mapper.writeValue(summaryFile.toFile(), summary);
        return new Result(document, rulesFile, summaryFile);
    }

    public record Result(RecruitmentRuleDocument document, Path rulesFile, Path summaryFile) {}
}
