package com.careeros.domain;

import static com.careeros.domain.CandidateEmploymentRecord.EmploymentMode.FULL_TIME;
import static com.careeros.domain.CandidateEmploymentRecord.VerificationStatus.VERIFIED;
import static com.careeros.domain.CandidateFacts.CandidateFactKey.EMPLOYMENT_HISTORY;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.OptionalInt;

/** Calculates hard-qualification experience only from confirmed, verified full-time intervals. */
public final class CandidateEmploymentExperience {
    private CandidateEmploymentExperience() {}

    public static OptionalInt completedYears(CandidateProfile candidate, CandidateFacts facts, LocalDate asOf) {
        if (!facts.isConfirmed(EMPLOYMENT_HISTORY)) return OptionalInt.empty();
        var intervals = candidate.employmentRecords().stream()
            .filter(record -> record.verificationStatus() == VERIFIED)
            .filter(record -> record.employmentMode() == FULL_TIME)
            .filter(record -> !record.startsOn().isAfter(asOf))
            .map(record -> new Interval(record.startsOn(), boundedEnd(record, asOf)))
            .sorted(Comparator.comparing(Interval::start))
            .toList();
        if (intervals.isEmpty()) {
            return candidate.employmentRecords().isEmpty() ? OptionalInt.of(0) : OptionalInt.empty();
        }

        var merged = new ArrayList<Interval>();
        for (var interval : intervals) {
            if (merged.isEmpty()) {
                merged.add(interval);
                continue;
            }
            var last = merged.getLast();
            if (!interval.start().isAfter(last.end().plusDays(1))) {
                merged.set(merged.size() - 1,
                    new Interval(last.start(), interval.end().isAfter(last.end()) ? interval.end() : last.end()));
            } else {
                merged.add(interval);
            }
        }
        long verifiedMonths = merged.stream()
            .mapToLong(interval -> ChronoUnit.MONTHS.between(interval.start(), interval.end().plusDays(1)))
            .sum();
        return OptionalInt.of((int) (verifiedMonths / 12));
    }

    private static LocalDate boundedEnd(CandidateEmploymentRecord record, LocalDate asOf) {
        return record.endsOn() == null || record.endsOn().isAfter(asOf) ? asOf : record.endsOn();
    }

    private record Interval(LocalDate start, LocalDate end) {}
}
