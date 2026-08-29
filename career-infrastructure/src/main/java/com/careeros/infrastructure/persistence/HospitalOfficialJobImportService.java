package com.careeros.infrastructure.persistence;

import com.careeros.application.JobUpsertService;
import com.careeros.application.JobUpsertService.JobUpsertBatch;
import com.careeros.application.OfficialJobAdmissionService;
import com.careeros.domain.DomainEnums.OrganizationType;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.infrastructure.acquisition.HospitalOfficialPageParser.ParsedHospitalAnnouncement;
import com.careeros.infrastructure.persistence.OfficialJobFieldMapper.ImportContext;
import com.careeros.infrastructure.persistence.OfficialJobFieldMapper.RawOfficialJob;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HospitalOfficialJobImportService {
    private static final String ORGANIZATION = "杭州市第一人民医院";
    private final RecruitmentEventJpaRepository events;
    private final OrganizationJpaRepository organizations;
    private final JobUpsertService upserts;
    private final OfficialJobAdmissionService admissions;
    private final OfficialJobFieldMapper fields;

    public HospitalOfficialJobImportService(
        RecruitmentEventJpaRepository events,
        OrganizationJpaRepository organizations,
        JobUpsertService upserts,
        OfficialJobAdmissionService admissions,
        OfficialJobFieldMapper fields
    ) {
        this.events = Objects.requireNonNull(events);
        this.organizations = Objects.requireNonNull(organizations);
        this.upserts = Objects.requireNonNull(upserts);
        this.admissions = Objects.requireNonNull(admissions);
        this.fields = Objects.requireNonNull(fields);
    }

    @Transactional
    public ImportResult importAnnouncement(String sourceUrl, ParsedHospitalAnnouncement parsed) {
        Objects.requireNonNull(parsed, "parsed");
        var event = events.findFirstBySourceUrl(sourceUrl)
            .orElseThrow(() -> new IllegalStateException("Hospital recruitment event is missing: " + sourceUrl));
        if (parsed.applicationUrl() != null && !parsed.applicationUrl().equals(event.registrationUrl)) {
            event.registrationUrl = parsed.applicationUrl();
            events.save(event);
        }
        if (parsed.jobs().isEmpty()) return new ImportResult(event.id, 0, 0, 0, 0);
        var organization = organizations.findFirstByName(ORGANIZATION).orElseGet(() -> {
            var created = new JpaModels.OrganizationEntity();
            created.id = UUID.randomUUID(); created.name = ORGANIZATION;
            created.organizationType = OrganizationType.HOSPITAL;
            created.province = "浙江"; created.city = "杭州";
            return organizations.save(created);
        });
        var context = new ImportContext(event.id, organization.id, organization.name, "杭州",
            event.ageReferenceDate, sourceUrl, sourceUrl,
            event.evidenceIds == null ? List.of() : event.evidenceIds,
            event.defaultEmploymentType == null ? EmploymentType.UNKNOWN : event.defaultEmploymentType,
            ORGANIZATION, null, event.employmentStatement);
        var normalized = new ArrayList<JobUpsertService.NormalizedJob>();
        for (var row : parsed.jobs()) {
            normalized.add(fields.toNormalizedJob(new RawOfficialJob(
                stableRowCode(row.department(), row.title()), row.title(), null, row.majors(),
                row.educationDegree(), row.employmentText(), row.headcount(),
                row.candidateScope(), row.ageLimit(), null, null, row.department(), row.category(),
                row.actualEmployer(), row.worksite()), context));
        }
        var result = upserts.upsert(new JobUpsertBatch(
            event.id, sourceUrl, normalized, parsed.completeSnapshot(), List.of()));
        admissions.classify(result.jobIds(), Instant.now());
        return new ImportResult(event.id, result.inserted(), result.updated(), result.unchanged(), result.deactivated());
    }

    private static String stableRowCode(String department, String title) {
        return (department == null || department.isBlank() ? "未标明科室" : department.trim()) + "|" + title.trim();
    }

    public record ImportResult(
        UUID recruitmentEventId,
        int inserted,
        int updated,
        int unchanged,
        int deactivated
    ) {}
}
