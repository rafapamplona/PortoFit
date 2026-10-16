package com.example.portofit.catalog.demo

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * Carrega os locais e atividades FICTÍCIOS de demonstração, só com o perfil "demo".
 * Corre depois das migrações Flyway, numa transação, em cada arranque. O script usa ids fixos e
 * INSERT ... ON CONFLICT DO UPDATE, por isso repeti-lo não cria duplicados.
 */
@Component
@Profile("demo")
class DemoDataLoader(
	private val dataSource: DataSource,
	private val transactions: TransactionTemplate,
	private val jdbc: JdbcClient,
) : ApplicationRunner {

	private val log = LoggerFactory.getLogger(javaClass)

	override fun run(args: ApplicationArguments) {
		load()
	}

	fun load() {
		transactions.executeWithoutResult {
			ResourceDatabasePopulator(ClassPathResource(SCRIPT)).apply {
				setSqlScriptEncoding("UTF-8")
			}.execute(dataSource)
		}
		val places = jdbc.sql("SELECT count(*) FROM place WHERE is_demo").query(Long::class.javaObjectType).single()
		log.info("Dados fictícios de demonstração carregados: {} locais com is_demo = true.", places)
	}

	private companion object {
		const val SCRIPT = "demo/catalog-demo-data.sql"
	}
}
