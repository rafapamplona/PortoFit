package com.example.portofit.users.auth

import com.example.portofit.users.api.ApiErrors
import com.example.portofit.users.api.FieldError
import com.example.portofit.users.config.SecurityProperties
import com.example.portofit.users.security.AttemptLimiter
import com.example.portofit.users.user.PrivateProfile
import com.example.portofit.users.user.ProfileService
import com.example.portofit.users.user.UserRepository
import com.example.portofit.users.user.UserRow
import com.example.portofit.users.user.UserRules
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Sessão emitida no registo, login ou renovação. Os tokens nunca são registados em logs. */
data class IssuedSession(
	val accessToken: String,
	val accessTokenExpiresIn: Long,
	val refreshToken: String,
	val refreshTokenExpiresIn: Long,
	val user: PrivateProfile?,
) {
	override fun toString() = "IssuedSession(user=${user?.userId})"
}

@Service
class AuthService(
	private val users: UserRepository,
	private val sessions: SessionRepository,
	private val profiles: ProfileService,
	private val tokens: TokenService,
	private val passwordEncoder: PasswordEncoder,
	private val limiter: AttemptLimiter,
	private val transactions: TransactionTemplate,
	private val properties: SecurityProperties,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/** Hash de referência para emails inexistentes: o login falhado demora o mesmo tempo nos dois casos. */
	private val dummyHash: String by lazy { passwordEncoder.encode("portofit-dummy-password-0")!! }

	fun register(usernameInput: String?, emailInput: String?, password: String?, origin: String): IssuedSession {
		limiter.beforeRegister(origin)
		val username = usernameInput?.let(UserRules::normalizeUsername)
		val email = emailInput?.let(UserRules::normalizeEmail)

		val errors = mutableListOf<FieldError>()
		if (username.isNullOrEmpty()) errors += FieldError("username", "Indica um nome de utilizador.") else UserRules.usernameError(username)?.let { errors += it }
		if (email.isNullOrEmpty()) errors += FieldError("email", "Indica um email.") else UserRules.emailError(email)?.let { errors += it }
		if (password.isNullOrEmpty()) {
			errors += FieldError("password", "Indica uma palavra-passe.")
		} else {
			UserRules.passwordError(password, username.orEmpty(), email.orEmpty())?.let { errors += it }
		}
		if (errors.isNotEmpty()) throw ApiErrors.validation(errors)

		conflicts(username!!, email!!).takeIf { it.isNotEmpty() }?.let { throw ApiErrors.conflict(it) }

		val id = UUID.randomUUID()
		val hash = passwordEncoder.encode(password)!!
		try {
			users.insert(id, username, email, hash)
		} catch (e: DuplicateKeyException) {
			// Registo simultâneo com os mesmos dados: a restrição de unicidade decide; reporta o campo em conflito.
			throw ApiErrors.conflict(conflicts(username, email).ifEmpty { listOf(FieldError("username", USERNAME_TAKEN)) })
		}
		log.info("Conta criada: {}", id)
		return startSession(users.findById(id)!!)
	}

	fun login(emailInput: String?, password: String?, origin: String): IssuedSession {
		val email = emailInput?.let(UserRules::normalizeEmail)
		if (email.isNullOrEmpty() || password.isNullOrEmpty()) {
			throw ApiErrors.validation(
				listOfNotNull(
					FieldError("email", "Indica o email.").takeIf { email.isNullOrEmpty() },
					FieldError("password", "Indica a palavra-passe.").takeIf { password.isNullOrEmpty() },
				),
			)
		}
		limiter.beforeLogin(email, origin)

		val user = users.findByEmail(email)
		val valid = if (user != null && UserRules.passwordWithinLimits(password)) {
			passwordEncoder.matches(password, user.passwordHash)
		} else {
			passwordEncoder.matches(DUMMY_CANDIDATE, dummyHash)
			false
		}
		if (!valid) {
			limiter.loginFailed(email)
			throw ApiErrors.invalidCredentials()
		}
		limiter.loginSucceeded(email)
		return startSession(user!!)
	}

	fun refresh(refreshToken: String?, origin: String): IssuedSession {
		limiter.beforeRefresh(origin)
		if (refreshToken.isNullOrBlank()) throw ApiErrors.validation("refreshToken", "Indica o refresh token.")
		val hash = tokens.hash(refreshToken)

		// Consumir o antigo e criar o novo na mesma transação. Uma reutilização revoga a família e a
		// transação termina normalmente (a revogação fica gravada); o erro só é lançado depois.
		val outcome = transactions.execute {
			val consumed = sessions.consume(hash)
			if (consumed != null) {
				val next = tokens.newRefreshToken()
				sessions.insert(consumed.userId, consumed.familyId, tokens.hash(next), consumed.expiresAt)
				RefreshOutcome.Rotated(consumed.userId, next, consumed.expiresAt)
			} else {
				sessions.revokeFamilyIfReused(hash)?.let { RefreshOutcome.Reused(it) } ?: RefreshOutcome.Invalid
			}
		}
		return when (outcome) {
			is RefreshOutcome.Rotated -> {
				val user = users.findById(outcome.userId) ?: throw ApiErrors.invalidRefreshToken()
				IssuedSession(
					accessToken = tokens.accessToken(user.id, user.username),
					accessTokenExpiresIn = tokens.accessTokenTtlSeconds,
					refreshToken = outcome.refreshToken,
					refreshTokenExpiresIn = secondsUntil(outcome.expiresAt),
					user = null,
				)
			}
			is RefreshOutcome.Reused -> {
				log.warn("Reutilização de refresh token: sessão {} revogada.", outcome.familyId)
				throw ApiErrors.invalidRefreshToken()
			}
			else -> throw ApiErrors.invalidRefreshToken()
		}
	}

	/** Idempotente: um token desconhecido ou já revogado não é erro. */
	fun logout(refreshToken: String?) {
		if (refreshToken.isNullOrBlank()) throw ApiErrors.validation("refreshToken", "Indica o refresh token.")
		sessions.revokeFamilyOf(tokens.hash(refreshToken))
	}

	/** Nova família de refresh tokens com prazo máximo fixo (não prolongado pela rotação). */
	private fun startSession(user: UserRow): IssuedSession {
		val refresh = tokens.newRefreshToken()
		val expiresAt = Instant.now().plus(properties.sessionTtl)
		sessions.insert(user.id, UUID.randomUUID(), tokens.hash(refresh), expiresAt)
		return IssuedSession(
			accessToken = tokens.accessToken(user.id, user.username),
			accessTokenExpiresIn = tokens.accessTokenTtlSeconds,
			refreshToken = refresh,
			refreshTokenExpiresIn = secondsUntil(expiresAt),
			user = profiles.privateProfile(user.id),
		)
	}

	private fun conflicts(username: String, email: String) = listOfNotNull(
		FieldError("username", USERNAME_TAKEN).takeIf { users.usernameExists(username) },
		FieldError("email", "Não é possível criar uma conta com este email.").takeIf { users.emailExists(email) },
	)

	private fun secondsUntil(instant: Instant) = maxOf(0, Duration.between(Instant.now(), instant).toSeconds())

	private sealed interface RefreshOutcome {
		data class Rotated(val userId: UUID, val refreshToken: String, val expiresAt: Instant) : RefreshOutcome {
			override fun toString() = "Rotated(userId=$userId)"
		}
		data class Reused(val familyId: UUID) : RefreshOutcome
		data object Invalid : RefreshOutcome
	}

	private companion object {
		const val USERNAME_TAKEN = "Este nome de utilizador já está a ser usado."
		const val DUMMY_CANDIDATE = "not-a-real-password"
	}
}
