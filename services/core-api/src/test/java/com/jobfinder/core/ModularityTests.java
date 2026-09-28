package com.jobfinder.core;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(CoreApiApplication.class);

    @Test
    void verifiesModuleStructure() {
        modules.verify();
    }
}
