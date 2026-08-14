package com.careeros.infrastructure.artifact;

import com.careeros.application.ExtractionPorts.ArtifactStore;
import com.careeros.domain.SourceArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class FileSystemArtifactStore implements ArtifactStore {
    private final Path root;

    public FileSystemArtifactStore(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    @Override
    public SourceArtifact put(byte[] content, String mediaType, Instant capturedAt) {
        Objects.requireNonNull(content, "content");
        if (mediaType == null || mediaType.isBlank()) throw new IllegalArgumentException("mediaType is required");
        Objects.requireNonNull(capturedAt, "capturedAt");
        String sha256 = sha256(content);
        Path directory = root.resolve(sha256.substring(0, 2));
        Path target = directory.resolve(sha256).normalize();
        requireInsideRoot(target);
        try {
            Files.createDirectories(directory);
            if (Files.notExists(target)) {
                Path temporary = directory.resolve(sha256 + "." + UUID.randomUUID() + ".tmp");
                try {
                    Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        moveNonAtomically(temporary, target);
                    } catch (IOException raceOrFailure) {
                        if (Files.notExists(target)) throw raceOrFailure;
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            UUID id = UUID.nameUUIDFromBytes(sha256.getBytes(StandardCharsets.US_ASCII));
            return new SourceArtifact(id, sha256, mediaType, content.length, target.toString(), capturedAt);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not store source artifact " + sha256, exception);
        }
    }

    @Override
    public InputStream open(SourceArtifact artifact) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        Path path = Path.of(artifact.storageUri()).toAbsolutePath().normalize();
        requireInsideRoot(path);
        return Files.newInputStream(path, StandardOpenOption.READ);
    }

    private void requireInsideRoot(Path path) {
        if (!path.startsWith(root)) throw new IllegalArgumentException("Artifact path is outside the configured root");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void moveNonAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target);
        } catch (IOException raceOrFailure) {
            if (Files.notExists(target)) throw raceOrFailure;
        }
    }
}
