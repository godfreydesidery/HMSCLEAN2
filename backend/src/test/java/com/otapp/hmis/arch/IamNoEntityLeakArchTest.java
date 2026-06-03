package com.otapp.hmis.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * Asserts that no class outside {@code com.otapp.hmis.iam} references domain entities from
 * {@code com.otapp.hmis.iam.domain} (build-spec §7, ADR-0008, Spring Modulith rule).
 *
 * <p>Only {@code com.otapp.hmis.iam.lookup} projections are allowed cross-module.
 */
class IamNoEntityLeakArchTest {

    private static final JavaClasses ALL_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.otapp.hmis");

    @Test
    void noClassOutsideIamReferencesIamDomainEntities() {
        noClasses()
                .that().resideOutsideOfPackage("com.otapp.hmis.iam..")
                .should().dependOnClassesThat()
                .resideInAPackage("com.otapp.hmis.iam.domain..")
                .allowEmptyShould(true)
                .because("iam.domain entities must not leak outside the iam module — " +
                         "use com.otapp.hmis.iam.lookup projections instead (ADR-0008)")
                .check(ALL_CLASSES);
    }
}
