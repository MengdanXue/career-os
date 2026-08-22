package com.careeros.infrastructure.acquisition;

import com.careeros.application.AcquisitionPorts.AcquisitionStore;
import com.careeros.application.AcquisitionPorts.ChangeCursor;
import com.careeros.application.AcquisitionPorts.ChangePage;
import com.careeros.application.AcquisitionPorts.PersistedDocumentChange;
import com.careeros.application.AcquisitionPorts.RunPage;
import com.careeros.application.AcquisitionPorts.RunQuery;
import com.careeros.domain.acquisition.AcquiredDocument;
import com.careeros.domain.acquisition.AcquisitionChange;
import com.careeros.domain.acquisition.AcquisitionChange.ChangeType;
import com.careeros.domain.acquisition.RecruitmentSource;
import com.careeros.domain.acquisition.SourceCrawlRun;
import com.careeros.domain.acquisition.SourceYearCoverage;
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
    @PersistenceContext private EntityManager entityManager;

    public JpaAcquisitionStore(
        RecruitmentSourceJpaRepository sources,
        SourceCrawlRunJpaRepository runs,
        AcquiredDocumentJpaRepository documents,
        AcquisitionChangeJpaRepository changes,
        SourceYearCoverageJpaRepository coverage
    ) {
        this.sources = sources;
        this.runs = runs;
        this.documents = documents;
        this.changes = changes;
        this.coverage = coverage;
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
        entity.contentFingerprint=value.contentFingerprint(); entity.etag=value.etag(); entity.lastModified=value.lastModified();
        entity.storageUri=value.storageUri().toString(); entity.state=value.state(); entity.firstSeenAt=value.firstSeenAt();
        entity.lastSeenAt=value.lastSeenAt(); entity.lastChangedAt=value.lastChangedAt(); entity.lastGoneAt=value.lastGoneAt();
        entity.consecutiveGoneCount=value.consecutiveGoneCount(); entity.lastHttpStatus=value.lastHttpStatus();
        entity.lastProcessedFingerprint=value.lastProcessedFingerprint();
        entity.lastProcessorVersion=value.lastProcessorVersion(); entity.version=value.version(); return entity;
    }

    private static AcquiredDocument toDomain(AcquisitionJpaModels.AcquiredDocumentEntity value) {
        return new AcquiredDocument(value.id,value.sourceId,URI.create(value.canonicalUri),value.parentDocumentId,
            value.kind,value.mediaType,value.contentFingerprint,value.etag,value.lastModified,URI.create(value.storageUri),
            value.state,value.firstSeenAt,value.lastSeenAt,value.lastChangedAt,value.lastGoneAt,value.consecutiveGoneCount,
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
        return entity;
    }

    private static SourceYearCoverage toDomain(AcquisitionJpaModels.SourceYearCoverageEntity value) {
        return new SourceYearCoverage(value.id.sourceId, value.id.recruitmentYear, value.status,
            value.discoveredCount, value.fetchedCount, value.parsedCount, value.targetJobCount,
            value.completionBasis, value.completedAt, value.updatedAt);
    }
}
