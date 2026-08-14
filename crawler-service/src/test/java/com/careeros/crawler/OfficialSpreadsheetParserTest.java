package com.careeros.crawler;

import com.careeros.crawler.domain.NormalizedJob;
import com.careeros.crawler.parser.OfficialSpreadsheetParser;
import com.careeros.crawler.parser.SpreadsheetCatalog;
import com.careeros.crawler.service.ItClassifier;
import com.careeros.crawler.service.SpreadsheetBatchPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialSpreadsheetParserTest {
    private static final Path RAW = Path.of("..", "..", "output", "career-os-samples", "raw");
    private static final Path SCHEMA = Path.of("..", "..", "output", "career-os-samples", "job.schema.json");
    private final OfficialSpreadsheetParser parser = new OfficialSpreadsheetParser(new ItClassifier());

    @Test
    void parsesAllFourLegacyXlsLayouts() throws Exception {
        OfficialSpreadsheetParser.ParseResult s01 = parse("S01");
        OfficialSpreadsheetParser.ParseResult s02 = parse("S02");
        OfficialSpreadsheetParser.ParseResult s03 = parse("S03");
        OfficialSpreadsheetParser.ParseResult s04 = parse("S04");

        assertEquals(5, s01.jobs().size());
        assertEquals(15, s02.jobs().size());
        assertEquals(289, s03.jobs().size());
        assertEquals(7, s04.jobs().size());
        assertEquals("no_job_header", s03.sheets().get(0).status());
        assertEquals(289, s03.sheets().get(1).parsedJobs());
    }

    @Test
    void preservesLegacyXlsGoldRowsAndMergedCells() throws Exception {
        NormalizedJob strategic = job(parse("S01").jobs(), "战略性新兴产业研究岗");
        assertEquals(2, strategic.position().headcount());
        assertEquals("本科以上", strategic.requirements().education());
        assertEquals("硕士以上", strategic.requirements().degree());
        assertEquals("不限", strategic.requirements().applicantType());
        assertEquals("1984-01-01", strategic.requirements().age().birthDateBoundary());
        assertEquals(0.55, strategic.classification().score());

        NormalizedJob respiratory = job(parse("S02").jobs(), "呼吸内科医师");
        assertEquals(35, respiratory.requirements().age().years());
        assertEquals("<", respiratory.requirements().age().operator());
        assertEquals("应届毕业生", respiratory.requirements().applicantType());

        NormalizedJob experiment = job(parse("S04").jobs(), "实验技术人员2");
        assertEquals("实验室处 （前沿科学中心）", experiment.employer().department());
        assertEquals("博士研究生", experiment.requirements().education());
        assertEquals("博士", experiment.requirements().degree());
        assertEquals("HSDZJ2402", experiment.position().positionCode());
        assertEquals("亓老师", experiment.application().contacts().get(0).name());
        assertEquals("20200122@hznu.edu.cn", experiment.application().contacts().get(0).email());

        NormalizedJob internetReport = job(parse("S03").jobs(), "互联网举报2");
        assertEquals("九级及以下", internetReport.position().positionLevel());
    }

    @Test
    void parsesModernXlsxThroughTheSamePath() throws Exception {
        OfficialSpreadsheetParser.ParseResult s05 = parse("S05");
        OfficialSpreadsheetParser.ParseResult s06 = parse("S06");
        assertEquals(2, s05.jobs().size());
        assertEquals(2, s06.jobs().size());

        NormalizedJob driver = job(s05.jobs(), "驾驶员");
        assertEquals("杭州市急救中心余杭分中心", driver.employer().name());
        assertEquals("高中及以上", driver.requirements().education());
        assertEquals("专业不限", driver.requirements().majorText());
        assertNull(driver.requirements().degree());
        assertFalse(driver.classification().isItRelated());
    }

    @Test
    void automaticallyRecognizesExplicitTechnicalTitles() throws Exception {
        List<NormalizedJob> jobs = parse("S03").jobs();
        for (String title : List.of("信息中心工作人员", "系统工程师", "数据分析工程师", "人工智能工程师")) {
            NormalizedJob job = job(jobs, title);
            assertTrue(job.classification().isItRelated(), title);
            assertTrue(job.classification().score() >= 0.70, title);
        }
    }

    @Test
    void validatesAndWritesTheSixWorkbookBatch(@TempDir Path output) throws Exception {
        SpreadsheetBatchPipeline.Result result = new SpreadsheetBatchPipeline().run(
                SpreadsheetCatalog.defaultEntries(RAW), SCHEMA, output
        );
        assertEquals(320, result.jobs().size());
        assertTrue(result.candidates().size() >= 12);
        assertTrue(result.autoRelated() >= 6);
        assertTrue(result.jobsFile().toFile().isFile());
        assertTrue(result.summaryFile().toFile().isFile());
    }

    private OfficialSpreadsheetParser.ParseResult parse(String sampleId) throws Exception {
        SpreadsheetCatalog.Entry entry = SpreadsheetCatalog.defaultEntries(RAW).stream()
                .filter(item -> item.config().sampleId().equals(sampleId)).findFirst().orElseThrow();
        return parser.parse(entry.file(), entry.config());
    }

    private static NormalizedJob job(List<NormalizedJob> jobs, String title) {
        return jobs.stream().filter(item -> item.position().title().equals(title)).findFirst().orElseThrow();
    }
}
