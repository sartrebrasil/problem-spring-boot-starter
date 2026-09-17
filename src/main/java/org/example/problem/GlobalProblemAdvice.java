package org.example.problem;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Global exception handler producing an {@link ApiProblem} for every failure that reaches the dispatcher.
 *
 * <p>It extends {@link ResponseEntityExceptionHandler}, which already maps the twenty-odd Spring MVC
 * exceptions to a {@link ProblemDetail} and the right status. Rather than rewriting one handler per
 * exception, this class overrides the single funnel every one of them goes through,
 * {@link #handleExceptionInternal}, and enriches whatever problem came out of it. Only validation needs
 * more than that, to add the field level {@code errors}.</p>
 *
 * <p><b>Ordering.</b> This advice is registered at {@link Ordered#LOWEST_PRECEDENCE} on purpose. Spring picks
 * the first advice, in order, that declares a handler for the thrown type, so a library advice with a
 * {@code Throwable} handler at high precedence would swallow every exception and no application advice would
 * ever run. As the last one, it is the safety net and the application overrides only what it wants to.</p>
 *
 * <p>An application advice with no {@code @Order} defaults to {@code LOWEST_PRECEDENCE} as well. Ordering
 * then falls back to bean discovery order, where auto-configured beans come last, so the application advice
 * still wins in practice. To make it explicit rather than incidental, give the application advice any
 * {@code @Order} below {@code Integer.MAX_VALUE}.</p>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalProblemAdvice extends ResponseEntityExceptionHandler {

	/**
	 * The exceptions {@code ExceptionTranslationFilter} owns, by name so that Spring Security does not have to
	 * be on the classpath.
	 */
	private static final Set<String> SECURITY_TRANSLATED_EXCEPTIONS = Set.of(
			"org.springframework.security.core.AuthenticationException",
			"org.springframework.security.access.AccessDeniedException");

	private final ProblemFactory factory;

	public GlobalProblemAdvice(ProblemFactory factory) {
		this.factory = factory;
	}

	protected ProblemFactory getFactory() {
		return this.factory;
	}

	/**
	 * Last resort handler. It covers {@link ApiException}, any exception mapped through
	 * {@code problem.mappings} or annotated with {@code @ResponseStatus}, and everything unexpected, which
	 * answers 500 with a generic detail.
	 *
	 * <p>The Spring Security denials are the exception: they are rethrown, see
	 * {@link #isTranslatedBySecurityFilter}.</p>
	 */
	@ExceptionHandler(Throwable.class)
	public ResponseEntity<Object> handleAny(Throwable ex, WebRequest request) throws Throwable {
		if (isTranslatedBySecurityFilter(ex)) {
			throw ex;
		}
		ApiProblem problem = this.factory.create(ex, HttpStatus.INTERNAL_SERVER_ERROR, request);
		return ResponseEntity.status(problem.getStatus()).body(problem);
	}

	/**
	 * Whether the exception is one {@code ExceptionTranslationFilter} has to decide on, that is an
	 * {@code AuthenticationException} or an {@code AccessDeniedException}.
	 *
	 * <p>Such a denial raised inside the dispatch, by a {@code @PreAuthorize} on a service or by a controller
	 * doing its own authorization, does reach this advice, and handling it here would be wrong. The filter
	 * consults the {@code AuthenticationTrustResolver} before answering: an {@code AccessDeniedException} is a
	 * 403 for an authenticated caller but a 401 through the {@code AuthenticationEntryPoint} for an anonymous
	 * one, which is the only place that distinction is made. Answering 403 from here would tell a caller with
	 * no credentials that its credentials are insufficient.</p>
	 *
	 * <p>Rethrowing the original exception from an {@code @ExceptionHandler} makes
	 * {@code ExceptionHandlerExceptionResolver} treat it as unresolved and the dispatcher propagates it back
	 * to the filter chain, where {@link ProblemSecurityHandler} produces the very same body, as long as it is
	 * wired into the {@code SecurityFilterChain} of the application.</p>
	 *
	 * <p>The check is by class name, walking the hierarchy, so that this class carries no reference to Spring
	 * Security and works unchanged when it is not on the classpath.</p>
	 */
	protected boolean isTranslatedBySecurityFilter(Throwable ex) {
		for (Class<?> type = ex.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			if (SECURITY_TRANSLATED_EXCEPTIONS.contains(type.getName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Handles binding failures of {@code @ModelAttribute} arguments, which the parent class does not cover.
	 */
	@ExceptionHandler(BindException.class)
	public ResponseEntity<Object> handleBind(BindException ex, WebRequest request) {
		ApiProblem problem = this.factory.create(ex, HttpStatus.BAD_REQUEST, request);
		problem.setErrors(violations(ex));
		return ResponseEntity.status(problem.getStatus()).body(problem);
	}

	// 1) Bean Validation em @RequestBody @Valid ---------------------------------
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		ApiProblem problem = this.factory.enrich(localizedBody(ex), ex, status, request);
		problem.setErrors(violations(ex));
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	// 2) Bean Validation em @RequestParam/@PathVariable (Spring 6.1+ / Boot 3.2+)
	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(
			HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		ApiProblem problem = this.factory.enrich(localizedBody(ex), ex, status, request);
		problem.setErrors(violations(ex));
		return handleExceptionInternal(ex, problem, headers, status, request);
	}

	// 3) JSON malformado / enum inválido dentro do corpo -------------------------
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(
			HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		Throwable cause = ex.getCause();
		if (cause instanceof InvalidFormatException invalidFormatException && !invalidFormatException.getPath().isEmpty()) {
			ApiProblem problem = this.factory.create(ex, status, request);
			problem.setErrors(violations(invalidFormatException));
			return handleExceptionInternal(ex, problem, headers, status, request);
		}

		return super.handleHttpMessageNotReadable(ex, headers, status, request);
	}

	// 4) Enum/tipo inválido em @RequestParam ou @PathVariable --------------------
	@Override
	protected ResponseEntity<Object> handleTypeMismatch(
			TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

		ApiProblem problem = this.factory.create(ex, status, request);
		problem.setErrors(violations(ex));
		return handleExceptionInternal(ex, problem, headers, status, request);
	}


	/**
	 * The funnel every handler of the parent class ends in, and therefore the single place where the standard
	 * extension members are added to a problem Spring MVC had built.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
			HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

		Object resolved = body;
		if (resolved == null && ex instanceof ErrorResponse errorResponse) {
			resolved = localizedBody(errorResponse);
		}
		ApiProblem problem = this.factory.enrich(resolved, ex, statusCode, request);
		return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
	}

	/**
	 * Resolves the problem detail of an {@link ErrorResponse} against the {@code MessageSource}, so an
	 * application that localizes the framework messages keeps that behaviour.
	 */
	private ProblemDetail localizedBody(ErrorResponse errorResponse) {
		return errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
	}

	private List<ApiProblem.Violation> violations(TypeMismatchException ex) {

		String field = (ex instanceof MethodArgumentTypeMismatchException mismatchException)
				? mismatchException.getName()
				: String.valueOf(ex.getPropertyName());
		Object value = ex.getValue() == null ? "" : ex.getValue();
		String message = buildInvalidValueMessage(field, ex.getValue(), ex.getRequiredType());
		return List.of(this.factory.violation(field, message, value));
	}

	private List<ApiProblem.Violation> violations(InvalidFormatException invalidFormatException) {
		String field = invalidFormatException.getPath().getLast().getFieldName();
		Object value = invalidFormatException.getValue();
		String message = buildInvalidValueMessage(field, value, invalidFormatException.getTargetType());
		return List.of(this.factory.violation(field, message, value));
	}

	private String buildInvalidValueMessage(String field, Object rejectedValue, Class<?> targetType) {
		if (targetType != null && targetType.isEnum()) {
			String accepted = Arrays.stream(targetType.getEnumConstants())
					.map(Object::toString)
					.collect(Collectors.joining(", "));
			return String.format(
					"Valor '%s' inválido para o campo '%s'. Valores aceitos: [%s]",
					rejectedValue, field, accepted
			);
		}
		return String.format("Valor '%s' inválido para o campo '%s'", rejectedValue, field);
	}

//	private List<ApiProblem.Violation> violations(MethodArgumentNotValidException ex) {
//		return violations(ex.getBindingResult());
//	}

	private List<ApiProblem.Violation> violations(BindException ex) {
		return violations(ex.getBindingResult());
	}

	private List<ApiProblem.Violation> violations(BindingResult result) {
		List<ApiProblem.Violation> violations = new ArrayList<>();
		result.getFieldErrors().forEach((error) -> violations
				.add(this.factory.violation(error.getField(), error.getDefaultMessage(), error.getRejectedValue())));
		result.getGlobalErrors().forEach((error) -> violations
				.add(this.factory.violation(error.getObjectName(), error.getDefaultMessage(), null)));
		return violations;
	}

	private List<ApiProblem.Violation> violations(HandlerMethodValidationException ex) {
		return ex.getParameterValidationResults().stream()
				.flatMap(result ->
						result.getResolvableErrors().stream()
								.map(error ->
										violation(result, error)))
				.toList();
	}

	private ApiProblem.Violation violation(ParameterValidationResult result, MessageSourceResolvable error) {
        String name = result.getMethodParameter().getParameterName();
        String message = error.getDefaultMessage();

        String field = name;
        if (error instanceof FieldError fieldError) {
            // violação em cascata dentro do objeto (@Valid) -> usa o campo real do record/DTO
            field = name != null
                    ? name + "." + fieldError.getField()
                    : fieldError.getField();
        }

		return this.factory.violation(field, message, result.getArgument());
    }

//	private ApiProblem.Violation violation(ParameterValidationResult result, MessageSourceResolvable error) {
//		String name = result.getMethodParameter().getParameterName();
//		String message = error.getDefaultMessage();
//
//		String field = name;
//		if (error instanceof FieldError fieldError) {
//			// violação em cascata dentro do objeto (@Valid) -> usa o campo real do record/DTO
//			field = name != null
//					? name + "." + fieldError.getField()
//					: fieldError.getField();
//		}
//
//		return this.factory.violation(field, message, result.getArgument());
//	}
}
