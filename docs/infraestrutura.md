# PortoFit — Infraestrutura local

> Como executar o backend em desenvolvimento. Desenho geral em [arquitetura.md](arquitetura.md).
> Estado: etapa 3 — PostgreSQL e catalog-service com modalidades, pesquisa de locais e detalhe (backend). A integração na app Android está pendente.

## 1. O que existe

| Componente | Função | Acesso |
|---|---|---|
| `postgres` | PostgreSQL com a base `catalog_db` e o utilizador próprio do catalog-service | Só na rede interna do Compose (sem porta no computador) |
| `catalog-service` | API do catálogo ([catalog-api.md](catalog-api.md)) sobre `catalog_db`, com migrações Flyway, dados fictícios do perfil `demo` e verificação de saúde | `http://localhost:8081` (no emulador Android: `http://10.0.2.2:8081`) |

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
│       └── src/
│           ├── main/kotlin/…/catalog/
│           │   ├── api/          envelopes de resposta e erros (Problem Details)
│           │   ├── sport/        GET /api/v1/sports
│           │   ├── place/        GET /api/v1/places e /places/{id}: validação, SQL, serviço
│           │   ├── rating/       avaliações (por agora sempre UNAVAILABLE)
│           │   └── demo/         carregamento dos dados fictícios (perfil demo)
│           ├── main/resources/
│           │   ├── application.yaml
│           │   ├── db/migration/ V1 (vazia), V2 (esquema), V3 (modalidades)
│           │   └── demo/catalog-demo-data.sql
│           └── test/kotlin/…     testes de integração (Testcontainers)
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
| Testcontainers (testes) | 2.0.5 | Gerido pelo Spring Boot 4.1.1; dependências iguais às geradas pelo Initializr; compatível com o Docker Engine 29 |
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

## 6. Usar a API do catálogo

Contrato completo em [catalog-api.md](catalog-api.md). Exemplos com os dados fictícios de demonstração (em PowerShell, usar `curl.exe`):

| Objetivo | Pedido |
|---|---|
| Listar modalidades | `curl.exe "http://localhost:8081/api/v1/sports"` |
| Pesquisar "boxe" (nome do local, modalidade ou atividade; ignora maiúsculas e acentos) | `curl.exe "http://localhost:8081/api/v1/places?q=boxe"` |
| Mesmo resultado com outra grafia | `curl.exe "http://localhost:8081/api/v1/places?q=BOXE"` · `…?q=natacao` encontra "Natação" |
| Filtrar por modalidade | `curl.exe "http://localhost:8081/api/v1/places?sport=natacao"` |
| Proximidade (raio em km; ordena por distância) | `curl.exe "http://localhost:8081/api/v1/places?sport=boxe&lat=41.1496&lng=-8.6109&radiusKm=3"` |
| Paginação | `curl.exe "http://localhost:8081/api/v1/places?size=3&page=1"` |
| Detalhe de um local e atividades | `curl.exe "http://localhost:8081/api/v1/places/3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01"` |
| Erro de validação (exemplo) | `curl.exe "http://localhost:8081/api/v1/places?lat=41.1496"` → 400 com `errors` |

Respostas resumidas:

```text
GET /places?q=boxe            → 200, totalItems 4 (Bonfim, Ribeira, Antas, Boavista — por nome)
GET /places?sport=boxe&lat=41.1496&lng=-8.6109&radiusKm=3
                              → 200, Ribeira 1.02 km, Bonfim 1.04 km, … (por distância)
GET /places/{id}              → 200, local + offerings[]; todos os "rating" com "status": "UNAVAILABLE"
GET /places/0000…0000         → 404 application/problem+json, "title": "Não encontrado"
```

As avaliações vêm sempre `UNAVAILABLE` (`average` e `ratingsCount` a `null`) até existir o experiences-service; não significam zero estrelas.

### Dados de demonstração (perfil `demo`)

- O `docker-compose.yml` ativa o perfil com `SPRING_PROFILES_ACTIVE: demo`. Com ele, em cada arranque, o serviço carrega **10 locais e 19 atividades fictícios** (`is_demo = true`, "(fictício)" no nome). Os nomes e moradas são inventados; as coordenadas ficam em zonas do Porto só para a proximidade ser realista e **não assinalam estabelecimentos reais**.
- O carregamento é repetível: ids fixos e `INSERT ... ON CONFLICT DO UPDATE`; reiniciar não cria duplicados.
- As 8 modalidades vêm de uma migração normal (dados de referência), com ou sem o perfil.
- Para arrancar sem dados fictícios, retirar a linha `SPRING_PROFILES_ACTIVE` do Compose. Dados já carregados mantêm-se até serem apagados (por `is_demo = true`) ou até o volume ser removido.

### Testes automáticos

```powershell
cd backend\catalog-service
.\gradlew.bat test
```

- 23 testes de integração (`CatalogApiIntegrationTest`) contra **PostgreSQL 18.6 real**, arrancado pelo Testcontainers (exige o Docker Desktop a correr). Aplicam as migrações Flyway e os dados do perfil `demo`.
- Cobrem: modalidades; pesquisa por modalidade, nome do local e atividade; maiúsculas e acentos; `%`/`_` literais; filtro por modalidade e combinação com texto; paginação estável e página para além do fim; ordenação por nome e por distância; raio e distância em km; parâmetros inválidos; local inexistente e id mal formado; avaliações `UNAVAILABLE`; repetição do carregamento de demonstração sem duplicados.
- Precisam de um JDK 25 completo (ex.: Temurin 25 no `PATH`). O build da imagem Docker não corre os testes.

## 7. Configuração relevante

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

## 8. Limitações conhecidas

- **Uma única instância PostgreSQL** para todos os serviços (quando existirem): é um ponto comum de falha (ver [arquitetura.md §3.4](arquitetura.md#34-isolamento-dos-dados)).
- As credenciais de administrador do PostgreSQL e a palavra-passe do `catalog_user` ficam nas variáveis de ambiente do contentor `postgres` (visíveis com `docker inspect`). Aceitável apenas em desenvolvimento local.
- Sem HTTPS: tráfego em claro, limitado ao próprio computador.
- O build do serviço foi verificado apenas dentro do Docker. Um build local (`.\gradlew.bat bootJar` em `backend/catalog-service`) precisa de um JDK 25 completo; o JRE embutido da extensão Java do VS Code não serve (ver o problema encontrado na etapa 0).
- Nos testes, o Testcontainers usa o utilizador administrador do contentor; os privilégios reduzidos do `catalog_user` só são verificados no ambiente Docker Compose.
- A pesquisa por texto usa `LIKE '%…%'` sobre nomes normalizados, sem índice de texto: adequado para dezenas ou centenas de locais, não para catálogos grandes.
- A normalização de acentos cobre os caracteres do português e de línguas próximas; não trata sinónimos (ex.: "yoga" não encontra "Ioga").
