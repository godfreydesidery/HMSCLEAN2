package com.otapp.hmis;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith boundary verification (ADR-0001, ADR-0008). Asserts the module graph (14
 * bounded contexts + shared kernel) is acyclic and respects declared allowed-dependencies.
 * Runs in Surefire (no DB, no Docker required).
 */
class ModularityTest {

    static final ApplicationModules MODULES = ApplicationModules.of(HmisApplication.class);

    @Test
    void verifiesModuleStructure() {
        MODULES.verify();
    }
}
