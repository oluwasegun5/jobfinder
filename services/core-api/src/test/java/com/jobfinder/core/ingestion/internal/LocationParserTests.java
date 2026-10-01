package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Location strings as boards actually write them: city, country, remote and hybrid markers, noise. */
class LocationParserTests {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            Lagos, Nigeria                          | Lagos          | NG | false | false
            Lagos                                   | Lagos          | NG | false | false
            Lagos, Lagos State, Nigeria             | Lagos          | NG | false | false
            Austin, TX                              | Austin         | US | false | false
            Austin, TX, United States               | Austin         | US | false | false
            Austin, Texas 78701                     | Austin         | US | false | false
            San Jose, CA                            | San Jose       | US | false | false
            New York, NY                            | New York       | US | false | false
            NYC                                     | New York       | US | false | false
            Seattle, WA, USA                        | Seattle        | US | false | false
            Toronto, ON, Canada                     | Toronto        | CA | false | false
            London, ON                              | London         | CA | false | false
            London, UK                              | London         | GB | false | false
            Greater London Area                     | London         | GB | false | false
            London, England, United Kingdom         | London         | GB | false | false
            Berlin, DE                              | Berlin         | DE | false | false
            Munchen, Germany                        | Munich         | DE | false | false
            Bangalore, India                        | Bengaluru      | IN | false | false
            Utrecht, NL                             | Utrecht        | NL | false | false
            BERLIN                                  | Berlin         | DE | false | false
            Remote - US                             | -              | US | true  | false
            Remote (Worldwide)                      | -              | -  | true  | false
            Remote, Nigeria                         | -              | NG | true  | false
            United States (Remote)                  | -              | US | true  | false
            Anywhere                                | -              | -  | true  | false
            US Remote                               | -              | US | true  | false
            100% Remote - Canada                    | -              | CA | true  | false
            Berlin / Remote                         | Berlin         | DE | true  | false
            Hybrid - London                         | London         | GB | false | true
            Lagos (Hybrid)                          | Lagos          | NG | false | true
            Hybrid, Austin, TX                      | Austin         | US | false | true
            New York, NY; San Francisco, CA         | New York       | US | false | false
            Remote, EMEA                            | -              | -  | true  | false
            Europe                                  | -              | -  | false | false
            Nigeria                                 | -              | NG | false | false
            California                              | -              | US | false | false
            Based in Accra, Ghana                   | Accra          | GH | false | false
            Singapore                               | Singapore      | SG | false | false
            """)
    void parsesCityCountryAndWorkModeWords(String text, String city, String country, boolean remote, boolean hybrid) {
        LocationParser.Parsed parsed = LocationParser.parse(text);

        assertThat(parsed.city()).as("city").isEqualTo(city);
        assertThat(parsed.country()).as("country").isEqualTo(country);
        assertThat(parsed.remote()).as("remote").isEqualTo(remote);
        assertThat(parsed.hybrid()).as("hybrid").isEqualTo(hybrid);
    }

    @Test
    void anUnknownPlaceIsKeptAsTheCityWithNoCountry() {
        LocationParser.Parsed parsed = LocationParser.parse("Ikeja");

        assertThat(parsed.city()).isEqualTo("Ikeja");
        assertThat(parsed.country()).isNull();
    }

    @Test
    void emptyAndNullTextHasNoLocation() {
        assertThat(LocationParser.parse(null)).isEqualTo(LocationParser.Parsed.NONE);
        assertThat(LocationParser.parse("   ")).isEqualTo(LocationParser.Parsed.NONE);
        assertThat(LocationParser.parse("()")).isEqualTo(LocationParser.Parsed.NONE);
    }

    @Test
    void onSiteIsRecognisedAsAWorkModeWord() {
        LocationParser.Parsed parsed = LocationParser.parse("Nairobi, Kenya (On-site)");

        assertThat(parsed.onsite()).isTrue();
        assertThat(parsed.city()).isEqualTo("Nairobi");
        assertThat(parsed.country()).isEqualTo("KE");
    }
}
