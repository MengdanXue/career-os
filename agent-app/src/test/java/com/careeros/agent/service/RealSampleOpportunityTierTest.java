package com.careeros.agent.service;

import com.careeros.agent.domain.OpportunityTierAssessment.OpportunityTier;
import com.careeros.crawler.domain.NormalizedJob;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealSampleOpportunityTierTest {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final OpportunityTierClassifier classifier = new OpportunityTierClassifier();

    @Test
    void separatesOfficialPublicInstitutionFromStateOwnedEnterprise() throws Exception {
        NormalizedJob hospital = readJobs(Path.of(
                "..", "..", "output", "career-os-samples", "actual", "spreadsheets", "jobs.json"
        )).stream().filter(item -> item.jobId().equals("job_4423d798fa9335bd")).findFirst().orElseThrow();
        NormalizedJob soe = readJobs(Path.of(
                "..", "..", "output", "career-os-samples", "actual", "soe", "jobs", "jobs.json"
        )).stream().findFirst().orElseThrow();

        assertEquals(OpportunityTier.T1_ESTABLISHMENT_TARGET, classifier.classify(hospital).tier());
        assertEquals(OpportunityTier.T3_STABLE_SOE_BACKUP, classifier.classify(soe).tier());
        assertTrue(classifier.classify(soe).reason().contains("不得标成事业编"));
    }

    private List<NormalizedJob> readJobs(Path file) throws Exception {
        return mapper.readValue(file.toFile(), new TypeReference<>() {});
    }
}
