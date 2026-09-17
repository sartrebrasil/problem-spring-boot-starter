package org.example.problem;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolationException;

/**
 * Registers the global exception handling. Nothing has to be declared by the application: adding the
 * dependency is enough.
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so any part can be replaced by declaring a bean of the
 * same type, and the whole thing can be turned off with {@code problem.enabled=false}.</p>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass({ RestControllerAdvice.class, ProblemDetail.class })
@ConditionalOnProperty(prefix = "problem", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ProblemProperties.class)
public class ProblemAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public ProblemFactory problemFactory(ProblemProperties properties) {
		return new ProblemFactory(properties);
	}

	@Bean
	@ConditionalOnMissingBean
	public GlobalProblemAdvice globalProblemAdvice(ProblemFactory problemFactory) {
		return new GlobalProblemAdvice(problemFactory);
	}

	/**
	 * Field level errors for Bean Validation failures raised outside argument binding.
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass(ConstraintViolationException.class)
	static class ValidationConfiguration {

		@Bean
		@ConditionalOnMissingBean
		ConstraintViolationProblemAdvice constraintViolationProblemAdvice(ProblemFactory problemFactory) {
			return new ConstraintViolationProblemAdvice(problemFactory);
		}
	}

	/**
	 * Problem responses for the 401 and 403 raised in the security filter chain. The bean still has to be
	 * wired into the application {@code SecurityFilterChain}, see {@link ProblemSecurityHandler}.
	 *
	 * <p>The bean is deliberately not {@code @Primary}: which handler answers a denial is the application's
	 * call, never the starter's. An application that declares its own {@code AccessDeniedHandler} keeps both
	 * beans in the context and picks one, either by injecting a concrete type into the chain, or by declaring
	 * its own {@code @Primary} {@code AccessDeniedHandler} and {@code AuthenticationEntryPoint} beans that
	 * return this handler. Both are shown in the README.</p>
	 */
	@Configuration(proxyBeanMethods = false)
	@ConditionalOnClass({ AccessDeniedHandler.class, ObjectMapper.class })
	static class SecurityConfiguration {

		@Bean
		@ConditionalOnMissingBean
		ProblemSecurityHandler problemSecurityHandler(ProblemFactory problemFactory, ObjectMapper objectMapper) {
			return new ProblemSecurityHandler(problemFactory, objectMapper);
		}
	}
}
