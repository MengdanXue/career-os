package com.careeros.application;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionPorts.TargetSourceRegistration;
import com.careeros.application.SourceCompletionAuditPorts.AuditSnapshots;
import com.careeros.domain.acquisition.RecruitmentSource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

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
        if (selectedCodes != null) selectedCodes.forEach(code -> { if (sources.stream().noneMatch(s -> s.code().equals(code)) && targets.stream().noneMatch(s -> s.code().equals(code))) throw new IllegalArgumentException("unknown source code: " + code); });
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
        UUID id = UUID.randomUUID();
        String payload = base.payload().replace("\"auditSnapshotId\":\"" + base.id() + "\"", "\"auditSnapshotId\":\"" + id + "\"")
            .replace("\"parentSnapshotId\":null", "\"parentSnapshotId\":\"" + base.id() + "\"");
        Instant now = Instant.now(clock);
        snapshots.append(id, base.id(), base.registrySnapshotId(), base.fromYear(), base.toYear(), base.coverageThrough(), base.cutoffAt(), now,
            base.registryHash(), base.assessorVersion(), payload);
        return new SnapshotResult(id, payload);
    }

    private static void validateYears(int from, int to) { if (from < 2000 || to > 2100 || from > to) throw new IllegalArgumentException("invalid audit year range"); }
    private static String payload(UUID id, UUID parent, UUID registry, int from, int to, LocalDate through, Instant assessed,
        String hash, List<RecruitmentSource> sources, List<TargetSourceRegistration> targets, List<String> selected, List<UUID> runIds) {
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"assessorVersion\":\"").append(ASSESSOR_VERSION)
            .append("\",\"auditSnapshotId\":\"").append(id).append("\",\"parentSnapshotId\":").append(parent == null ? "null" : quote(parent.toString()))
            .append(",\"registrySnapshotId\":\"").append(registry).append("\",\"fromYear\":").append(from).append(",\"toYear\":").append(to)
            .append(",\"coverageThrough\":").append(quote(through.toString())).append(",\"assessedAt\":").append(quote(assessed.toString()))
            .append(",\"registryHash\":").append(quote(hash)).append(",\"selectionMode\":").append(quote(selected == null ? "ALL_PLANNED" : "SUBSET"))
            .append(",\"selectedCodes\":").append(array(selected == null ? List.of() : selected)).append(",\"runIds\":").append(array(runIds == null ? List.of() : runIds.stream().map(UUID::toString).toList()))
            .append(",\"sources\":[");
        int count = 0;
        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (RecruitmentSource s : sources) { if (count++ > 0) json.append(','); emitted.add(s.code()); json.append("{\"code\":").append(quote(s.code())).append(",\"sourceId\":").append(quote(s.id().toString())).append(",\"name\":").append(quote(s.name())).append(",\"registryEnabled\":true,\"acquisitionEnabled\":").append(s.enabled()).append(",\"completion\":{\"level\":0,\"status\":\"UNKNOWN\",\"reasonCodes\":[\"NO_INDEPENDENT_ASSESSMENT\"]}}"); }
        for (TargetSourceRegistration t : targets) { if (!emitted.add(t.code())) continue; if (count++ > 0) json.append(','); json.append("{\"code\":").append(quote(t.code())).append(",\"sourceId\":").append(quote(t.recruitmentSourceId() == null ? null : t.recruitmentSourceId().toString())).append(",\"name\":").append(quote(t.name())).append(",\"registryEnabled\":").append(t.enabled()).append(",\"acquisitionEnabled\":").append(t.recruitmentSourceId() != null && t.enabled()).append(",\"completion\":{\"level\":0,\"status\":\"UNKNOWN\",\"reasonCodes\":[\"NO_INDEPENDENT_ASSESSMENT\"]}}"); }
        return json.append("]}").toString();
    }
    private static String quote(String value) { return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static String array(List<?> values) { return values.stream().map(v -> quote(String.valueOf(v))).collect(java.util.stream.Collectors.joining(",", "[", "]")); }
    public record SnapshotResult(UUID id, String payload) {}
}
