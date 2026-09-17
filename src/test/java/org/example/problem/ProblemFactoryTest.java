package org.example.problem;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.logging.LogLevel;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolution rules of the factory, exercised without a web context.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
class ProblemFactoryTest {

	private final ProblemProperties properties = new ProblemProperties();

	private final ProblemFactory factory = new ProblemFactory(this.properties);

	private final WebRequest request = new ServletWebRequest(new MockHttpServletRequest("GET", "/orders/1"));

	static class OrderNotFoundException extends RuntimeException {
	}

	@Test
	void derivesTheCodeFromTheExceptionClassName() {
		// arrange / act
		ApiProblem problem = this.factory.create(new OrderNotFoundException(), HttpStatus.NOT_FOUND, this.request);

		// assert
		assertThat(problem.getCode()).isEqualTo("ORDER_NOT_FOUND");
		assertThat(problem.getStatus()).isEqualTo(404);
		assertThat(problem.getInstance()).isEqualTo(URI.create("/orders/1"));
	}

	@Test
	void configuredMappingWinsOverEveryOtherSource() {
		// arrange
		this.properties.setMappings(Map.of(OrderNotFoundException.class.getName(),
				new ProblemProperties.Mapping(HttpStatus.GONE.value(), "ORDER_ARCHIVED", "Gone", "Archived order.",
						LogLevel.INFO)));

		// act
		ApiProblem problem = this.factory.create(new OrderNotFoundException(), HttpStatus.NOT_FOUND, this.request);

		// assert
		assertThat(problem.getStatus()).isEqualTo(410);
		assertThat(problem.getCode()).isEqualTo("ORDER_ARCHIVED");
		assertThat(problem.getTitle()).isEqualTo("Gone");
		assertThat(problem.getDetail()).isEqualTo("Archived order.");
	}

	@Test
	void mappingDeclaredOnASuperclassCoversItsSubclasses() {
		// arrange
		this.properties.setMappings(Map.of(RuntimeException.class.getName(),
				new ProblemProperties.Mapping(HttpStatus.BAD_REQUEST.value(), null, null, null, null)));

		// act
		ApiProblem problem = this.factory.create(new OrderNotFoundException(), HttpStatus.NOT_FOUND, this.request);

		// assert
		assertThat(problem.getStatus()).isEqualTo(400);
		assertThat(problem.getCode()).isEqualTo("ORDER_NOT_FOUND");
	}

	@Test
	void baseExceptionCarriesItsOwnStatusCodeAndExtensions() {
		// arrange
		ApiException ex = ApiException.conflict("ORDER_SUBMITTED", "Already submitted.").with("orderId", "7");

		// act
		ApiProblem problem = this.factory.create(ex, null, this.request);

		// assert
		assertThat(problem.getStatus()).isEqualTo(409);
		assertThat(problem.getCode()).isEqualTo("ORDER_SUBMITTED");
		assertThat(problem.getDetail()).isEqualTo("Already submitted.");
		assertThat(problem.getProperties()).containsEntry("orderId", "7");
	}

	@Test
	void serverErrorsAnswerWithAGenericCodeAndDetail() {
		// arrange / act
		ApiProblem problem = this.factory.create(new IllegalStateException("connection string with a password"),
				HttpStatus.INTERNAL_SERVER_ERROR, this.request);

		// assert
		assertThat(problem.getStatus()).isEqualTo(500);
		assertThat(problem.getCode()).isEqualTo("INTERNAL_ERROR");
		assertThat(problem.getDetail()).isEqualTo(this.properties.getServerErrorDetail());
	}

	@Test
	void dataAccessFailuresAreMappedWithoutLeakingTheSchema() {
		// arrange / act
		ApiProblem problem = this.factory.create(
				new DataIntegrityViolationException("duplicate key value violates unique constraint uk_order_number"),
				null, this.request);

		// assert
		assertThat(problem.getStatus()).isEqualTo(409);
		assertThat(problem.getCode()).isEqualTo("DATA_CONFLICT");
		assertThat(problem.getDetail()).doesNotContain("uk_order_number");
	}

	@Test
	void typeIsBuiltFromTheBaseUriAndTheCode() {
		// arrange
		this.properties.setBaseTypeUri(URI.create("https://errors.example.org"));

		// act
		ApiProblem problem = this.factory.create(new OrderNotFoundException(), HttpStatus.NOT_FOUND, this.request);

		// assert
		assertThat(problem.getType()).isEqualTo(URI.create("https://errors.example.org/order-not-found"));
	}

	@Test
	void rejectedValueIsWithheldUnlessExplicitlyEnabled() {
		// arrange / act
		ApiProblem.Violation withheld = this.factory.violation("email", "invalid", "user@example.org");
		this.properties.setIncludeRejectedValue(true);
		ApiProblem.Violation included = this.factory.violation("email", "invalid", "user@example.org");

		// assert
		assertThat(withheld.rejectedValue()).isNull();
		assertThat(included.rejectedValue()).isEqualTo("user@example.org");
	}
}
