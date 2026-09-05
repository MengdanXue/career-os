package com.careeros;

import com.careeros.application.CareerDecisionService;
import com.careeros.application.RepositoryPorts;
import com.careeros.domain.*;
import com.careeros.domain.DomainEnums.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.format.annotation.DateTimeFormat;
import com.careeros.infrastructure.persistence.OfficialExcelImportService;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class CareerMvpController {
    private final RepositoryPorts.Organizations organizations;
    private final RepositoryPorts.RecruitmentEvents events;
    private final RepositoryPorts.JobPostings jobs;
    private final RepositoryPorts.CandidateProfiles candidates;
    private final RepositoryPorts.Opportunities opportunities;
    private final CareerDecisionService decisions;
    private final OfficialExcelImportService excelImports;

    CareerMvpController(RepositoryPorts.Organizations organizations, RepositoryPorts.RecruitmentEvents events, RepositoryPorts.JobPostings jobs, RepositoryPorts.CandidateProfiles candidates, RepositoryPorts.Opportunities opportunities, CareerDecisionService decisions, OfficialExcelImportService excelImports) {
        this.organizations=organizations; this.events=events; this.jobs=jobs; this.candidates=candidates; this.opportunities=opportunities; this.decisions=decisions; this.excelImports=excelImports;
    }

    @GetMapping("/organizations") List<Organization> organizations() { return organizations.findAll(); }
    @GetMapping("/organizations/{id}") Organization organization(@PathVariable UUID id) { return required(organizations.findById(id),"Organization",id); }
    @PostMapping("/organizations") @ResponseStatus(HttpStatus.CREATED) Organization createOrganization(@RequestBody OrganizationRequest request) { return organizations.save(request.toDomain(UUID.randomUUID())); }
    @PutMapping("/organizations/{id}") Organization updateOrganization(@PathVariable UUID id,@RequestBody OrganizationRequest request) { required(organizations.findById(id),"Organization",id); return organizations.save(request.toDomain(id)); }
    @DeleteMapping("/organizations/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteOrganization(@PathVariable UUID id) { required(organizations.findById(id),"Organization",id); organizations.deleteById(id); }

    @GetMapping("/recruitment-events") List<RecruitmentEvent> events() { return events.findAll(); }
    @GetMapping("/recruitment-events/{id}") RecruitmentEvent event(@PathVariable UUID id) { return required(events.findById(id),"RecruitmentEvent",id); }
    @PostMapping("/recruitment-events") @ResponseStatus(HttpStatus.CREATED) RecruitmentEvent createEvent(@RequestBody EventRequest request) { return events.save(request.toDomain(UUID.randomUUID())); }
    @PutMapping("/recruitment-events/{id}") RecruitmentEvent updateEvent(@PathVariable UUID id,@RequestBody EventRequest request) { required(events.findById(id),"RecruitmentEvent",id); return events.save(request.toDomain(id)); }
    @DeleteMapping("/recruitment-events/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteEvent(@PathVariable UUID id) { required(events.findById(id),"RecruitmentEvent",id); events.deleteById(id); }

    @GetMapping("/jobs") List<JobPosting> jobs() { return jobs.findAll(); }
    @GetMapping("/jobs/{id}") JobPosting job(@PathVariable UUID id) { return required(jobs.findById(id),"JobPosting",id); }
    @PostMapping("/jobs") @ResponseStatus(HttpStatus.CREATED) JobPosting createJob(@RequestBody JobRequest request) { validateReferences(request); return jobs.save(request.toDomain(UUID.randomUUID())); }
    @PutMapping("/jobs/{id}") JobPosting updateJob(@PathVariable UUID id,@RequestBody JobRequest request) { required(jobs.findById(id),"JobPosting",id); validateReferences(request); return jobs.save(request.toDomain(id)); }
    @DeleteMapping("/jobs/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteJob(@PathVariable UUID id) { required(jobs.findById(id),"JobPosting",id); jobs.deleteById(id); }

    @GetMapping("/candidates") List<CandidateProfile> candidates() { return candidates.findAll(); }
    @GetMapping("/candidates/{id}") CandidateProfile candidate(@PathVariable UUID id) { return required(candidates.findById(id),"CandidateProfile",id); }
    @PostMapping("/candidates") @ResponseStatus(HttpStatus.CREATED) CandidateProfile createCandidate(@RequestBody CandidateRequest request) { return candidates.save(request.toDomain(UUID.randomUUID())); }
    @PutMapping("/candidates/{id}") CandidateProfile updateCandidate(@PathVariable UUID id,@RequestBody CandidateRequest request) { required(candidates.findById(id),"CandidateProfile",id); return candidates.save(request.toDomain(id)); }
    @DeleteMapping("/candidates/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void deleteCandidate(@PathVariable UUID id) { required(candidates.findById(id),"CandidateProfile",id); candidates.deleteById(id); }

    @PostMapping("/eligibility-assessments") CareerDecisionService.DecisionResult assess(@RequestBody AssessmentRequest request) { return decisions.assess(request.candidateId(),request.jobId(),Instant.now()); }
    @GetMapping("/opportunities") List<Opportunity> opportunities() { return opportunities.findAll(); }
    @PatchMapping("/opportunities/{id}/status") Opportunity updateOpportunityStatus(@PathVariable UUID id,@RequestBody OpportunityStatusRequest request) {
        var current=required(opportunities.findById(id),"Opportunity",id);
        return opportunities.save(new Opportunity(current.id(),current.candidateProfileId(),current.jobPostingId(),current.eligibilityAssessmentId(),request.status(),current.scorecard(),request.decisionNote()==null?current.decisionNote():request.decisionNote(),current.createdAt(),Instant.now()));
    }

    @PostMapping(value="/imports/excel",consumes="multipart/form-data")
    OfficialExcelImportService.ImportResult importExcel(
        @RequestPart("file") MultipartFile file,
        @RequestParam String announcementTitle,
        @RequestParam String sourceUrl,
        @RequestParam int recruitmentYear,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate publishedOn,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate ageReferenceDate,
        @RequestParam(defaultValue="浙江杭州") String defaultLocation,
        @RequestParam(defaultValue="PUBLIC_INSTITUTION") EventType eventType,
        @RequestParam(required=false) String defaultOrganizationName
    ) throws Exception {
        return excelImports.importWorkbook(file.getInputStream(),new OfficialExcelImportService.ImportCommand(announcementTitle,sourceUrl,recruitmentYear,publishedOn,ageReferenceDate,defaultLocation,eventType,defaultOrganizationName));
    }

    private void validateReferences(JobRequest request) { required(events.findById(request.recruitmentEventId()),"RecruitmentEvent",request.recruitmentEventId()); required(organizations.findById(request.organizationId()),"Organization",request.organizationId()); }
    private static <T> T required(Optional<T> value,String type,UUID id) { return value.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,type+" not found: "+id)); }

    record OrganizationRequest(String name,OrganizationType organizationType,String administrativeLevel,String province,String city,String district,UUID parentOrganizationId,String officialWebsite) {
        Organization toDomain(UUID id){return new Organization(id,name,organizationType,administrativeLevel,province,city,district,parentOrganizationId,officialWebsite);}
    }
    record EventRequest(String title,int recruitmentYear,EventType eventType,LocalDate publishedOn,LocalDate applicationStartsOn,LocalDate applicationEndsOn,String sourceUrl,EmploymentType defaultEmploymentType,List<UUID> evidenceIds) {
        RecruitmentEvent toDomain(UUID id){return new RecruitmentEvent(id,title,recruitmentYear,eventType,publishedOn,applicationStartsOn,applicationEndsOn,sourceUrl,defaultEmploymentType,evidenceIds);}
    }
    record JobRequest(UUID recruitmentEventId,UUID organizationId,String externalJobCode,String title,JobFamily jobFamily,EmploymentType employmentType,String location,int headcount,EducationLevel minimumEducation,Set<String> exactMajors,Set<Integer> acceptedGraduationYears,Integer maximumAge,LocalDate ageReferenceDate,Integer minimumExperienceYears,Set<String> requiredProfessionalTitles,String duties,String sourceUrl,List<UUID> evidenceIds,java.util.Map<com.careeros.domain.DomainEnums.JobField,List<UUID>> fieldEvidence) {
        JobPosting toDomain(UUID id){return new JobPosting(id,recruitmentEventId,organizationId,externalJobCode,title,jobFamily,employmentType,location,headcount,minimumEducation,exactMajors,acceptedGraduationYears,maximumAge,ageReferenceDate,minimumExperienceYears,requiredProfessionalTitles,duties,sourceUrl,evidenceIds,fieldEvidence==null?java.util.Map.of():fieldEvidence);}
    }
    record CandidateRequest(String displayName,int birthYear,int birthMonth,Integer birthDay,EducationLevel highestEducation,Set<String> majors,Integer graduationYear,Integer experienceYears,Set<String> professionalTitles,List<String> preferredLocations,Set<EmploymentType> acceptedEmploymentTypes,String profileVersion) {
        CandidateProfile toDomain(UUID id){return new CandidateProfile(id,displayName,new PartialDate(birthYear,birthMonth,birthDay),highestEducation,majors,graduationYear,experienceYears,professionalTitles,preferredLocations,acceptedEmploymentTypes,profileVersion);}
    }
    record AssessmentRequest(UUID candidateId,UUID jobId) {}
    record OpportunityStatusRequest(OpportunityStatus status,String decisionNote) {}
}
