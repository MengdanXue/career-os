package com.careeros.domain.acquisition;

import java.net.URI;
import java.util.Objects;

public record FetchObservation(
    URI uri,
    int status,
    ObservationType type,
    String contentFingerprint,
    String mediaType,
    String etag,
    String lastModified
) {
    public FetchObservation {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(type, "type");
    }

    public static FetchObservation ok(
        URI uri, int status, String fingerprint, String mediaType, String etag, String lastModified
    ) {
        return new FetchObservation(uri, status, ObservationType.OK, fingerprint, mediaType, etag, lastModified);
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

    public enum ObservationType { OK, NOT_MODIFIED, GONE, FAILURE }
}
