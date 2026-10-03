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
	void apiDocsDescribeTheMockInterviewEndpoints() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/interview-sessions\"", "\"/interview-sessions/{id}\"",
				"\"/interview-sessions/{id}/answers\"", "\"/interview-sessions/{id}/complete\"", "StartSessionRequest",
				"AnswerRequest", "AnswerResult", "SessionView", "SessionPage", "FeedbackView", "SummaryView");
	}

	@Test
	void apiDocsDescribeTheInterviewPrepEndpoints() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/interview-prep\"", "\"/interview-prep/{id}\"", "GeneratePrepRequest",
				"InterviewPrepView", "CompanyBriefView", "BriefClaimView");
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

	@Test
	void apiDocsDescribeTheNotificationEndpoints() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/saved-searches\"", "\"/saved-searches/{id}\"", "\"/notifications/preferences\"",
				"\"/notifications/unsubscribe/{token}\"", "SavedSearchRequest", "NotificationPreferencesRequest", "UnsubscribeInfo");
	}

	@Test
	void apiDocsDescribeTheDocumentEndpoints() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/jobs/{id}/tailor\"", "\"/documents\"", "\"/documents/{id}\"",
				"\"/documents/{id}/approve\"", "DraftResponse", "PatchRequest", "FactCheckView", "FlagView");
	}

	@Test
	void apiDocsDescribeTheRenderEndpoints() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/documents/{id}/render\"", "\"/resumes/{id}/render\"", "RenderRequest",
				"RenderedFileResponse", "RenderedFileSummary", "\"/documents/{id}/files\"",
				"\"/documents/{id}/files/{fileId}/download\"", "\"downloadUrl\"", "\"STYLED\"", "\"LETTER\"", "\"DOCX\"");
	}

	@Test
	void apiDocsDescribeCoverLettersAnswersAndPacks() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/jobs/{id}/cover-letter\"", "\"/jobs/{id}/screening-answers\"",
				"\"/jobs/{id}/application-pack\"", "\"/application-packs\"", "\"/application-packs/{id}\"",
				"\"/application-packs/{id}/retry\"", "PackResponse", "PartView", "PartError", "\"COVER_LETTER\"",
				"\"SCREENING_ANSWERS\"", "\"BLOCKED_BY_CAP\"", "\"SUPERSEDED\"");
	}

	@Test
	void apiDocsDescribeTheApplicationTracker() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).contains("\"/applications\"", "\"/applications/{id}\"", "\"/applications/{id}/status\"",
				"\"/applications/{id}/reminders\"", "\"/applications/{id}/reminders/{reminderId}\"",
				"\"/applications/{id}/follow-up-draft\"", "ApplicationDetail", "EventView", "ReminderView",
				"FollowUpDraft", "\"WITHDRAWN\"", "\"SCREENING\"", "\"INTERVIEW\"");
	}

	@Test
	void theApplicationListHasItsOwnSchemaName() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		// Two records named ListResponse would share one schema and the generated client would type one route with the
		// other's body.
		assertThat(body).contains("\"ApplicationListResponse\"").contains("\"ListResponse\"");
	}

}
