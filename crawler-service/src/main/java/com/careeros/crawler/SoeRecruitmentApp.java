package com.careeros.crawler;

import com.careeros.crawler.domain.RecruitmentSourceScan;
import com.careeros.crawler.parser.SpreadsheetCatalog;
import com.careeros.crawler.parser.SpreadsheetSourceConfig;
import com.careeros.crawler.service.OfficialFileDownloader;
import com.careeros.crawler.service.SoeRecruitmentPipeline;
import com.careeros.crawler.service.SpreadsheetBatchPipeline;

import java.nio.file.Path;
import java.util.List;

public final class SoeRecruitmentApp {
    private SoeRecruitmentApp() {}

    public static void main(String[] args) throws Exception {
        if (args.length > 3) {
            System.err.println("Usage: SoeRecruitmentApp [source-scan.schema.json] [job.schema.json] [output-root]");
            System.exit(2);
        }
        Path scanSchema = args.length >= 1
                ? Path.of(args[0])
                : Path.of("..", "..", "output", "career-os-samples", "source-scan.schema.json");
        Path jobSchema = args.length >= 2
                ? Path.of(args[1])
                : Path.of("..", "..", "output", "career-os-samples", "job.schema.json");
        Path outputRoot = args.length >= 3
                ? Path.of(args[2])
                : Path.of("..", "..", "output", "career-os-samples", "actual", "soe");

        SoeRecruitmentPipeline.Result result = new SoeRecruitmentPipeline().runLive(
                scanSchema, outputRoot.resolve("scan")
        );
        RecruitmentSourceScan.Item latest = result.s09().items().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("S09 canonical page has no current announcement"));
        RecruitmentSourceScan.Attachment workbook = latest.attachments().stream()
                .filter(attachment -> attachment.format().equals("xlsx") || attachment.format().equals("xls"))
                .findFirst().orElseThrow(() -> new IllegalStateException("Latest announcement has no spreadsheet attachment"));
        String extension = workbook.format().equals("xls") ? "xls" : "xlsx";
        Path workbookFile = outputRoot.resolve("downloads").resolve("s09-" + latest.itemId() + "-plan." + extension);
        OfficialFileDownloader.DownloadResult download = new OfficialFileDownloader().download(workbook.url(), workbookFile);

        SpreadsheetSourceConfig config = new SpreadsheetSourceConfig(
                "S09", latest.title(), latest.detailUrl(), workbook.url(), latest.publishedAt(),
                employerFromTitle(latest.title()), "state_owned_enterprise", "社会招聘"
        );
        SpreadsheetBatchPipeline.Result jobs = new SpreadsheetBatchPipeline().run(
                List.of(new SpreadsheetCatalog.Entry(workbookFile, config)), jobSchema, outputRoot.resolve("jobs")
        );
        System.out.printf(
                "S09: %s, announcements=%d, attachments=%d%n" +
                        "S10: %s, organizations=%d, empty=%d%n" +
                        "Downloaded: %s (%d bytes, sha256=%s)%n" +
                        "Jobs: %d, IT candidates=%d (auto=%d, review=%d)%nOutput: %s%n",
                result.s09().state().value(), result.s09().metrics().announcementCount(),
                result.s09().metrics().attachmentCount(), result.s10().state().value(),
                result.s10().metrics().organizationCount(), result.s10().metrics().explicitEmptySectionCount(),
                download.file().toAbsolutePath(), download.sizeBytes(), download.sha256(),
                jobs.jobs().size(), jobs.candidates().size(), jobs.autoRelated(),
                jobs.candidates().size() - jobs.autoRelated(), jobs.summaryFile().toAbsolutePath()
        );
    }

    private static String employerFromTitle(String title) {
        int marker = title.indexOf("公开招聘");
        return marker > 0 ? title.substring(0, marker).trim() : "杭州资本所属企业";
    }
}
