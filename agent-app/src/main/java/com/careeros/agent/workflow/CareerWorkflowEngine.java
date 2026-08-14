package com.careeros.agent.workflow;

import com.careeros.agent.config.CareerOsProperties;
import com.careeros.agent.domain.EligibilityAssessment;
import com.careeros.agent.domain.IncrementalWatchlist;
import com.careeros.agent.domain.OpportunityTierAssessment;
import com.careeros.agent.service.CandidateProfileRepository;
import com.careeros.agent.service.EligibilityEngine;
import com.careeros.agent.service.IncrementalWatchlistService;
import com.careeros.agent.service.OpportunityTierClassifier;
import com.careeros.crawler.domain.JobDelta;
import com.careeros.crawler.domain.NormalizedJob;
import com.careeros.crawler.domain.RecruitmentSourceScan;
import com.careeros.crawler.parser.SpreadsheetCatalog;
import com.careeros.crawler.parser.SpreadsheetSourceConfig;
import com.careeros.crawler.service.IncrementalJobStore;
import com.careeros.crawler.service.OfficialFileDownloader;
import com.careeros.crawler.service.SoeRecruitmentPipeline;
import com.careeros.crawler.service.SpreadsheetBatchPipeline;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

@Service
public class CareerWorkflowEngine {
    private final CareerOsProperties properties;
    private final CandidateProfileRepository profiles;
    private final EligibilityEngine eligibility;
    private final OpportunityTierClassifier opportunityTiers;
    private final IncrementalWatchlistService watchlists;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public CareerWorkflowEngine(
            CareerOsProperties properties,
            CandidateProfileRepository profiles,
            EligibilityEngine eligibility,
            OpportunityTierClassifier opportunityTiers,
            IncrementalWatchlistService watchlists
    ) {
        this.properties = properties;
        this.profiles = profiles;
        this.eligibility = eligibility;
        this.opportunityTiers = opportunityTiers;
        this.watchlists = watchlists;
    }

    public ScanResult scanSources() throws Exception {
        SoeRecruitmentPipeline.Result result = new SoeRecruitmentPipeline().runLive(
                properties.samplesRoot().resolve("source-scan.schema.json"), scanDirectory()
        );
        return new ScanResult(
                result.s09().state().value(), result.s09().metrics().announcementCount(),
                result.s09().metrics().attachmentCount(), result.s10().state().value(),
                result.s10().metrics().organizationCount(), result.s10().metrics().explicitEmptySectionCount(),
                result.summaryFile().toAbsolutePath().toString()
        );
    }

    public ImportResult importLatestAttachment() throws Exception {
        Path scanFile = scanDirectory().resolve("s09-scan.json");
        if (!Files.isRegularFile(scanFile)) {
            throw new IllegalStateException("Missing S09 scan. Call scan_official_recruitment_sources first.");
        }
        RecruitmentSourceScan scan = mapper.readValue(scanFile.toFile(), RecruitmentSourceScan.class);
        RecruitmentSourceScan.Item latest = scan.items().stream()
                .max(Comparator.comparing(
                        RecruitmentSourceScan.Item::publishedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .orElseThrow(() -> new IllegalStateException("S09 has no announcement to import"));
        RecruitmentSourceScan.Attachment attachment = latest.attachments().stream()
                .filter(item -> item.format().equals("xlsx") || item.format().equals("xls"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Latest announcement has no spreadsheet attachment"));

        Path file = properties.outputRoot().resolve("downloads")
                .resolve("s09-" + latest.itemId() + "-plan." + attachment.format());
        OfficialFileDownloader.DownloadResult downloaded = new OfficialFileDownloader().download(attachment.url(), file);
        SpreadsheetSourceConfig config = new SpreadsheetSourceConfig(
                "S09", latest.title(), latest.detailUrl(), attachment.url(), latest.publishedAt(),
                employerFromTitle(latest.title()), "state_owned_enterprise", "社会招聘"
        );
        SpreadsheetBatchPipeline.Result jobs = new SpreadsheetBatchPipeline().run(
                List.of(new SpreadsheetCatalog.Entry(file, config)),
                properties.samplesRoot().resolve("job.schema.json"), jobsDirectory()
        );
        return new ImportResult(
                latest.itemId(), latest.title(), downloaded.file().toAbsolutePath().toString(),
                downloaded.sha256(), jobs.jobs().size(), jobs.candidates().size(), jobs.autoRelated(),
                jobs.candidates().size() - jobs.autoRelated(), jobs.summaryFile().toAbsolutePath().toString()
        );
    }

    public DeltaResult calculateChanges() throws Exception {
        Path jobsFile = jobsDirectory().resolve("jobs.json");
        if (!Files.isRegularFile(jobsFile)) {
            throw new IllegalStateException("Missing normalized jobs. Call import_latest_job_attachment first.");
        }
        List<NormalizedJob> jobs = mapper.readValue(jobsFile.toFile(), new TypeReference<>() {});
        IncrementalJobStore.Result result = new IncrementalJobStore().update(
                "S09", jobs, properties.outputRoot().resolve("state/s09-job-state.json"),
                properties.samplesRoot().resolve("job-delta.schema.json"),
                properties.outputRoot().resolve("delta/s09-job-delta.json")
        );
        JobDelta delta = result.delta();
        return new DeltaResult(
                delta.previousCount(), delta.currentCount(), delta.added().size(), delta.changed().size(),
                delta.removed().size(), delta.unchangedCount(), result.deltaFile().toAbsolutePath().toString()
        );
    }

    public MatchResult findItCandidates() throws IOException {
        Path candidatesFile = jobsDirectory().resolve("it-candidates.json");
        if (!Files.isRegularFile(candidatesFile)) {
            throw new IllegalStateException("Missing candidates. Call import_latest_job_attachment first.");
        }
        List<NormalizedJob> candidates = mapper.readValue(candidatesFile.toFile(), new TypeReference<>() {});
        List<Candidate> top = candidates.stream()
                .sorted(Comparator.comparingDouble((NormalizedJob job) -> job.classification().score()).reversed())
                .limit(10)
                .map(job -> new Candidate(
                        job.jobId(), job.employer().name(), job.position().title(), job.classification().score(),
                        job.classification().reason()
                ))
                .toList();
        return new MatchResult(candidates.size(), top, candidatesFile.toAbsolutePath().toString());
    }

    public EligibilityAssessment evaluateJobEligibility(String jobId) throws IOException {
        return eligibility.assess(profiles.load(), findJob(jobId));
    }

    public OpportunityTierAssessment classifyJobOpportunityTier(String jobId) throws IOException {
        return opportunityTiers.classify(findJob(jobId));
    }

    public IncrementalWatchlist generateIncrementalWatchlist() throws IOException {
        Path deltaFile = properties.outputRoot().resolve("delta/s09-job-delta.json");
        if (!Files.isRegularFile(deltaFile)) {
            throw new IllegalStateException("Missing job delta. Calculate incremental changes first.");
        }
        Path jobsFile = jobsDirectory().resolve("jobs.json");
        JobDelta delta = mapper.readValue(deltaFile.toFile(), JobDelta.class);
        List<NormalizedJob> jobs = mapper.readValue(jobsFile.toFile(), new TypeReference<>() {});
        IncrementalWatchlist report = watchlists.generate(delta, jobs, profiles.load());
        Path reportFile = properties.outputRoot().resolve("reports/latest-incremental-watchlist.json");
        Files.createDirectories(reportFile.toAbsolutePath().getParent());
        mapper.writeValue(reportFile.toFile(), report);
        return report;
    }

    private NormalizedJob findJob(String jobId) throws IOException {
        Path jobsFile = jobsDirectory().resolve("jobs.json");
        if (!Files.isRegularFile(jobsFile)) {
            throw new IllegalStateException("Missing normalized jobs. Import a recruitment attachment first.");
        }
        List<NormalizedJob> jobs = mapper.readValue(jobsFile.toFile(), new TypeReference<>() {});
        return jobs.stream()
                .filter(item -> item.jobId().equals(jobId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown job id: " + jobId));
    }

    public DailyRunReport runDaily() throws Exception {
        ScanResult scan = scanSources();
        ImportResult imported = importLatestAttachment();
        DeltaResult delta = calculateChanges();
        MatchResult matches = findItCandidates();
        IncrementalWatchlist watchlist = generateIncrementalWatchlist();
        DailyRunReport report = new DailyRunReport(
                OffsetDateTime.now(ZoneOffset.UTC), scan, imported, delta, matches, watchlist
        );
        Path reportFile = properties.outputRoot().resolve("reports/latest-daily-run.json");
        Files.createDirectories(reportFile.toAbsolutePath().getParent());
        mapper.writeValue(reportFile.toFile(), report);
        return report;
    }

    private Path scanDirectory() {
        return properties.outputRoot().resolve("scan");
    }

    private Path jobsDirectory() {
        return properties.outputRoot().resolve("jobs");
    }

    private static String employerFromTitle(String title) {
        int marker = title.indexOf("公开招聘");
        return marker > 0 ? title.substring(0, marker).trim() : "杭州资本所属企业";
    }

    public record ScanResult(
            String s09State, int s09Announcements, int s09Attachments,
            String s10State, int s10Organizations, int s10ExplicitEmptySections, String artifact
    ) {}

    public record ImportResult(
            String announcementId, String announcementTitle, String downloadedFile, String fileSha256,
            int parsedJobs, int itCandidates, long autoRelated, long needsReview, String artifact
    ) {}

    public record DeltaResult(
            int previousCount, int currentCount, int added, int changed, int removed, int unchanged, String artifact
    ) {}

    public record Candidate(String jobId, String employer, String title, double score, String reason) {}

    public record MatchResult(int candidateCount, List<Candidate> topCandidates, String artifact) {
        public MatchResult {
            topCandidates = List.copyOf(topCandidates);
        }
    }

    public record DailyRunReport(
            OffsetDateTime completedAt, ScanResult scan, ImportResult imported, DeltaResult delta,
            MatchResult matches, IncrementalWatchlist watchlist
    ) {}
}
