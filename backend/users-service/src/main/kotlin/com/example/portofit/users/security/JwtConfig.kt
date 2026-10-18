package com.example.portofit.users.security

import com.example.portofit.users.config.SecurityProperties
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.security.converter.RsaKeyConverters
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Par de chaves RSA lido de ficheiros PEM montados no contentor (gerados uma vez e guardados fora do Git
 * e da imagem; ver docs/infraestrutura.md). O serviço não gera nem altera chaves.
 */
@Configuration
class JwtConfig(private val properties: SecurityProperties) {

	@Bean
	fun signingKey(): RSAKey {
		val loader = DefaultResourceLoader()
		val privateKey = loader.getResource(asLocation(properties.privateKeyLocation)).inputStream.use {
			RsaKeyConverters.pkcs8().convert(it)
		}
		val publicKey = loader.getResource(asLocation(properties.publicKeyLocation)).inputStream.use {
			RsaKeyConverters.x509().convert(it)
		}
		check(privateKey.modulus == publicKey.modulus) { "A chave pública não corresponde à chave privada." }
		check(publicKey.modulus.bitLength() >= 2048) { "A chave RSA tem de ter pelo menos 2048 bits." }
		// kid = impressão digital RFC 7638 da chave pública (calculada pela biblioteca Nimbus).
		return RSAKey.Builder(publicKey).privateKey(privateKey).keyIDFromThumbprint().build()
	}

	@Bean
	fun jwtEncoder(signingKey: RSAKey): JwtEncoder =
		NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(signingKey)))

	/** Valida assinatura (só RS256), emissor, destinatário e validade (com 60 s de tolerância de relógio). */
	@Bean
	fun jwtDecoder(signingKey: RSAKey): JwtDecoder {
		val decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey())
			.signatureAlgorithm(SignatureAlgorithm.RS256)
			.build()
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

	/** Caminhos simples (ex.: /run/secrets/...) são tratados como ficheiros. */
	private fun asLocation(path: String) = if (path.contains(':') && !path.matches(Regex("^[A-Za-z]:.*"))) path else "file:$path"
}

/** Chaves públicas de verificação (sem parâmetros privados). */
@RestController
class JwksController(private val signingKey: RSAKey) {

	@GetMapping("/.well-known/jwks.json")
	fun jwks(): Map<String, Any> = JWKSet(signingKey.toPublicJWK()).toJSONObject()
}
