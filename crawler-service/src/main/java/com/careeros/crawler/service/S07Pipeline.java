package com.careeros.crawler.service;

import com.careeros.crawler.domain.NormalizedJob;
import com.careeros.crawler.parser.HduDispatchDocxParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class S07Pipeline {
    private final ItClassifier classifier;
    private final ObjectMapper mapper;

    public S07Pipeline() {
        this.classifier = new ItClassifier();
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public Result run(Path document, Path schemaPath, Path outputDirectory) throws IOException {
        HduDispatchDocxParser parser = new HduDispatchDocxParser(classifier);
        List<NormalizedJob> jobs = parser.parse(document);
        JobSchemaValidator validator = new JobSchemaValidator(schemaPath);
        for (NormalizedJob job : jobs) validator.validate(mapper.writeValueAsString(job));

        List<NormalizedJob> candidates = jobs.stream()
                .filter(job -> classifier.isCandidate(job.classification()))
                .toList();
        long autoRelated = jobs.stream().filter(job -> job.classification().isItRelated()).count();
        long needsReview = candidates.size() - autoRelated;

        Files.createDirectories(outputDirectory);
        Path jobsFile = outputDirectory.resolve("jobs.json");
        Path candidatesFile = outputDirectory.resolve("it-candidates.json");
        Path summaryFile = outputDirectory.resolve("summary.json");
        mapper.writeValue(jobsFile.toFile(), jobs);
        mapper.writeValue(candidatesFile.toFile(), candidates);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("source_sample", "S07");
        summary.put("parsed_jobs", jobs.size());
        summary.put("it_candidates", candidates.size());
        summary.put("auto_it_related", autoRelated);
        summary.put("needs_review", needsReview);
        summary.put("schema_validated", true);
        summary.put("input_sha256", jobs.isEmpty() ? null : jobs.get(0).source().fileSha256());
        mapper.writeValue(summaryFile.toFile(), summary);
        return new Result(jobs.size(), candidates.size(), autoRelated, needsReview, jobsFile, candidatesFile, summaryFile);
    }

    public record Result(
            int parsedJobs,
            int candidates,
            long autoRelated,
            long needsReview,
            Path jobsFile,
            Path candidatesFile,
            Path summaryFile
    ) {}
}
