package org.example.problem;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.validation.ConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring of the auto-configuration: what is registered, when it backs off and what it never does.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
class ProblemAutoConfigurationTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
					ProblemAutoConfiguration.class));

	@Test
	void registersTheHandlersOutOfTheBox() {
		this.runner.run((context) -> assertThat(context)
				.hasSingleBean(ProblemFactory.class)
				.hasSingleBean(GlobalProblemAdvice.class)
				.hasSingleBean(ConstraintViolationProblemAdvice.class)
				.hasSingleBean(ProblemSecurityHandler.class));
	}

	@Test
	void backsOffWhenDisabled() {
		this.runner.withPropertyValues("problem.enabled=false")
				.run((context) -> assertThat(context).doesNotHaveBean(GlobalProblemAdvice.class));
	}

	@Test
	void backsOffOutsideAServletWebApplication() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(ProblemAutoConfiguration.class))
				.run((context) -> assertThat(context).doesNotHaveBean(GlobalProblemAdvice.class));
	}

	@Test
	void backsOffFromValidationWhenBeanValidationIsAbsent() {
		this.runner.withClassLoader(new FilteredClassLoader(ConstraintViolationException.class))
				.run((context) -> assertThat(context)
						.hasSingleBean(GlobalProblemAdvice.class)
						.doesNotHaveBean(ConstraintViolationProblemAdvice.class));
	}

	@Test
	void backsOffFromSecurityWhenSpringSecurityIsAbsent() {
		this.runner.withClassLoader(new FilteredClassLoader("org.springframework.security.web"))
				.run((context) -> assertThat(context)
						.hasSingleBean(GlobalProblemAdvice.class)
						.doesNotHaveBean(ProblemSecurityHandler.class));
	}

	@Test
	void yieldsToAFactoryDeclaredByTheApplication() {
		this.runner.withUserConfiguration(CustomFactoryConfiguration.class)
				.run((context) -> assertThat(context).getBean(ProblemFactory.class)
						.isSameAs(context.getBean(CustomFactoryConfiguration.class).factory));
	}

	@Test
	void neverForcesItselfOverAnAccessDeniedHandlerDeclaredByTheApplication() {
		this.runner.withUserConfiguration(CustomAccessDeniedHandlerConfiguration.class)
				.run((context) -> assertThat(context)
						.hasSingleBean(ProblemSecurityHandler.class)
						.getBeans(AccessDeniedHandler.class).hasSize(2));
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomFactoryConfiguration {

		private final ProblemFactory factory = new ProblemFactory(new ProblemProperties());

		@Bean
		ProblemFactory problemFactory() {
			return this.factory;
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomAccessDeniedHandlerConfiguration {

		@Bean
		AccessDeniedHandler customAccessDeniedHandler() {
			return (request, response, ex) -> response.setStatus(403);
		}
	}
}
