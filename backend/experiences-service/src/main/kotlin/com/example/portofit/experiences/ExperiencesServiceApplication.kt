package com.example.portofit.experiences

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class ExperiencesServiceApplication {

	/** Relógio da memória de atividades e do limite de publicações (substituível nos testes). */
	@Bean
	fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
	runApplication<ExperiencesServiceApplication>(*args)
}
