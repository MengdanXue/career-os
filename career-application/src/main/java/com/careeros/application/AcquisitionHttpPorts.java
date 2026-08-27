package com.careeros.application;

import com.careeros.domain.acquisition.RecruitmentSource;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AcquisitionHttpPorts {
    private AcquisitionHttpPorts() {}

    public interface SourceDiscoverer {
        List<DiscoveredLink> discover(RecruitmentSource source, URI pageUri, byte[] html);

        default List<DiscoveredLink> discoverAll(RecruitmentSource source, URI pageUri, byte[] html) {
            return discover(source, pageUri, html);
        }
    }

    public interface DocumentFetcher {
        FetchedDocument fetch(FetchRequest request);
    }

    public interface AttachmentDiscoverer {
        List<DiscoveredLink> discover(RecruitmentSource source, URI pageUri, byte[] html);
    }

    public interface SourceListingReader {
        ListingResult read(RecruitmentSource source, ListingQuery query);
    }

    public record ListingQuery(Set<Integer> recruitmentYears, boolean historical) {
        public ListingQuery {
            recruitmentYears = recruitmentYears == null ? Set.of() : Set.copyOf(recruitmentYears);
            if (historical && recruitmentYears.isEmpty()) {
                throw new IllegalArgumentException("recruitmentYears is required for historical listing");
            }
        }
    }

    public record YearDiscoveredLink(DiscoveredLink link, int recruitmentYear) {
        public YearDiscoveredLink {
            Objects.requireNonNull(link, "link");
            if (recruitmentYear < 2000 || recruitmentYear > 2100) {
                throw new IllegalArgumentException("recruitmentYear is invalid");
            }
        }
    }

    public record ListingEvidence(
        int pageCount,
        int rawCount,
        int acceptedCount,
        int filteredCount,
        int failedCount,
        LocalDate earliestPublishedOn,
        LocalDate latestPublishedOn,
        boolean traversalComplete,
        String stopReason,
        String completionBasis
    ) {
        public ListingEvidence {
            if (pageCount < 0 || rawCount < 0 || acceptedCount < 0 || filteredCount < 0 || failedCount < 0) {
                throw new IllegalArgumentException("listing counters cannot be negative");
            }
            if (traversalComplete && (stopReason == null || stopReason.isBlank()
                || completionBasis == null || completionBasis.isBlank())) {
                throw new IllegalArgumentException("completed traversal requires stopReason and completionBasis");
            }
        }
    }

    public record ListingEntryEvidence(
        String entryCode,
        boolean completenessRequired,
        Map<Integer, ListingEvidence> evidenceByYear
    ) {
        public ListingEntryEvidence {
            if (entryCode == null || entryCode.isBlank()) {
                throw new IllegalArgumentException("entryCode is required");
            }
            evidenceByYear = evidenceByYear == null ? Map.of() : Map.copyOf(evidenceByYear);
            for (int recruitmentYear : evidenceByYear.keySet()) {
                if (recruitmentYear < 2000 || recruitmentYear > 2100) {
                    throw new IllegalArgumentException("recruitmentYear is invalid");
                }
            }
        }
    }

    public record ListingResult(
        List<YearDiscoveredLink> links,
        Map<Integer, ListingEvidence> evidenceByYear,
        Map<String, ListingEntryEvidence> evidenceByEntry
    ) {
        public ListingResult(
            List<YearDiscoveredLink> links,
            Map<Integer, ListingEvidence> evidenceByYear
        ) {
            this(links, evidenceByYear, Map.of());
        }

        public ListingResult {
            links = links == null ? List.of() : List.copyOf(links);
            evidenceByYear = evidenceByYear == null ? Map.of() : Map.copyOf(evidenceByYear);
            evidenceByEntry = evidenceByEntry == null ? Map.of() : Map.copyOf(evidenceByEntry);
            evidenceByEntry.forEach((entryCode, evidence) -> {
                if (!entryCode.equals(evidence.entryCode())) {
                    throw new IllegalArgumentException(
                        "evidence map key must match listing entry code: " + entryCode);
                }
            });
        }
    }

    public record DiscoveredLink(URI uri, String title) {
        public DiscoveredLink {
            Objects.requireNonNull(uri, "uri");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
        }
    }

    public enum FetchMethod { GET, HEAD }

    public enum TransportPolicy { HTTPS_ONLY, AUDITED_HTTP_READ_ONLY }

    public enum TransportRisk { NONE, PLAINTEXT_OFFICIAL_HTTP }

    public record HttpReadContract(
        TransportPolicy transportPolicy,
        Set<String> exactHosts,
        Set<String> allowedPathPrefixes
    ) {
        public HttpReadContract {
            Objects.requireNonNull(transportPolicy, "transportPolicy");
            exactHosts = exactHosts == null ? Set.of() : exactHosts.stream()
                .map(host -> transportPolicy == TransportPolicy.AUDITED_HTTP_READ_ONLY
                    ? exactHost(host) : legacyHost(host))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            allowedPathPrefixes = allowedPathPrefixes == null ? Set.of() : allowedPathPrefixes.stream()
                .map(HttpReadContract::pathPrefix)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (transportPolicy == TransportPolicy.AUDITED_HTTP_READ_ONLY) {
                if (exactHosts.isEmpty()) {
                    throw new IllegalArgumentException("AUDITED_HTTP_READ_ONLY requires exact hosts");
                }
                if (allowedPathPrefixes.isEmpty()) {
                    throw new IllegalArgumentException("AUDITED_HTTP_READ_ONLY requires nonempty path prefixes");
                }
            }
        }

        private static String exactHost(String value) {
            if (value == null || !value.matches(
                "(?i)(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")) {
                throw new IllegalArgumentException("transport read contract requires exact hosts");
            }
            return value.toLowerCase(Locale.ROOT);
        }

        private static String legacyHost(String value) {
            return Objects.requireNonNull(value, "exact host").toLowerCase(Locale.ROOT);
        }

        private static String pathPrefix(String value) {
            if (value == null || value.isBlank() || !value.startsWith("/")) {
                throw new IllegalArgumentException("transport read contract path prefixes must be nonempty absolute paths");
            }
            return value;
        }
    }

    public record FetchRequest(
        URI uri,
        Set<String> allowedHosts,
        String etag,
        String lastModified,
        Duration requestTimeout,
        long maxBytes,
        UUID sourceId,
        Duration minimumRequestInterval,
        FetchMethod method,
        HttpReadContract readContract
    ) {
        public FetchRequest(
            URI uri, Set<String> allowedHosts, String etag, String lastModified,
            Duration requestTimeout, long maxBytes
        ) {
            this(uri, allowedHosts, etag, lastModified, requestTimeout, maxBytes, null, Duration.ZERO);
        }

        public FetchRequest(
            URI uri, Set<String> allowedHosts, String etag, String lastModified,
            Duration requestTimeout, long maxBytes, UUID sourceId, Duration minimumRequestInterval
        ) {
            this(uri, allowedHosts, etag, lastModified, requestTimeout, maxBytes, sourceId,
                minimumRequestInterval, FetchMethod.GET,
                new HttpReadContract(TransportPolicy.HTTPS_ONLY, allowedHosts, Set.of()));
        }

        public FetchRequest {
            Objects.requireNonNull(uri, "uri");
            allowedHosts = allowedHosts == null ? Set.of() : allowedHosts.stream()
                .map(host -> host.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
            Objects.requireNonNull(requestTimeout, "requestTimeout");
            if (requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("requestTimeout must be positive");
            }
            if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
            minimumRequestInterval = minimumRequestInterval == null ? Duration.ZERO : minimumRequestInterval;
            if (minimumRequestInterval.isNegative()) {
                throw new IllegalArgumentException("minimumRequestInterval cannot be negative");
            }
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(readContract, "readContract");
        }
    }

    public record FetchedDocument(
        URI finalUri,
        int status,
        String mediaType,
        byte[] content,
        String etag,
        String lastModified,
        TransportRisk transportRisk
    ) {
        public FetchedDocument(
            URI finalUri, int status, String mediaType, byte[] content,
            String etag, String lastModified
        ) {
            this(finalUri, status, mediaType, content, etag, lastModified, TransportRisk.NONE);
        }

        public FetchedDocument {
            Objects.requireNonNull(finalUri, "finalUri");
            content = content == null ? new byte[0] : content.clone();
            Objects.requireNonNull(transportRisk, "transportRisk");
        }
        @Override public byte[] content() { return content.clone(); }
        public boolean notModified() { return status == 304; }
        public boolean gone() { return status == 404 || status == 410; }
    }

    public static class FetchException extends RuntimeException {
        public FetchException(String message) { super(message); }
        public FetchException(String message, Throwable cause) { super(message, cause); }
    }
    public static final class FetchRejectedException extends FetchException {
        public FetchRejectedException(String message) { super(message); }
    }
    public static final class FetchFailedException extends FetchException {
        public FetchFailedException(String message) { super(message); }
        public FetchFailedException(String message, Throwable cause) { super(message, cause); }
    }
    public static final class ResponseTooLargeException extends FetchException {
        public ResponseTooLargeException(String message) { super(message); }
    }
}
