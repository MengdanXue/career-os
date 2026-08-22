package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.careeros.application.RepositoryPorts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "career-os.acquisition.scheduling-enabled=false")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CandidateProfileTransactionIntegrationTest {
    private static final UUID CANDIDATE_ID =
        UUID.fromString("01992f09-0000-7000-8000-000000000001");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("career_os")
        .withUsername("career_os")
        .withPassword("career_os");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoSpyBean
    RepositoryPorts.CandidateFactConfirmations confirmations;

    @Test
    void failedFactWriteRollsBackTheProfileVersion(
        @Autowired MockMvc mvc,
        @Autowired RepositoryPorts.CandidateProfiles candidates
    ) {
        String originalVersion = candidates.findById(CANDIDATE_ID).orElseThrow().profileVersion();
        doThrow(new IllegalStateException("simulated candidate fact persistence failure"))
            .when(confirmations).saveAll(anyList());

        try {
            assertThatThrownBy(() -> mvc.perform(post("/api/v1/candidates/{id}/facts/confirm", CANDIDATE_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"factKeys\":[\"BIRTH_DATE\"]}")))
                .hasRootCauseMessage("simulated candidate fact persistence failure");
        } finally {
            reset(confirmations);
        }

        assertThat(candidates.findById(CANDIDATE_ID).orElseThrow().profileVersion())
            .isEqualTo(originalVersion);
    }

    @Test
    void seededCandidateLoadsCompletedBachelorAndExpectedMaster(
        @Autowired RepositoryPorts.CandidateProfiles candidates
    ) {
        var candidate = candidates.findById(CANDIDATE_ID).orElseThrow();

        assertThat(candidate.educationRecords()).hasSize(2);
        assertThat(candidate.educationRecords().get(0).majorName()).isEqualTo("计算机科学与技术");
        assertThat(candidate.educationRecords().get(0).completionStatus().name()).isEqualTo("COMPLETED");
        assertThat(candidate.educationRecords().get(1).institutionName()).isEqualTo("示例海外大学");
        assertThat(candidate.educationRecords().get(1).graduationYear()).isEqualTo(2027);
        assertThat(candidate.educationRecords().get(1).completionStatus().name()).isEqualTo("EXPECTED");
    }
}
