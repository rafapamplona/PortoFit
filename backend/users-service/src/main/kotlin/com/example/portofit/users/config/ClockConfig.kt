package com.example.portofit.users.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {
	/** Relógio do envio da outbox (substituível nos testes). */
	@Bean
	fun clock(): Clock = Clock.systemUTC()
}
