package com.careeros.agent.domain;

import java.time.LocalDate;
import java.util.List;

public record CandidateProfile(
        String profileVersion,
        PartialDate birthDate,
        Fact education,
        Fact degree,
        Fact major,
        Boolean overseasEducation,
        Fact overseasDegreeCertification,
        NumericFact experienceYears,
        List<Fact> professionalTitles,
        Fact politicalStatus,
        Fact graduateStatus,
        Fact socialSecurityStatus
) {
    public CandidateProfile {
        professionalTitles = professionalTitles == null ? List.of() : List.copyOf(professionalTitles);
    }

    public enum FactStatus {
        CONFIRMED,
        EXPECTED,
        UNKNOWN
    }

    public record PartialDate(Integer year, Integer month, Integer day, FactStatus status, String notes) {}

    public record Fact(String value, FactStatus status, LocalDate expectedAt, String notes) {}

    public record NumericFact(Double value, FactStatus status, String notes) {}
}
