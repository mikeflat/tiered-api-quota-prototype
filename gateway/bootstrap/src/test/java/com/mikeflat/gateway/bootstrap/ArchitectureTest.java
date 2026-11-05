package com.mikeflat.gateway.bootstrap;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Module dependency rules (dependencies point inward). Runs here because the bootstrap test
 * classpath holds every module.
 */
@AnalyzeClasses(packages = ArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String ROOT = "com.mikeflat.gateway";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String APP = ROOT + ".app..";
    private static final String ADAPTERS = ROOT + ".adapters..";
    private static final String API = ROOT + ".adapters.api..";
    private static final String INFRA = ROOT + ".adapters.infra..";
    private static final String BOOTSTRAP = ROOT + ".bootstrap..";
    private static final String SPRING = "org.springframework..";

    @ArchTest
    static final ArchRule domainDependsOnNothingOuterOrSpring = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(APP, ADAPTERS, BOOTSTRAP, SPRING);

    @ArchTest
    static final ArchRule appDependsOnNothingOuterOrSpring = noClasses()
            .that().resideInAPackage(APP)
            .should().dependOnClassesThat().resideInAnyPackage(ADAPTERS, BOOTSTRAP, SPRING);

    @ArchTest
    static final ArchRule apiDoesNotDependOnInfra = noClasses()
            .that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAPackage(INFRA);

    @ArchTest
    static final ArchRule infraDoesNotDependOnApi = noClasses()
            .that().resideInAPackage(INFRA)
            .should().dependOnClassesThat().resideInAPackage(API);

    @ArchTest
    static final ArchRule nothingDependsOnBootstrap = noClasses()
            .that().resideOutsideOfPackage(BOOTSTRAP)
            .should().dependOnClassesThat().resideInAPackage(BOOTSTRAP);
}
