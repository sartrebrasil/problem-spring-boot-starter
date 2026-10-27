package org.example.problem;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Application under test. Security auto-configuration is excluded because the starter is on the test
 * classpath as an optional dependency and would otherwise answer 401 to every request here; the security
 * beans are asserted through the context runner instead.
 *
 * @author Sartre Brasil
 * @since 1.0
 */
@SpringBootApplication(exclude = { SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class,
		ServletWebSecurityAutoConfiguration.class })
public class TestApplication {

	/** New-project style exception: extends the base class, code derived from the class name. */
	static class OrderNotFoundException extends ApiException {

		OrderNotFoundException(String orderId) {
			super(HttpStatus.NOT_FOUND, "Order " + orderId + " was not found");
			with("orderId", orderId);
		}
	}

	/** Legacy exception already annotated by the project. */
	@ResponseStatus(code = HttpStatus.CONFLICT, reason = "Order already submitted")
	static class LegacyAnnotatedException extends RuntimeException {
	}

	/** Legacy exception with no annotation, mapped through configuration. */
	static class LegacyMappedException extends RuntimeException {

		LegacyMappedException() {
			super("balance 10 is lower than the amount 50");
		}
	}

	record CreateUserRequest(@NotBlank(message = "Name is required") String name,
			@Email(message = "Invalid email format") String email,
			@Min(value = 18, message = "Age must be 18+") Integer age) {
	}

	@Service
	@Validated
	static class AgeService {

		void check(@Min(value = 18, message = "Age must be 18+") int age) {
		}
	}

	@RestController
	static class TestController {

		private final AgeService ageService;

		TestController(AgeService ageService) {
			this.ageService = ageService;
		}

		@GetMapping("/orders/{id}")
		String order(@PathVariable String id) {
			throw new OrderNotFoundException(id);
		}

		@GetMapping("/legacy/annotated")
		String legacyAnnotated() {
			throw new LegacyAnnotatedException();
		}

		@GetMapping("/legacy/mapped")
		String legacyMapped() {
			throw new LegacyMappedException();
		}

		@GetMapping("/boom")
		String boom() {
			throw new IllegalStateException("jdbc://user:secret@db/internal detail");
		}

		@GetMapping("/search")
		String search(@RequestParam int size) {
			return "size=" + size;
		}

		@GetMapping("/age")
		String age(@RequestParam int value) {
			this.ageService.check(value);
			return "ok";
		}

		@PostMapping("/users")
		String createUser(@Valid @RequestBody CreateUserRequest request) {
			return "created " + request.name();
		}
	}
}
