package com.careeros.application;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionPorts.TargetSourceRegistration;
import com.careeros.application.SourceCompletionAuditPorts.AuditSnapshots;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.SourceCrawlRun;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures a reproducible assessment envelope; gate evidence remains UNKNOWN until independently observed. */
public final class SourceCompletionAuditService {
    public static final String ASSESSOR_VERSION = "source-completion-v1";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final AcquisitionStore acquisition;
    private final AuditSnapshots snapshots;
    private final Clock clock;

    public SourceCompletionAuditService(AcquisitionStore acquisition, AuditSnapshots snapshots, Clock clock) {
        this.acquisition = acquisition; this.snapshots = snapshots; this.clock = clock;
    }

    public SnapshotResult create(Integer requestedFrom, Integer requestedTo, LocalDate requestedThrough,
        List<String> selectedCodes, List<UUID> runIds) {
        int from = requestedFrom == null ? 2024 : requestedFrom;
        int to = requestedTo == null ? Math.max(from, LocalDate.now(clock.withZone(SHANGHAI)).getYear()) : requestedTo;
        validateYears(from, to);
        List<RecruitmentSource> sources = acquisition.findSources();
        List<TargetSourceRegistration> targets = acquisition.findTargetSources();
        if (selectedCodes != null) {
            if (selectedCodes.stream().anyMatch(java.util.Objects::isNull)
                || selectedCodes.size() != selectedCodes.stream().distinct().count()) {
                throw new IllegalArgumentException("selectedCodes must not contain duplicates or nulls");
            }
            selectedCodes.forEach(code -> { if (sources.stream().noneMatch(s -> s.code().equals(code)) && targets.stream().noneMatch(s -> s.code().equals(code))) throw new IllegalArgumentException("unknown source code: " + code); });
        }
        Set<UUID> allowedRunSources = new java.util.HashSet<>(sources.stream()
            .map(RecruitmentSource::id).collect(java.util.stream.Collectors.toSet()));
        targets.stream().map(TargetSourceRegistration::recruitmentSourceId).filter(java.util.Objects::nonNull)
            .forEach(allowedRunSources::add);
        validateRunIds(runIds, allowedRunSources);
        Instant assessedAt = Instant.now(clock);
        LocalDate today = LocalDate.now(clock.withZone(SHANGHAI));
        LocalDate through = requestedThrough == null ? today : requestedThrough;
        if (through.isAfter(today)) through = today;
        if (through.isAfter(LocalDate.of(to, 12, 31))) through = LocalDate.of(to, 12, 31);
        UUID id = UUID.randomUUID(), registryId = UUID.randomUUID();
        String hash = Integer.toHexString(java.util.stream.Stream.concat(sources.stream().map(RecruitmentSource::code), targets.stream().map(TargetSourceRegistration::code)).distinct().sorted().toList().hashCode());
        String payload = payload(id, null, registryId, from, to, through, assessedAt, hash, sources, targets, selectedCodes, runIds);
        snapshots.append(id, null, registryId, from, to, through.toString(), assessedAt, assessedAt, hash, ASSESSOR_VERSION, payload);
        return new SnapshotResult(id, payload);
    }

    public String get(UUID id) { return snapshots.find(id).orElseThrow(() -> new java.util.NoSuchElementException("audit snapshot not found: " + id)); }
    public String latest(int from, int to) { return snapshots.findLatest(from, to).orElseThrow(() -> new java.util.NoSuchElementException("audit snapshot not found")); }

    public SnapshotResult append(UUID baseId, List<UUID> runIds) {
        var base = snapshots.findEnvelope(baseId).orElseThrow(() -> new java.util.NoSuchElementException("audit snapshot not found: " + baseId));
        validateRunIds(runIds, extractSourceIds(base.payload()));
        UUID id = UUID.randomUUID();
        Set<String> mergedRunIds = new LinkedHashSet<>(extractRunIds(base.payload()));
        if (runIds != null) runIds.stream().map(UUID::toString).forEach(mergedRunIds::add);
        String payload = base.payload().replace(base.id().toString(), id.toString());
        payload = PARENT_ID.matcher(payload).replaceFirst(Matcher.quoteReplacement("\"parentSnapshotId\":\"" + base.id() + "\""));
        String mergedRuns = Matcher.quoteReplacement("\"runIds\":[" + mergedRunIds.stream()
            .map(SourceCompletionAuditService::quote).collect(java.util.stream.Collectors.joining(",")) + "]");
        payload = RUN_IDS.matcher(payload).replaceAll(mergedRuns);
        Instant now = Instant.now(clock);
        snapshots.append(id, base.id(), base.registrySnapshotId(), base.fromYear(), base.toYear(), base.coverageThrough(), base.cutoffAt(), now,
            base.registryHash(), base.assessorVersion(), payload);
        return new SnapshotResult(id, payload);
    }

    private static void validateYears(int from, int to) { if (from < 2000 || to > 2100 || from > to) throw new IllegalArgumentException("invalid audit year range"); }
    private void validateRunIds(List<UUID> runIds, Set<UUID> allowedSourceIds) {
        if (runIds == null) return;
        for (UUID runId : runIds) {
            if (runId == null) throw new IllegalArgumentException("runIds must not contain nulls");
            final SourceCrawlRun run;
            try { run = acquisition.findRun(runId); }
            catch (RuntimeException missing) { throw new IllegalArgumentException("unknown run id: " + runId, missing); }
            if (run == null || !allowedSourceIds.contains(run.sourceId())) {
                throw new IllegalArgumentException("run does not belong to the frozen source registry: " + runId);
            }
        }
    }

    private static Set<UUID> extractSourceIds(String payload) {
        Matcher matcher = Pattern.compile("\\\"sourceId\\\"\\s*:\\s*\\\"([0-9a-fA-F-]{36})\\\"").matcher(payload == null ? "" : payload);
        Set<UUID> ids = new java.util.HashSet<>();
        while (matcher.find()) ids.add(UUID.fromString(matcher.group(1)));
        return ids;
    }
    private static String payload(UUID id, UUID parent, UUID registry, int from, int to, LocalDate through, Instant assessed,
        String hash, List<RecruitmentSource> sources, List<TargetSourceRegistration> targets, List<String> selected, List<UUID> runIds) {
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"assessorVersion\":\"").append(ASSESSOR_VERSION)
            .append("\",\"auditSnapshotId\":\"").append(id).append("\",\"parentSnapshotId\":").append(parent == null ? "null" : quote(parent.toString()))
            .append(",\"registrySnapshotId\":\"").append(registry).append("\",\"fromYear\":").append(from).append(",\"toYear\":").append(to)
            .append(",\"coverageThrough\":").append(quote(through.toString())).append(",\"cutoffAt\":").append(quote(assessed.toString()))
            .append(",\"assessedAt\":").append(quote(assessed.toString()))
            .append(",\"registryHash\":").append(quote(hash)).append(",\"selectionMode\":").append(quote(selected == null ? "ALL_PLANNED" : "SUBSET"))
            .append(",\"selectedCodes\":").append(array(selected == null ? List.of() : selected)).append(",\"runIds\":").append(array(runIds == null ? List.of() : runIds.stream().map(UUID::toString).toList()))
            .append(",\"sources\":[");
        int count = 0;
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (RecruitmentSource s : sources) { if (count++ > 0) json.append(','); emitted.add(s.code()); json.append("{\"code\":").append(quote(s.code())).append(",\"sourceId\":").append(quote(s.id().toString())).append(",\"name\":").append(quote(s.name())).append(",\"registryEnabled\":true,\"acquisitionEnabled\":").append(s.enabled()).append(assessment(id, registry, s.code(), s.id(), from, to, through, assessed, runIds)).append('}'); }
        for (TargetSourceRegistration t : targets) { if (!emitted.add(t.code())) continue; if (count++ > 0) json.append(','); json.append("{\"code\":").append(quote(t.code())).append(",\"sourceId\":").append(quote(t.recruitmentSourceId() == null ? null : t.recruitmentSourceId().toString())).append(",\"name\":").append(quote(t.name())).append(",\"registryEnabled\":").append(t.enabled()).append(",\"acquisitionEnabled\":").append(t.recruitmentSourceId() != null && t.enabled()).append(assessment(id, registry, t.code(), t.recruitmentSourceId(), from, to, through, assessed, runIds)).append('}'); }
        return json.append("],\"summary\":{\"scope\":\"FULL_REGISTRY\",\"completionLevel\":0,\"gates\":").append(unknownGates()).append(",\"years\":").append(summaryYears(from, to)).append("},\"selectedSummary\":{\"completionLevel\":0,\"gates\":").append(unknownGates()).append("},\"metrics\":").append(metrics()).append('}').toString();
    }
    private static String assessment(UUID auditId, UUID registryId, String sourceCode, UUID sourceId,
        int from, int to, LocalDate through, Instant assessed, List<UUID> runIds) {
        return ",\"completion\":{\"auditSnapshotId\":" + quote(auditId.toString())
            + ",\"registrySnapshotId\":" + quote(registryId.toString())
            + ",\"sourceCode\":" + quote(sourceCode)
            + ",\"sourceId\":" + quote(sourceId == null ? null : sourceId.toString())
            + ",\"fromYear\":" + from + ",\"toYear\":" + to
            + ",\"coverageThrough\":" + quote(through.toString())
            + ",\"cutoffAt\":" + quote(assessed.toString()) + ",\"assessedAt\":" + quote(assessed.toString())
            + ",\"configurationHash\":null,\"currentConfigurationHash\":null"
            + ",\"runIds\":" + array(runIds == null ? List.of() : runIds.stream().map(UUID::toString).toList())
            + ",\"completionLevel\":0,\"level\":0,\"status\":\"UNKNOWN\",\"reasonCodes\":[\"NO_INDEPENDENT_ASSESSMENT\"]"
            + ",\"gates\":" + unknownGates()
            + ",\"years\":" + years(auditId, registryId, sourceCode, sourceId, from, to, through, assessed, runIds)
            + ",\"metrics\":" + metrics() + "}";
    }
    private static String unknownGates() { return "{\"level1\":\"UNKNOWN\",\"level2\":\"UNKNOWN\",\"level3\":\"UNKNOWN\",\"level4\":\"UNKNOWN\",\"level5\":\"UNKNOWN\",\"level6\":\"UNKNOWN\"}"; }
    private static String years(UUID auditId, UUID registryId, String sourceCode, UUID sourceId, int from, int to,
        LocalDate through, Instant assessed, List<UUID> runIds) {
        StringBuilder value = new StringBuilder("[");
        for (int year = from; year <= to; year++) {
            if (year > from) value.append(',');
            value.append("{\"auditSnapshotId\":").append(quote(auditId.toString()))
                .append(",\"registrySnapshotId\":").append(quote(registryId.toString()))
                .append(",\"sourceCode\":").append(quote(sourceCode))
                .append(",\"sourceId\":").append(quote(sourceId == null ? null : sourceId.toString()))
                .append(",\"year\":").append(year).append(",\"conclusion\":\"UNKNOWN\",\"coverageThrough\":").append(quote(through.toString()))
                .append(",\"assessedAt\":").append(quote(assessed.toString()))
                .append(",\"runIds\":").append(array(runIds == null ? List.of() : runIds.stream().map(UUID::toString).toList()))
                .append(",\"reasonCodes\":[\"NO_INDEPENDENT_ASSESSMENT\"],\"evidenceRefs\":[],\"legacyEvidence\":false,\"supportsAbsenceConclusion\":false,\"metrics\":").append(metrics()).append('}');
        }
        return value.append(']').toString();
    }
    private static String summaryYears(int from, int to) {
        StringBuilder value = new StringBuilder("[");
        for (int year = from; year <= to; year++) {
            if (year > from) value.append(',');
            value.append("{\"year\":").append(year).append(",\"conclusion\":\"UNKNOWN\"}");
        }
        return value.append(']').toString();
    }
    private static String metrics() { return "{\"announcementCount\":null,\"recruitmentBatchCount\":null,\"deduplicatedJobCount\":null,\"lifecycleEventCount\":null,\"importantAttachmentCount\":null,\"attachmentSucceededCount\":null,\"attachmentFailedCount\":null,\"attachmentUnresolvedCount\":null,\"orphanDocumentCount\":null,\"unresolvedJobCount\":null,\"unresolvedEventCount\":null,\"coverageGapCount\":null,\"verificationWarningCount\":null,\"reasonCodes\":[\"METRICS_NOT_ASSESSED\"]}"; }
    private static String quote(String value) { return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String array(List<?> values) { return values.stream().map(v -> quote(String.valueOf(v))).collect(java.util.stream.Collectors.joining(",", "[", "]")); }
    private static final Pattern RUN_IDS = Pattern.compile("\\\"runIds\\\"\\s*:\\s*\\[(.*?)\\]");
    private static final Pattern PARENT_ID = Pattern.compile("\\\"parentSnapshotId\\\"\\s*:\\s*null");
    private static List<String> extractRunIds(String payload) {
        Matcher matcher = RUN_IDS.matcher(payload == null ? "" : payload);
        if (!matcher.find() || matcher.group(1).isBlank()) return List.of();
        Matcher value = Pattern.compile("\\\"([0-9a-fA-F-]{36})\\\"").matcher(matcher.group(1));
        List<String> ids = new java.util.ArrayList<>();
        while (value.find()) ids.add(value.group(1));
        return ids;
    }
    public record SnapshotResult(UUID id, String payload) {}
}
