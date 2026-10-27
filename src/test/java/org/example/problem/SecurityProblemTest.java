package org.example.problem;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behaviour of the denials raised inside the dispatch, by a controller doing its own authorization or by a
 * {@code @PreAuthorize} on a service it calls.
 *
 * <p>These reach the advice, unlike the ones raised in a filter, and the advice rethrows them so that
 * {@code ExceptionTranslationFilter} keeps making the decision only it can make: 403 for an authenticated
 * caller, 401 for an anonymous one. Both come back in the {@link ApiProblem} shape through
 * {@link ProblemSecurityHandler}.</p>
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@SpringBootTest(classes = SecurityProblemTest.TestConfiguration.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "problem.base-type-uri=https://errors.example.org/")
class SecurityProblemTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void controllerDenialForAnAuthenticatedCallerIsAForbiddenProblem() throws Exception {
		this.mockMvc.perform(get("/denied").with(user("ana")))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
				.andExpect(jsonPath("$.detail").value("Access is denied."))
				.andExpect(jsonPath("$.instance").value("/denied"))
				.andExpect(jsonPath("$.traceId").exists());
	}

	@Test
	void theSameDenialForAnAnonymousCallerIsAnUnauthorizedProblem() throws Exception {
		this.mockMvc.perform(get("/denied"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.traceId").exists());
	}

	@Test
	void methodSecurityDenialOnAServiceIsHandledTheSameWay() throws Exception {
		this.mockMvc.perform(get("/admin").with(user("ana").roles("USER")))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void badCredentialsThrownByTheControllerItselfIsAGenericUnauthorizedProblem() throws Exception {
		this.mockMvc.perform(get("/login"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(jsonPath("$.detail").value("Authentication is required."))
				.andExpect(content().string(not(containsString("ana"))));
	}

	@Test
	void anUnreachableIdentityProviderIsAServerErrorAndNotA401() throws Exception {
		this.mockMvc.perform(get("/login/broken"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"))
				.andExpect(jsonPath("$.detail").value("Unexpected error. Please try again later."))
				.andExpect(content().string(not(containsString("ldap"))));
	}

	@Test
	void aBusinessFailureInTheSameApplicationStillGoesThroughTheAdvice() throws Exception {
		this.mockMvc.perform(get("/orders/9").with(user("ana")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"))
				.andExpect(jsonPath("$.orderId").value("9"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EnableMethodSecurity
	static class TestConfiguration {

		@Bean
		SecurityFilterChain filterChain(HttpSecurity http, ProblemSecurityHandler problems) throws Exception {
			return http.csrf((csrf) -> csrf.disable())
					.authorizeHttpRequests((requests) -> requests.anyRequest().permitAll())
					.exceptionHandling((handling) -> handling
							.authenticationEntryPoint(problems)
							.accessDeniedHandler(problems))
					.build();
		}

		@Service
		static class AdminService {

			@PreAuthorize("hasRole('ADMIN')")
			String secret() {
				return "secret";
			}
		}

		@RestController
		static class SecuredController {

			private final AdminService adminService;

			SecuredController(AdminService adminService) {
				this.adminService = adminService;
			}

			@GetMapping("/denied")
			String denied() {
				throw new AccessDeniedException("not the owner of the resource");
			}

			@GetMapping("/admin")
			String admin() {
				return this.adminService.secret();
			}

			@GetMapping("/login")
			String login() {
				throw new BadCredentialsException("no such user: ana");
			}

			@GetMapping("/login/broken")
			String loginBroken() {
				throw new InternalAuthenticationServiceException("ldap://identity.internal is unreachable");
			}

			@GetMapping("/orders/{id}")
			String order() {
				throw ApiException.notFound("ORDER_NOT_FOUND", "Order 9 was not found").with("orderId", "9");
			}
		}
	}
}
