package com.otapp.hmis.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Structural CI gates (ADR-0005, ADR-0014 §1/§5/§6). All run in Surefire (no DB/Docker).
 *
 * <ol>
 *   <li>No {@code @PathVariable("id")}.</li>
 *   <li>No {@code {id}} in any request-mapping pattern.</li>
 *   <li>No {@code Long}/{@code long} {@code id} field on any class in a {@code dto} package.</li>
 *   <li>No {@code @Transactional} on a {@code @RestController}.</li>
 *   <li>{@code GlobalExceptionHandler} is the single {@code @RestControllerAdvice}.</li>
 * </ol>
 */
class ApiConventionsArchTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.otapp.hmis");

    @Test
    void noPathVariableNamedId() {
        classes()
                .should(notDeclareAnyMethodWithPathVariableNamedId())
                .allowEmptyShould(true)
                .because("ADR-0014 §1: the internal id must never appear in any URL or @PathVariable")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void noIdPlaceholderInRoutePatterns() {
        classes()
                .should(notDeclareAnyMappingPatternContainingIdPlaceholder())
                .allowEmptyShould(true)
                .because("ADR-0005 §4: routes address resources by uid (/uid/{uid}), never {id}")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void noLongIdFieldOnDtoClasses() {
        ArchRule rule = fields()
                .that().areDeclaredInClassesThat().resideInAPackage("..dto..")
                .and().haveName("id")
                .should(notBeAnIdentifierType())
                .allowEmptyShould(true)
                .because("ADR-0014 §1: DTOs must not carry a Long/long id field");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    void noTransactionalOnRestControllers() {
        classes()
                .that().areAnnotatedWith(RestController.class)
                .should(notBeTransactionalAtClassOrMethodLevel())
                .allowEmptyShould(true)
                .because("ADR-0014 §5: @Transactional belongs on services, never controllers")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void singleRestControllerAdvice() {
        List<JavaClass> advices = PRODUCTION_CLASSES.stream()
                .filter(c -> c.isAnnotatedWith(RestControllerAdvice.class))
                .toList();
        Assertions.assertThat(advices)
                .as("ADR-0014 §6: exactly one @RestControllerAdvice")
                .hasSize(1);
        Assertions.assertThat(advices.get(0).getSimpleName())
                .as("the single advice is GlobalExceptionHandler")
                .isEqualTo("GlobalExceptionHandler");
    }

    // ----------------------------------------------------------------------------------
    // Conditions
    // ----------------------------------------------------------------------------------

    private static ArchCondition<JavaClass> notDeclareAnyMethodWithPathVariableNamedId() {
        return new ArchCondition<>("not declare a @PathVariable named \"id\"") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (JavaMethod method : clazz.getMethods()) {
                    for (JavaParameter parameter : method.getParameters()) {
                        Optional<PathVariable> pv = parameter.tryGetAnnotationOfType(PathVariable.class);
                        if (pv.isPresent()) {
                            PathVariable annotation = pv.get();
                            String name = !annotation.value().isBlank() ? annotation.value() : annotation.name();
                            if ("id".equals(name)) {
                                events.add(SimpleConditionEvent.violated(method,
                                        method.getFullName() + " has @PathVariable(\"id\")"));
                            }
                        }
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDeclareAnyMappingPatternContainingIdPlaceholder() {
        return new ArchCondition<>("not declare a request mapping containing {id}") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (String pattern : classLevelPatterns(clazz)) {
                    flagIfIdPlaceholder(clazz, pattern, events);
                }
                for (JavaMethod method : clazz.getMethods()) {
                    for (String pattern : methodLevelPatterns(method)) {
                        flagIfIdPlaceholder(clazz, pattern, events);
                    }
                }
            }
        };
    }

    private static void flagIfIdPlaceholder(JavaClass clazz, String pattern, ConditionEvents events) {
        if (pattern.contains("{id}")) {
            events.add(SimpleConditionEvent.violated(clazz,
                    clazz.getName() + " maps a route containing {id}: " + pattern));
        }
    }

    private static List<String> classLevelPatterns(JavaClass clazz) {
        List<String> patterns = new ArrayList<>();
        clazz.tryGetAnnotationOfType(RequestMapping.class)
                .ifPresent(a -> add(patterns, a.value(), a.path()));
        return patterns;
    }

    private static List<String> methodLevelPatterns(JavaMethod method) {
        List<String> patterns = new ArrayList<>();
        method.tryGetAnnotationOfType(RequestMapping.class).ifPresent(a -> add(patterns, a.value(), a.path()));
        method.tryGetAnnotationOfType(GetMapping.class).ifPresent(a -> add(patterns, a.value(), a.path()));
        method.tryGetAnnotationOfType(PostMapping.class).ifPresent(a -> add(patterns, a.value(), a.path()));
        method.tryGetAnnotationOfType(PutMapping.class).ifPresent(a -> add(patterns, a.value(), a.path()));
        method.tryGetAnnotationOfType(PatchMapping.class).ifPresent(a -> add(patterns, a.value(), a.path()));
        method.tryGetAnnotationOfType(DeleteMapping.class).ifPresent(a -> add(patterns, a.value(), a.path()));
        return patterns;
    }

    private static void add(List<String> target, String[] values, String[] paths) {
        if (values != null) {
            for (String v : values) {
                target.add(v);
            }
        }
        if (paths != null) {
            for (String p : paths) {
                target.add(p);
            }
        }
    }

    private static ArchCondition<JavaClass> notBeTransactionalAtClassOrMethodLevel() {
        return new ArchCondition<>("not be annotated with @Transactional (class or method level)") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                if (clazz.isAnnotatedWith(Transactional.class)) {
                    events.add(SimpleConditionEvent.violated(clazz,
                            clazz.getName() + " is @Transactional at class level on a controller"));
                }
                for (JavaMethod method : clazz.getMethods()) {
                    if (method.isAnnotatedWith(Transactional.class)) {
                        events.add(SimpleConditionEvent.violated(method,
                                method.getFullName() + " is @Transactional on a controller"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaField> notBeAnIdentifierType() {
        return new ArchCondition<>("not be an id of type long/Long") {
            @Override
            public void check(JavaField field, ConditionEvents events) {
                String type = field.getRawType().getName();
                if ("long".equals(type) || "java.lang.Long".equals(type)) {
                    events.add(SimpleConditionEvent.violated(field,
                            field.getFullName() + " is a Long/long id field in a dto package"));
                }
            }
        };
    }
}
