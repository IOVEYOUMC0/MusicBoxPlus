package com.huidu.musicboxplus.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

// The layered-architecture contract from ARCHITECTURE.md, enforced mechanically so a
// refactor that reintroduces an upward dependency fails the build instead of silently
// drifting. api -> common -> core -> module, dependencies point downward only.
class LayerDependencyRuleTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .importPackages("com.huidu.musicboxplus");

    private static final String API = "com.huidu.musicboxplus.api..";
    private static final String COMMON = "com.huidu.musicboxplus.common..";
    private static final String CORE = "com.huidu.musicboxplus.core..";
    private static final String MODULE = "com.huidu.musicboxplus.module..";

    @Test
    void apiDependsOnNothingInternal() {
        ArchRule rule = noClasses().that().resideInAPackage(API)
                .should().dependOnClassesThat().resideInAnyPackage(COMMON, CORE, MODULE);
        rule.check(CLASSES);
    }

    @Test
    void commonDependsOnNothingInternalExceptApi() {
        ArchRule rule = noClasses().that().resideInAPackage(COMMON)
                .should().dependOnClassesThat().resideInAnyPackage(CORE, MODULE);
        rule.check(CLASSES);
    }

    @Test
    void coreDependsOnNothingInternalExceptApiAndCommon() {
        ArchRule rule = noClasses().that().resideInAPackage(CORE)
                .should().dependOnClassesThat().resideInAnyPackage(MODULE);
        rule.check(CLASSES);
    }
}
