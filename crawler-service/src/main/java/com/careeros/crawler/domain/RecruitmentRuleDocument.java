package com.careeros.crawler.domain;

import java.util.List;

public record RecruitmentRuleDocument(
        String schemaVersion,
        String documentId,
        Source source,
        List<Section> sections,
        Extraction extraction
) {
    public record Source(
            String sampleId,
            String title,
            String announcementUrl,
            String attachmentUrl,
            String retrievedAt,
            String fileName,
            String fileSha256,
            int pages,
            boolean textLayer
    ) {}

    public record Section(
            String number,
            String title,
            int startPage,
            int endPage,
            List<RuleItem> rules
    ) {}

    public record RuleItem(
            String ruleId,
            int questionNumber,
            String question,
            String answer,
            int startPage,
            int endPage,
            List<String> tags,
            String evidenceText
    ) {}

    public record Extraction(
            String parser,
            String parserVersion,
            int extractedQuestions,
            String reviewStatus,
            List<String> warnings
    ) {}
}
