package com.example.portofit.users

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class UsersServiceApplication

fun main(args: Array<String>) {
	runApplication<UsersServiceApplication>(*args)
}
