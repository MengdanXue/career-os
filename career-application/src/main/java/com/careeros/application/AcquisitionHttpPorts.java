package com.careeros.application;

import com.careeros.domain.acquisition.RecruitmentSource;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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

    public record DiscoveredLink(URI uri, String title) {
        public DiscoveredLink {
            Objects.requireNonNull(uri, "uri");
            if (title == null || title.isBlank()) throw new IllegalArgumentException("title is required");
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
        Duration minimumRequestInterval
    ) {
        public FetchRequest(
            URI uri, Set<String> allowedHosts, String etag, String lastModified,
            Duration requestTimeout, long maxBytes
        ) {
            this(uri, allowedHosts, etag, lastModified, requestTimeout, maxBytes, null, Duration.ZERO);
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
        }
    }

    public record FetchedDocument(
        URI finalUri,
        int status,
        String mediaType,
        byte[] content,
        String etag,
        String lastModified
    ) {
        public FetchedDocument {
            Objects.requireNonNull(finalUri, "finalUri");
            content = content == null ? new byte[0] : content.clone();
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
