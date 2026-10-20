package com.example.portofit.messaging.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {
	/** Relógio dos limites de frequência (substituível nos testes). */
	@Bean
	fun clock(): Clock = Clock.systemUTC()
}
