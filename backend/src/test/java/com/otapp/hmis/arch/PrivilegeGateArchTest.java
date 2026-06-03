package com.otapp.hmis.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Asserts every authority code referenced in a {@code @PreAuthorize} annotation belongs to the
 * 26 live codes (build-spec §7, §1, ADR-0006).
 *
 * <p>Dead codes (9) and invented codes must never appear in a gate — this test fails the build
 * if any such code sneaks in.
 */
class PrivilegeGateArchTest {

    /**
     * The 27 live gate codes (build-spec §1 + inc-04 billing addition).
     * Any other code in @PreAuthorize is a bug.
     *
     * <p>BILL-A is seeded in V2:53 and is the gate for all billing endpoints
     * (view/pay/credit-note/collections — inc-04 build-spec §5.4, 11-DECISIONS-RATIFIED).
     * It was previously listed as a dead code before the billing increment was built.
     */
    private static final Set<String> LIVE_CODES = Set.of(
            "ADMIN-ACCESS",
            "DAY-ACCESS",
            "EMPLOYEE-ALL",
            "GOODS_RECEIVED_NOTE-ALL",
            "GOODS_RECEIVED_NOTE-CREATE",
            "GOODS_RECEIVED_NOTE-UPDATE",
            "GOODS_RECEIVED_NOTE-APPROVE",
            "LOCAL_PURCHASE_ORDER-ALL",
            "LOCAL_PURCHASE_ORDER-CREATE",
            "LOCAL_PURCHASE_ORDER-UPDATE",
            "PHARMACY_ORDER-ALL",
            "PHARMACY_ORDER-CREATE",
            "PHARMACY_ORDER-UPDATE",
            "STORE_ORDER-ALL",
            "PATIENT-ALL",
            "PATIENT-CREATE",
            "PATIENT-UPDATE",
            "PAYROLL-ALL",
            "PAYROLL-CREATE",
            "PAYROLL-UPDATE",
            "SUPPLIER_PRICE_LIST-ALL",
            "ITEM_STOCK-UPDATE",
            "MEDICINE_STOCK-UPDATE",
            "USER-ALL",
            "USER-UPDATE",
            "ROLE-ALL",
            // inc-04 billing gate (seeded V2:53; previously marked dead pending billing build)
            "BILL-A"
    );

    /** Matches single-quoted authority codes inside hasAnyAuthority / hasAuthority expressions. */
    private static final Pattern CODE_PATTERN =
            Pattern.compile("'([A-Z][A-Z0-9_\\-]*)'");

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.otapp.hmis");

    @Test
    void allPreAuthorizeCodesAreInTheLiveCodeSet() {
        List<String> violations = new ArrayList<>();

        for (JavaClass clazz : PRODUCTION_CLASSES) {
            // Check class-level @PreAuthorize (shouldn't exist per convention, but guard anyway)
            clazz.tryGetAnnotationOfType(PreAuthorize.class)
                    .ifPresent(a -> checkExpression(a.value(), clazz.getName(), "<class>", violations));

            // Check method-level @PreAuthorize
            for (JavaMethod method : clazz.getMethods()) {
                method.tryGetAnnotationOfType(PreAuthorize.class)
                        .ifPresent(a -> checkExpression(
                                a.value(), clazz.getName(), method.getName(), violations));
            }
        }

        assertThat(violations)
                .as("Every @PreAuthorize authority code must be in the 27 live codes (build-spec §1). " +
                    "Dead codes (GOO-ALL, PATIENT-A, PATIENT-C, PATIENT-U, " +
                    "PROCUREMENT-ACCESS, PRODUCT-CREATE, ROLE-CREATE, ROLE-U) must never gate anything. " +
                    "BILL-A is live as of inc-04. " +
                    "Violations:")
                .isEmpty();
    }

    private void checkExpression(String expression, String className, String memberName,
                                  List<String> violations) {
        Matcher matcher = CODE_PATTERN.matcher(expression);
        while (matcher.find()) {
            String code = matcher.group(1);
            if (!LIVE_CODES.contains(code)) {
                violations.add(String.format(
                        "Non-live authority code '%s' in @PreAuthorize on %s#%s",
                        code, className, memberName));
            }
        }
    }
}
