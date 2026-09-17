# problem-spring-boot-starter

Auto-configured global exception handling for Spring Boot 3 REST APIs. Every failure leaves the application
as one `application/problem+json` body (RFC 9457) and one log line, with no `try`/`catch` in a controller and
no `@ExceptionHandler` written per project.

## Install

```xml
<dependency>
    <groupId>org.example</groupId>
    <artifactId>problem-spring-boot-starter</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

That is all. There is nothing to enable, import or extend.

## What a response looks like

```http
GET /orders/123
```

```json
{
  "type": "https://errors.myapp.com/order-not-found",
  "title": "Not Found",
  "status": 404,
  "detail": "Order 123 was not found",
  "instance": "/orders/123",
  "code": "ORDER_NOT_FOUND",
  "timestamp": "2026-09-16T10:00:00Z",
  "traceId": "b7c1e5a3d8f04e2a",
  "orderId": "123"
}
```

The inherited members keep their RFC meaning; `code`, `timestamp`, `traceId` and `errors` are the extensions
this library adds. A validation failure fills in `errors`:

```json
{
  "status": 400,
  "code": "VALIDATION_FAILED",
  "detail": "Invalid request content.",
  "instance": "/users",
  "errors": [
    { "field": "name", "message": "Name is required" },
    { "field": "email", "message": "Invalid email format" },
    { "field": "age", "message": "Age must be 18+" }
  ]
}
```

## Adopting it in a new project

Throw `ApiException`, either directly or through a subclass. The code is derived from the class name when it
is not given, so `OrderNotFoundException` reports `ORDER_NOT_FOUND`.

```java
throw ApiException.notFound("ORDER_NOT_FOUND", "Order " + id + " was not found").with("orderId", id);
```

```java
public class OrderNotFoundException extends ApiException {

    public OrderNotFoundException(String orderId) {
        super(HttpStatus.NOT_FOUND, "Order " + orderId + " was not found");
        with("orderId", orderId);
    }
}
```

`badRequest`, `unauthorized`, `forbidden`, `notFound`, `conflict`, `unprocessable` and `badGateway` are
available as factory methods.

## Adopting it in a project that already has its own exceptions

Nothing has to be rewritten, and the existing hierarchy stays as it is. Three routes, in the order they are
resolved:

**1. Declare the mapping in configuration.** Works for any exception, including one from a third-party jar.
The key is the fully qualified class name and has to be bracketed in YAML because it contains dots. A mapping
on a base exception covers its subclasses.

```yaml
problem:
  base-type-uri: https://errors.myapp.com/
  mappings:
    "[com.legacy.OrderNotFoundException]":
      status: 404
      code: ORDER_NOT_FOUND
    "[com.legacy.InsufficientBalanceException]":
      status: 422
      code: INSUFFICIENT_BALANCE
      detail: The account balance is lower than the requested amount.
    "[com.legacy.LegacyBaseException]":
      status: 400
      log-level: INFO
```

**2. Keep the `@ResponseStatus` already on the exception.** It is read as is, `reason` included, and only the
body shape changes.

```java
@ResponseStatus(code = HttpStatus.CONFLICT, reason = "Order already submitted")
public class OrderAlreadySubmittedException extends RuntimeException {
}
```

**3. Let the code be derived.** An unmapped, unannotated 4xx still gets a usable code from its class name.

## What is handled out of the box

| Failure | Status | Code |
|---|---|---|
| `ApiException` and subclasses | as declared | as declared or derived |
| `@ResponseStatus` exceptions | as annotated | derived |
| `problem.mappings` entries | as configured | as configured |
| `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `BindException` | 400 | `VALIDATION_FAILED` + `errors` |
| `ConstraintViolationException` (Bean Validation) | 400 | `VALIDATION_FAILED` + `errors` |
| `HttpMessageNotReadableException` | 400 | `MALFORMED_REQUEST_BODY` |
| `MissingServletRequestParameterException` | 400 | `MISSING_PARAMETER` |
| `MethodArgumentTypeMismatchException`, `TypeMismatchException` | 400 | `INVALID_PARAMETER_TYPE` |
| `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` |
| `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| `NoResourceFoundException`, `NoHandlerFoundException` | 404 | `RESOURCE_NOT_FOUND` |
| `MaxUploadSizeExceededException` | 413 | `PAYLOAD_TOO_LARGE` |
| `AuthenticationException` and subclasses (`BadCredentialsException`, `DisabledException`, `LockedException`, `UsernameNotFoundException`, …) | 401 | `UNAUTHENTICATED` |
| `AuthenticationServiceException`, `InternalAuthenticationServiceException`, `ProviderNotFoundException` | 503 | `AUTHENTICATION_UNAVAILABLE` |
| `AccessDeniedException` and subclasses (`AuthorizationDeniedException`, `MissingCsrfTokenException`, …) | 403 | `ACCESS_DENIED` |
| `DataIntegrityViolationException` (Spring Data) | 409 | `DATA_CONFLICT` |
| `OptimisticLockingFailureException` | 409 | `CONCURRENT_MODIFICATION` |
| `EmptyResultDataAccessException` | 404 | `RESOURCE_NOT_FOUND` |
| `DataAccessException` | 500 | `DATA_ACCESS_ERROR` |
| anything else | 500 | `INTERNAL_ERROR` |

The Spring Security and Spring Data entries are built-in configuration mappings resolved by class name, not
extra advices, so nothing is loaded when those libraries are absent and nothing competes with the advice of
the application for ordering.

## Spring Security needs one line of wiring

Every Spring Security denial ends up in the same place, whether it was raised in a filter or inside the
dispatch, and that place is `ExceptionTranslationFilter`, not the advice.

A denial raised in a filter never reaches a `@ControllerAdvice` to begin with: the filter sits outside the
dispatcher. A denial raised *inside* the dispatch, by a `@PreAuthorize` on a service or by a controller doing
its own authorization, does reach the advice, and the advice deliberately rethrows it. Handling it there would
answer 403 to a caller that has no credentials at all, because the trust resolver decision lives in the
filter: an `AccessDeniedException` is a 403 for an authenticated caller and a 401 through the
`AuthenticationEntryPoint` for an anonymous one.

So both paths converge on `ProblemSecurityHandler`, which is auto-configured, but Spring Security cannot be
told about it from the outside:

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http, ProblemSecurityHandler problems) throws Exception {
    return http.exceptionHandling((handling) -> handling
            .authenticationEntryPoint(problems)
            .accessDeniedHandler(problems))
        // ...
        .build();
}
```

This wiring is required, not optional, whenever Spring Security is on the classpath: without it every 401 and
403 falls back to Spring Security's own response shape, including the ones thrown by your controllers.

`ProblemSecurityHandler` is not `@Primary`, and the starter never installs itself into a chain. That is
deliberate: which handler answers a denial is the application's decision, and a starter that forces it takes
that decision away.

An application that declares its own `AccessDeniedHandler` therefore ends up with both beans in the context,
because the condition on `problemSecurityHandler` matches on that type and not on the interface. Injecting
`AccessDeniedHandler` is then ambiguous. Two ways out, both in the application:

Inject the concrete type in the chain that should use it:

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http, ProblemSecurityHandler problems) throws Exception {
    return http.exceptionHandling((handling) -> handling
            .authenticationEntryPoint(problems)
            .accessDeniedHandler(problems))
        // ...
        .build();
}
```

Or, to make every injection by interface resolve here, promote the handler in the application:

```java
@Configuration(proxyBeanMethods = false)
public class ProblemSecurityConfig {

    @Bean
    public ProblemSecurityHandler problemSecurityHandler(final ProblemFactory problemFactory,
                                                       final ObjectMapper objectMapper) {

        return new ProblemSecurityHandler(problemFactory, objectMapper);
    }
}
```

To drop this handler altogether, declare a `ProblemSecurityHandler` bean of your own: the condition backs off
and nothing else changes.

```java
// reaches the advice, is rethrown, and comes back as a problem through the filter
@GetMapping("/orders/{id}")
Order get(@PathVariable String id, @AuthenticationPrincipal User caller) {
    Order order = this.orders.find(id);
    if (!order.isOwnedBy(caller)) {
        throw new AccessDeniedException("not the owner of the order");
    }
    return order;
}
```

| Caller | Response |
|---|---|
| authenticated | 403, `code: ACCESS_DENIED` |
| anonymous | 401, `code: UNAUTHENTICATED` |

To answer a denial straight from the advice instead, without the trust resolver step, throw
`ApiException.forbidden(...)` rather than an `AccessDeniedException`. Only the Spring Security types are
rethrown.

### Every authentication failure is one answer

Mappings are resolved up the class hierarchy, so the whole
`org.springframework.security.authentication` package is covered by the `AuthenticationException` entry:
`BadCredentialsException`, `UsernameNotFoundException`, `DisabledException`, `LockedException`,
`AccountExpiredException`, `CredentialsExpiredException`, `InsufficientAuthenticationException` and
`AuthenticationCredentialsNotFoundException` all answer the same 401 with the same generic detail. That is
deliberate: telling a bad password apart from an unknown user, or from a locked account, is user enumeration.
The distinction stays in the log.

`AuthenticationServiceException` is the exception to that rule. It inherits from `AuthenticationException` but
it means the identity provider is unreachable or misconfigured, not that the caller got its password wrong, so
it is mapped one level lower in the hierarchy to 503 `AUTHENTICATION_UNAVAILABLE` and, being a 5xx, is logged
at `ERROR` with its stack trace. Otherwise an outage of the identity store would show up as "invalid
credentials" and the caller would retry forever with the right password.

To change any of it, map it yourself; a configured mapping always wins over a built-in one:

```yaml
problem:
  mappings:
    "[org.springframework.security.authentication.LockedException]":
      status: 423
      code: ACCOUNT_LOCKED
      detail: The account is locked. Contact support.
```

### Not reached by any of this

`RequestRejectedException`, from `StrictHttpFirewall`, is thrown by `FilterChainProxy` before
`ExceptionTranslationFilter` and before the dispatcher, so neither the advice nor the handler sees it. It needs
a `RequestRejectedHandler` bean. `OAuth2AuthorizationException`, raised when a token endpoint call fails,
inherits from neither Spring Security family and lands on the generic 500; map it if the API talks to an
authorization server.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `problem.enabled` | `true` | Registers the handling at all |
| `problem.base-type-uri` | none | Prefix for the RFC `type` member, built from the code (`ORDER_NOT_FOUND` becomes `.../order-not-found`) |
| `problem.server-error-detail` | `Unexpected error. Please try again later.` | Detail exposed on any 5xx |
| `problem.server-error-code` | `INTERNAL_ERROR` | Code exposed on an unexpected 5xx |
| `problem.include-rejected-value` | `false` | Whether validation errors echo the rejected value |
| `problem.trace-id-mdc-keys` | `traceId,trace_id` | MDC keys read for the correlation id |
| `problem.mappings.<fqcn>.status` | | HTTP status code, as a number |
| `problem.mappings.<fqcn>.code` | | Application error code |
| `problem.mappings.<fqcn>.title` | | Short human readable summary |
| `problem.mappings.<fqcn>.detail` | | Message for the caller |
| `problem.mappings.<fqcn>.log-level` | `WARN` for 4xx, `ERROR` for 5xx | Level this failure is logged at, `OFF` to silence it |

## What it never does

- Expose a stack trace, an exception message on a 5xx, an SQL statement or a constraint name.
- Log a request body, headers or parameters. Only `code`, `status`, `method`, `path`, `traceId`, the exception
  class and its message, with the stack trace attached from `ERROR` up.
- Echo a rejected value unless `problem.include-rejected-value` is on, since caller input can carry PII.

## Overriding it

Every bean is `@ConditionalOnMissingBean`, so declaring a `ProblemFactory` replaces the resolution and
logging rules wholesale, and declaring a `GlobalProblemAdvice` replaces the advice.

For a single exception, declare an ordinary advice in the application. This library registers its advice at
`Ordered.LOWEST_PRECEDENCE`, the lowest possible, because Spring picks the first advice in order that declares
a handler for the thrown type: a library advice with a `Throwable` handler at high precedence would swallow
everything and no application advice would ever run.

An application advice with no `@Order` also defaults to `LOWEST_PRECEDENCE`. Ordering then falls back to bean
discovery order, where auto-configured beans come last, so the application advice still wins in practice. To
make it explicit rather than incidental, give it an `@Order`:

```java
@Order(0)
@RestControllerAdvice
public class PaymentAdvice {

    @ExceptionHandler(PaymentDeclinedException.class)
    ResponseEntity<ProblemDetail> handle(PaymentDeclinedException ex) {
        // ...
    }
}
```

## Not included

- **WebFlux.** A reactive application needs an `ErrorWebExceptionHandler`, a different mechanism from
  `@RestControllerAdvice`. Add it when there is a reactive application to serve.
- **A `@ProblemMapping` annotation.** Configuration covers the same ground and also works for exceptions from
  a jar that cannot be edited.
- **Localized titles and details.** An `ErrorResponse` produced by Spring MVC is still resolved against the
  application `MessageSource`; the codes and details of this library are not.

## Build

```bash
mvn clean install     # build + tests
mvn test              # tests only
```
