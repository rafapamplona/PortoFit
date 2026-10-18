package com.example.portofit.users.security

import com.example.portofit.users.api.ApiExceptionHandler
import com.example.portofit.users.config.SecurityProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import tools.jackson.databind.json.JsonMapper

@Configuration
class SecurityConfig {

	@Bean
	fun securityFilterChain(http: HttpSecurity, mapper: JsonMapper): SecurityFilterChain {
		val entryPoint = ProblemAuthenticationEntryPoint(mapper)
		http {
			csrf { disable() } // sem cookies: a autenticação é só pelo cabeçalho Authorization
			httpBasic { disable() }
			formLogin { disable() }
			logout { disable() }
			sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
			authorizeHttpRequests {
				authorize(HttpMethod.POST, "/api/v1/auth/**", permitAll)
				authorize(HttpMethod.GET, "/api/v1/users", permitAll)
				authorize(HttpMethod.GET, "/api/v1/users/**", permitAll)
				authorize(HttpMethod.GET, "/.well-known/jwks.json", permitAll)
				authorize("/actuator/health", permitAll)
				authorize("/error", permitAll)
				authorize("/api/v1/me", authenticated)
				authorize("/api/v1/me/**", authenticated)
				authorize(anyRequest, authenticated)
			}
			oauth2ResourceServer {
				jwt { }
				bearerTokenResolver = authAwareBearerTokenResolver()
				authenticationEntryPoint = entryPoint
			}
			exceptionHandling {
				authenticationEntryPoint = entryPoint
				accessDeniedHandler = ProblemAccessDeniedHandler(mapper)
			}
		}
		return http.build()
	}

	/** BCrypt com o custo configurado; o prefixo {bcrypt} no hash permite migrar de algoritmo mais tarde. */
	@Bean
	fun passwordEncoder(properties: SecurityProperties): PasswordEncoder =
		DelegatingPasswordEncoder("bcrypt", mapOf("bcrypt" to BCryptPasswordEncoder(properties.bcryptStrength)))

	// Os endpoints em /api/v1/auth/ não usam o access token: um token expirado no cabeçalho não pode
	// impedir o login, a renovação ou o logout.
	private fun authAwareBearerTokenResolver(): BearerTokenResolver {
		val default = DefaultBearerTokenResolver()
		return BearerTokenResolver { request ->
			if (request.requestURI.startsWith("/api/v1/auth/")) null else default.resolve(request)
		}
	}
}

/** 401 em Problem Details: sem token → AUTHENTICATION_REQUIRED; token inválido/expirado → INVALID_ACCESS_TOKEN. */
class ProblemAuthenticationEntryPoint(private val mapper: JsonMapper) : AuthenticationEntryPoint {
	override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
		val invalidToken = authException is OAuth2AuthenticationException
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, if (invalidToken) "Bearer error=\"invalid_token\"" else "Bearer")
		val problem = if (invalidToken) {
			ApiExceptionHandler.problem(
				HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Sessão inválida",
				"O token de acesso é inválido ou expirou.", request,
			)
		} else {
			ApiExceptionHandler.problem(
				HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Sessão necessária",
				"Inicia sessão para continuar.", request,
			)
		}
		write(response, mapper, HttpStatus.UNAUTHORIZED, problem)
	}
}

class ProblemAccessDeniedHandler(private val mapper: JsonMapper) : AccessDeniedHandler {
	override fun handle(
		request: HttpServletRequest,
		response: HttpServletResponse,
		accessDeniedException: org.springframework.security.access.AccessDeniedException,
	) {
		val problem = ApiExceptionHandler.problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acesso negado", "Não tens permissão para este pedido.", request)
		write(response, mapper, HttpStatus.FORBIDDEN, problem)
	}
}

/** Escreve os mesmos campos que o Spring MVC produz para um ProblemDetail (propriedades no nível de topo). */
private fun write(response: HttpServletResponse, mapper: JsonMapper, status: HttpStatus, problem: org.springframework.http.ProblemDetail) {
	val body = linkedMapOf<String, Any?>(
		"title" to problem.title,
		"status" to problem.status,
		"detail" to problem.detail,
		"instance" to problem.instance?.toString(),
	)
	problem.properties?.let { body.putAll(it) }
	response.status = status.value()
	response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
	response.characterEncoding = "UTF-8"
	mapper.writeValue(response.outputStream, body)
}
