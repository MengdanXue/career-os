package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SourceOfficialEntryRepairMigrationContractTest {
    @Test
    void v73ReplacesDeadOfficialEntriesWithLiveGovernmentListings() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V73__replace_dead_chunan_and_childrens_hospital_entries.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                "HZ_CHUNAN_GOV", "col1289604", "webId=2241", "pageId=1289604",
                "historicalMaxPages", "120", "HZ_CHILDRENS_HOSPITAL",
                "wsjkw.hangzhou.gov.cn", "recruitment-announcements",
                "appointment-publicity", "独立招聘系统持续返回 504");
        }
    }
}
