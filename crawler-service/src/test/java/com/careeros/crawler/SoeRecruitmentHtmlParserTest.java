package com.careeros.crawler;

import com.careeros.crawler.domain.RecruitmentSourceScan;
import com.careeros.crawler.parser.SoeRecruitmentHtmlParser;
import com.careeros.crawler.parser.OfficialSpreadsheetParser;
import com.careeros.crawler.parser.SpreadsheetSourceConfig;
import com.careeros.crawler.service.ItClassifier;
import com.careeros.crawler.service.IncrementalJobStore;
import com.careeros.crawler.service.SoeRecruitmentPipeline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoeRecruitmentHtmlParserTest {
    private static final Path SAMPLES = Path.of("..", "..", "output", "career-os-samples");
    private static final Path HISTORICAL_S09 = SAMPLES.resolve("raw/09-hz-capital-recruiting.html");
    private static final Path S10 = SAMPLES.resolve("raw/10-hzfi-social-recruiting.html");
    private static final Path CURRENT_S09 = SAMPLES.resolve("actual/soe/raw/s09-current-page.html");
    private static final Path S09_DETAIL = SAMPLES.resolve("actual/soe/raw/s09-announcement-2427.html");
    private static final Path HANGYANG_PLAN = SAMPLES.resolve("actual/soe/raw/hangyang-2026-midyear-plan.xlsx");
    private static final Path SCHEMA = SAMPLES.resolve("source-scan.schema.json");
    private static final Path DELTA_SCHEMA = SAMPLES.resolve("job-delta.schema.json");
    private final SoeRecruitmentHtmlParser parser = new SoeRecruitmentHtmlParser();

    @Test
    void distinguishesStaleEmptyPageFromCanonicalPage() throws Exception {
        RecruitmentSourceScan stale = parser.parseHzCapital(
                Files.readString(HISTORICAL_S09),
                "https://hzzbco.com/joinRecruiting?current=147",
                "https://hzzbco.com/joinRecruiting?current=147",
                OffsetDateTime.parse("2026-08-14T00:00:00Z"), "0".repeat(64)
        );
        RecruitmentSourceScan current = parser.parseHzCapital(
                Files.readString(CURRENT_S09),
                SoeRecruitmentHtmlParser.HZ_CAPITAL_CANONICAL,
                SoeRecruitmentHtmlParser.HZ_CAPITAL_CANONICAL,
                OffsetDateTime.parse("2026-08-14T00:00:00Z"), "1".repeat(64)
        );

        assertEquals(RecruitmentSourceScan.State.EMPTY_INFERRED, stale.state());
        assertTrue(stale.warnings().stream().anyMatch(message -> message.contains("分页页码可能过期")));
        assertEquals(RecruitmentSourceScan.State.CONTENT_FOUND, current.state());
        assertEquals(6, current.items().size());
        assertEquals("2427", current.items().get(0).itemId());
        assertEquals("2026-08-10", current.items().get(0).publishedAt());
    }

    @Test
    void extractsAnnouncementCountsAndAttachments() throws Exception {
        SoeRecruitmentHtmlParser.AnnouncementDetail detail = parser.parseHzCapitalDetail(
                Files.readString(S09_DETAIL), "https://hzzbco.com/newDet_2427_8"
        );

        assertEquals("2427", detail.itemId());
        assertEquals("2026-08-10", detail.publishedAt());
        assertEquals(101, detail.announcedPositionCount());
        assertEquals(198, detail.announcedHeadcount());
        assertEquals(2, detail.attachments().size());
        assertEquals("xlsx", detail.attachments().get(0).format());
    }

    @Test
    void confirmsAllHzfiOrganizationSectionsAreEmpty() throws Exception {
        RecruitmentSourceScan scan = parser.parseHzfi(
                Files.readString(S10), SoeRecruitmentHtmlParser.HZFI_CANONICAL,
                SoeRecruitmentHtmlParser.HZFI_CANONICAL,
                OffsetDateTime.parse("2026-08-14T00:00:00Z"), "2".repeat(64)
        );

        assertEquals(RecruitmentSourceScan.State.EMPTY_CONFIRMED, scan.state());
        assertEquals(28, scan.metrics().organizationCount());
        assertEquals(28, scan.metrics().jobSectionCount());
        assertEquals(28, scan.metrics().explicitEmptySectionCount());
        assertTrue(scan.organizations().stream().anyMatch(section ->
                section.name().equals("杭州市数据集团有限公司")
                        && "hdg@hzfi.cn".equals(section.email())));
    }

    @Test
    void parsesTheNewlyDiscoveredHangyangWorkbook() throws Exception {
        ItClassifier classifier = new ItClassifier();
        OfficialSpreadsheetParser.ParseResult result = new OfficialSpreadsheetParser(classifier).parse(
                HANGYANG_PLAN,
                new SpreadsheetSourceConfig(
                        "S09", "杭氧集团股份有限公司公开招聘公告 (杭州资本所属上市企业）",
                        "https://hzzbco.com/newDet_2427_8", "https://hzzb-web.oss-cn-hangzhou.aliyuncs.com/hzzb/plan.xlsx",
                        "2026-08-10", "杭氧集团股份有限公司", "state_owned_enterprise", "社会招聘"
                )
        );

        assertEquals(101, result.jobs().size());
        assertEquals("证券事务管理", result.jobs().get(0).position().title());
        assertEquals("1", result.jobs().get(0).position().positionCode());
        assertTrue(result.jobs().stream().allMatch(job -> job.classification() != null));
    }

    @Test
    void validatesAndWritesSnapshotArtifacts(@TempDir Path output) throws Exception {
        SoeRecruitmentPipeline.Result result = new SoeRecruitmentPipeline().runSnapshots(
                HISTORICAL_S09, "https://hzzbco.com/joinRecruiting?current=147",
                S10, SoeRecruitmentHtmlParser.HZFI_CANONICAL, SCHEMA, output
        );
        assertTrue(result.s09File().toFile().isFile());
        assertTrue(result.s10File().toFile().isFile());
        assertTrue(result.summaryFile().toFile().isFile());
    }

    @Test
    void incrementalStoreIsStableAcrossRepeatedRuns(@TempDir Path output) throws Exception {
        ItClassifier classifier = new ItClassifier();
        OfficialSpreadsheetParser.ParseResult parsed = new OfficialSpreadsheetParser(classifier).parse(
                HANGYANG_PLAN,
                new SpreadsheetSourceConfig(
                        "S09", "杭氧集团股份有限公司公开招聘公告 (杭州资本所属上市企业）",
                        "https://hzzbco.com/newDet_2427_8", "https://hzzb-web.oss-cn-hangzhou.aliyuncs.com/hzzb/plan.xlsx",
                        "2026-08-10", "杭氧集团股份有限公司", "state_owned_enterprise", "社会招聘"
                )
        );
        IncrementalJobStore store = new IncrementalJobStore();
        Path state = output.resolve("state/s09-jobs.json");

        IncrementalJobStore.Result first = store.update(
                "S09", parsed.jobs(), state, DELTA_SCHEMA, output.resolve("first-delta.json")
        );
        IncrementalJobStore.Result second = store.update(
                "S09", parsed.jobs(), state, DELTA_SCHEMA, output.resolve("second-delta.json")
        );

        assertEquals(101, first.delta().added().size());
        assertEquals(0, first.delta().unchangedCount());
        assertEquals(0, second.delta().added().size());
        assertEquals(0, second.delta().changed().size());
        assertEquals(0, second.delta().removed().size());
        assertEquals(101, second.delta().unchangedCount());
        assertEquals(
                store.stableKey(parsed.jobs().get(0)),
                store.stableKey(parsed.jobs().get(0))
        );
    }
}
