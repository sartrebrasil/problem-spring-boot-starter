package org.example.problem;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.boot.logging.LogLevel;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns any {@link Throwable} into an {@link ApiProblem} and logs it. Every handler in this library goes
 * through here, which is what keeps a single response shape and a single log statement for the whole
 * application.
 *
 * <p>Status, code and detail are resolved in this order, the first source that answers winning:</p>
 * <ol>
 * <li>a {@code problem.mappings} entry for the exception class or one of its superclasses;</li>
 * <li>{@link ApiException}, which carries them itself;</li>
 * <li>a {@code @ResponseStatus} annotation on the exception class;</li>
 * <li>{@link ErrorResponse}, implemented by {@code ResponseStatusException} and the Spring MVC exceptions;</li>
 * <li>the {@link ProblemDetail} Spring MVC had already built, when wrapping one;</li>
 * <li>the fallback status the caller passed in, otherwise 500.</li>
 * </ol>
 *
 * <p>The exception message is never exposed on a 5xx response, it is only logged. Replace this bean to change
 * any of it: the auto-configuration backs off on {@code @ConditionalOnMissingBean}.</p>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
public class ProblemFactory {

	private static final Logger log = LoggerFactory.getLogger(ProblemFactory.class);

	private static final URI BLANK_TYPE = URI.create("about:blank");

	private static final String UNDERSCORE = "_";

	private static final String DASH = "-";

	/**
	 * Canonical codes for the framework exceptions, keyed by simple class name so that no optional class has
	 * to be loaded here. Overridable through {@code problem.mappings}.
	 */
	private static final Map<String, String> FRAMEWORK_CODES = Map.ofEntries(
			Map.entry("MethodArgumentNotValidException", "VALIDATION_FAILED"),
			Map.entry("HandlerMethodValidationException", "VALIDATION_FAILED"),
			Map.entry("ConstraintViolationException", "VALIDATION_FAILED"),
			Map.entry("BindException", "VALIDATION_FAILED"),
			Map.entry("HttpMessageNotReadableException", "MALFORMED_REQUEST_BODY"),
			Map.entry("HttpMessageNotWritableException", "RESPONSE_NOT_WRITABLE"),
			Map.entry("MissingServletRequestParameterException", "MISSING_PARAMETER"),
			Map.entry("MissingServletRequestPartException", "MISSING_REQUEST_PART"),
			Map.entry("MissingPathVariableException", "MISSING_PATH_VARIABLE"),
			Map.entry("MissingRequestHeaderException", "MISSING_HEADER"),
			Map.entry("MethodArgumentTypeMismatchException", "INVALID_PARAMETER_TYPE"),
			Map.entry("TypeMismatchException", "INVALID_PARAMETER_TYPE"),
			Map.entry("HttpRequestMethodNotSupportedException", "METHOD_NOT_ALLOWED"),
			Map.entry("HttpMediaTypeNotSupportedException", "UNSUPPORTED_MEDIA_TYPE"),
			Map.entry("HttpMediaTypeNotAcceptableException", "NOT_ACCEPTABLE"),
			Map.entry("NoResourceFoundException", "RESOURCE_NOT_FOUND"),
			Map.entry("NoHandlerFoundException", "RESOURCE_NOT_FOUND"),
			Map.entry("MaxUploadSizeExceededException", "PAYLOAD_TOO_LARGE"),
			Map.entry("AsyncRequestTimeoutException", "REQUEST_TIMEOUT"),
			Map.entry("ErrorResponseException", "REQUEST_FAILED"),
			Map.entry("ResponseStatusException", "REQUEST_FAILED"));

	/**
	 * Built-in mappings for the exceptions of the optional integrations, keyed by fully qualified class name so
	 * that Spring Security, Spring Data and Bean Validation are covered without loading a class that may not be
	 * on the classpath and without registering one advice per integration, which would compete for ordering
	 * with the advice of the application.
	 *
	 * <p>The data access details are deliberately generic: an SQL statement or a constraint name in the
	 * response would leak the schema. So are the authentication ones, where telling a bad password apart from
	 * an unknown user would be user enumeration. They stay in the log.</p>
	 *
	 * <p>{@code AuthenticationServiceException} is mapped apart from the rest of the authentication failures
	 * although it inherits from them, because it is the identity provider being unreachable or misconfigured,
	 * not a caller presenting bad credentials. As a 5xx it is logged at {@code ERROR}, and the caller is told
	 * to retry rather than to fix its credentials.</p>
	 *
	 * <p>A {@code problem.mappings} entry for the same class, or for any of its subclasses, replaces the entry
	 * here.</p>
	 */
	private static final Map<String, ProblemProperties.Mapping> DEFAULT_MAPPINGS = Map.ofEntries(
			Map.entry("org.springframework.security.core.AuthenticationException",
					new ProblemProperties.Mapping(HttpStatus.UNAUTHORIZED.value(), "UNAUTHENTICATED", null,
							"Authentication is required.", null)),
			Map.entry("org.springframework.security.authentication.AuthenticationServiceException",
					new ProblemProperties.Mapping(HttpStatus.SERVICE_UNAVAILABLE.value(),
							"AUTHENTICATION_UNAVAILABLE", null, null, null)),
			Map.entry("org.springframework.security.access.AccessDeniedException",
					new ProblemProperties.Mapping(HttpStatus.FORBIDDEN.value(), "ACCESS_DENIED", null,
							"Access is denied.", null)),
			Map.entry("jakarta.validation.ConstraintViolationException",
					new ProblemProperties.Mapping(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", null,
							"Invalid request parameters.", null)),
			Map.entry("org.springframework.dao.DataIntegrityViolationException",
					new ProblemProperties.Mapping(HttpStatus.CONFLICT.value(), "DATA_CONFLICT", null,
							"The request conflicts with the current state of the resource.", null)),
			Map.entry("org.springframework.dao.OptimisticLockingFailureException",
					new ProblemProperties.Mapping(HttpStatus.CONFLICT.value(), "CONCURRENT_MODIFICATION", null,
							"The resource was modified concurrently. Retry the request.", null)),
			Map.entry("org.springframework.dao.EmptyResultDataAccessException",
					new ProblemProperties.Mapping(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", null,
							"Resource not found.", null)),
			Map.entry("org.springframework.dao.DataAccessException",
					new ProblemProperties.Mapping(HttpStatus.INTERNAL_SERVER_ERROR.value(), "DATA_ACCESS_ERROR", null,
							null, null)),
			Map.entry("org.apache.catalina.connector.ClientAbortException",
					new ProblemProperties.Mapping(null, "CLIENT_ABORTED", null, null, LogLevel.DEBUG)));

	private final ProblemProperties properties;

	public ProblemFactory(ProblemProperties properties) {
		this.properties = properties;
	}

	public ProblemProperties getProperties() {
		return this.properties;
	}

	/**
	 * Builds a problem for an exception no framework handler had already described.
	 */
	public ApiProblem create(Throwable ex, @Nullable HttpStatusCode fallbackStatus, WebRequest request) {
		return create(ex, null, fallbackStatus, method(request), path(request));
	}

	/**
	 * Builds a problem for an exception raised outside the dispatcher, such as in a security filter.
	 */
	public ApiProblem create(Throwable ex, @Nullable HttpStatusCode fallbackStatus, HttpServletRequest request) {
		return create(ex, null, fallbackStatus, request.getMethod(), request.getRequestURI());
	}

	/**
	 * Wraps the {@link ProblemDetail} Spring MVC already produced, adding the extension members. Returns the
	 * argument unchanged when it is already an {@link ApiProblem}, so a handler may enrich a problem and then
	 * let it flow through the funnel without it being processed, or logged, twice.
	 */
	public ApiProblem enrich(@Nullable Object body, Throwable ex, @Nullable HttpStatusCode fallbackStatus,
			WebRequest request) {
		if (body instanceof ApiProblem problem) {
			return problem;
		}
		ProblemDetail base = (body instanceof ProblemDetail detail) ? detail : null;
		return create(ex, base, fallbackStatus, method(request), path(request));
	}

	/**
	 * The one place a problem is built and logged.
	 *
	 * @param ex             the failure
	 * @param base           a problem already built by Spring MVC to be preserved, may be {@code null}
	 * @param fallbackStatus status to use when no other source resolves one, may be {@code null}
	 * @param method         the HTTP method, for the log line only
	 * @param path           the request path, exposed as the {@code instance} member
	 */
	public ApiProblem create(Throwable ex, @Nullable ProblemDetail base, @Nullable HttpStatusCode fallbackStatus,
			@Nullable String method, @Nullable String path) {

		ProblemProperties.Mapping mapping = mappingFor(ex);
		ResponseStatus responseStatus = AnnotatedElementUtils.findMergedAnnotation(ex.getClass(),
				ResponseStatus.class);
		HttpStatusCode status = resolveStatus(ex, base, mapping, responseStatus, fallbackStatus);
		String code = resolveCode(ex, mapping, status);

		ApiProblem problem = (base != null) ? new ApiProblem(base) : new ApiProblem(status.value());
		problem.setStatus(status.value());
		problem.setCode(code);
		problem.setDetail(resolveDetail(ex, base, mapping, responseStatus, status));
		if (mapping != null && StringUtils.hasText(mapping.title())) {
			problem.setTitle(mapping.title());
		}
		if (path != null) {
			problem.setInstance(URI.create(path));
		}
		URI type = type(code);
		if (type != null && (problem.getType() == null || BLANK_TYPE.equals(problem.getType()))) {
			problem.setType(type);
		}
		problem.setTraceId(traceId());
		if (ex instanceof ApiException api) {
			api.getExtensions().forEach(problem::setProperty);
		}

		log(ex, problem, mapping, method, path);
		return problem;
	}

	/**
	 * Converts one validation failure into an {@code errors} entry, honouring
	 * {@code problem.include-rejected-value}.
	 */
	public ApiProblem.Violation violation(String field, @Nullable String message, @Nullable Object rejectedValue) {
		return new ApiProblem.Violation(field, (message != null) ? message : "invalid",
				this.properties.isIncludeRejectedValue() ? rejectedValue : null);
	}

	/**
	 * Looks the exception up in the configured mappings, then in the built-in ones, walking the class hierarchy
	 * in each pass so that a mapping declared for a base exception covers its subclasses. The configured
	 * mappings are searched first in full, so an entry on a base class still wins over a built-in entry on one
	 * of its subclasses.
	 */
	@Nullable
	private ProblemProperties.Mapping mappingFor(Throwable ex) {
		ProblemProperties.Mapping configured = lookup(ex, this.properties.getMappings());
		return (configured != null) ? configured : lookup(ex, DEFAULT_MAPPINGS);
	}

	@Nullable
	private ProblemProperties.Mapping lookup(Throwable ex, Map<String, ProblemProperties.Mapping> mappings) {
		if (mappings.isEmpty()) {
			return null;
		}
		for (Class<?> type = ex.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			ProblemProperties.Mapping mapping = mappings.get(type.getName());
			if (mapping != null) {
				return mapping;
			}
		}
		return null;
	}

	private HttpStatusCode resolveStatus(Throwable ex, @Nullable ProblemDetail base,
			@Nullable ProblemProperties.Mapping mapping, @Nullable ResponseStatus responseStatus,
			@Nullable HttpStatusCode fallbackStatus) {

		if (mapping != null && mapping.status() != null) {
			return HttpStatusCode.valueOf(mapping.status());
		}
		if (ex instanceof ApiException api) {
			return api.getStatus();
		}
		if (responseStatus != null) {
			return responseStatus.code();
		}
		if (ex instanceof ErrorResponse errorResponse) {
			return errorResponse.getStatusCode();
		}
		if (base != null && base.getStatus() > 0) {
			return HttpStatusCode.valueOf(base.getStatus());
		}
		return (fallbackStatus != null) ? fallbackStatus : HttpStatus.INTERNAL_SERVER_ERROR;
	}

	/**
	 * The code is derived from the exception class name only when the failure was described on purpose, that is
	 * by a mapping, by {@link ApiException}, by {@code @ResponseStatus} or by the framework. An unexpected
	 * server error answers the configured {@code problem.server-error-code} instead, because the name of an
	 * internal class is itself an implementation detail the caller has no business seeing.
	 */
	private String resolveCode(Throwable ex, @Nullable ProblemProperties.Mapping mapping, HttpStatusCode status) {
		if (mapping != null && StringUtils.hasText(mapping.code())) {
			return mapping.code();
		}
		if (ex instanceof ApiException api && StringUtils.hasText(api.getCode())) {
			return api.getCode();
		}
		String simpleName = ex.getClass().getSimpleName();
		String framework = FRAMEWORK_CODES.get(simpleName);
		if (framework != null) {
			return framework;
		}
		boolean described = (mapping != null) || (ex instanceof ApiException)
				|| AnnotatedElementUtils.hasAnnotation(ex.getClass(), ResponseStatus.class);
		if (status.is5xxServerError() && !described) {
			return this.properties.getServerErrorCode();
		}
		return deriveCode(simpleName);
	}

	/**
	 * {@code OrderNotFoundException} becomes {@code ORDER_NOT_FOUND}, so an application gets a usable code
	 * without having to configure one per exception.
	 */
	private String deriveCode(String simpleName) {
		String name = simpleName;
		for (String suffix : List.of("Exception", "Error")) {
			if (name.length() > suffix.length() && name.endsWith(suffix)) {
				name = name.substring(0, name.length() - suffix.length());
				break;
			}
		}
		if (!StringUtils.hasText(name)) {
			return this.properties.getServerErrorCode();
		}
		return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
	}

	private String resolveDetail(Throwable ex, @Nullable ProblemDetail base,
			@Nullable ProblemProperties.Mapping mapping, @Nullable ResponseStatus responseStatus,
			HttpStatusCode status) {

		if (mapping != null && StringUtils.hasText(mapping.detail())) {
			return mapping.detail();
		}
		if (ex instanceof ApiException && StringUtils.hasText(ex.getMessage())) {
			return ex.getMessage();
		}
		if (responseStatus != null && StringUtils.hasText(responseStatus.reason())) {
			return responseStatus.reason();
		}
		if (status.is5xxServerError()) {
			return this.properties.getServerErrorDetail();
		}
		if (base != null && StringUtils.hasText(base.getDetail())) {
			return base.getDetail();
		}
		return StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : this.properties.getServerErrorDetail();
	}

	@Nullable
	private URI type(String code) {
		URI baseTypeUri = this.properties.getBaseTypeUri();
		if (baseTypeUri == null) {
			return null;
		}
		String prefix = baseTypeUri.toString();
		if (!prefix.endsWith("/")) {
			prefix = prefix + "/";
		}
		return URI.create(prefix + code.toLowerCase(Locale.ROOT).replace(UNDERSCORE, DASH));
	}

	private String traceId() {
		for (String key : this.properties.getTraceIdMdcKeys()) {
			String value = MDC.get(key);
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return UUID.randomUUID().toString();
	}

	/**
	 * Logs the failure once, with the context needed to find it again and nothing that could leak: no request
	 * body, no headers, no parameters. The stack trace is attached from {@code ERROR} up only.
	 */
	private void log(Throwable ex, ApiProblem problem, @Nullable ProblemProperties.Mapping mapping,
			@Nullable String method, @Nullable String path) {

		LogLevel level = (mapping != null && mapping.logLevel() != null) ? mapping.logLevel()
				: (problem.getStatus() >= 500 ? LogLevel.ERROR : LogLevel.WARN);
		String template = "API failure code={} status={} method={} path={} traceId={} exception={}: {}";
		Object[] args = { problem.getCode(), problem.getStatus(), method, path, problem.getTraceId(),
				ex.getClass().getName(), ex.getMessage() };

		switch (level) {
			case ERROR, FATAL -> log.error(template, withCause(args, ex));
			case WARN -> log.warn(template, args);
			case INFO -> log.info(template, args);
			case DEBUG -> log.debug(template, args);
			case TRACE -> log.trace(template, args);
			case OFF -> {
			}
		}
	}

	private Object[] withCause(Object[] args, Throwable ex) {
		Object[] withCause = new Object[args.length + 1];
		System.arraycopy(args, 0, withCause, 0, args.length);
		withCause[args.length] = ex;
		return withCause;
	}

	@Nullable
	private String method(WebRequest request) {
		return (request instanceof ServletWebRequest servletRequest) ? servletRequest.getRequest().getMethod() : null;
	}

	@Nullable
	private String path(WebRequest request) {
		return (request instanceof ServletWebRequest servletRequest)
				? servletRequest.getRequest().getRequestURI() : null;
	}
}
