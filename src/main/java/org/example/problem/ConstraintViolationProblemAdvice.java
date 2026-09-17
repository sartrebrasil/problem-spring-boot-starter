package org.example.problem;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import jakarta.validation.ConstraintViolationException;

/**
 * Adds the field level {@code errors} to a {@link ConstraintViolationException}, the failure raised when Bean
 * Validation runs outside argument binding, typically on a {@code @Validated} service method.
 *
 * <p>Registered only when Bean Validation is on the classpath, and separately from
 * {@link GlobalProblemAdvice} for that reason. Its status and code come from the built-in mapping in
 * {@link ProblemFactory}, so the response stays the same shape even when this advice is absent, only without
 * the field breakdown.</p>
 *
 * <p>Its precedence is one above {@link GlobalProblemAdvice}, otherwise the {@code Throwable} handler there
 * could claim the exception first, since both advices would sort at
 * {@link Ordered#LOWEST_PRECEDENCE}. An application that wants to handle this exception itself has to declare
 * an explicit {@code @Order} on its own advice.</p>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class ConstraintViolationProblemAdvice {

	private final ProblemFactory factory;

	public ConstraintViolationProblemAdvice(ProblemFactory factory) {
		this.factory = factory;
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
		ApiProblem problem = this.factory.create(ex, HttpStatus.BAD_REQUEST, request);
		List<ApiProblem.Violation> violations = new ArrayList<>();
		if (ex.getConstraintViolations() != null) {
			ex.getConstraintViolations().forEach((violation) -> violations.add(this.factory
					.violation(field(violation.getPropertyPath().toString()), violation.getMessage(),
							violation.getInvalidValue())));
		}
		problem.setErrors(violations);
		return ResponseEntity.status(problem.getStatus()).body(problem);
	}

	/**
	 * Keeps the last segment of the property path, so {@code createUser.request.email} is reported as
	 * {@code email}.
	 */
	private String field(String propertyPath) {
		int lastDot = propertyPath.lastIndexOf('.');
		return (lastDot >= 0 && lastDot < propertyPath.length() - 1) ? propertyPath.substring(lastDot + 1)
				: propertyPath;
	}
}
