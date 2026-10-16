# PortoFit — Infraestrutura local

> Como executar o backend em desenvolvimento. Desenho geral em [arquitetura.md](arquitetura.md).
> Estado: etapa 2 — PostgreSQL e esqueleto do catalog-service (sem funcionalidades de catálogo).

## 1. O que existe

| Componente | Função | Acesso |
|---|---|---|
| `postgres` | PostgreSQL com a base `catalog_db` e o utilizador próprio do catalog-service | Só na rede interna do Compose (sem porta no computador) |
| `catalog-service` | Esqueleto Spring Boot ligado a `catalog_db`, com migrações Flyway e verificação de saúde | `http://localhost:8081` (no emulador Android: `http://10.0.2.2:8081`) |

```
PortoFit/
├── docker-compose.yml            serviços, redes, volume, healthchecks, limites de memória
├── .env.example                  modelo das credenciais locais (o .env real não é versionado)
├── backend/
│   ├── postgres/init/
│   │   └── 01-catalog-db.sh      cria catalog_db e o seu utilizador (1.ª inicialização)
│   └── catalog-service/          projeto Gradle independente da app Android
│       ├── Dockerfile            build em duas etapas (JDK → JRE)
│       ├── build.gradle.kts, settings.gradle.kts, gradlew, gradle/wrapper/
│       └── src/main/
│           ├── kotlin/…/CatalogServiceApplication.kt
│           └── resources/
│               ├── application.yaml
│               └── db/migration/V1__baseline.sql
└── app/                          app Android (não alterada por esta etapa)
```

O backend tem o seu próprio wrapper Gradle e JDK; não partilha build, versões nem configuração com a app Android.

## 2. Versões

Confirmadas a 2026-10-16 no Spring Initializr (start.spring.io) e na documentação oficial de cada projeto. Sem versões *snapshot* ou *milestone*.

| Componente | Versão | Origem / compatibilidade |
|---|---|---|
| Spring Boot | 4.1.1 | Versão estável por omissão no Initializr; requer Java 17+ (compatível até 26) |
| Kotlin (plugins `jvm` e `spring`) | 2.3.21 | Gerado pelo Initializr; é a versão gerida pelo Spring Boot 4.1.1 (exige ≥ 2.2) |
| Plugin `io.spring.dependency-management` | 1.1.7 | Gerado pelo Initializr |
| Gradle (wrapper) | 9.8.1 | Gerado pelo Initializr; Spring Boot aceita 8.14+ ou 9.x; Java 25 exige ≥ 9.1 |
| JDK (toolchain e imagens) | 25 (Temurin 25.0.3+9) | LTS; imagens `eclipse-temurin:25.0.3_9-jdk-noble` (build) e `…-jre-noble` (execução) |
| PostgreSQL | 18.6 | Imagem `postgres:18.6-trixie`; versão principal suportada até novembro de 2030 |
| Flyway | 12.4.0 | Gerida pelo Spring Boot 4.1.1; PostgreSQL 18 consta como versão verificada na documentação do Flyway (módulo `flyway-database-postgresql`) |
| Driver JDBC PostgreSQL | 42.7.13 | Gerido pelo Spring Boot 4.1.1 |
| Docker Desktop / Engine / Compose | 4.95.0 / 29.8.2 / v5.6.0 | Instalação local usada na verificação |

Dependências do serviço: `spring-boot-starter-webmvc`, `-actuator`, `-jdbc`, `-flyway`, `flyway-database-postgresql`, driver PostgreSQL e Jackson para Kotlin. Usa-se Spring JDBC (sem JPA/Hibernate): o esquema é criado **só** pelas migrações Flyway, nunca automaticamente.

## 3. Primeira utilização

1. Copiar o modelo de credenciais e escolher palavras-passe próprias:
   ```powershell
   Copy-Item .env.example .env
   notepad .env
   ```
   O `.env` é ignorado pelo Git e não entra nas imagens Docker.
2. Construir e arrancar:
   ```powershell
   docker compose up -d --build --wait
   ```
   A primeira construção demora alguns minutos (descarrega o Gradle e as dependências). `--wait` só termina quando os dois contentores estão saudáveis.

O script `backend/postgres/init/01-catalog-db.sh` só corre quando o volume de dados está **vazio**. Alterar as credenciais no `.env` depois disso não altera os utilizadores já criados na base de dados.

## 4. Operação diária

| Ação | Comando |
|---|---|
| Arrancar (sem reconstruir) | `docker compose up -d --wait` |
| Reconstruir após alterar o código do serviço | `docker compose up -d --build --wait catalog-service` |
| Estado e saúde | `docker compose ps` |
| Logs (seguir em direto) | `docker compose logs -f catalog-service` |
| Logs da base de dados | `docker compose logs postgres` |
| Reiniciar só o serviço | `docker compose restart catalog-service` |
| Parar tudo (mantém contentores e dados) | `docker compose stop` |
| Parar e remover contentores (mantém dados) | `docker compose down` |
| ⚠️ Remover contentores **e apagar os dados** | `docker compose down -v` |

**Dados:** a base de dados vive no volume Docker `portofit_postgres-data`. `stop`, `restart` e `down` **preservam** os dados. Só `down -v` (ou `docker volume rm`) remove o volume e **apaga todos os dados**; na próxima subida o PostgreSQL é inicializado de novo e o script de criação volta a correr.

## 5. Verificar o serviço

```powershell
curl.exe http://localhost:8081/actuator/health
```

Resposta esperada (HTTP 200):

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

- O estado inclui a ligação à base de dados: com o PostgreSQL parado, a resposta passa a `"status":"DOWN"` com HTTP 503, e volta a `UP` quando a base de dados regressa, sem reiniciar o serviço.
- Não são mostrados detalhes (componentes, versões, nomes de bases de dados). Os restantes endpoints do Actuator, incluindo `/actuator`, não estão expostos (404).

## 6. Configuração relevante

| Aspeto | Valor | Onde |
|---|---|---|
| Porta publicada | `127.0.0.1:8081` (só no próprio computador) | `docker-compose.yml` |
| PostgreSQL | sem porta publicada; rede `data` marcada como interna | `docker-compose.yml` |
| Utilizador da base de dados | `catalog_user`: sem superutilizador, sem criar bases/papéis; dono apenas de `catalog_db`; sem acesso à base `postgres` | `01-catalog-db.sh` |
| Pool de ligações | máx. 5; espera por ligação 5 s; validação 3 s | `application.yaml` |
| Driver JDBC | ligação 5 s; resposta 30 s | `application.yaml` |
| Servidor HTTP | timeout de ligação 10 s; até 50 threads; encerramento gracioso até 20 s | `application.yaml` |
| Memória | limite de 512 MiB por contentor; heap Java até 70 % desse limite; o processo termina em falta de memória e o Docker reinicia-o | `docker-compose.yml`, `Dockerfile` |
| Reinício automático | `unless-stopped` nos dois contentores | `docker-compose.yml` |
| Healthcheck PostgreSQL | `pg_isready` por TCP a cada 5 s | `docker-compose.yml` |
| Healthcheck catalog-service | pedido a `/actuator/health` a cada 10 s (via `bash`, porque a imagem JRE não tem `curl`) | `docker-compose.yml` |
| Imagem do serviço | só o JRE e o `app.jar`; corre com o utilizador `app` (não root); `.env` e ficheiros locais excluídos por `.dockerignore` | `Dockerfile` |

Valores medidos nesta etapa: catalog-service ≈ 190 MiB e PostgreSQL ≈ 80 MiB, com 3,7 GiB atribuídos ao Docker Desktop. Com seis serviços esse total poderá ser apertado; reavaliar os limites quando houver mais serviços.

## 7. Limitações conhecidas

- **Uma única instância PostgreSQL** para todos os serviços (quando existirem): é um ponto comum de falha (ver [arquitetura.md §3.4](arquitetura.md#34-isolamento-dos-dados)).
- As credenciais de administrador do PostgreSQL e a palavra-passe do `catalog_user` ficam nas variáveis de ambiente do contentor `postgres` (visíveis com `docker inspect`). Aceitável apenas em desenvolvimento local.
- Sem HTTPS: tráfego em claro, limitado ao próprio computador.
- O build do serviço foi verificado apenas dentro do Docker. Um build local (`.\gradlew.bat bootJar` em `backend/catalog-service`) precisa de um JDK 25 completo; o JRE embutido da extensão Java do VS Code não serve (ver o problema encontrado na etapa 0).
- Ainda não há testes automáticos do serviço: o teste gerado pelo Initializr exigia uma base de dados e foi retirado. Os testes entram com as funcionalidades do catálogo.
