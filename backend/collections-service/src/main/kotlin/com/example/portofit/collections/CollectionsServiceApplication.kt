package com.example.portofit.collections

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class CollectionsServiceApplication

fun main(args: Array<String>) {
	runApplication<CollectionsServiceApplication>(*args)
}
