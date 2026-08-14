package com.careeros.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;

public record PartialDate(int year, int month, Integer day) {
    public PartialDate {
        try {
            var ym = YearMonth.of(year, month);
            if (day != null && (day < 1 || day > ym.lengthOfMonth())) throw new DateTimeException("invalid day");
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Invalid partial date", exception);
        }
    }
    public static PartialDate exact(LocalDate value) { Objects.requireNonNull(value); return new PartialDate(value.getYear(), value.getMonthValue(), value.getDayOfMonth()); }
    public static PartialDate month(int year, int month) { return new PartialDate(year, month, null); }
    public Optional<LocalDate> exactDate() { return day == null ? Optional.empty() : Optional.of(LocalDate.of(year, month, day)); }
    public LocalDate earliest() { return LocalDate.of(year, month, day == null ? 1 : day); }
    public LocalDate latest() { return day == null ? YearMonth.of(year, month).atEndOfMonth() : earliest(); }
}
