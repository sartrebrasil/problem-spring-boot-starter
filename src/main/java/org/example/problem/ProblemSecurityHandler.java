package org.example.problem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Produces the same {@link ApiProblem} body for the 401 and 403 raised inside the security filter chain.
 *
 * <p>This is not redundant with the advice. {@code ExceptionTranslationFilter} intercepts an
 * {@link AuthenticationException} or {@link AccessDeniedException} thrown in a filter before the dispatcher
 * is ever reached, so no {@code @ControllerAdvice} sees it and the response would otherwise be Spring
 * Security's own, in a different shape than every other error of the API. Denials raised inside the dispatch,
 * such as a {@code @PreAuthorize} on a service called from a controller, do reach the advice and are handled
 * there.</p>
 *
 * <p>Spring Security cannot be told about this bean from the outside, the application has to wire it into its
 * own chain:</p>
 * <pre>{@code
 * @Bean
 * SecurityFilterChain filterChain(HttpSecurity http, ProblemSecurityHandler problems) throws Exception {
 *     return http.exceptionHandling((handling) -> handling
 *             .authenticationEntryPoint(problems)
 *             .accessDeniedHandler(problems))
 *         // ...
 *         .build();
 * }
 * }</pre>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
public class ProblemSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final ProblemFactory factory;

	private final ObjectMapper objectMapper;

	public ProblemSecurityHandler(ProblemFactory factory, ObjectMapper objectMapper) {
		this.factory = factory;
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException ex) throws IOException {

		write(request, response, ex, HttpStatus.UNAUTHORIZED);
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException ex) throws IOException {

		write(request, response, ex, HttpStatus.FORBIDDEN);
	}

	private void write(HttpServletRequest request, HttpServletResponse response, RuntimeException ex,
			HttpStatus fallbackStatus) throws IOException {

		ApiProblem problem = this.factory.create(ex, fallbackStatus, request);
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(problem.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		this.objectMapper.writeValue(response.getOutputStream(), problem);
	}
}
