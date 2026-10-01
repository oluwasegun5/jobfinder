package com.jobfinder.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web client in packages/api-contract is generated from /v3/api-docs, so the
 * spec must describe the health endpoint the web app calls.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void apiDocsIncludeActuatorHealth() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/actuator/health\"");
	}

	@Test
	void apiDocsIncludeTheAdminIngestionTargetEndpoint() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/admin/ingestion/targets\"").contains("AddTargetRequest");
	}

	@Test
	void apiDocsDescribeTheJobSearchEndpoints() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/jobs\"", "\"/jobs/{id}\"", "\"/jobs/{id}/similar\"", "\"/jobs/{id}/save\"",
				"\"/jobs/{id}/hide\"", "\"/jobs/{id}/match\"", "\"/saved-jobs\"", "\"nextCursor\"", "\"attribution\"", "\"postedWithinDays\"");
	}

	@Test
	void apiDocsDescribeTheBillingEndpointsAndLeaveOutTheInternalOnes() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/admin/billing/costs\"", "CostReportResponse", "\"/billing/allowance\"",
				"AllowanceResponse", "\"/resumes/{id}/reparse\"");
		assertThat(body).doesNotContain("/internal/v1/billing");
	}

}
