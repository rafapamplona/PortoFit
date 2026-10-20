package com.example.portofit.experiences.security

import com.example.portofit.experiences.api.ApiExceptionHandler
import com.example.portofit.experiences.config.SecurityProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.converter.RsaKeyConverters
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import tools.jackson.databind.json.JsonMapper
import java.security.interfaces.RSAPublicKey

/**
 * Validação **local** dos access tokens do users-service (docs/experiences-api.md §3): só a chave pública,
 * lida de um ficheiro montado só de leitura. Nenhum pedido ao users-service.
 */
@Configuration
class SecurityConfig(private val properties: SecurityProperties) {

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
				authorize(HttpMethod.GET, "/api/v1/experiences", permitAll)
				authorize(HttpMethod.GET, "/api/v1/experiences/**", permitAll)
				authorize(HttpMethod.GET, "/api/v1/ratings/**", permitAll)
				// Feed popular público (E4); /feed e /me/stats exigem sessão (regra geral abaixo).
				authorize(HttpMethod.GET, "/api/v1/feed/popular", permitAll)
				// Consulta interna em lote (§4.7): sem token de utilizador; a chave interna é verificada no controlador.
				authorize(HttpMethod.GET, "/api/v1/internal/experiences", permitAll)
				// Visualizações (E3): token opcional; um token inválido continua a dar 401 (filtro do resource server).
				authorize(HttpMethod.POST, "/api/v1/experiences/*/views", permitAll)
				// Fotografias: consulta pública (só as associadas); envio com sessão (regra geral abaixo).
				authorize(HttpMethod.GET, "/api/v1/media/**", permitAll)
				authorize("/actuator/health", permitAll)
				authorize("/error", permitAll)
				authorize(anyRequest, authenticated)
			}
			oauth2ResourceServer {
				jwt { }
				authenticationEntryPoint = entryPoint
			}
			exceptionHandling {
				authenticationEntryPoint = entryPoint
				accessDeniedHandler = ProblemAccessDeniedHandler(mapper)
			}
		}
		return http.build()
	}

	/** Assinatura só RS256, emissor, destinatário e validade (60 s de tolerância), como no users-service. */
	@Bean
	fun jwtDecoder(): JwtDecoder {
		val location = properties.publicKeyLocation.let { if (it.contains(':') && !it.matches(Regex("^[A-Za-z]:.*"))) it else "file:$it" }
		val publicKey = DefaultResourceLoader().getResource(location).inputStream.use { RsaKeyConverters.x509().convert(it) } as RSAPublicKey
		check(publicKey.modulus.bitLength() >= 2048) { "A chave RSA tem de ter pelo menos 2048 bits." }
		val decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build()
		val audience = OAuth2TokenValidator<Jwt> { jwt ->
			if (jwt.audience?.contains(properties.audience) == true) {
				OAuth2TokenValidatorResult.success()
			} else {
				OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Destinatário do token inválido.", null))
			}
		}
		decoder.setJwtValidator(DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer(properties.issuer), audience))
		return decoder
	}
}

/** 401 em Problem Details: sem token → AUTHENTICATION_REQUIRED; token inválido/expirado → INVALID_ACCESS_TOKEN. */
class ProblemAuthenticationEntryPoint(private val mapper: JsonMapper) : AuthenticationEntryPoint {
	override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
		val invalidToken = authException is OAuth2AuthenticationException
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, if (invalidToken) "Bearer error=\"invalid_token\"" else "Bearer")
		val problem = if (invalidToken) {
			ApiExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "INVALID_ACCESS_TOKEN", "Sessão inválida", "O token de acesso é inválido ou expirou.", request)
		} else {
			ApiExceptionHandler.problem(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Sessão necessária", "Inicia sessão para continuar.", request)
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
private fun write(response: HttpServletResponse, mapper: JsonMapper, status: HttpStatus, problem: ProblemDetail) {
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
