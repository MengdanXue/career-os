package com.careeros.agent.service;

import com.careeros.agent.config.CareerOsProperties;
import com.careeros.agent.domain.CandidateProfile;
import com.careeros.agent.domain.IncrementalWatchlist;
import com.careeros.crawler.domain.JobDelta;
import com.careeros.crawler.domain.NormalizedJob;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IncrementalWatchlistServiceTest {
    @Test
    void createsSeparatedActionsForRealPublicInstitutionAndSoeSamples() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        List<NormalizedJob> publicJobs = mapper.readValue(Path.of(
                "..", "..", "output", "career-os-samples", "actual", "spreadsheets", "jobs.json"
        ).toFile(), new TypeReference<>() {});
        List<NormalizedJob> soeJobs = mapper.readValue(Path.of(
                "..", "..", "output", "career-os-samples", "actual", "soe", "jobs", "jobs.json"
        ).toFile(), new TypeReference<>() {});
        NormalizedJob hospital = publicJobs.stream()
                .filter(item -> item.jobId().equals("job_4423d798fa9335bd")).findFirst().orElseThrow();
        NormalizedJob soe = soeJobs.get(0);
        JobDelta delta = new JobDelta(
                "1.0.0", "S09", OffsetDateTime.now(), 0, 2, 0,
                List.of(change(hospital), change(soe)), List.of(), List.of()
        );
        CandidateProfile profile = new CandidateProfileRepository(new CareerOsProperties(
                null, null, Path.of("..", "config", "candidate-profile.json")
        )).load();
        IncrementalWatchlist report = new IncrementalWatchlistService(
                new EligibilityEngine(), new OpportunityTierClassifier()
        ).generate(delta, List.of(hospital, soe), profile);

        assertEquals(2, report.items().size());
        assertEquals(IncrementalWatchlist.Action.VERIFY_FIRST, report.items().get(0).action());
        assertEquals(IncrementalWatchlist.Action.REJECT, report.items().get(1).action());
        assertEquals(IncrementalWatchlist.DeltaType.ADDED, report.items().get(0).deltaType());
    }

    private JobDelta.Change change(NormalizedJob job) {
        return new JobDelta.Change(
                "position_" + "a".repeat(20), null, job.jobId(), job.employer().name(),
                job.position().title(), job.position().positionCode(), null, "b".repeat(64)
        );
    }
}
