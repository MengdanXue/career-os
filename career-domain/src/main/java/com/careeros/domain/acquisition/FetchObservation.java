package com.careeros.domain.acquisition;

import java.net.URI;
import java.util.Objects;
import com.careeros.domain.acquisition.AcquiredDocument.TransportRisk;

public record FetchObservation(
    URI uri,
    int status,
    ObservationType type,
    String contentFingerprint,
    String mediaType,
    String etag,
    String lastModified,
    TransportRisk transportRisk
) {
    public FetchObservation(
        URI uri, int status, ObservationType type, String contentFingerprint,
        String mediaType, String etag, String lastModified
    ) {
        this(uri, status, type, contentFingerprint, mediaType, etag, lastModified,
            riskFor(uri));
    }

    public FetchObservation {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(transportRisk, "transportRisk");
    }

    public static FetchObservation ok(
        URI uri, int status, String fingerprint, String mediaType, String etag, String lastModified
    ) {
        return new FetchObservation(uri, status, ObservationType.OK, fingerprint, mediaType, etag, lastModified);
    }

    public static FetchObservation ok(
        URI uri, int status, String fingerprint, String mediaType, String etag,
        String lastModified, TransportRisk transportRisk
    ) {
        return new FetchObservation(uri, status, ObservationType.OK, fingerprint, mediaType,
            etag, lastModified, transportRisk);
    }

    public static FetchObservation notModified(URI uri, String etag, String lastModified) {
        return new FetchObservation(uri, 304, ObservationType.NOT_MODIFIED, null, null, etag, lastModified);
    }

    public static FetchObservation gone(URI uri, int status) {
        if (status != 404 && status != 410) throw new IllegalArgumentException("gone status must be 404 or 410");
        return new FetchObservation(uri, status, ObservationType.GONE, null, null, null, null);
    }

    public static FetchObservation failure(URI uri, int status) {
        return new FetchObservation(uri, status, ObservationType.FAILURE, null, null, null, null);
    }

    private static TransportRisk riskFor(URI uri) {
        return uri != null && "http".equalsIgnoreCase(uri.getScheme())
            ? TransportRisk.PLAINTEXT_OFFICIAL_HTTP : TransportRisk.NONE;
    }

    public enum ObservationType { OK, NOT_MODIFIED, GONE, FAILURE }
}
