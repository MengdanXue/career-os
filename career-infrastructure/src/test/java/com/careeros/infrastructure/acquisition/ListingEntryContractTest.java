package com.careeros.infrastructure.acquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.careeros.application.AcquisitionHttpPorts.TransportPolicy;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.RecruitmentSource.CrawlMode;
import com.careeros.domain.acquisition.RecruitmentSource.SourceType;
import java.net.URI;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ListingEntryContractTest {
    @Test
    void parsesTwoDistinctTypedEntriesInConfigurationOrder() {
        RecruitmentSource source = source(Map.ofEntries(
            Map.entry("articleUrlRegex", "^https://official\\.example/notices/.+$"),
            Map.entry("allowedHosts", List.of("cdn.official.example")),
            Map.entry("listingEntries", List.of(
                Map.ofEntries(
                    Map.entry("code", "primary"),
                    Map.entry("entryUri", "https://official.example/notices/index.html"),
                    Map.entry("role", "PRIMARY"),
                    Map.entry("mode", "QUERY_PAGE"),
                    Map.entry("recruitmentYears", List.of(2025, 2026)),
                    Map.entry("completenessRequired", true)
                ),
                Map.ofEntries(
                    Map.entry("code", "lifecycle"),
                    Map.entry("entryUri", "https://official.example/results/index.html"),
                    Map.entry("role", "LIFECYCLE"),
                    Map.entry("mode", "LINKED_PAGE"),
                    Map.entry("articleUrlRegex", "^https://official\\.example/results/.+$"),
                    Map.entry("completenessRequired", false)
                )
            ))
        ));

        var entries = ListingEntryContract.from(source);

        assertThat(entries).extracting(ListingEntryContract::code)
            .containsExactly("primary", "lifecycle");
        assertThat(entries.get(0).role()).isEqualTo(ListingEntryContract.Role.PRIMARY);
        assertThat(entries.get(0).mode()).isEqualTo(ListingEntryContract.Mode.QUERY_PAGE);
        assertThat(entries.get(0).recruitmentYears()).containsExactlyInAnyOrder(2025, 2026);
        assertThat(entries.get(0).articleUrlRegex())
            .isEqualTo("^https://official\\.example/notices/.+$");
        assertThat(entries.get(0).readContract().exactHosts())
            .contains("official.example", "cdn.official.example");
        assertThat(entries.get(1).articleUrlRegex())
            .isEqualTo("^https://official\\.example/results/.+$");
        assertThat(entries.get(1).completenessRequired()).isFalse();
    }

    @Test
    void legacyModeBecomesOneSyntheticEntryAndNormalizesItsAlias() {
        RecruitmentSource source = source(Map.ofEntries(
            Map.entry("historicalPaginationMode", "STATIC_PAGE_SUFFIX"),
            Map.entry("articleUrlRegex", "^https://official\\.example/notices/.+$"),
            Map.entry("titleIncludeRegex", "recruitment|hiring")
        ));

        var entries = ListingEntryContract.from(source);

        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.code()).isEqualTo("legacy");
            assertThat(entry.entryUri()).isEqualTo(source.entryUri());
            assertThat(entry.role()).isEqualTo(ListingEntryContract.Role.PRIMARY);
            assertThat(entry.mode()).isEqualTo(ListingEntryContract.Mode.STATIC_SUFFIX_TEMPLATE);
            assertThat(entry.titleIncludeRegex()).isEqualTo("recruitment|hiring");
            assertThat(entry.readContract().transportPolicy()).isEqualTo(TransportPolicy.HTTPS_ONLY);
        });
    }

    @Test
    void fractionalRecruitmentYearIsRejectedInsteadOfTruncated() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.ofEntries(
                Map.entry("code", "primary"),
                Map.entry("entryUri", "https://official.example/notices"),
                Map.entry("mode", "QUERY_PAGE"),
                Map.entry("recruitmentYears", List.of(2025.9))
            )
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recruitment year");
    }

    @Test
    void overflowingLongRecruitmentYearIsRejectedInsteadOfTruncated() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.ofEntries(
                Map.entry("code", "primary"),
                Map.entry("entryUri", "https://official.example/notices"),
                Map.entry("mode", "QUERY_PAGE"),
                Map.entry("recruitmentYears", List.of(4_294_969_322L))
            )
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recruitment year");
    }

    @Test
    void overflowingBigIntegerRecruitmentYearIsRejectedInsteadOfTruncated() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.ofEntries(
                Map.entry("code", "primary"),
                Map.entry("entryUri", "https://official.example/notices"),
                Map.entry("mode", "QUERY_PAGE"),
                Map.entry("recruitmentYears", List.of(
                    BigInteger.ONE.shiftLeft(128).add(BigInteger.valueOf(2026))))
            )
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recruitment year");
    }

    @Test
    void unknownRoleErrorIdentifiesTheEntryCode() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.of(
                "code", "lifecycle-results",
                "entryUri", "https://official.example/results",
                "role", "RESULTS",
                "mode", "LINKED_PAGE")
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role")
            .hasMessageContaining("lifecycle-results");
    }

    @Test
    void unknownModeErrorIdentifiesTheEntryCode() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.of(
                "code", "primary-archive",
                "entryUri", "https://official.example/archive",
                "role", "HISTORICAL",
                "mode", "MAGIC_PAGE")
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("mode")
            .hasMessageContaining("primary-archive");
    }

    @Test
    void duplicateEntryCodesFailBeforeTraversal() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.of("code", "primary", "entryUri", "https://official.example/notices", "mode", "QUERY_PAGE"),
            Map.of("code", "primary", "entryUri", "https://official.example/archive", "mode", "LINKED_PAGE")
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entry code");
    }

    @Test
    void entryCodesAreTrimmedBeforeDuplicateValidation() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.of("code", "primary", "entryUri", "https://official.example/notices", "mode", "QUERY_PAGE"),
            Map.of("code", " primary ", "entryUri", "https://official.example/archive", "mode", "LINKED_PAGE")
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("entry code");
    }

    @Test
    void plainHttpEntryRequiresTheAuditedReadOnlyPolicy() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.of("code", "primary", "entryUri", "http://official.example/public/jobs/", "mode", "LINKED_PAGE")
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("AUDITED_HTTP_READ_ONLY");
    }

    @Test
    void auditedHttpRequiresExactHosts() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.ofEntries(
                Map.entry("code", "primary"),
                Map.entry("entryUri", "http://official.example/public/jobs/"),
                Map.entry("mode", "LINKED_PAGE"),
                Map.entry("transportPolicy", "AUDITED_HTTP_READ_ONLY"),
                Map.entry("allowedHosts", List.of("*.official.example")),
                Map.entry("allowedPathPrefixes", List.of("/public/jobs/"))
            )
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exact hosts");
    }

    @Test
    void auditedHttpRequiresANonemptyPathPrefix() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.ofEntries(
                Map.entry("code", "primary"),
                Map.entry("entryUri", "http://official.example/public/jobs/"),
                Map.entry("mode", "LINKED_PAGE"),
                Map.entry("transportPolicy", "AUDITED_HTTP_READ_ONLY"),
                Map.entry("allowedHosts", List.of("official.example")),
                Map.entry("allowedPathPrefixes", List.of())
            )
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("path prefixes");
    }

    @Test
    void auditedHttpPolicyIsRejectedForAnHttpsEntry() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.ofEntries(
                Map.entry("code", "primary"),
                Map.entry("entryUri", "https://official.example/public/jobs/"),
                Map.entry("mode", "LINKED_PAGE"),
                Map.entry("transportPolicy", "AUDITED_HTTP_READ_ONLY"),
                Map.entry("allowedHosts", List.of("official.example")),
                Map.entry("allowedPathPrefixes", List.of("/public/jobs/"))
            )
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("plain HTTP");
    }

    @Test
    void entryUriRejectsUserInfo() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.of(
                "code", "primary",
                "entryUri", "https://user@official.example/notices/",
                "mode", "LINKED_PAGE")
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("user-info");
    }

    @Test
    void auditedHttpRejectsDotPrefixedHostEntriesEvenWhenAnExactHostIsAlsoPresent() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.ofEntries(
                Map.entry("code", "primary"),
                Map.entry("entryUri", "http://official.example/public/jobs/"),
                Map.entry("mode", "LINKED_PAGE"),
                Map.entry("transportPolicy", "AUDITED_HTTP_READ_ONLY"),
                Map.entry("allowedHosts", List.of("official.example", ".official.example")),
                Map.entry("allowedPathPrefixes", List.of("/public/jobs/"))
            )
        )));

        assertThatThrownBy(() -> ListingEntryContract.from(source))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exact hosts");
    }

    @Test
    void parsedEntryDefensivelyCopiesNestedYearsAndConfigurationValues() {
        List<Integer> years = new ArrayList<>(List.of(2025, 2026));
        Map<String, Object> pagination = new LinkedHashMap<>(Map.of("pageSize", 20));
        Map<String, Object> configuredEntry = new LinkedHashMap<>();
        configuredEntry.put("code", "primary");
        configuredEntry.put("entryUri", "https://official.example/notices/");
        configuredEntry.put("mode", "QUERY_PAGE");
        configuredEntry.put("recruitmentYears", years);
        configuredEntry.put("pagination", pagination);
        List<Map<String, Object>> configuredEntries = new ArrayList<>(List.of(configuredEntry));
        RecruitmentSource source = source(Map.of("listingEntries", configuredEntries));
        ListingEntryContract parsed = ListingEntryContract.from(source).getFirst();

        years.add(2024);
        pagination.put("pageSize", 999);
        configuredEntry.put("titleIncludeRegex", "changed after parsing");
        configuredEntries.clear();

        assertThat(parsed.recruitmentYears()).containsExactlyInAnyOrder(2025, 2026);
        assertThat(parsed.configuration().get("recruitmentYears"))
            .isEqualTo(List.of(2025, 2026));
        assertThat(parsed.configuration().get("pagination"))
            .isEqualTo(Map.of("pageSize", 20));
        assertThat(parsed.titleIncludeRegex()).isNull();
    }

    @Test
    void scopedAuditedHttpProducesAnExplicitReadContract() {
        RecruitmentSource source = source(Map.of("listingEntries", List.of(
            Map.ofEntries(
                Map.entry("code", "primary"),
                Map.entry("entryUri", "http://official.example/public/jobs/index.html"),
                Map.entry("mode", "LINKED_PAGE"),
                Map.entry("transportPolicy", "AUDITED_HTTP_READ_ONLY"),
                Map.entry("allowedHosts", List.of("official.example")),
                Map.entry("allowedPathPrefixes", List.of("/public/jobs/"))
            )
        )));

        var contract = ListingEntryContract.from(source).getFirst().readContract();

        assertThat(contract.transportPolicy()).isEqualTo(TransportPolicy.AUDITED_HTTP_READ_ONLY);
        assertThat(contract.exactHosts()).containsExactly("official.example");
        assertThat(contract.allowedPathPrefixes()).containsExactly("/public/jobs/");
    }

    private static RecruitmentSource source(Map<String, Object> configuration) {
        Instant now = Instant.parse("2026-08-27T12:00:00Z");
        return new RecruitmentSource(
            UUID.fromString("01992f09-0000-7000-8000-000000000499"),
            "TEST_OFFICIAL", "Test official recruitment",
            URI.create("https://official.example/"),
            URI.create("https://official.example/notices/index.html"),
            SourceType.OFFICIAL_ORGANIZATION, "Hangzhou", CrawlMode.STATIC_HTML,
            true, "0 0 8 * * *", "Asia/Shanghai", Duration.ofSeconds(1),
            configuration, null, null, now, 0, now, now);
    }
}
