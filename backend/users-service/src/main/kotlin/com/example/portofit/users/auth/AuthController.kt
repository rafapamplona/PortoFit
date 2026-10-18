package com.example.portofit.users.auth

import com.example.portofit.users.user.PrivateProfile
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

// Pedidos com segredos: toString() não os inclui, para não irem parar a logs por engano.
data class RegisterRequest(val username: String? = null, val email: String? = null, val password: String? = null) {
	override fun toString() = "RegisterRequest(username=$username)"
}

data class LoginRequest(val email: String? = null, val password: String? = null) {
	override fun toString() = "LoginRequest()"
}

data class RefreshTokenRequest(val refreshToken: String? = null) {
	override fun toString() = "RefreshTokenRequest()"
}

data class SessionResponse(
	val tokenType: String,
	val accessToken: String,
	val accessTokenExpiresIn: Long,
	val refreshToken: String,
	val refreshTokenExpiresIn: Long,
	@get:JsonInclude(JsonInclude.Include.NON_NULL)
	val user: PrivateProfile?,
) {
	override fun toString() = "SessionResponse(user=${user?.userId})"

	companion object {
		fun of(session: IssuedSession) = SessionResponse(
			"Bearer", session.accessToken, session.accessTokenExpiresIn,
			session.refreshToken, session.refreshTokenExpiresIn, session.user,
		)
	}
}

/**
 * Endpoints públicos de sessão. A origem usada nos limites de tentativas é o endereço da ligação
 * (não se confia em cabeçalhos como X-Forwarded-For enviados pelo cliente).
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val auth: AuthService) {

	@PostMapping("/register")
	fun register(@RequestBody body: RegisterRequest, request: HttpServletRequest): ResponseEntity<SessionResponse> {
		val session = auth.register(body.username, body.email, body.password, request.remoteAddr)
		return ResponseEntity.created(URI.create("/api/v1/users/${session.user!!.userId}")).body(SessionResponse.of(session))
	}

	@PostMapping("/login")
	fun login(@RequestBody body: LoginRequest, request: HttpServletRequest) =
		SessionResponse.of(auth.login(body.email, body.password, request.remoteAddr))

	@PostMapping("/refresh")
	fun refresh(@RequestBody body: RefreshTokenRequest, request: HttpServletRequest) =
		SessionResponse.of(auth.refresh(body.refreshToken, request.remoteAddr))

	@PostMapping("/logout")
	fun logout(@RequestBody body: RefreshTokenRequest): ResponseEntity<Void> {
		auth.logout(body.refreshToken)
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build()
	}
}
