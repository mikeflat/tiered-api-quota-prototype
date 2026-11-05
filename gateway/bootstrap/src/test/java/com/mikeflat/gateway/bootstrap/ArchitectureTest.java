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
    private static final String PERSISTENCE = ROOT + ".adapters.persistence..";
    private static final String BOOTSTRAP = ROOT + ".bootstrap..";
    private static final String SPRING = "org.springframework..";

    @ArchTest
    static final ArchRule domain_depends_on_nothing_outer_or_spring = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(APP, ADAPTERS, BOOTSTRAP, SPRING);

    @ArchTest
    static final ArchRule app_depends_on_nothing_outer_or_spring = noClasses()
            .that().resideInAPackage(APP)
            .should().dependOnClassesThat().resideInAnyPackage(ADAPTERS, BOOTSTRAP, SPRING);

    @ArchTest
    static final ArchRule api_does_not_depend_on_infra = noClasses()
            .that().resideInAPackage(API)
            .should().dependOnClassesThat().resideInAPackage(INFRA);

    @ArchTest
    static final ArchRule infra_does_not_depend_on_api = noClasses()
            .that().resideInAPackage(INFRA)
            .should().dependOnClassesThat().resideInAPackage(API);

    @ArchTest
    static final ArchRule persistence_depends_on_domain_only = noClasses()
            .that().resideInAPackage(PERSISTENCE)
            .should().dependOnClassesThat().resideInAnyPackage(APP, API, INFRA);

    @ArchTest
    static final ArchRule other_adapters_do_not_depend_on_persistence = noClasses()
            .that().resideInAnyPackage(API, INFRA)
            .should().dependOnClassesThat().resideInAPackage(PERSISTENCE);

    @ArchTest
    static final ArchRule nothing_depends_on_bootstrap = noClasses()
            .that().resideOutsideOfPackage(BOOTSTRAP)
            .should().dependOnClassesThat().resideInAPackage(BOOTSTRAP);
}
