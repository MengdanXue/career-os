package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ChildrensHospitalIdentityMigrationContractTest {
    @Test
    void v75LimitsTheHealthCommissionFallbackToExplicitChildrensHospitalTitles() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V75__enforce_childrens_hospital_source_identity.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                "HZ_CHILDRENS_HOSPITAL",
                "杭州市儿童医院|杭州儿童医院|市儿童医院",
                "document_state = 'DEACTIVATED'",
                "SOURCE_IDENTITY_FILTER_CHANGED",
                "仅接收标题明确包含杭州市儿童医院");
        }
    }
}
