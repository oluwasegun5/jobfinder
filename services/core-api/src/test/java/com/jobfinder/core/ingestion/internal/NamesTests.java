package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.jobfinder.core.ingestion.internal.NormalizedJob.WorkMode;

/** The comparison forms that fingerprints are built from. */
class NamesTests {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', textBlock = """
            Acme, Inc.                    | acme
            ACME Corp                     | acme
            The Acme Company              | acme
            Acme Co., Ltd.                | acme
            Acme GmbH                     | acme
            Acme (Nigeria) Limited        | acme
            Johnson & Johnson             | johnson and johnson
            Nestlé SA                | nestle
            Acme Technologies Ltd.        | acme technologies
            Inc                           | inc
            """)
    void companyNamesLoseLegalSuffixesCaseAndAccents(String name, String expected) {
        assertThat(Names.company(name)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            Sr. Software Engineer (m/f/d) - Remote [REQ-1042]     | -      | senior software engineer
            Senior Software Engineer (Remote)                     | -      | senior software engineer
            Software Engineer - Lagos                             | Lagos  | software engineer
            Software Engineer in Lagos                            | Lagos  | software engineer
            Jr Developer                                          | -      | junior developer
            Data Scientist (f/m/x)                                | -      | data scientist
            Customer Success Manager – EMEA (Hybrid)         | -      | customer success manager emea
            C++ Developer                                         | -      | c++ developer
            Café Manager                                     | -      | cafe manager
            Engineer (Platform)                                   | -      | engineer platform
            Backend Engineer #4521                                | -      | backend engineer
            R&D Engineer                                          | -      | r and d engineer
            Product Manager, Growth                               | -      | product manager growth
            Senior Engineer                                       | -      | senior engineer
            Junior Engineer                                       | -      | junior engineer
            """)
    void titlesLoseDecorationButKeepTheLevelAndTheSpecialty(String title, String city, String expected) {
        assertThat(Names.title(title, city)).isEqualTo(expected);
    }

    @Test
    void locationKeysMakeRemoteJobsMeetByCountryAndOnSiteJobsByCity() {
        var remoteUs = LocationParser.parse("Remote - US");
        var remoteUnitedStates = LocationParser.parse("United States (Remote)");
        var lagos = LocationParser.parse("Lagos, Nigeria");
        var lagosShort = LocationParser.parse("Lagos");

        assertThat(Names.location(remoteUs, WorkMode.REMOTE)).isEqualTo("remote|US")
                .isEqualTo(Names.location(remoteUnitedStates, WorkMode.REMOTE));
        assertThat(Names.location(lagos, WorkMode.ONSITE)).isEqualTo("lagos|NG")
                .isEqualTo(Names.location(lagosShort, WorkMode.ONSITE));
        assertThat(Names.location(LocationParser.parse("Anywhere"), WorkMode.REMOTE)).isEqualTo("remote");
        assertThat(Names.location(LocationParser.parse(null), null)).isEmpty();
    }

    @Test
    void fingerprintsAreStable64CharacterHexAndSensitiveToEachPart() {
        String base = Names.fingerprint("acme", "senior engineer", "lagos|NG");

        assertThat(base).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(Names.fingerprint("acme", "senior engineer", "lagos|NG")).isEqualTo(base);
        assertThat(Names.fingerprint("acme2", "senior engineer", "lagos|NG")).isNotEqualTo(base);
        assertThat(Names.fingerprint("acme", "junior engineer", "lagos|NG")).isNotEqualTo(base);
        assertThat(Names.fingerprint("acme", "senior engineer", "abuja|NG")).isNotEqualTo(base);
        assertThat(Names.fingerprint("ab", "c", "d")).as("parts are delimited, not concatenated")
                .isNotEqualTo(Names.fingerprint("a", "bc", "d"));
    }
}
