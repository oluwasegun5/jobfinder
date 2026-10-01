package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.jobfinder.core.ingestion.internal.NormalizedJob.SalaryPeriod;

/** Salary text and structured fields as ATS and aggregator feeds give them. */
class SalaryParserTests {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            $120k - $150k a year                       | 120000 | 150000 | USD | YEAR
            $120,000 - $150,000 per annum              | 120000 | 150000 | USD | YEAR
            120-150k USD                               | 120000 | 150000 | USD | YEAR
            US$95,000 to US$110,000                    | 95000  | 110000 | USD | YEAR
            GBP 45,000 - 55,000 p.a.                   | 45000  | 55000  | GBP | YEAR
            £45,000 - £55,000 per year       | 45000  | 55000  | GBP | YEAR
            €60.000 - €75.000 jaehrlich yearly | 60000  | 75000  | EUR | YEAR
            45.000 - 55.000 EUR                        | 45000  | 55000  | EUR | YEAR
            ₦400,000 monthly                      | 400000 | 400000 | NGN | MONTH
            NGN 350,000 - 500,000 per month            | 350000 | 500000 | NGN | MONTH
            $50/hr                                     | 50     | 50     | USD | HOUR
            $45 - $60 per hour                         | 45     | 60     | USD | HOUR
            C$ 85,000 - 95,000 annually                | 85000  | 95000  | CAD | YEAR
            A$150k - A$180k                            | 150000 | 180000 | AUD | YEAR
            up to $90k a year                          | -      | 90000  | USD | YEAR
            from £50,000                          | 50000  | -      | GBP | YEAR
            $130k+                                     | 130000 | -      | USD | YEAR
            $200 a day                                 | 200    | 200    | USD | DAY
            ₹30,00,000 per annum                  | 3000000 | 3000000 | INR | YEAR
            150k - 120k USD                            | 120000 | 150000 | USD | YEAR
            """)
    void textSalariesAreNormalized(String text, BigDecimal min, BigDecimal max, String currency, SalaryPeriod period) {
        SalaryParser.Salary salary = SalaryParser.parse(null, null, null, null, text);

        assertThat(salary).isNotNull();
        if (min == null) {
            assertThat(salary.min()).as("min").isNull();
        } else {
            assertThat(salary.min()).as("min").isEqualByComparingTo(min);
        }
        if (max == null) {
            assertThat(salary.max()).as("max").isNull();
        } else {
            assertThat(salary.max()).as("max").isEqualByComparingTo(max);
        }
        assertThat(salary.currency()).isEqualTo(currency);
        assertThat(salary.period()).isEqualTo(period);
    }

    @Test
    void structuredFieldsAreTakenAsTheyAreWithTheCurrencyCodeUppercased() {
        SalaryParser.Salary salary = SalaryParser.parse(new BigDecimal("80000"), new BigDecimal("100000"), "usd",
                "YEAR", null);

        assertThat(salary).isEqualTo(new SalaryParser.Salary(new BigDecimal("80000.00"), new BigDecimal("100000.00"),
                "USD", SalaryPeriod.YEAR));
    }

    @Test
    void structuredAmountsOutOfOrderAreSwappedSoTheRangeConstraintHolds() {
        SalaryParser.Salary salary = SalaryParser.parse(new BigDecimal("9000"), new BigDecimal("7000"), "EUR",
                "month", null);

        assertThat(salary.min()).isEqualByComparingTo("7000");
        assertThat(salary.max()).isEqualByComparingTo("9000");
        assertThat(salary.period()).isEqualTo(SalaryPeriod.MONTH);
    }

    @Test
    void aSalaryWithNoKnownCurrencyIsDroppedNotGuessed() {
        assertThat(SalaryParser.parse(new BigDecimal("80000"), new BigDecimal("100000"), null, "YEAR", null)).isNull();
        assertThat(SalaryParser.parse(null, null, null, null, "80,000 - 100,000 a year")).isNull();
    }

    @Test
    void theCurrencyCanComeFromTheSalaryTextWhenTheFieldIsEmpty() {
        SalaryParser.Salary salary = SalaryParser.parse(new BigDecimal("60000"), new BigDecimal("70000"), null, null,
                "£60-70k");

        assertThat(salary.currency()).isEqualTo("GBP");
        assertThat(salary.period()).as("60-70 thousand is unmistakably a year").isEqualTo(SalaryPeriod.YEAR);
    }

    @Test
    void nonPositiveAndAbsurdAmountsAreDropped() {
        assertThat(SalaryParser.parse(BigDecimal.ZERO, BigDecimal.ZERO, "USD", "YEAR", null)).isNull();
        assertThat(SalaryParser.parse(new BigDecimal("-5"), new BigDecimal("10"), "USD", "HOUR", null)).isNull();
        assertThat(SalaryParser.parse(new BigDecimal("1"), new BigDecimal("999999999999999"), "USD", "YEAR", null))
                .isNull();
    }

    @Test
    void anUnstatedPeriodIsInferredOnlyWhereTheMagnitudeLeavesNoDoubt() {
        assertThat(SalaryParser.parse(new BigDecimal("42"), new BigDecimal("55"), "USD", null, null).period())
                .isEqualTo(SalaryPeriod.HOUR);
        assertThat(SalaryParser.parse(new BigDecimal("3000"), new BigDecimal("4000"), "USD", null, null).period())
                .as("3,000 to 4,000 could be a month or a week").isNull();
    }

    @Test
    void noNumbersMeansNoSalary() {
        assertThat(SalaryParser.parse(null, null, "USD", "YEAR", "Competitive salary")).isNull();
        assertThat(SalaryParser.parse(null, null, null, null, null)).isNull();
    }
}
