package org.example.problem;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.ProblemDetail;
import org.springframework.lang.Nullable;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RFC 9457 problem representation extended with the fields every API in the house is expected to expose:
 * an application level {@code code}, the {@code timestamp} of the failure, the correlation {@code traceId}
 * and, for validation failures, the list of field level {@code errors}.
 *
 * <p>The inherited members keep their RFC meaning: {@code type} identifies the problem class, {@code title} is
 * the short human readable summary, {@code status} the HTTP status, {@code detail} the message meant for the
 * caller and {@code instance} the request path where the failure happened.</p>
 *
 * <p>The Jackson annotations are declared here on purpose. Spring registers its {@code ProblemDetailJacksonMixin}
 * against {@code ProblemDetail} itself, and relying on a superclass mixin to reach a subclass is brittle, so this
 * class restates the two behaviours it depends on: empty members are omitted and the {@code properties} map is
 * flattened into the JSON object instead of being nested under a {@code properties} key.</p>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ApiProblem extends ProblemDetail {

	private String code;

	private Instant timestamp = Instant.now();

	private String traceId;

	private List<Violation> errors;

	/**
	 * Creates an empty problem. Required by Jackson when a client deserializes a response.
	 */
	protected ApiProblem() {
		super();
	}

	ApiProblem(int rawStatusCode) {
		super(rawStatusCode);
	}

	/**
	 * Copy constructor used to wrap a {@link ProblemDetail} already produced by Spring MVC, preserving whatever
	 * {@code type}, {@code title}, {@code detail} and extension properties it had filled in.
	 */
	ApiProblem(ProblemDetail other) {
		super(other);
	}

	public String getCode() {
		return this.code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public Instant getTimestamp() {
		return this.timestamp;
	}

	public void setTimestamp(Instant timestamp) {
		this.timestamp = timestamp;
	}

	public String getTraceId() {
		return this.traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public List<Violation> getErrors() {
		return this.errors;
	}

	public void setErrors(List<Violation> errors) {
		this.errors = (errors == null || errors.isEmpty()) ? null : List.copyOf(errors);
	}

	void addError(Violation violation) {
		if (this.errors == null) {
			this.errors = new ArrayList<>();
		}
		else {
			this.errors = new ArrayList<>(this.errors);
		}
		this.errors.add(violation);
	}

	@JsonAnyGetter
	@Override
	@Nullable
	public Map<String, Object> getProperties() {
		return super.getProperties();
	}

	/**
	 * A single field level validation failure.
	 *
	 * @param field         the offending field, or the parameter name for method level validation
	 * @param message       the validation message
	 * @param rejectedValue the value that was rejected; only filled in when
	 *                      {@code problem.include-rejected-value} is enabled, since it can carry PII
	 */
	public record Violation(String field, String message, @Nullable Object rejectedValue) {

		public Violation(String field, String message) {
			this(field, message, null);
		}
	}
}
