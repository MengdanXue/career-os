package com.careeros.crawler;

import com.careeros.crawler.domain.RecruitmentRuleDocument;
import com.careeros.crawler.domain.RecruitmentRuleDocument.RuleItem;
import com.careeros.crawler.parser.PdfGuideParser;
import com.careeros.crawler.service.PdfGuidePipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfGuideParserTest {
    private static final Path PDF = Path.of("..", "..", "output", "career-os-samples", "raw", "08-zj-2025-applicant-guide.pdf");
    private static final Path SCHEMA = Path.of("..", "..", "output", "career-os-samples", "recruitment-rule.schema.json");

    @Test
    void extractsTheCompleteGuideStructure() throws Exception {
        RecruitmentRuleDocument document = new PdfGuideParser().parse(PDF);

        assertEquals(20, document.source().pages());
        assertEquals(15, document.sections().size());
        assertEquals(79, document.sections().stream().mapToInt(section -> section.rules().size()).sum());
        assertEquals("关于招聘信息查询", document.sections().get(0).title());
        assertEquals("其他有关问题", document.sections().get(14).title());
        assertEquals("59b0ccb3f374b2e60abf31532bed5c1c097c3db6bdaedc30cd01a6118513635b", document.source().fileSha256());
    }

    @Test
    void keepsCrossPageAnswersAndImportantRules() throws Exception {
        RecruitmentRuleDocument document = new PdfGuideParser().parse(PDF);

        RuleItem question3 = rule(document, 3);
        assertEquals(1, question3.startPage());
        assertEquals(2, question3.endPage());
        assertTrue(question3.answer().contains("微信公众号"));

        RuleItem question20 = rule(document, 20);
        assertTrue(question20.question().contains("35周岁以下"));
        assertTrue(question20.answer().contains("满18周岁"));
        assertTrue(question20.tags().contains("age"));

        RuleItem question25 = rule(document, 25);
        assertTrue(question25.question().contains("专业目录"));
        assertTrue(question25.tags().contains("major"));

        RuleItem question78 = rule(document, 78);
        assertTrue(question78.answer().contains("2025年3月26日"));
    }

    @Test
    void validatesAndWritesRuleArtifacts(@TempDir Path output) throws Exception {
        PdfGuidePipeline.Result result = new PdfGuidePipeline().run(PDF, SCHEMA, output);
        assertEquals(79, result.document().extraction().extractedQuestions());
        assertTrue(result.rulesFile().toFile().isFile());
        assertTrue(result.summaryFile().toFile().isFile());
    }

    private static RuleItem rule(RecruitmentRuleDocument document, int number) {
        return document.sections().stream().flatMap(section -> section.rules().stream())
                .filter(item -> item.questionNumber() == number).findFirst().orElseThrow();
    }
}
