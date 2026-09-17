package org.example.problem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.Nullable;

/**
 * Optional base exception for new projects: it carries the HTTP status, the application error code and any
 * extension members the response should expose, so no handler has to be written per exception.
 *
 * <p>Adopting it is not required. Projects that already have their own hierarchy keep it and declare the
 * mapping under {@code problem.mappings.*} or rely on an existing {@code @ResponseStatus}; the same
 * {@link ApiProblem} response comes out either way.</p>
 *
 * <p>Two usage styles are supported. Either throw it directly through the factory methods:</p>
 * <pre>{@code throw ApiException.notFound("ORDER_NOT_FOUND", "Order 123 was not found").with("orderId", "123");}</pre>
 * <p>or subclass it and leave the code to be derived from the class name
 * ({@code OrderNotFoundException} becomes {@code ORDER_NOT_FOUND}):</p>
 * <pre>{@code
 * public class OrderNotFoundException extends ApiException {
 *     public OrderNotFoundException(String id) {
 *         super(HttpStatus.NOT_FOUND, "Order " + id + " was not found");
 *         with("orderId", id);
 *     }
 * }
 * }</pre>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final HttpStatusCode status;

	private final String code;

	private final Map<String, Object> extensions = new LinkedHashMap<>();

	/**
	 * @param status the HTTP status the response must carry
	 * @param code   the application error code, or {@code null} to derive it from the exception class name
	 * @param detail the message meant for the caller; it is exposed in the response for 4xx statuses
	 */
	public ApiException(HttpStatusCode status, @Nullable String code, String detail) {
		this(status, code, detail, null);
	}

	public ApiException(HttpStatusCode status, String detail) {
		this(status, null, detail, null);
	}

	public ApiException(HttpStatusCode status, @Nullable String code, String detail, @Nullable Throwable cause) {
		super(detail, cause);
		this.status = status;
		this.code = code;
	}

	public HttpStatusCode getStatus() {
		return this.status;
	}

	/**
	 * @return the explicit error code, or {@code null} when it should be derived from the class name
	 */
	@Nullable
	public String getCode() {
		return this.code;
	}

	/**
	 * Adds an extension member to the problem response, such as the identifier that was not found.
	 * Never put credentials or PII here: the value is serialized to the client.
	 */
	public ApiException with(String name, Object value) {
		this.extensions.put(name, value);
		return this;
	}

	public Map<String, Object> getExtensions() {
		return Collections.unmodifiableMap(this.extensions);
	}

	public static ApiException badRequest(String code, String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
	}

	public static ApiException unauthorized(String code, String detail) {
		return new ApiException(HttpStatus.UNAUTHORIZED, code, detail);
	}

	public static ApiException forbidden(String code, String detail) {
		return new ApiException(HttpStatus.FORBIDDEN, code, detail);
	}

	public static ApiException notFound(String code, String detail) {
		return new ApiException(HttpStatus.NOT_FOUND, code, detail);
	}

	public static ApiException conflict(String code, String detail) {
		return new ApiException(HttpStatus.CONFLICT, code, detail);
	}

	public static ApiException unprocessable(String code, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, detail);
	}

	public static ApiException badGateway(String code, String detail) {
		return new ApiException(HttpStatus.BAD_GATEWAY, code, detail);
	}
}
