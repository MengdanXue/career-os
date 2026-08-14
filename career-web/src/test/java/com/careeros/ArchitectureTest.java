package com.careeros;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.careeros")
class ArchitectureTest {
    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses()
        .that().resideInAPackage("com.careeros.domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework..", "jakarta.persistence..", "org.springframework.ai..");

    @ArchTest
    static final ArchRule APPLICATION_DEPENDS_INWARD = noClasses()
        .that().resideInAPackage("com.careeros.application..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.careeros.infrastructure..", "org.springframework..", "org.springframework.ai..");

    @ArchTest
    static final ArchRule ELIGIBILITY_NEVER_DEPENDS_ON_AI = noClasses()
        .that().haveSimpleNameContaining("Eligibility")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..");
}
