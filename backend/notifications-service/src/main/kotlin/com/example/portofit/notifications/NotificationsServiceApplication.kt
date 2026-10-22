package com.example.portofit.notifications

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class NotificationsServiceApplication

fun main(args: Array<String>) {
	runApplication<NotificationsServiceApplication>(*args)
}
