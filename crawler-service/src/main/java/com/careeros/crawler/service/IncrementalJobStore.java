package com.careeros.crawler.service;

import com.careeros.crawler.domain.JobDelta;
import com.careeros.crawler.domain.JobDelta.Change;
import com.careeros.crawler.domain.NormalizedJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class IncrementalJobStore {
    public static final String DELTA_SCHEMA_VERSION = "1.0.0";
    private static final String STATE_SCHEMA_VERSION = "1.0.0";

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public Result update(
            String sourceId,
            List<NormalizedJob> jobs,
            Path stateFile,
            Path deltaSchema,
            Path deltaFile
    ) throws IOException {
        StateFile previous = readState(sourceId, stateFile);
        Map<String, StateEntry> oldEntries = byKey(previous.entries());
        Map<String, StateEntry> newEntries = buildEntries(jobs);

        List<Change> added = new ArrayList<>();
        List<Change> changed = new ArrayList<>();
        List<Change> removed = new ArrayList<>();
        int unchanged = 0;

        for (Map.Entry<String, StateEntry> item : newEntries.entrySet()) {
            StateEntry current = item.getValue();
            StateEntry old = oldEntries.get(item.getKey());
            if (old == null) {
                added.add(change(null, current));
            } else if (!old.contentHash().equals(current.contentHash())) {
                changed.add(change(old, current));
            } else {
                unchanged++;
            }
        }
        for (Map.Entry<String, StateEntry> item : oldEntries.entrySet()) {
            if (!newEntries.containsKey(item.getKey())) removed.add(change(item.getValue(), null));
        }

        OffsetDateTime generatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        JobDelta delta = new JobDelta(
                DELTA_SCHEMA_VERSION, sourceId, generatedAt, oldEntries.size(), newEntries.size(), unchanged,
                sorted(added), sorted(changed), sorted(removed)
        );
        new JsonSchemaContractValidator(deltaSchema).validate(mapper.writeValueAsString(delta));

        StateFile next = new StateFile(
                STATE_SCHEMA_VERSION, sourceId, generatedAt,
                newEntries.values().stream().sorted(Comparator.comparing(StateEntry::stableKey)).toList()
        );
        atomicWrite(deltaFile, mapper.writeValueAsBytes(delta));
        atomicWrite(stateFile, mapper.writeValueAsBytes(next));
        return new Result(delta, stateFile, deltaFile);
    }

    public String stableKey(NormalizedJob job) {
        String discriminator = nonBlank(job.position().positionCode())
                ? job.position().positionCode()
                : join(job.employer().department(), job.position().title());
        return "position_" + sha256(join(
                job.source().announcementUrl(), job.employer().name(), discriminator
        ).toLowerCase(Locale.ROOT)).substring(0, 20);
    }

    private Map<String, StateEntry> buildEntries(List<NormalizedJob> jobs) throws IOException {
        Map<String, StateEntry> result = new TreeMap<>();
        for (NormalizedJob job : jobs) {
            String stableKey = stableKey(job);
            StateEntry entry = new StateEntry(
                    stableKey, contentHash(job), job.jobId(), job.employer().name(), job.position().title(),
                    job.position().positionCode()
            );
            StateEntry collision = result.putIfAbsent(stableKey, entry);
            if (collision != null) {
                throw new IllegalArgumentException("Duplicate stable job key " + stableKey + " for "
                        + collision.employer() + "/" + collision.positionTitle() + " and "
                        + entry.employer() + "/" + entry.positionTitle());
            }
        }
        return result;
    }

    private String contentHash(NormalizedJob job) throws IOException {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("announcement_url", job.source().announcementUrl());
        content.put("published_at", job.source().publishedAt());
        content.put("employer", job.employer());
        content.put("position", job.position());
        content.put("requirements", job.requirements());
        content.put("application", job.application());
        content.put("classification", job.classification());
        return sha256(mapper.writeValueAsString(content));
    }

    private StateFile readState(String sourceId, Path stateFile) throws IOException {
        if (!Files.isRegularFile(stateFile)) {
            return new StateFile(STATE_SCHEMA_VERSION, sourceId, null, List.of());
        }
        StateFile state = mapper.readValue(stateFile.toFile(), StateFile.class);
        if (!STATE_SCHEMA_VERSION.equals(state.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported state schema version: " + state.schemaVersion());
        }
        if (!sourceId.equals(state.sourceId())) {
            throw new IllegalArgumentException("State belongs to " + state.sourceId() + ", expected " + sourceId);
        }
        return state;
    }

    private static Map<String, StateEntry> byKey(List<StateEntry> entries) {
        Map<String, StateEntry> result = new TreeMap<>();
        for (StateEntry entry : entries) {
            if (result.putIfAbsent(entry.stableKey(), entry) != null) {
                throw new IllegalArgumentException("Duplicate stable key in stored state: " + entry.stableKey());
            }
        }
        return result;
    }

    private static Change change(StateEntry old, StateEntry current) {
        StateEntry display = current != null ? current : old;
        return new Change(
                display.stableKey(), old == null ? null : old.jobId(), current == null ? null : current.jobId(),
                display.employer(), display.positionTitle(), display.positionCode(),
                old == null ? null : old.contentHash(), current == null ? null : current.contentHash()
        );
    }

    private static List<Change> sorted(List<Change> changes) {
        return changes.stream().sorted(Comparator.comparing(Change::stableKey)).toList();
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Path absolute = target.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String join(String... values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) normalized.add(value == null ? "" : value.trim());
        return String.join("|", normalized);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record StateFile(
            String schemaVersion,
            String sourceId,
            OffsetDateTime updatedAt,
            List<StateEntry> entries
    ) {
        public StateFile {
            entries = List.copyOf(entries);
        }
    }

    public record StateEntry(
            String stableKey,
            String contentHash,
            String jobId,
            String employer,
            String positionTitle,
            String positionCode
    ) {}

    public record Result(JobDelta delta, Path stateFile, Path deltaFile) {}
}
