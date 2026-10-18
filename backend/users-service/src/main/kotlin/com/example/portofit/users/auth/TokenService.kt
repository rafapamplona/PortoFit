package com.example.portofit.users.auth

import com.example.portofit.users.config.SecurityProperties
import com.nimbusds.jose.jwk.RSAKey
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Emissão de access tokens (JWT RS256) e geração/hash de refresh tokens opacos. */
@Component
class TokenService(
	private val encoder: JwtEncoder,
	private val signingKey: RSAKey,
	private val properties: SecurityProperties,
) {

	private val random = SecureRandom()

	fun accessToken(userId: UUID, username: String, now: Instant = Instant.now()): String {
		val claims = JwtClaimsSet.builder()
			.issuer(properties.issuer)
			.audience(listOf(properties.audience))
			.subject(userId.toString())
			.claim("username", username)
			.issuedAt(now)
			.expiresAt(now.plus(properties.accessTokenTtl))
			.id(UUID.randomUUID().toString())
			.build()
		val header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.keyID).type("JWT").build()
		return encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
	}

	val accessTokenTtlSeconds: Long get() = properties.accessTokenTtl.toSeconds()

	/** 256 bits aleatórios em base64url (43 caracteres). O valor em claro só existe na resposta ao cliente. */
	fun newRefreshToken(): String {
		val bytes = ByteArray(32).also(random::nextBytes)
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	/** O que fica guardado: SHA-256 em hexadecimal. */
	fun hash(refreshToken: String): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray(Charsets.US_ASCII)))
}
