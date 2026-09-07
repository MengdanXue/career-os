package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionPorts.ChangeCursor;
import com.careeros.application.AcquisitionPorts.ChangePage;
import com.careeros.application.AcquisitionPorts.PersistedDocumentChange;
import com.careeros.application.AcquisitionPorts.RunPage;
import com.careeros.application.AcquisitionPorts.RunQuery;
import com.careeros.application.AcquisitionPorts.TargetSourceRegistration;
import com.careeros.domain.acquisition.AcquiredDocument;
import com.careeros.domain.acquisition.AcquisitionChange;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.ArtifactImportFailure;
import com.careeros.domain.acquisition.ArtifactDiscovery;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceOnboardingCheckpoint;
import com.careeros.domain.acquisition.SourceYearCoverage;
import com.careeros.domain.acquisition.TargetSource.ConnectionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JpaAcquisitionStore implements AcquisitionStore {
    private final RecruitmentSourceJpaRepository sources;
    private final SourceCrawlRunJpaRepository runs;
    private final AcquiredDocumentJpaRepository documents;
    private final AcquisitionChangeJpaRepository changes;
    private final SourceYearCoverageJpaRepository coverage;
    private final SourceOnboardingCheckpointJpaRepository checkpoints;
    private final ArtifactImportFailureJpaRepository importFailures;
    private final ArtifactDiscoveryJpaRepository discoveries;
    @PersistenceContext private EntityManager entityManager;

    public JpaAcquisitionStore(
        RecruitmentSourceJpaRepository sources,
        SourceCrawlRunJpaRepository runs,
        AcquiredDocumentJpaRepository documents,
        AcquisitionChangeJpaRepository changes,
        SourceYearCoverageJpaRepository coverage,
        SourceOnboardingCheckpointJpaRepository checkpoints,
        ArtifactImportFailureJpaRepository importFailures,
        ArtifactDiscoveryJpaRepository discoveries
    ) {
        this.sources = sources;
        this.runs = runs;
        this.documents = documents;
        this.changes = changes;
        this.coverage = coverage;
        this.checkpoints = checkpoints;
        this.importFailures = importFailures;
        this.discoveries = discoveries;
    }

    @Override @Transactional(readOnly = true)
    public List<RecruitmentSource> findDueSources(Instant now, int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        return sources.findByEnabledTrueAndNextDueAtLessThanEqualOrderByNextDueAtAscIdAsc(
            now, PageRequest.of(0, limit)).stream().map(JpaAcquisitionStore::toDomain).toList();
    }

    @Override @Transactional(readOnly = true)
    public List<RecruitmentSource> findSources() {
        return sources.findAll().stream().map(JpaAcquisitionStore::toDomain)
            .sorted(java.util.Comparator.comparing(RecruitmentSource::code)).toList();
    }

    @Override @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<TargetSourceRegistration> findTargetSources() {
        List<Object[]> rows = entityManager.createNativeQuery("""
            select code, name, region, official_root_url, connection_status,
                   recruitment_source_id, enabled, scope_level, scope_code,
                   priority_tier, coverage_role
            from target_source_catalog
            order by case priority_tier when 'P0' then 0 when 'P1' then 1 else 2 end,
                     scope_code, code
            """).getResultList();
        return rows.stream().map(row -> new TargetSourceRegistration(
            row[0].toString(), row[1].toString(), row[2].toString(), row[3].toString(),
            ConnectionStatus.valueOf(row[4].toString()), (UUID) row[5], (Boolean) row[6],
            row[7].toString(), row[8].toString(), row[9].toString(), row[10].toString()
        )).toList();
    }

    @Override @Transactional(readOnly = true)
    public RecruitmentSource findSource(UUID id) {
        return sources.findById(id).map(JpaAcquisitionStore::toDomain)
            .orElseThrow(() -> new NoSuchElementException("Recruitment source not found: " + id));
    }

    @Override @Transactional
    public RecruitmentSource saveSource(RecruitmentSource source) {
        return toDomain(sources.saveAndFlush(toEntity(source)));
    }

    @Override @Transactional
    public SourceCrawlRun saveRun(SourceCrawlRun run) {
        return toDomain(runs.saveAndFlush(toEntity(run)));
    }

    @Override @Transactional(readOnly = true)
    public SourceCrawlRun findRun(UUID id) {
        return runs.findById(id).map(JpaAcquisitionStore::toDomain)
            .orElseThrow(() -> new NoSuchElementException("Source crawl run not found: " + id));
    }

    @Override @Transactional(readOnly = true)
    public java.util.Optional<SourceCrawlRun> findLatestRun(UUID sourceId) {
        return runs.findTopBySourceIdOrderByStartedAtDescIdDesc(sourceId).map(JpaAcquisitionStore::toDomain);
    }

    @Override @Transactional(readOnly = true)
    public RunPage findRuns(RunQuery query, int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw new IllegalArgumentException("Invalid run page");
        StringBuilder jpql = new StringBuilder("select r from SourceCrawlRunEntity r where 1=1");
        StringBuilder count = new StringBuilder("select count(r) from SourceCrawlRunEntity r where 1=1");
        appendRunFilters(jpql, query);
        appendRunFilters(count, query);
        jpql.append(" order by r.startedAt desc, r.id desc");
        var dataQuery = entityManager.createQuery(jpql.toString(), AcquisitionJpaModels.SourceCrawlRunEntity.class);
        var countQuery = entityManager.createQuery(count.toString(), Long.class);
        bindRunFilters(dataQuery, query);
        bindRunFilters(countQuery, query);
        dataQuery.setFirstResult(page * size).setMaxResults(size);
        return new RunPage(dataQuery.getResultList().stream().map(JpaAcquisitionStore::toDomain).toList(),
            page, size, countQuery.getSingleResult());
    }

    @Override @Transactional(readOnly = true)
    public java.util.Optional<AcquiredDocument> findDocument(UUID sourceId, URI canonicalUri) {
        return documents.findBySourceIdAndCanonicalUri(sourceId, canonicalUri.toString())
            .map(JpaAcquisitionStore::toDomain);
    }

    @Override @Transactional(readOnly = true)
    public List<AcquiredDocument> findDocuments(UUID sourceId) {
        return documents.findBySourceIdOrderByCanonicalUriAsc(sourceId).stream()
            .map(JpaAcquisitionStore::toDomain).toList();
    }

    @Override @Transactional
    public AcquiredDocument saveDocument(AcquiredDocument document) {
        return toDomain(documents.saveAndFlush(toEntity(document)));
    }

    @Override @Transactional
    public AcquisitionChange appendChange(AcquisitionChange change) {
        return toDomain(changes.saveAndFlush(toEntity(change)));
    }

    @Override @Transactional
    public PersistedDocumentChange saveDocumentAndChange(AcquiredDocument document, AcquisitionChange change) {
        AcquiredDocument savedDocument = toDomain(documents.saveAndFlush(toEntity(document)));
        AcquisitionChange savedChange = toDomain(changes.saveAndFlush(toEntity(change)));
        return new PersistedDocumentChange(savedDocument, savedChange);
    }

    @Override @Transactional(readOnly = true)
    public ChangePage findChanges(ChangeCursor cursor, UUID sourceId, Set<ChangeType> types, int size) {
        if (size < 1 || size > 200) throw new IllegalArgumentException("size must be between 1 and 200");
        StringBuilder jpql = new StringBuilder("select c from AcquisitionChangeEntity c where 1=1");
        if (sourceId != null) jpql.append(" and c.sourceId = :sourceId");
        if (types != null && !types.isEmpty()) jpql.append(" and c.changeType in :types");
        if (cursor != null) jpql.append(" and (c.occurredAt > :at or (c.occurredAt = :at and c.id > :id))");
        jpql.append(" order by c.occurredAt asc, c.id asc");
        var query = entityManager.createQuery(jpql.toString(), AcquisitionJpaModels.AcquisitionChangeEntity.class)
            .setMaxResults(size + 1);
        if (sourceId != null) query.setParameter("sourceId", sourceId);
        if (types != null && !types.isEmpty()) query.setParameter("types", types);
        if (cursor != null) query.setParameter("at", cursor.occurredAt()).setParameter("id", cursor.id());
        List<AcquisitionChange> all = query.getResultList().stream().map(JpaAcquisitionStore::toDomain).toList();
        List<AcquisitionChange> items = all.size() > size ? all.subList(0, size) : all;
        ChangeCursor next = all.size() > size && !items.isEmpty()
            ? new ChangeCursor(items.getLast().occurredAt(), items.getLast().id()) : null;
        return new ChangePage(items, next);
    }

    @Override @Transactional(readOnly = true)
    public List<SourceYearCoverage> findSourceYearCoverage(UUID sourceId, Integer recruitmentYear) {
        if (sourceId != null && recruitmentYear != null) {
            return coverage.findByIdSourceIdAndIdRecruitmentYear(sourceId, recruitmentYear).stream()
                .map(JpaAcquisitionStore::toDomain).toList();
        }
        if (sourceId != null) {
            return coverage.findByIdSourceIdOrderByIdRecruitmentYearAsc(sourceId).stream()
                .map(JpaAcquisitionStore::toDomain).toList();
        }
        if (recruitmentYear != null) {
            return coverage.findByIdRecruitmentYearOrderByIdSourceIdAsc(recruitmentYear).stream()
                .map(JpaAcquisitionStore::toDomain).toList();
        }
        return coverage.findAll().stream().map(JpaAcquisitionStore::toDomain)
            .sorted(java.util.Comparator.comparingInt(SourceYearCoverage::recruitmentYear)
                .thenComparing(SourceYearCoverage::sourceId)).toList();
    }

    @Override @Transactional
    public SourceYearCoverage saveSourceYearCoverage(SourceYearCoverage value) {
        return toDomain(coverage.saveAndFlush(toEntity(value)));
    }

    @Override @Transactional
    public SourceOnboardingCheckpoint saveCheckpoint(SourceOnboardingCheckpoint value) {
        return toDomain(checkpoints.saveAndFlush(toEntity(value)));
    }

    @Override @Transactional(readOnly = true)
    public List<SourceOnboardingCheckpoint> findCheckpoints(UUID sourceId) {
        return checkpoints.findByIdSourceIdOrderByIdCheckpointAsc(sourceId).stream()
            .map(JpaAcquisitionStore::toDomain).toList();
    }

    @Override @Transactional
    public List<ArtifactImportFailure> saveImportFailures(List<ArtifactImportFailure> values) {
        if (values == null || values.isEmpty()) return List.of();
        return importFailures.saveAllAndFlush(values.stream().map(JpaAcquisitionStore::toEntity).toList())
            .stream().map(JpaAcquisitionStore::toDomain).toList();
    }

    @Override @Transactional(readOnly = true)
    public List<ArtifactImportFailure> findImportFailures(UUID sourceId, UUID runId) {
        var values = runId == null
            ? importFailures.findBySourceIdOrderByOccurredAtDescIdDesc(sourceId)
            : importFailures.findBySourceIdAndRunIdOrderByOccurredAtAscIdAsc(sourceId, runId);
        return values.stream()
            .map(JpaAcquisitionStore::toDomain).toList();
    }

    @Override @Transactional(readOnly = true)
    public long countImportFailures(UUID sourceId) {
        return importFailures.countBySourceId(sourceId);
    }

    @Override @Transactional(readOnly = true)
    public long countDocumentImportFailures(UUID sourceId) {
        Number count = (Number) entityManager.createNativeQuery("""
            select count(*) from artifact_import_failure
            where source_id = :sourceId
              and stage not in ('DISCOVERY_CONTRACT_CHANGED', 'REMOTE_ACCESS_FAILED')
            """).setParameter("sourceId", sourceId).getSingleResult();
        return count.longValue();
    }

    @Override @Transactional
    public ArtifactDiscovery saveArtifactDiscovery(ArtifactDiscovery value) {
        var existing = discoveries.findBySourceIdAndCanonicalUri(value.sourceId(), value.canonicalUri().toString());
        var entity = toEntity(value);
        if (existing.isPresent()) {
            var previous = existing.orElseThrow();
            entity.id = previous.id;
            entity.firstSeenAt = previous.firstSeenAt;
            entity.attemptCount = value.status() == ArtifactDiscovery.DiscoveryStatus.DISCOVERED
                ? previous.attemptCount + 1 : previous.attemptCount;
            // A status-only retry (for example PARSE_FAILED after FETCHED) must
            // retain the bytes' provenance captured by the successful fetch.
            if (value.rawChecksum() == null && value.mediaType() == null && value.sizeBytes() == 0) {
                entity.mediaType = previous.mediaType;
                entity.rawChecksum = previous.rawChecksum;
                entity.sizeBytes = previous.sizeBytes;
            }
        }
        return toDomain(discoveries.saveAndFlush(entity));
    }

    @Override @Transactional(readOnly = true)
    public List<ArtifactDiscovery> findArtifactDiscoveries(UUID sourceId, UUID runId) {
        var values = runId == null
            ? discoveries.findAll().stream()
                .filter(value -> sourceId == null || sourceId.equals(value.sourceId))
                .sorted(java.util.Comparator.comparing(
                    (AcquisitionJpaModels.ArtifactDiscoveryEntity value) -> value.lastAttemptAt)
                    .thenComparing(value -> value.id)).toList()
            : discoveries.findBySourceIdAndRunIdOrderByLastAttemptAtAsc(sourceId, runId);
        return values.stream().map(JpaAcquisitionStore::toDomain).toList();
    }

    @Override @Transactional(readOnly = true)
    public long countUnresolvedArtifactDiscoveries(UUID sourceId) {
        return discoveries.countBySourceIdAndStatusIn(sourceId, List.of(
            ArtifactDiscovery.DiscoveryStatus.DISCOVERED,
            ArtifactDiscovery.DiscoveryStatus.FETCHED,
            ArtifactDiscovery.DiscoveryStatus.FETCH_FAILED,
            ArtifactDiscovery.DiscoveryStatus.PARSE_FAILED));
    }

    @Override @Transactional(readOnly = true)
    public com.careeros.application.AcquisitionPorts.LifecycleCounts lifecycleCounts(UUID sourceId) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            select
                count(distinct lifecycle.source_url),
                count(distinct lifecycle.source_url) filter (where lifecycle.match_status='MATCHED'),
                count(distinct lifecycle.source_url) filter (where lifecycle.match_status='UNMATCHED'),
                count(distinct lifecycle.source_url) filter (where lifecycle.match_status='AMBIGUOUS')
            from recruitment_lifecycle_document lifecycle
            join acquired_document document
              on document.source_id=:sourceId
             and document.canonical_uri=lifecycle.source_url
            """).setParameter("sourceId", sourceId).getSingleResult();
        return new com.careeros.application.AcquisitionPorts.LifecycleCounts(
            ((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
            ((Number) row[2]).longValue(), ((Number) row[3]).longValue());
    }

    @Override @Transactional(readOnly = true)
    public ConnectionStatus findTargetSourceStatus(String sourceCode) {
        Object value = entityManager.createNativeQuery(
            "select connection_status from target_source_catalog where code = :code")
            .setParameter("code", sourceCode).getResultStream().findFirst().orElse("NOT_CONNECTED");
        return ConnectionStatus.valueOf(value.toString());
    }

    @Override @Transactional
    public void updateTargetSourceStatus(
        String sourceCode, ConnectionStatus status, UUID recruitmentSourceId, Instant updatedAt
    ) {
        entityManager.createNativeQuery("""
            update target_source_catalog
            set connection_status = :status,
                recruitment_source_id = :sourceId,
                updated_at = :updatedAt
            where code = :code
            """)
            .setParameter("status", status.name())
            .setParameter("sourceId", recruitmentSourceId)
            .setParameter("updatedAt", updatedAt)
            .setParameter("code", sourceCode)
            .executeUpdate();
    }

    @Override @Transactional(readOnly = true)
    public long countActiveTargetJobs(UUID sourceId, int recruitmentYear) {
        Number result = (Number) entityManager.createNativeQuery("""
            select count(distinct job.id)
            from job_posting job
            join recruitment_event event on event.id = job.recruitment_event_id
            join acquired_document announcement
              on announcement.source_id = :sourceId
             and announcement.document_kind = 'ANNOUNCEMENT'
             and announcement.document_state = 'ACTIVE'
             and announcement.canonical_uri = job.source_url
            where job.active = true
              and job.job_family <> 'OTHER'
              and event.recruitment_year = :recruitmentYear
            """)
            .setParameter("sourceId", sourceId)
            .setParameter("recruitmentYear", recruitmentYear)
            .getSingleResult();
        return result.longValue();
    }

    private static void appendRunFilters(StringBuilder jpql, RunQuery query) {
        if (query == null) return;
        if (query.sourceId() != null) jpql.append(" and r.sourceId = :sourceId");
        if (query.status() != null) jpql.append(" and r.status = :status");
        if (query.from() != null) jpql.append(" and r.startedAt >= :from");
        if (query.to() != null) jpql.append(" and r.startedAt <= :to");
    }

    private static void bindRunFilters(jakarta.persistence.Query query, RunQuery filters) {
        if (filters == null) return;
        if (filters.sourceId() != null) query.setParameter("sourceId", filters.sourceId());
        if (filters.status() != null) query.setParameter("status", filters.status());
        if (filters.from() != null) query.setParameter("from", filters.from());
        if (filters.to() != null) query.setParameter("to", filters.to());
    }

    private static AcquisitionJpaModels.RecruitmentSourceEntity toEntity(RecruitmentSource value) {
        var entity = new AcquisitionJpaModels.RecruitmentSourceEntity();
        entity.id=value.id(); entity.code=value.code(); entity.name=value.name(); entity.baseUri=value.baseUri().toString();
        entity.entryUri=value.entryUri().toString(); entity.sourceType=value.sourceType(); entity.region=value.region();
        entity.crawlMode=value.crawlMode(); entity.enabled=value.enabled(); entity.cronExpression=value.cronExpression();
        entity.timeZone=value.timeZone(); entity.minimumRequestIntervalMs=value.minimumRequestInterval().toMillis();
        entity.configuration=new java.util.LinkedHashMap<>(value.configuration()); entity.lastSuccessAt=value.lastSuccessAt();
        entity.lastFailureAt=value.lastFailureAt(); entity.nextDueAt=value.nextDueAt();
        entity.consecutiveFailureCount=value.consecutiveFailureCount(); entity.createdAt=value.createdAt(); entity.updatedAt=value.updatedAt();
        return entity;
    }

    private static RecruitmentSource toDomain(AcquisitionJpaModels.RecruitmentSourceEntity value) {
        return new RecruitmentSource(value.id,value.code,value.name,URI.create(value.baseUri),URI.create(value.entryUri),
            value.sourceType,value.region,value.crawlMode,value.enabled,value.cronExpression,value.timeZone,
            Duration.ofMillis(value.minimumRequestIntervalMs),value.configuration,value.lastSuccessAt,value.lastFailureAt,
            value.nextDueAt,value.consecutiveFailureCount,value.createdAt,value.updatedAt);
    }

    private static AcquisitionJpaModels.SourceCrawlRunEntity toEntity(SourceCrawlRun value) {
        var entity=new AcquisitionJpaModels.SourceCrawlRunEntity();
        entity.id=value.id(); entity.sourceId=value.sourceId(); entity.trigger=value.trigger(); entity.status=value.status();
        entity.startedAt=value.startedAt(); entity.completedAt=value.completedAt(); entity.discoveredCount=value.discoveredCount();
        entity.fetchedCount=value.fetchedCount(); entity.unchangedCount=value.unchangedCount(); entity.addedCount=value.addedCount();
        entity.updatedCount=value.updatedCount(); entity.deactivatedCount=value.deactivatedCount(); entity.failedCount=value.failedCount();
        entity.errorCode=value.errorCode(); entity.errorMessage=value.errorMessage(); return entity;
    }

    private static SourceCrawlRun toDomain(AcquisitionJpaModels.SourceCrawlRunEntity value) {
        return new SourceCrawlRun(value.id,value.sourceId,value.trigger,value.status,value.startedAt,value.completedAt,
            value.discoveredCount,value.fetchedCount,value.unchangedCount,value.addedCount,value.updatedCount,
            value.deactivatedCount,value.failedCount,value.errorCode,value.errorMessage);
    }

    private static AcquisitionJpaModels.AcquiredDocumentEntity toEntity(AcquiredDocument value) {
        var entity=new AcquisitionJpaModels.AcquiredDocumentEntity();
        entity.id=value.id(); entity.sourceId=value.sourceId(); entity.canonicalUri=value.canonicalUri().toString();
        entity.parentDocumentId=value.parentDocumentId(); entity.kind=value.kind(); entity.mediaType=value.mediaType();
        entity.contentFingerprint=value.contentFingerprint();
        entity.listingMetadataFingerprint=value.listingMetadataFingerprint();
        entity.etag=value.etag(); entity.lastModified=value.lastModified();
        entity.storageUri=value.storageUri().toString(); entity.transportRisk=value.transportRisk();
        entity.state=value.state(); entity.firstSeenAt=value.firstSeenAt();
        entity.lastSeenAt=value.lastSeenAt(); entity.lastChangedAt=value.lastChangedAt(); entity.lastGoneAt=value.lastGoneAt();
        entity.consecutiveGoneCount=value.consecutiveGoneCount(); entity.lastHttpStatus=value.lastHttpStatus();
        entity.lastProcessedFingerprint=value.lastProcessedFingerprint();
        entity.lastProcessorVersion=value.lastProcessorVersion(); entity.version=value.version(); return entity;
    }

    private static AcquiredDocument toDomain(AcquisitionJpaModels.AcquiredDocumentEntity value) {
        return new AcquiredDocument(value.id,value.sourceId,URI.create(value.canonicalUri),value.parentDocumentId,
            value.kind,value.mediaType,value.contentFingerprint,value.listingMetadataFingerprint,
            value.etag,value.lastModified,URI.create(value.storageUri),
            value.transportRisk,value.state,value.firstSeenAt,value.lastSeenAt,value.lastChangedAt,value.lastGoneAt,value.consecutiveGoneCount,
            value.lastHttpStatus,value.lastProcessedFingerprint,value.lastProcessorVersion,value.version);
    }

    private static AcquisitionJpaModels.AcquisitionChangeEntity toEntity(AcquisitionChange value) {
        var entity=new AcquisitionJpaModels.AcquisitionChangeEntity();
        entity.id=value.id(); entity.runId=value.runId(); entity.sourceId=value.sourceId(); entity.documentId=value.documentId();
        entity.changeType=value.changeType(); entity.previousFingerprint=value.previousFingerprint();
        entity.currentFingerprint=value.currentFingerprint(); entity.canonicalUri=value.canonicalUri().toString();
        entity.jobDeltaSummary=new java.util.LinkedHashMap<>(value.jobDeltaSummary()); entity.occurredAt=value.occurredAt(); return entity;
    }

    private static AcquisitionChange toDomain(AcquisitionJpaModels.AcquisitionChangeEntity value) {
        return new AcquisitionChange(value.id,value.runId,value.sourceId,value.documentId,value.changeType,
            value.previousFingerprint,value.currentFingerprint,URI.create(value.canonicalUri),value.jobDeltaSummary,value.occurredAt);
    }

    private static AcquisitionJpaModels.SourceYearCoverageEntity toEntity(SourceYearCoverage value) {
        var entity = new AcquisitionJpaModels.SourceYearCoverageEntity();
        entity.id = new AcquisitionJpaModels.SourceYearCoverageId(value.sourceId(), value.recruitmentYear());
        entity.status = value.status(); entity.discoveredCount = value.discoveredCount();
        entity.fetchedCount = value.fetchedCount(); entity.parsedCount = value.parsedCount();
        entity.targetJobCount = value.targetJobCount(); entity.completionBasis = value.completionBasis();
        entity.completedAt = value.completedAt(); entity.updatedAt = value.updatedAt();
        entity.listingPageCount = value.listingPageCount(); entity.filteredCount = value.filteredCount();
        entity.failedCount = value.failedCount(); entity.earliestPublishedOn = value.earliestPublishedOn();
        entity.latestPublishedOn = value.latestPublishedOn(); entity.stopReason = value.stopReason();
        return entity;
    }

    private static SourceYearCoverage toDomain(AcquisitionJpaModels.SourceYearCoverageEntity value) {
        return new SourceYearCoverage(value.id.sourceId, value.id.recruitmentYear, value.status,
            value.discoveredCount, value.fetchedCount, value.parsedCount, value.targetJobCount,
            value.completionBasis, value.completedAt, value.updatedAt, value.listingPageCount,
            value.filteredCount, value.failedCount, value.earliestPublishedOn,
            value.latestPublishedOn, value.stopReason);
    }

    private static AcquisitionJpaModels.SourceOnboardingCheckpointEntity toEntity(
        SourceOnboardingCheckpoint value
    ) {
        var entity = new AcquisitionJpaModels.SourceOnboardingCheckpointEntity();
        entity.id = new AcquisitionJpaModels.SourceOnboardingCheckpointId(value.sourceId(), value.checkpoint());
        entity.status = value.status(); entity.evidence = value.evidence(); entity.verifiedAt = value.verifiedAt();
        return entity;
    }

    private static SourceOnboardingCheckpoint toDomain(
        AcquisitionJpaModels.SourceOnboardingCheckpointEntity value
    ) {
        return new SourceOnboardingCheckpoint(value.id.sourceId, value.id.checkpoint,
            value.status, value.evidence, value.verifiedAt);
    }

    private static AcquisitionJpaModels.ArtifactImportFailureEntity toEntity(ArtifactImportFailure value) {
        var entity = new AcquisitionJpaModels.ArtifactImportFailureEntity();
        entity.id = value.id(); entity.runId = value.runId(); entity.sourceId = value.sourceId();
        entity.documentId = value.documentId(); entity.stage = value.stage(); entity.sheetName = value.sheetName();
        entity.rowNumber = value.rowNumber(); entity.errorCode = value.errorCode();
        entity.safeMessage = value.safeMessage(); entity.occurredAt = value.occurredAt();
        return entity;
    }

    private static ArtifactImportFailure toDomain(AcquisitionJpaModels.ArtifactImportFailureEntity value) {
        return new ArtifactImportFailure(value.id, value.runId, value.sourceId, value.documentId,
            value.stage, value.sheetName, value.rowNumber, value.errorCode, value.safeMessage, value.occurredAt);
    }

    private static AcquisitionJpaModels.ArtifactDiscoveryEntity toEntity(ArtifactDiscovery value) {
        var entity = new AcquisitionJpaModels.ArtifactDiscoveryEntity();
        entity.id = value.id(); entity.sourceId = value.sourceId(); entity.runId = value.runId();
        entity.parentDocumentId = value.parentDocumentId(); entity.canonicalUri = value.canonicalUri().toString();
        entity.fetchUri = value.fetchUri().toString(); entity.title = value.title(); entity.kind = value.kind();
        entity.publishedOn = value.publishedOn(); entity.status = value.status(); entity.errorCode = value.errorCode();
        entity.firstSeenAt = value.firstSeenAt(); entity.lastAttemptAt = value.lastAttemptAt();
        entity.attemptCount = value.attemptCount(); entity.mediaType = value.mediaType();
        entity.rawChecksum = value.rawChecksum(); entity.sizeBytes = value.sizeBytes();
        return entity;
    }

    private static ArtifactDiscovery toDomain(AcquisitionJpaModels.ArtifactDiscoveryEntity value) {
        return new ArtifactDiscovery(value.id, value.sourceId, value.runId, value.parentDocumentId,
            URI.create(value.canonicalUri), URI.create(value.fetchUri), value.title, value.kind,
            value.publishedOn, value.status, value.errorCode, value.firstSeenAt, value.lastAttemptAt,
            value.attemptCount, value.mediaType, value.rawChecksum, value.sizeBytes);
    }
}
