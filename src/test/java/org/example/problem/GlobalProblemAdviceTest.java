package org.example.problem;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End to end behaviour of the advice against a real dispatcher.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalProblemAdviceTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void baseExceptionCarriesStatusCodeAndExtensions() throws Exception {
		this.mockMvc.perform(get("/orders/123"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
				.andExpect(jsonPath("$.detail").value("Order 123 was not found"))
				.andExpect(jsonPath("$.instance").value("/orders/123"))
				.andExpect(jsonPath("$.type").value("https://errors.example.org/order-not-found"))
				.andExpect(jsonPath("$.orderId").value("123"))
				.andExpect(jsonPath("$.timestamp").exists())
				.andExpect(jsonPath("$.traceId").exists());
	}

	@Test
	void legacyExceptionWithResponseStatusIsReused() throws Exception {
		this.mockMvc.perform(get("/legacy/annotated"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("LEGACY_ANNOTATED"))
				.andExpect(jsonPath("$.detail").value("Order already submitted"));
	}

	@Test
	void legacyExceptionIsMappedByConfiguration() throws Exception {
		this.mockMvc.perform(get("/legacy/mapped"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"))
				.andExpect(jsonPath("$.detail").value("The account balance is lower than the requested amount."));
	}

	@Test
	void unexpectedExceptionNeverLeaksItsMessage() throws Exception {
		this.mockMvc.perform(get("/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.detail").value("Unexpected error. Please try again later."))
				.andExpect(content().string(not(containsString("secret"))))
				.andExpect(content().string(not(containsString("IllegalStateException"))));
	}

	@Test
	void bodyValidationReturnsFieldErrors() throws Exception {
		String body = """
				{"name": "", "email": "not-an-email", "age": 12}
				""";

		this.mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors.length()").value(3))
				.andExpect(jsonPath("$.errors[?(@.field == 'name')].message").value("Name is required"))
				.andExpect(jsonPath("$.errors[?(@.field == 'email')].message").value("Invalid email format"))
				.andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
	}

	@Test
	void unreadableBodyIsAClientError() throws Exception {
		this.mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"))
				.andExpect(jsonPath("$.traceId").exists());
	}

	@Test
	void missingParameterIsAClientError() throws Exception {
		this.mockMvc.perform(get("/search"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
	}

	@Test
	void parameterOfTheWrongTypeIsAClientError() throws Exception {
		this.mockMvc.perform(get("/search").param("size", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PARAMETER_TYPE"));
	}

	@Test
	void unsupportedMethodIsReported() throws Exception {
		this.mockMvc.perform(post("/search"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void serviceLevelValidationReturnsFieldErrors() throws Exception {
		this.mockMvc.perform(get("/age").param("value", "10"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].message").value("Age must be 18+"));
	}
}
