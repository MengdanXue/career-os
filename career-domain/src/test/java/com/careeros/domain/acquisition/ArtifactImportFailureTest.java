package com.careeros.domain.acquisition;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static com.careeros.domain.acquisition.ArtifactImportFailure.FailureStage.ROW_PARSE_FAILED;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArtifactImportFailureTest {
    @Test
    void rowFailureRequiresSheetAndPositiveRowNumber() {
        assertThatThrownBy(() -> new ArtifactImportFailure(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            ROW_PARSE_FAILED, null, 0, "MISSING_ORGANIZATION", "招聘单位为空",
            Instant.parse("2026-08-24T12:00:00Z")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("row failure");
    }

    @Test
    void sanitizesSensitiveValuesAndCapsPersistedMessage() {
        String raw = "手机号13812345678 身份证330106199212311234 " + "x".repeat(700);

        var failure = new ArtifactImportFailure(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
            ArtifactImportFailure.FailureStage.REMOTE_ACCESS_FAILED, null, null,
            "FETCH_FAILED", raw, Instant.parse("2026-08-24T12:00:00Z"));

        assertThat(failure.safeMessage()).doesNotContain("13812345678", "330106199212311234")
            .contains("[手机号已脱敏]", "[身份证号已脱敏]")
            .hasSize(500);
    }
}
