package org.example.problem;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.logging.LogLevel;
import org.springframework.lang.Nullable;

/**
 * Configuration of the global exception handling, under the {@code problem} prefix.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@ConfigurationProperties("problem")
public class ProblemProperties {

	/**
	 * Whether the global exception handling is registered at all. Turning it off leaves the application with
	 * Spring Boot's own error handling.
	 */
	private boolean enabled = true;

	/**
	 * Base URI prepended to the lower-cased, dash-separated error code to build the RFC 9457 {@code type}
	 * member, for example {@code https://errors.myapp.com/order-not-found}. When unset, {@code type} stays
	 * {@code about:blank}.
	 */
	@Nullable
	private URI baseTypeUri;

	/**
	 * Detail exposed for any 5xx response. The real exception message is never sent to the caller, it is only
	 * logged, so this value has to stay generic.
	 */
	private String serverErrorDetail = "Unexpected error. Please try again later.";

	/**
	 * Error code used for 5xx responses that no mapping matched.
	 */
	private String serverErrorCode = "INTERNAL_ERROR";

	/**
	 * Whether validation failures echo back the rejected value. Off by default: the rejected value is caller
	 * input and can carry PII.
	 */
	private boolean includeRejectedValue = true;

	/**
	 * MDC keys read, in order, to pick up the correlation id. Micrometer Tracing populates {@code traceId} on
	 * the MDC, so the default covers a traced application. When no key holds a value, a random id is generated
	 * and logged alongside the failure, so the caller always has something to quote.
	 */
	private List<String> traceIdMdcKeys = List.of("traceId", "trace_id");

	/**
	 * Per-exception mapping, keyed by fully qualified class name. Because the key contains dots it has to be
	 * bracketed in YAML:
	 * <pre>
	 * problem:
	 *   mappings:
	 *     "[com.legacy.OrderNotFoundException]":
	 *       status: 404
	 *       code: ORDER_NOT_FOUND
	 * </pre>
	 * A mapping declared for a superclass applies to its subclasses, the closest match winning.
	 */
	private Map<String, Mapping> mappings = new LinkedHashMap<>();

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@Nullable
	public URI getBaseTypeUri() {
		return this.baseTypeUri;
	}

	public void setBaseTypeUri(@Nullable URI baseTypeUri) {
		this.baseTypeUri = baseTypeUri;
	}

	public String getServerErrorDetail() {
		return this.serverErrorDetail;
	}

	public void setServerErrorDetail(String serverErrorDetail) {
		this.serverErrorDetail = serverErrorDetail;
	}

	public String getServerErrorCode() {
		return this.serverErrorCode;
	}

	public void setServerErrorCode(String serverErrorCode) {
		this.serverErrorCode = serverErrorCode;
	}

	public boolean isIncludeRejectedValue() {
		return this.includeRejectedValue;
	}

	public void setIncludeRejectedValue(boolean includeRejectedValue) {
		this.includeRejectedValue = includeRejectedValue;
	}

	public List<String> getTraceIdMdcKeys() {
		return this.traceIdMdcKeys;
	}

	public void setTraceIdMdcKeys(List<String> traceIdMdcKeys) {
		this.traceIdMdcKeys = traceIdMdcKeys;
	}

	public Map<String, Mapping> getMappings() {
		return this.mappings;
	}

	public void setMappings(Map<String, Mapping> mappings) {
		this.mappings = mappings;
	}

	/**
	 * How one exception class is turned into a response. Every member is optional; what is left out falls back
	 * to the resolution chain in {@link ProblemFactory}.
	 *
	 * @param status   the HTTP status code to answer with, as a number, for example {@code 404}
	 * @param code     the application error code; derived from the class name when omitted
	 * @param title    the short human readable summary; the status reason phrase when omitted
	 * @param detail   the message for the caller; the exception message when omitted, for 4xx only
	 * @param logLevel the level this failure is logged at; {@code WARN} for 4xx and {@code ERROR} for 5xx
	 *                 when omitted
	 */
	public record Mapping(@Nullable Integer status, @Nullable String code, @Nullable String title,
			@Nullable String detail, @Nullable LogLevel logLevel) {
	}
}
