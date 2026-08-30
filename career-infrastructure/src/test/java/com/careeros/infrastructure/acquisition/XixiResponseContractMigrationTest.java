package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class XixiResponseContractMigrationTest {
    @Test
    void v48InvalidatesEvidenceProducedByTheLegacyAjaxRequestContract() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V48__fix_xixi_response_contract.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                .contains("HZ_XIXI_HOSPITAL", "X-Requested-With", "UTF-8",
                    "status = 'NOT_DISCOVERED'",
                    "completion_basis = NULL", "listing_page_count = 0",
                    "connection_status = 'PARTIAL'", "X-Requested-With")
                .doesNotContain("connection_status = 'CONNECTED'", "GB18030");
        }
    }
}
