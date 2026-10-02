package com.jobfinder.core.rendering.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ResumeModelTests {

    @Test
    void datesReadAsMonthAndYear() {
        assertThat(ResumeModel.date("2021-03")).isEqualTo("Mar 2021");
        assertThat(ResumeModel.date("2017")).isEqualTo("2017");
        assertThat(ResumeModel.date("2021-13")).isEqualTo("2021");
        assertThat(ResumeModel.date("")).isEmpty();
    }

    @Test
    void textIsNfcSingleLineAndFreeOfInvisibleCharacters() {
        assertThat(ResumeModel.clean("ẹ́ á")).isEqualTo("ẹ́ á");
        assertThat(ResumeModel.clean(" a\t b  c\r\nd​­﻿ ")).isEqualTo("a b c d");
        assertThat(ResumeModel.clean(null)).isEmpty();
    }

    @Test
    void missingAndWrongTypedFieldsAreSimplyNotPrinted() {
        ResumeModel model = ResumeModel.parse(RenderingFixtures.JSON.readTree("""
                {"contact": {"full_name": 12, "links": [{"url": null}, {"label": "Site", "url": "https://a.test"}]},
                 "experience": [{"company": null, "title": null}, {"company": "Acme", "bullets": ["x", 3, "", " y "]}],
                 "skills": ["Java", "Java", "", null], "projects": [{"description": "no name"}], "education": "oops"}
                """));
        assertThat(model.name()).isEmpty();
        assertThat(model.contactLines()).containsExactly("Site: https://a.test");
        assertThat(model.experience()).hasSize(1);
        assertThat(model.experience().get(0).title()).isEqualTo("Acme");
        assertThat(model.experience().get(0).bullets()).containsExactly("x", "y");
        assertThat(model.skills()).containsExactly("Java");
        assertThat(model.projects()).isEmpty();
        assertThat(model.education()).isEmpty();
    }

    @Test
    void aCurrentRoleEndsInPresentAndAnEmptyResumeIsEmpty() {
        ResumeModel model = ResumeModel.parse(RenderingFixtures.JSON.readTree(
                "{\"experience\":[{\"company\":\"A\",\"title\":\"T\",\"start_date\":\"2020-01\",\"is_current\":true}]}"));
        assertThat(model.experience().get(0).dates()).isEqualTo("Jan 2020 – Present");
        assertThat(ResumeModel.parse(RenderingFixtures.JSON.readTree("{}")).empty()).isTrue();
    }
}
