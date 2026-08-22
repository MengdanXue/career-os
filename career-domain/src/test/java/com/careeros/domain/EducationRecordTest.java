package com.careeros.domain;

import static com.careeros.domain.DomainEnums.EducationLevel.BACHELOR;
import static com.careeros.domain.DomainEnums.EducationLevel.MASTER;
import static com.careeros.domain.EducationRecord.CompletionStatus.COMPLETED;
import static com.careeros.domain.EducationRecord.CompletionStatus.EXPECTED;
import static com.careeros.domain.EducationRecord.CredentialVerificationStatus.NOT_REQUIRED;
import static com.careeros.domain.EducationRecord.CredentialVerificationStatus.PLANNED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class EducationRecordTest {
    @Test
    void preservesCompletedBachelorAndExpectedMasterAsDifferentFacts() {
        var records = List.of(
            new EducationRecord(null, null, BACHELOR, "计算机科学与技术", 2014, null,
                COMPLETED, NOT_REQUIRED),
            new EducationRecord("示例海外大学", "示例国", MASTER, "计算机科学", 2027, null,
                EXPECTED, PLANNED)
        );

        assertThat(records).extracting(EducationRecord::completionStatus)
            .containsExactly(COMPLETED, EXPECTED);
        assertThat(records.get(1).institutionName()).isEqualTo("示例海外大学");
        assertThat(records.get(1).graduationYear()).isEqualTo(2027);
    }

    @Test
    void rejectsImpossibleGraduationMonthInsteadOfSilentlyNormalizingIt() {
        assertThatThrownBy(() -> new EducationRecord(
            "示例海外大学", "示例国", MASTER, "计算机科学", 2027, 13,
            EXPECTED, PLANNED
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("graduationMonth");
    }
}
