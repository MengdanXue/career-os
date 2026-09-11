package com.careeros.domain;

import com.careeros.domain.DomainEnums.EducationLevel;
import com.careeros.domain.DomainEnums.EmploymentType;
import com.careeros.domain.DomainEnums.Gender;
import com.careeros.domain.DomainEnums.JobFamily;
import com.careeros.domain.DomainEnums.OrganizationType;
import com.careeros.domain.DomainEnums.ApplicationTimeStatus;
import com.careeros.domain.DomainEnums.PoliticalAffiliation;
import java.util.List;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CandidateProfile(UUID id, String displayName, PartialDate birthDate, EducationLevel highestEducation, Set<String> majors, Integer graduationYear, Integer experienceYears, Set<String> professionalTitles, List<String> preferredLocations, Set<EmploymentType> acceptedEmploymentTypes, String profileVersion, Set<String> skills, Set<String> researchKeywords, Set<JobFamily> targetJobFamilies, Set<OrganizationType> preferredOrganizationTypes, List<EducationRecord> educationRecords, Gender gender, PoliticalAffiliation politicalAffiliation, List<CandidateEmploymentRecord> employmentRecords, ApplicationTimeStatus employerSettlementAtApplication, ApplicationTimeStatus socialInsuranceAtApplication) {
    public CandidateProfile {
        Objects.requireNonNull(id); require(displayName, "displayName"); Objects.requireNonNull(birthDate); Objects.requireNonNull(highestEducation);
        if (experienceYears != null && experienceYears < 0) throw new IllegalArgumentException("experienceYears is invalid");
        majors = majors == null ? Set.of() : Set.copyOf(majors); professionalTitles = professionalTitles == null ? Set.of() : Set.copyOf(professionalTitles);
        preferredLocations = preferredLocations == null ? List.of() : List.copyOf(preferredLocations); acceptedEmploymentTypes = acceptedEmploymentTypes == null ? Set.of() : Set.copyOf(acceptedEmploymentTypes);
        require(profileVersion, "profileVersion");
        skills = skills == null ? Set.of() : Set.copyOf(skills);
        researchKeywords = researchKeywords == null ? Set.of() : Set.copyOf(researchKeywords);
        targetJobFamilies = targetJobFamilies == null ? Set.of() : Set.copyOf(targetJobFamilies);
        preferredOrganizationTypes = preferredOrganizationTypes == null ? Set.of() : Set.copyOf(preferredOrganizationTypes);
        educationRecords = educationRecords == null ? List.of() : List.copyOf(educationRecords);
        gender = gender == null ? Gender.UNKNOWN : gender;
        politicalAffiliation = politicalAffiliation == null ? PoliticalAffiliation.UNKNOWN : politicalAffiliation;
        employmentRecords = employmentRecords == null ? List.of() : List.copyOf(employmentRecords);
        employerSettlementAtApplication = employerSettlementAtApplication == null
            ? ApplicationTimeStatus.UNDECLARED : employerSettlementAtApplication;
        socialInsuranceAtApplication = socialInsuranceAtApplication == null
            ? ApplicationTimeStatus.UNDECLARED : socialInsuranceAtApplication;
        requireTokens(majors, "majors");
        requireTokens(professionalTitles, "professionalTitles");
        requireTokens(preferredLocations, "preferredLocations");
        requireTokens(skills, "skills");
        requireTokens(researchKeywords, "researchKeywords");
    }
    public CandidateProfile(UUID id, String displayName, PartialDate birthDate, EducationLevel highestEducation, Set<String> majors, Integer graduationYear, Integer experienceYears, Set<String> professionalTitles, List<String> preferredLocations, Set<EmploymentType> acceptedEmploymentTypes, String profileVersion) {
        this(id, displayName, birthDate, highestEducation, majors, graduationYear, experienceYears,
            professionalTitles, preferredLocations, acceptedEmploymentTypes, profileVersion,
            Set.of(), Set.of(), Set.of(), Set.of(), List.of(), Gender.UNKNOWN, PoliticalAffiliation.UNKNOWN, List.of(), ApplicationTimeStatus.UNDECLARED, ApplicationTimeStatus.UNDECLARED);
    }
    public CandidateProfile(UUID id, String displayName, PartialDate birthDate, EducationLevel highestEducation, Set<String> majors, Integer graduationYear, Integer experienceYears, Set<String> professionalTitles, List<String> preferredLocations, Set<EmploymentType> acceptedEmploymentTypes, String profileVersion, Set<String> skills, Set<String> researchKeywords, Set<JobFamily> targetJobFamilies, Set<OrganizationType> preferredOrganizationTypes) {
        this(id, displayName, birthDate, highestEducation, majors, graduationYear, experienceYears,
            professionalTitles, preferredLocations, acceptedEmploymentTypes, profileVersion,
            skills, researchKeywords, targetJobFamilies, preferredOrganizationTypes, List.of(), Gender.UNKNOWN, PoliticalAffiliation.UNKNOWN, List.of(), ApplicationTimeStatus.UNDECLARED, ApplicationTimeStatus.UNDECLARED);
    }
    public CandidateProfile(UUID id, String displayName, PartialDate birthDate, EducationLevel highestEducation, Set<String> majors, Integer graduationYear, Integer experienceYears, Set<String> professionalTitles, List<String> preferredLocations, Set<EmploymentType> acceptedEmploymentTypes, String profileVersion, Set<String> skills, Set<String> researchKeywords, Set<JobFamily> targetJobFamilies, Set<OrganizationType> preferredOrganizationTypes, List<EducationRecord> educationRecords) {
        this(id, displayName, birthDate, highestEducation, majors, graduationYear, experienceYears,
            professionalTitles, preferredLocations, acceptedEmploymentTypes, profileVersion,
            skills, researchKeywords, targetJobFamilies, preferredOrganizationTypes, educationRecords,
            Gender.UNKNOWN, PoliticalAffiliation.UNKNOWN, List.of(), ApplicationTimeStatus.UNDECLARED, ApplicationTimeStatus.UNDECLARED);
    }
    public CandidateProfile(UUID id, String displayName, PartialDate birthDate, EducationLevel highestEducation, Set<String> majors, Integer graduationYear, Integer experienceYears, Set<String> professionalTitles, List<String> preferredLocations, Set<EmploymentType> acceptedEmploymentTypes, String profileVersion, Set<String> skills, Set<String> researchKeywords, Set<JobFamily> targetJobFamilies, Set<OrganizationType> preferredOrganizationTypes, List<EducationRecord> educationRecords, Gender gender, PoliticalAffiliation politicalAffiliation, List<CandidateEmploymentRecord> employmentRecords) {
        this(id, displayName, birthDate, highestEducation, majors, graduationYear, experienceYears,
            professionalTitles, preferredLocations, acceptedEmploymentTypes, profileVersion,
            skills, researchKeywords, targetJobFamilies, preferredOrganizationTypes, educationRecords,
            gender, politicalAffiliation, employmentRecords,
            ApplicationTimeStatus.UNDECLARED, ApplicationTimeStatus.UNDECLARED);
    }
    /**
     * 只改一项、其余原样复制。
     *
     * <p>逐字段重建构造参数出过一次事故：漏掉两个报名时状态字段，结果每保存一次资料就把用户
     * 的声明静默重置成"未声明"。这类改动不会报错，只会安静地丢数据。所以全部字段只在这里
     * 抄写一次，各个 {@code with*} 都走它；{@code CandidateProfileTest} 用记录组件逐个比对，
     * 将来新增字段若忘了带上，测试会失败。
     */
    private CandidateProfile copy(
        Gender gender, PoliticalAffiliation politicalAffiliation,
        ApplicationTimeStatus employerSettlementAtApplication, ApplicationTimeStatus socialInsuranceAtApplication
    ) {
        return new CandidateProfile(
            id, displayName, birthDate, highestEducation, majors, graduationYear, experienceYears,
            professionalTitles, preferredLocations, acceptedEmploymentTypes, profileVersion, skills,
            researchKeywords, targetJobFamilies, preferredOrganizationTypes, educationRecords,
            gender, politicalAffiliation, employmentRecords,
            employerSettlementAtApplication, socialInsuranceAtApplication
        );
    }

    public CandidateProfile withGender(Gender value) {
        return copy(value, politicalAffiliation, employerSettlementAtApplication, socialInsuranceAtApplication);
    }

    public CandidateProfile withPoliticalAffiliation(PoliticalAffiliation value) {
        return copy(gender, value, employerSettlementAtApplication, socialInsuranceAtApplication);
    }

    public CandidateProfile withEmployerSettlementAtApplication(ApplicationTimeStatus value) {
        return copy(gender, politicalAffiliation, value, socialInsuranceAtApplication);
    }

    public CandidateProfile withSocialInsuranceAtApplication(ApplicationTimeStatus value) {
        return copy(gender, politicalAffiliation, employerSettlementAtApplication, value);
    }

    private static void require(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required"); }
    private static void requireTokens(Collection<String> values, String field) {
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(field + " cannot contain blank values");
        }
    }
}
