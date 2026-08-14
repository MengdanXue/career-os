package com.careeros.crawler.service;

import com.careeros.crawler.domain.NormalizedJob;
import com.careeros.crawler.parser.OfficialSpreadsheetParser;
import com.careeros.crawler.parser.SpreadsheetCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpreadsheetBatchPipeline {
    private final ItClassifier classifier = new ItClassifier();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public Result run(List<SpreadsheetCatalog.Entry> entries, Path schemaPath, Path outputDirectory) throws IOException {
        OfficialSpreadsheetParser parser = new OfficialSpreadsheetParser(classifier);
        JobSchemaValidator validator = new JobSchemaValidator(schemaPath);
        List<NormalizedJob> allJobs = new ArrayList<>();
        List<Map<String, Object>> sourceSummaries = new ArrayList<>();

        for (SpreadsheetCatalog.Entry entry : entries) {
            OfficialSpreadsheetParser.ParseResult parsed = parser.parse(entry.file(), entry.config());
            for (NormalizedJob job : parsed.jobs()) validator.validate(mapper.writeValueAsString(job));
            allJobs.addAll(parsed.jobs());

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("sample_id", entry.config().sampleId());
            source.put("file", entry.file().getFileName().toString());
            source.put("format", extension(entry.file()));
            source.put("parsed_jobs", parsed.jobs().size());
            source.put("it_candidates", parsed.jobs().stream().filter(job -> classifier.isCandidate(job.classification())).count());
            source.put("auto_it_related", parsed.jobs().stream().filter(job -> job.classification().isItRelated()).count());
            source.put("sheets", parsed.sheets());
            sourceSummaries.add(source);
        }

        List<NormalizedJob> candidates = allJobs.stream()
                .filter(job -> classifier.isCandidate(job.classification())).toList();
        long autoRelated = allJobs.stream().filter(job -> job.classification().isItRelated()).count();
        Files.createDirectories(outputDirectory);
        Path jobsFile = outputDirectory.resolve("jobs.json");
        Path candidatesFile = outputDirectory.resolve("it-candidates.json");
        Path summaryFile = outputDirectory.resolve("summary.json");
        mapper.writeValue(jobsFile.toFile(), allJobs);
        mapper.writeValue(candidatesFile.toFile(), candidates);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("samples", entries.size());
        summary.put("parsed_jobs", allJobs.size());
        summary.put("it_candidates", candidates.size());
        summary.put("auto_it_related", autoRelated);
        summary.put("needs_review", candidates.size() - autoRelated);
        summary.put("schema_validated", true);
        summary.put("sources", sourceSummaries);
        mapper.writeValue(summaryFile.toFile(), summary);
        return new Result(allJobs, candidates, autoRelated, jobsFile, candidatesFile, summaryFile);
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    public record Result(
            List<NormalizedJob> jobs,
            List<NormalizedJob> candidates,
            long autoRelated,
            Path jobsFile,
            Path candidatesFile,
            Path summaryFile
    ) {}
}
