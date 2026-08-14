package com.careeros.crawler;

import com.careeros.crawler.domain.NormalizedJob;
import com.careeros.crawler.parser.HduDispatchDocxParser;
import com.careeros.crawler.service.ItClassifier;
import com.careeros.crawler.service.S07Pipeline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S07PipelineTest {
    private static final Path SAMPLE = Path.of("..", "..", "output", "career-os-samples", "raw", "07-hdu-2026-dispatch-plan.docx");
    private static final Path SCHEMA = Path.of("..", "..", "output", "career-os-samples", "job.schema.json");

    @Test
    void parsesEightJobsAndPreservesGoldRow() throws Exception {
        List<NormalizedJob> jobs = new HduDispatchDocxParser(new ItClassifier()).parse(SAMPLE);

        assertEquals(8, jobs.size());
        NormalizedJob dataPlatform = job(jobs, "数据平台开发与运维岗");
        assertEquals("job_da7c14a2d1c3c3ff", dataPlatform.jobId());
        assertEquals(1, dataPlatform.position().headcount());
        assertEquals("专技", dataPlatform.position().category());
        assertEquals("硕士及以上", dataPlatform.requirements().degree());
        assertEquals(List.of("计算机科学与技术", "电子信息", "软件工程", "大数据", "网络安全"), dataPlatform.requirements().majors());
        assertEquals("yc@hdu.edu.cn", dataPlatform.application().contacts().get(0).email());
        assertTrue(dataPlatform.classification().isItRelated());
        assertEquals(0.99, dataPlatform.classification().score());
    }

    @Test
    void separatesAutomaticAndReviewCandidates() throws Exception {
        List<NormalizedJob> jobs = new HduDispatchDocxParser(new ItClassifier()).parse(SAMPLE);

        long candidates = jobs.stream().filter(job -> job.classification().score() >= 0.40).count();
        long automatic = jobs.stream().filter(job -> job.classification().isItRelated()).count();
        NormalizedJob informationOffice = job(jobs, "办公室信息化管理岗");

        assertEquals(3, candidates);
        assertEquals(2, automatic);
        assertFalse(informationOffice.classification().isItRelated());
        assertEquals(0.50, informationOffice.classification().score());
    }

    @Test
    void validatesEveryJobAndWritesPipelineArtifacts(@TempDir Path output) throws Exception {
        S07Pipeline.Result result = new S07Pipeline().run(SAMPLE, SCHEMA, output);

        assertEquals(8, result.parsedJobs());
        assertEquals(3, result.candidates());
        assertEquals(2, result.autoRelated());
        assertEquals(1, result.needsReview());
        assertTrue(result.jobsFile().toFile().isFile());
        assertTrue(result.candidatesFile().toFile().isFile());
        assertTrue(result.summaryFile().toFile().isFile());

        JsonNode summary = new ObjectMapper().readTree(result.summaryFile().toFile());
        assertTrue(summary.get("schema_validated").asBoolean());
        assertEquals(8, summary.get("parsed_jobs").asInt());
    }

    private static NormalizedJob job(List<NormalizedJob> jobs, String title) {
        return jobs.stream().filter(job -> job.position().title().equals(title)).findFirst().orElseThrow();
    }
}
