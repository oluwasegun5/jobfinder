package com.jobfinder.core.ingestion.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.jobfinder.core.ingestion.internal.NormalizedJob.EmploymentType;
import com.jobfinder.core.ingestion.internal.NormalizedJob.Seniority;
import com.jobfinder.core.ingestion.internal.NormalizedJob.WorkMode;

/** Work mode, employment type and seniority read from the words real postings use. */
class InferenceTests {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            Senior Software Engineer                    | SENIOR
            Sr. Backend Developer                       | SENIOR
            Snr Data Engineer                           | SENIOR
            Software Engineer III                       | SENIOR
            Software Engineer II                        | MID
            Mid-Level Frontend Engineer                 | MID
            Junior QA Analyst                           | JUNIOR
            Jr. Java Developer                          | JUNIOR
            Entry Level Data Analyst                    | JUNIOR
            Graduate Software Engineer                  | JUNIOR
            Software Engineering Intern                 | INTERN
            Werkstudent Marketing                       | INTERN
            Apprentice Electrician                      | INTERN
            Tech Lead - Payments                        | LEAD
            Lead Backend Engineer                       | LEAD
            Staff Software Engineer                     | LEAD
            Principal Engineer                          | LEAD
            Senior Staff Engineer                       | LEAD
            Engineering Manager                         | LEAD
            Senior Manager, Data Science                | LEAD
            Manager of Engineering                      | LEAD
            Director of Engineering                     | EXECUTIVE
            VP Product                                  | EXECUTIVE
            Head of Growth                              | EXECUTIVE
            Chief Technology Officer                    | EXECUTIVE
            Senior Director, Finance                    | EXECUTIVE
            Product Manager                             | -
            Senior Product Manager                      | SENIOR
            Project Manager                             | -
            Lead Generation Specialist                  | -
            Data Entry Clerk                            | -
            Internal Tools Engineer                     | -
            Staff Accountant                            | -
            Software Engineer                           | -
            """)
    void seniorityComesFromTheTitleAlone(String title, Seniority expected) {
        assertThat(Inference.seniority(title)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0} / {1}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            Full-time          | -                               | FULL_TIME
            FULL_TIME          | -                               | FULL_TIME
            Permanent          | -                               | FULL_TIME
            Part time          | -                               | PART_TIME
            PART_TIME          | -                               | PART_TIME
            Contractor         | -                               | CONTRACT
            Contract           | -                               | CONTRACT
            Freelance          | -                               | CONTRACT
            Temporary          | -                               | TEMPORARY
            Seasonal           | -                               | TEMPORARY
            Internship         | -                               | INTERNSHIP
            -                  | Marketing Intern (Summer)       | INTERNSHIP
            -                  | Support Engineer (6 month contract) | CONTRACT
            -                  | Part-Time Barista               | PART_TIME
            -                  | Data Engineer                   | -
            Employee           | Data Engineer                   | -
            """)
    void employmentTypeComesFromTheLabelThenTheTitle(String label, String title, EmploymentType expected) {
        assertThat(Inference.employmentType(label, title, null)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            Employment type: Contract. Apply now.       | CONTRACT
            Job type - part time, 20 hours              | PART_TIME
            Great job with great people.                | -
            """)
    void anExplicitEmploymentTypeLineInTheDescriptionIsUsedAsALastResort(String description, EmploymentType expected) {
        assertThat(Inference.employmentType(null, "Engineer", description)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] flag={0} location={1} title={2}")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            true   | Lagos, Nigeria  | Engineer                  | -                                              | REMOTE
            -      | Remote - US     | Engineer                  | -                                              | REMOTE
            -      | Lagos           | Remote Support Agent      | -                                              | REMOTE
            -      | Lagos (Hybrid)  | Engineer                  | -                                              | HYBRID
            true   | Lagos           | Hybrid Engineer           | -                                              | HYBRID
            -      | Lagos           | Engineer                  | This role is hybrid with three office days.    | HYBRID
            -      | Lagos           | Engineer                  | We are a fully remote company.                 | REMOTE
            -      | Lagos           | Engineer                  | Work with remote teams and an onsite gym.      | ONSITE
            false  | -               | Engineer                  | -                                              | ONSITE
            -      | Nairobi (On-site) | Engineer                | -                                              | ONSITE
            -      | Austin, TX      | Engineer                  | -                                              | ONSITE
            -      | -               | Engineer                  | -                                              | -
            """)
    void workModeUsesFlagsAndLocationBeforeProse(Boolean flag, String location, String title, String description,
            WorkMode expected) {
        assertThat(Inference.workMode(flag, LocationParser.parse(location), title, description)).isEqualTo(expected);
    }
}
