package com.careeros.crawler.domain;

import java.util.List;

public record NormalizedJob(
        String schemaVersion,
        String jobId,
        Source source,
        Employer employer,
        Position position,
        Requirements requirements,
        Application application,
        Classification classification,
        Extraction extraction
) {
    public record Source(
            String sampleId,
            String announcementTitle,
            String announcementUrl,
            String attachmentUrl,
            String publishedAt,
            String retrievedAt,
            String fileName,
            String fileSha256,
            Locator locator,
            String evidenceText
    ) {}

    public record Locator(
            String kind,
            String sheet,
            Integer tableIndex,
            Integer page,
            Integer row,
            String coordinateBasis
    ) {}

    public record Employer(String name, String department, String organizationType) {}

    public record Position(
            String title,
            String positionCode,
            String positionLevel,
            String category,
            Integer headcount,
            String employmentType,
            List<String> locations,
            List<String> responsibilities
    ) {}

    public record Requirements(
            String applicantType,
            String education,
            String degree,
            String majorText,
            List<String> majors,
            Age age,
            Double experienceYearsMin,
            String politicalStatus,
            List<String> certifications,
            String householdRegistration,
            String genderCondition,
            List<String> skills,
            List<String> otherConditions
    ) {}

    public record Age(String operator, Integer years, String birthDateBoundary, String rawText) {}

    public record Application(
            String startAt,
            String deadline,
            String method,
            String applicationUrl,
            List<Contact> contacts
    ) {}

    public record Contact(String name, String phone, String email) {}

    public record Classification(
            boolean isItRelated,
            double score,
            List<String> keywordHits,
            List<String> tags,
            String reason
    ) {}

    public record Extraction(
            String parser,
            String parserVersion,
            double confidence,
            String reviewStatus,
            List<String> warnings
    ) {}
}
