# PortoFit — Infraestrutura local

> Como executar o backend em desenvolvimento. Desenho geral em [arquitetura.md](arquitetura.md). Ligação da app Android ao backend em [app-android.md](app-android.md).
> Estado: etapa 6 (backend da E1) — PostgreSQL, catalog-service (modalidades, locais, atividades; avaliações vindas do experiences-service), users-service (contas, sessões, perfis e seguir; usado pela app) e experiences-service (experiências de texto e estrelas e avaliações agregadas; ainda sem integração na app).

## 1. O que existe

| Componente | Função | Acesso |
|---|---|---|
| `postgres` | PostgreSQL com as bases `catalog_db`, `users_db`, `experiences_db`, `collections_db`, `messaging_db` e `notifications_db`, cada uma com o seu utilizador | Só na rede interna do Compose (sem porta no computador) |
| `catalog-service` | API do catálogo ([catalog-api.md](catalog-api.md)) sobre `catalog_db`, com migrações Flyway, locais reais importados pelo perfil `real-places` (fictícios retirados) e verificação de saúde | `http://localhost:8081` (no emulador Android: `http://10.0.2.2:8081`) |
| `users-service` | Contas, sessões (JWT + refresh tokens), perfis e seguir ([users-api.md](users-api.md)) sobre `users_db`; valida interesses no catalog-service | `http://localhost:8082` |
| `experiences-service` | Experiências, fotografias e avaliações agregadas ([experiences-api.md](experiences-api.md)) sobre `experiences_db` e o volume `experiences-media`; valida atividades no catalog-service; valida os JWT só com a chave pública | `http://localhost:8083` |
| `collections-service` | Listas pessoais e experiências guardadas ([collections-api.md](collections-api.md)) sobre `collections_db`; consulta as experiências no experiences-service em lote; valida os JWT só com a chave pública | `http://localhost:8084` |
| `messaging-service` | Conversas entre duas pessoas, mensagens e partilha de experiências ([messaging-api.md](messaging-api.md)) sobre `messaging_db`; valida o destinatário no users-service e as partilhas no experiences-service (em lote, com chave própria); valida os JWT só com a chave pública; entrega os eventos de mensagens novas ao notifications-service (outbox) | `http://localhost:8085` |
| `notifications-service` | Caixa de notificações privada e entrega interna de eventos ([notifications-api.md](notifications-api.md)) sobre `notifications_db`; uma chave por produtor (users, experiences, messaging); resolve os seguidores das novas experiências no users-service; valida os JWT só com a chave pública | `http://localhost:8086` |

```
PortoFit/
├── docker-compose.yml            serviços, redes, volume, healthchecks, limites de memória
├── .env.example                  modelo das credenciais locais (o .env real não é versionado)
├── scripts/
│   └── generate-jwt-keys.sh      gera uma vez as chaves de assinatura dos JWT (não substitui existentes)
├── secrets/                      chaves locais (ignorada pelo Git; não entra nas imagens)
├── backend/
│   ├── postgres/init/
│   │   ├── 01-catalog-db.sh      cria catalog_db e o seu utilizador (1.ª inicialização)
│   │   ├── 02-users-db.sh        cria users_db e o seu utilizador (idempotente; ver §3.1)
│   │   ├── 03-experiences-db.sh  cria experiences_db e o seu utilizador (idempotente; ver §3.1)
│   │   ├── 04-collections-db.sh  cria collections_db e o seu utilizador (idempotente; ver §3.1)
│   │   ├── 05-messaging-db.sh    cria messaging_db e o seu utilizador (idempotente; ver §3.1)
│   │   └── 06-notifications-db.sh cria notifications_db e o seu utilizador (idempotente; ver §3.1)
│   ├── users-service/            projeto Gradle independente: auth/, user/, outbox/, security/, catalog/, api/
│   ├── experiences-service/      projeto Gradle independente: experience/, interaction/, outbox/, rating/, catalog/, security/, api/
│   ├── collections-service/      projeto Gradle independente: collection/, experiences/, security/, api/
│   ├── messaging-service/        projeto Gradle independente: conversation/, outbox/, users/, experiences/, security/, api/
│   ├── notifications-service/    projeto Gradle independente: notification/, internal/, security/, api/
│   └── catalog-service/          projeto Gradle independente da app Android
│       ├── Dockerfile            build em duas etapas (JDK → JRE)
│       ├── build.gradle.kts, settings.gradle.kts, gradlew, gradle/wrapper/
│       └── src/
│           ├── main/kotlin/…/catalog/
│           │   ├── api/          envelopes de resposta e erros (Problem Details)
│           │   ├── sport/        GET /api/v1/sports
│           │   ├── place/        GET /api/v1/places e /places/{id}: validação, SQL, serviço
│           │   ├── offering/     GET /api/v1/offerings/{id} (com includeRating)
│           │   ├── rating/       avaliações pedidas ao experiences-service (UNAVAILABLE se falhar)
│           │   └── demo/         carregamento dos dados fictícios (perfil demo)
│           ├── main/resources/
│           │   ├── application.yaml
│           │   ├── db/migration/ V1 (vazia), V2 (esquema), V3 (modalidades), V4 (estado ACTIVE/RETIRED)
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

Dependências do catalog-service: `spring-boot-starter-webmvc`, `-actuator`, `-jdbc`, `-flyway`, `flyway-database-postgresql`, driver PostgreSQL e Jackson para Kotlin. Usa-se Spring JDBC (sem JPA/Hibernate): o esquema é criado **só** pelas migrações Flyway, nunca automaticamente.

O experiences-service usa exatamente as mesmas versões e dependências do users-service (o mesmo `build.gradle.kts`, só com outro nome); do resource server usa apenas a validação de JWT, só com a chave pública. Os ids das experiências são UUID versão 7 gerados pela função nativa `uuidv7()` do PostgreSQL 18, sem biblioteca nem gerador próprio.

O users-service usa as mesmas versões (gerado pelo Initializr com as mesmas opções) e acrescenta `spring-boot-starter-security` e `spring-boot-starter-security-oauth2-resource-server` (Spring Security com a biblioteca Nimbus JOSE para assinar e validar JWT, e BCrypt para palavras-passe). Nos testes: `spring-boot-starter-security-test` e `-security-oauth2-resource-server-test`.

## 3. Primeira utilização

1. Copiar o modelo de credenciais e escolher palavras-passe próprias:
   ```powershell
   Copy-Item .env.example .env
   notepad .env
   ```
   O `.env` é ignorado pelo Git e não entra nas imagens Docker. A partir da E4 inclui também `USERS_INTERNAL_KEY` (valor aleatório longo, partilhado só pelo users-service e pelo experiences-service).
2. Gerar as chaves de assinatura dos JWT (uma única vez; usa o `openssl` incluído no Git para Windows):
   ```powershell
   bash scripts/generate-jwt-keys.sh
   ```
   Cria `secrets/users-jwt/private.pem` e `public.pem`. Se já existirem, o script não faz nada: gerar chaves novas terminaria todas as sessões. A pasta `secrets/` é ignorada pelo Git e não é copiada para nenhuma imagem. O users-service recebe a pasta só de leitura; o experiences-service recebe **só o ficheiro `public.pem`**, só de leitura.
3. Construir e arrancar:
   ```powershell
   docker compose up -d --build --wait
   ```
   A primeira construção demora alguns minutos (descarrega o Gradle e as dependências). `--wait` só termina quando todos os contentores estão saudáveis.

Os scripts de `backend/postgres/init/` só correm quando o volume de dados está **vazio**. Alterar as credenciais no `.env` depois disso não altera os utilizadores já criados (ver §3.1).

### 3.1 Acrescentar a base de um serviço novo a um volume existente

Num volume que já tem dados (ex.: criado antes do users-service, do experiences-service, do collections-service, do messaging-service ou do notifications-service), os scripts de inicialização não voltam a correr. O `02-users-db.sh`, o `03-experiences-db.sh`, o `04-collections-db.sh`, o `05-messaging-db.sh` e o `06-notifications-db.sh` são **idempotentes** e podem ser corridos à mão sem apagar nada. Exemplo para o users-service (para o experiences-service, o mesmo com `EXPERIENCES_DB_*`, `03-experiences-db.sh` e `experiences-service`):

1. Acrescentar `USERS_DB_USER` e `USERS_DB_PASSWORD` ao `.env` (ver `.env.example`).
2. Aplicar a configuração ao PostgreSQL (o contentor é recriado com as novas variáveis; **o volume e os dados mantêm-se**; os outros serviços perdem a ligação alguns segundos e recuperam sozinhos):
   ```powershell
   docker compose up -d --wait postgres
   ```
3. Criar a base de dados e o utilizador (repetir não faz mal; num utilizador existente só sincroniza a palavra-passe com o `.env`):
   ```powershell
   docker compose exec -T postgres bash /docker-entrypoint-initdb.d/02-users-db.sh
   ```
   Em Git Bash, prefixar com `MSYS_NO_PATHCONV=1`, senão o caminho do contentor é convertido num caminho Windows.
4. Arrancar o serviço: `docker compose up -d --build --wait users-service`.

## 4. Operação diária

| Ação | Comando |
|---|---|
| Arrancar (sem reconstruir) | `docker compose up -d --wait` |
| Reconstruir após alterar o código de um serviço | `docker compose up -d --build --wait catalog-service` (ou `users-service`, `experiences-service`, `collections-service`, `messaging-service`, `notifications-service`); um de cada vez, para poupar memória |
| Estado e saúde | `docker compose ps` |
| Logs (seguir em direto) | `docker compose logs -f catalog-service` (ou `users-service`) |
| Logs da base de dados | `docker compose logs postgres` |
| Reiniciar só um serviço | `docker compose restart users-service` |
| Parar tudo (mantém contentores e dados) | `docker compose stop` |
| Parar e remover contentores (mantém dados) | `docker compose down` |
| ⚠️ Remover contentores **e apagar os dados** | `docker compose down -v` |

**Dados:** a base de dados vive no volume Docker `portofit_postgres-data` e as fotografias no volume `portofit_experiences-media` (montado só no experiences-service, em `/data/media`; os ficheiros são `<mediaId>.jpg`, referidos pela tabela `media`). Os dois têm de ser copiados juntos numa cópia de segurança. `stop`, `restart` e `down` **preservam** os dados. Só `down -v` (ou `docker volume rm`) remove o volume e **apaga todos os dados**; na próxima subida o PostgreSQL é inicializado de novo e o script de criação volta a correr.

## 5. Verificar os serviços

```powershell
curl.exe http://localhost:8081/actuator/health   # catalog-service
curl.exe http://localhost:8082/actuator/health   # users-service
curl.exe http://localhost:8083/actuator/health   # experiences-service
curl.exe http://localhost:8084/actuator/health   # collections-service
curl.exe http://localhost:8085/actuator/health   # messaging-service
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
GET /places/{id}              → 200, local + offerings[]; "rating" com AVAILABLE ou NO_RATINGS
GET /offerings/{id}?includeRating=false
                              → 200, atividade com status e local, sem "rating"
GET /places/0000…0000         → 404 application/problem+json, "title": "Não encontrado"
```

As avaliações vêm do experiences-service num único pedido por resposta, com prazo de ~1 s. Com ele parado, lento ou em erro, vêm `UNAVAILABLE` (`average` e `ratingsCount` a `null`) e o resto da resposta é igual; não significam zero estrelas. Logo depois de reiniciar o catálogo, as primeiras uma ou duas respostas podem vir `UNAVAILABLE` por excederem o prazo (arranque a frio; medido: 2,4 s e 1,0 s, depois 0,14 s).

### Locais reais (perfil `real-places`)

- Desde 2026-10-27, o `docker-compose.yml` ativa `SPRING_PROFILES_ACTIVE: real-places`. Em cada arranque, o catálogo importa de forma repetível os locais reais aprovados (5 locais e 7 atividades; [locais-reais.md §9](locais-reais.md#9-primeira-importação-2026-10-27)), e os fictícios ficam retirados (`V6`), sem ser apagados nem recarregados.
- Cópia de segurança e reposição de `catalog_db`: [locais-reais.md §9.5](locais-reais.md#95-cópia-de-segurança-e-reposição).

### Dados de demonstração (perfil `demo`, ambiente separado)

- Só num ambiente de demonstração com volume próprio: `docker compose -p portofit-demo -f docker-compose.yml -f docker-compose.demo.yml up -d --wait` (perfil `demo` e `FEED_INCLUDE_DEMO_PLACES=true`; parar primeiro o ambiente normal, porque as portas são as mesmas). Com ele, em cada arranque, o serviço carrega **10 locais e 19 atividades fictícios** (`is_demo = true`, "(fictício)" no nome). Os nomes e moradas são inventados; as coordenadas ficam em zonas do Porto só para a proximidade ser realista e **não assinalam estabelecimentos reais**.
- O carregamento é repetível: ids fixos e `INSERT ... ON CONFLICT DO UPDATE`; reiniciar não cria duplicados.
- As 15 modalidades vêm de migrações normais (`V3`, `V8`; dados de referência), com ou sem o perfil.
- No volume normal, os fictícios carregados antes de 2026-10-27 continuam lá, `RETIRED` (`is_demo = true`), porque há experiências que os referem.

### Testes automáticos

```powershell
cd backend\catalog-service
.\gradlew.bat test
```

- 35 testes de integração (`CatalogApiIntegrationTest` e `RealPlacesImportIntegrationTest`, cada um com a sua base) contra **PostgreSQL 18.6 real**, arrancado pelo Testcontainers (exige o Docker Desktop a correr). Aplicam as migrações Flyway e os dados do perfil `demo`.
- Cobrem: modalidades; pesquisa por modalidade, nome do local e atividade; maiúsculas e acentos; `%`/`_` literais; filtro por modalidade e combinação com texto; paginação estável e página para além do fim; ordenação por nome e por distância; raio e distância em km; parâmetros inválidos; local inexistente e id mal formado; avaliações `UNAVAILABLE`; repetição do carregamento de demonstração sem duplicados. Desde a etapa 6, com um falso experiences-service: `GET /offerings/{id}` (estado, `includeRating=false` sem pedidos, 404/400, atividade ou local retirados), avaliações num só pedido por resposta, e `UNAVAILABLE` com erro, lentidão ou resposta inválida.
- Precisam de um JDK 25 completo (ex.: Temurin 25 no `PATH`). O build da imagem Docker não corre os testes.

### users-service

Contrato completo em [users-api.md](users-api.md). Não há contas de demonstração: cria-se uma conta com o registo.

```powershell
# Registo (devolve accessToken e refreshToken; não os partilhes nem os guardes em ficheiros versionados)
curl.exe -X POST http://localhost:8082/api/v1/auth/register -H "Content-Type: application/json" `
  -d '{\"username\":\"ana.silva\",\"email\":\"ana@example.org\",\"password\":\"uma frase longa e minha\"}'
# Perfil próprio (substituir <access> pelo accessToken)
curl.exe http://localhost:8082/api/v1/me -H "Authorization: Bearer <access>"
# Pesquisa pública
curl.exe "http://localhost:8082/api/v1/users?q=ana"
```

- Chaves: `secrets/users-jwt/` (§3, passo 2). Reiniciar o serviço mantém as chaves e, por isso, as sessões; as contas vivem em `users_db`, no mesmo volume do PostgreSQL.
- Os interesses são validados no catalog-service pela rede interna do Compose (`http://catalog-service:8081`); se o catálogo falhar, só acrescentar interesses novos devolve `503 CATALOG_UNAVAILABLE`.
- Limites de tentativas (`portofit.rate-limit.*` em `application.yaml`): em memória, por conta e por origem; reiniciar o serviço apaga-os.
- Contexto do feed (E4): `GET /api/v1/internal/me/feed-context`, só para o experiences-service, com o token do utilizador e `X-Internal-Key` = `USERS_INTERNAL_KEY` do `.env` (sem a chave → `403`).
- Testes: `cd backend\users-service; .\gradlew.bat test` (29 testes; PostgreSQL real via Testcontainers; chaves, catálogo e notifications-service falsos gerados pelos próprios testes).

### experiences-service

Contrato completo em [experiences-api.md](experiences-api.md). Não há publicações de demonstração: publica-se com o access token de uma conta (obtido no registo ou login do users-service).

```powershell
# Publicar (Idempotency-Key: um UUID novo por tentativa; repetir a mesma tentativa usa o mesmo)
curl.exe -X POST http://localhost:8083/api/v1/experiences -H "Authorization: Bearer <access>" `
  -H "Idempotency-Key: 5d0c7f5e-2a41-4b8e-9f13-6c2b8e1d4a70" -H "Content-Type: application/json" `
  -d '{\"placeId\":\"3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01\",\"offeringId\":\"8a7b6c5d-4e3f-4a1b-9c8d-000000000101\",\"text\":\"Boa aula.\",\"stars\":4}'
# Experiências de um local (públicas), por estrelas
curl.exe "http://localhost:8083/api/v1/experiences?placeId=3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01&sort=stars"
# Avaliações em lote (usado pelo catálogo)
curl.exe "http://localhost:8083/api/v1/ratings/places?ids=3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01&includeOfferings=true"
```

- Valida os access tokens localmente com `secrets/users-jwt/public.pem` (montado só de leitura); nunca chama o users-service.
- Valida atividades no catalog-service pela rede interna (`http://catalog-service:8081`, `includeRating=false`), com memória de 10 min. Com o catálogo parado, só falha publicar numa atividade não validada recentemente (`503 CATALOG_UNAVAILABLE`). Reiniciar o experiences-service esvazia essa memória.
- Limite de publicações por autor e retenção das chaves de idempotência: `portofit.publishing.*` em `application.yaml` (o limite é em memória e reinicia com o serviço).
- Fotografias (E2): `POST /api/v1/media` (multipart, campo `file`, até 10 MiB, JPEG ou PNG) devolve um `mediaId` pendente; associa-se com `"mediaId"` ao publicar ou editar. Limites e limpeza em `portofit.media.*` (`application.yaml`). A imagem guarda a pasta `/data/media` com dono `app` (o volume novo herda-o).
- Feeds e dashboard (E4): o feed pessoal pede o contexto ao users-service (`http://users-service:8082`, com `USERS_INTERNAL_KEY`); se ele falhar, responde com o popular (`POPULAR_FALLBACK`). Modo de demonstração em `portofit.feed.include-demo-places`.
- Consulta interna em lote para o collections-service e o messaging-service: `GET /api/v1/internal/experiences?ids=…` com `X-Internal-Key` = `COLLECTIONS_INTERNAL_KEY` ou `MESSAGING_INTERNAL_KEY` do `.env`, uma chave por serviço (sem chave ou com outra → `403`).
- Testes: `cd backend\experiences-service; .\gradlew.bat test` (50 testes: 17 da E1, 13 das fotografias, 10 dos comentários, gostos e visualizações, 7 dos feeds e dashboard, 1 da consulta interna e 2 dos eventos de notificações; PostgreSQL real via Testcontainers; catálogo e users-service falsos, chaves, relógio e pasta de fotografias dos próprios testes).

### collections-service

Contrato completo em [collections-api.md](collections-api.md). Todas as operações exigem o access token de uma conta (do registo ou login do users-service).

```powershell
# Criar uma lista (a chave de idempotência é um UUID novo por tentativa)
curl.exe -X POST http://localhost:8084/api/v1/collections -H "Authorization: Bearer <access>" `
  -H "Idempotency-Key: <uuid>" -H "Content-Type: application/json" -d '{\"name\":\"Para experimentar\"}'
# Guardar uma experiência e ver os itens
curl.exe -X PUT http://localhost:8084/api/v1/collections/<collectionId>/items/<experienceId> -H "Authorization: Bearer <access>"
curl.exe http://localhost:8084/api/v1/collections/<collectionId>/items -H "Authorization: Bearer <access>"
```

- **Primeira vez num volume existente:**
  1. acrescentar `COLLECTIONS_DB_USER`, `COLLECTIONS_DB_PASSWORD` e `COLLECTIONS_INTERNAL_KEY` ao `.env` (ver `.env.example`);
  2. `docker compose up -d --wait postgres`;
  3. `docker compose exec -T postgres bash /docker-entrypoint-initdb.d/04-collections-db.sh`;
  4. reconstruir o experiences-service e arrancar o collections-service (um de cada vez).
- **Chaves:** valida os JWT só com `secrets/users-jwt/public.pem` (montado só de leitura); nunca recebe a chave privada nem chama o users-service.
- **Experiências:** consulta o experiences-service pela rede interna (`http://experiences-service:8083`, consulta em lote com `COLLECTIONS_INTERNAL_KEY`). Com ele parado, as listas continuam e os itens aparecem como `UNKNOWN` (nunca como eliminados).
- **Limites:** `portofit.collections.*` em `application.yaml` (50 listas por pessoa, 500 itens por lista).
- **Testes:** `cd backend\collections-service; .\gradlew.bat test` (9 testes; PostgreSQL real via Testcontainers; experiences-service falso e chaves dos próprios testes).

### messaging-service

Contrato completo em [messaging-api.md](messaging-api.md). Todas as operações exigem o access token de uma conta.

```powershell
# Abrir (criar ou devolver) a conversa com outra pessoa
curl.exe -X POST http://localhost:8085/api/v1/conversations -H "Authorization: Bearer <access>" `
  -H "Content-Type: application/json" -d '{\"participantId\":\"<userId>\"}'
# Enviar (uma chave de idempotência por tentativa) e consultar as novas depois do seq 0
curl.exe -X POST http://localhost:8085/api/v1/conversations/<conversationId>/messages -H "Authorization: Bearer <access>" `
  -H "Idempotency-Key: <uuid>" -H "Content-Type: application/json" -d '{\"text\":\"Olá!\"}'
curl.exe "http://localhost:8085/api/v1/conversations/<conversationId>/messages?after=0" -H "Authorization: Bearer <access>"
```

- **Primeira vez num volume existente** (feito em 2026-10-20, volume preservado):
  1. acrescentar `MESSAGING_DB_USER`, `MESSAGING_DB_PASSWORD` e `MESSAGING_INTERNAL_KEY` ao `.env` (ver `.env.example`; a chave tem de ser diferente da `COLLECTIONS_INTERNAL_KEY`);
  2. `docker compose up -d --wait postgres`;
  3. `docker compose exec -T postgres bash /docker-entrypoint-initdb.d/05-messaging-db.sh` (repetir não faz mal);
  4. reconstruir o experiences-service (passa a aceitar a chave nova) e arrancar o messaging-service, um de cada vez.
- **Chaves:** valida os JWT só com `secrets/users-jwt/public.pem` (montado só de leitura).
- **users-service:** só ao criar uma conversa nova, pelo perfil público (`http://users-service:8082`, sem token nem chave). Com ele parado, as conversas existentes continuam.
- **Experiências:** consulta em lote no experiences-service com `MESSAGING_INTERNAL_KEY`. Com ele parado, as mensagens de texto continuam e as partilhas aparecem como `UNKNOWN`.
- **Limites:** `portofit.messaging.*` em `application.yaml` (texto 1000 caracteres, 120 mensagens e 20 conversas novas por hora e por pessoa, em memória; 10 000 mensagens por conversa).
- **Outbox de notificações:** cada mensagem nova grava um evento na tabela `outbox` de `messaging_db`, entregue de 5 em 5 s ao notifications-service com `NOTIFY_MESSAGING_KEY` (ver notifications-service, abaixo). Com ele parado, as mensagens continuam a ser enviadas.
- **Testes:** `cd backend\messaging-service; .\gradlew.bat test` (27 testes; PostgreSQL real via Testcontainers; users-service, experiences-service e notifications-service falsos e chaves dos próprios testes).

### notifications-service

Contrato completo em [notifications-api.md](notifications-api.md). A caixa exige o access token da própria pessoa; a entrega interna exige a chave de um produtor.

```powershell
# Notificações mais recentes, as novas depois do seq 0 e a contagem por ler
curl.exe "http://localhost:8086/api/v1/notifications?limit=20" -H "Authorization: Bearer <access>"
curl.exe "http://localhost:8086/api/v1/notifications?after=0" -H "Authorization: Bearer <access>"
curl.exe http://localhost:8086/api/v1/notifications/unread-count -H "Authorization: Bearer <access>"
# Marcar como lidas até ao seq 12
curl.exe -X PUT http://localhost:8086/api/v1/notifications/read -H "Authorization: Bearer <access>" `
  -H "Content-Type: application/json" -d '{\"upToSeq\":12}'
```

- **Primeira vez num volume existente** (feito em 2026-10-22, volume preservado):
  1. acrescentar `NOTIFICATIONS_DB_USER`, `NOTIFICATIONS_DB_PASSWORD` e `NOTIFY_MESSAGING_KEY` ao `.env` (ver `.env.example`; valores aleatórios longos, e a chave diferente de todas as outras);
  2. `docker compose up -d --wait postgres`;
  3. `docker compose exec -T postgres bash /docker-entrypoint-initdb.d/06-notifications-db.sh` (repetir não faz mal; em Git Bash, com `MSYS_NO_PATHCONV=1`);
  4. arrancar o notifications-service e reconstruir o messaging-service (passa a ter a outbox), um de cada vez.
- **Segunda parte** (2026-10-22): acrescentar `NOTIFY_USERS_KEY`, `NOTIFY_EXPERIENCES_KEY` e `USERS_FOLLOWERS_KEY` ao `.env` e reconstruir, um de cada vez, o notifications-service, o users-service e o experiences-service (migrações novas aplicadas no arranque).
- **Chaves** (só do `.env`, cada uma exclusiva):
  - `NOTIFY_USERS_KEY`, `NOTIFY_EXPERIENCES_KEY` e `NOTIFY_MESSAGING_KEY`: cada produtor e o notifications-service; cada chave só entrega os tipos do seu produtor;
  - `USERS_FOLLOWERS_KEY`: notifications-service e users-service, para `GET /api/v1/internal/users/{id}/followers`;
  - para mudar uma chave: alterar o `.env` e recriar os dois contentores que a usam (ex.: `docker compose up -d --wait notifications-service users-service`).
- **Novas experiências:** os destinatários (quem segue o autor) são resolvidos depois, por páginas, a partir do users-service. Com o users-service parado, a resolução fica `PENDING` e retoma sozinha.
- **Retenção:** 90 dias e 1000 notificações por pessoa; os ids dos eventos recebidos guardam-se 35 dias (`portofit.notifications.*`).
- **Testes:** `cd backend\notifications-service; .\gradlew.bat test` (17 testes; PostgreSQL real via Testcontainers; users-service falso e chaves dos próprios testes).

**Diagnóstico da outbox** (no produtor: `messaging_db`, `users_db` ou `experiences_db`; contagens e códigos, nunca o `payload`):

```powershell
# Eventos por estado e último erro, com o mais antigo de cada grupo
"SELECT status, last_error, count(*), min(created_at) FROM outbox GROUP BY 1, 2 ORDER BY 1, 2;" |
  docker compose exec -T postgres sh -c 'psql -U $POSTGRES_USER -d messaging_db'
# Ids dos eventos FAILED
"SELECT event_id, type, attempts, last_error, created_at FROM outbox WHERE status = 'FAILED' ORDER BY created_at;" |
  docker compose exec -T postgres sh -c 'psql -U $POSTGRES_USER -d messaging_db'
```

O log de cada produtor mostra, de minuto a minuto e só quando há pendentes ou falhados, uma linha `Outbox: N pendentes (mais antigo há S s), F falhados, E entregues na última hora`. Um erro de chave aparece como `Falha de configuração na entrega de notificações (HTTP_403)`, uma vez por período de recuo.

**Reenvio manual de um evento `FAILED`** (depois de corrigida a causa; [notifications-api.md §6.4](notifications-api.md#64-eventos-antigos-e-reenvio-manual)):

```powershell
"UPDATE outbox SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL WHERE status = 'FAILED' AND event_id = '<eventId>';" |
  docker compose exec -T postgres sh -c 'psql -U $POSTGRES_USER -d messaging_db'
```

- Não altera o `eventId` nem o conteúdo: um evento que já tinha chegado responde `DUPLICATE`, e um com mais de 30 dias responde `EXPIRED` e volta a `FAILED`.
- Como o evento tem mais de 24 h, uma nova falha temporária devolve-o logo a `FAILED/MAX_AGE`; nesse caso, repetir quando o notifications-service estiver saudável.
- O mesmo comando serve para `users_db` e `experiences_db` (trocar o `-d`). Exercitado em 2026-10-22 num evento de teste: `UPDATE 1` e entrega única.

**Resoluções de novas experiências** (no notifications-service; só estados e códigos):

```powershell
"SELECT status, last_error, count(*), sum(recipients) FROM fan_out_job GROUP BY 1, 2 ORDER BY 1, 2;" |
  docker compose exec -T postgres sh -c 'psql -U $POSTGRES_USER -d notifications_db'
# Repor uma resolução FAILED (retoma do último cursor guardado; ninguém é notificado duas vezes)
"UPDATE fan_out_job SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL WHERE status = 'FAILED' AND event_id = '<eventId>';" |
  docker compose exec -T postgres sh -c 'psql -U $POSTGRES_USER -d notifications_db'
```

## 7. Configuração relevante

| Aspeto | Valor | Onde |
|---|---|---|
| Portas publicadas | `127.0.0.1:8081` (catálogo), `127.0.0.1:8082` (users), `127.0.0.1:8083` (experiences), `127.0.0.1:8084` (collections), `127.0.0.1:8085` (messaging), `127.0.0.1:8086` (notifications), só no próprio computador | `docker-compose.yml` |
| PostgreSQL | sem porta publicada; rede `data` marcada como interna | `docker-compose.yml` |
| Utilizador da base de dados | `catalog_user`: sem superutilizador, sem criar bases/papéis; dono apenas de `catalog_db`; sem acesso à base `postgres` | `01-catalog-db.sh` |
| Pool de ligações | máx. 5; espera por ligação 5 s; validação 3 s | `application.yaml` |
| Driver JDBC | ligação 5 s; resposta 30 s | `application.yaml` |
| Servidor HTTP | timeout de ligação 10 s; até 50 threads; encerramento gracioso até 20 s | `application.yaml` |
| Memória | limite de 512 MiB por contentor; heap Java até 70 % desse limite; o processo termina em falta de memória e o Docker reinicia-o | `docker-compose.yml`, `Dockerfile` |
| Reinício automático | `unless-stopped` em todos os contentores | `docker-compose.yml` |
| Healthcheck PostgreSQL | `pg_isready` por TCP a cada 5 s | `docker-compose.yml` |
| Healthcheck dos serviços | pedido a `/actuator/health` a cada 10 s (via `bash`, porque a imagem JRE não tem `curl`) | `docker-compose.yml` |
| Imagem do serviço | só o JRE e o `app.jar`; corre com o utilizador `app` (não root); `.env` e ficheiros locais excluídos por `.dockerignore` | `Dockerfile` |

Valores medidos na etapa 3: catalog-service ≈ 190 MiB e PostgreSQL ≈ 80 MiB, com 3,7 GiB atribuídos ao Docker Desktop. Na etapa 6 (2026-10-19), com quatro contentores: catalog-service ≈ 206 MiB, users-service ≈ 222 MiB, experiences-service ≈ 232 MiB, PostgreSQL ≈ 61 MiB (≈ 0,7 GiB no total). Na etapa 12 (2026-10-20), com seis contentores e sem carga: messaging-service ≈ 244 MiB, users-service ≈ 248 MiB, catalog-service ≈ 231 MiB, experiences-service ≈ 228 MiB, collections-service ≈ 223 MiB, PostgreSQL ≈ 80 MiB (≈ 1,25 GiB no total). Com o emulador e o Android Studio abertos, o computador ficou com cerca de 0,4 GiB livres; o messaging-service pode ser parado quando não é preciso (`docker compose stop messaging-service`). Na etapa 10 (2026-10-22), com sete contentores e sem carga: notifications-service ≈ 214 MiB, messaging-service ≈ 211 MiB, catalog-service ≈ 231 MiB, collections-service ≈ 203 MiB, experiences-service ≈ 203 MiB, users-service ≈ 192 MiB, PostgreSQL ≈ 80 MiB (≈ 1,33 GiB no total). O notifications-service também pode ficar parado: as mensagens continuam e os eventos esperam na outbox. Depois da segunda parte (outbox no users-service e no experiences-service): notifications-service ≈ 215 MiB, users-service ≈ 244 MiB, experiences-service ≈ 235 MiB, PostgreSQL ≈ 89 MiB (≈ 1,3 GiB no total).

## 8. Limitações conhecidas

- **Uma única instância PostgreSQL** para os seis serviços: é um ponto comum de falha (ver [arquitetura.md §3.4](arquitetura.md#34-isolamento-dos-dados)).
- As credenciais de administrador do PostgreSQL e a palavra-passe do `catalog_user` ficam nas variáveis de ambiente do contentor `postgres` (visíveis com `docker inspect`). Aceitável apenas em desenvolvimento local.
- Sem HTTPS: tráfego em claro, limitado ao próprio computador.
- O build do serviço foi verificado apenas dentro do Docker. Um build local (`.\gradlew.bat bootJar` em `backend/catalog-service`) precisa de um JDK 25 completo; o JRE embutido da extensão Java do VS Code não serve (ver §9).
- Nos testes, o Testcontainers usa o utilizador administrador do contentor; os privilégios reduzidos do `catalog_user` e do `users_user` só são verificados no ambiente Docker Compose.
- `secrets/users-jwt/private.pem` fica no disco sem cifra; no Windows as permissões de ficheiro do script (`umask 077`) não se aplicam. Aceitável só em desenvolvimento local.
- Com o PC quase sem memória livre, o BCrypt (custo 12) leva cerca de 2 s por login (ver [users-api.md §12](users-api.md#12-estado-da-implementação)).
- A pesquisa por texto usa `LIKE '%…%'` sobre nomes normalizados, sem índice de texto: adequado para dezenas ou centenas de locais, não para catálogos grandes.
- A normalização de acentos cobre os caracteres do português e de línguas próximas; não trata sinónimos (ex.: "yoga" não encontra "Ioga").

## 9. JDK do Gradle

O Android Gradle Plugin precisa de um **JDK 25 completo, com `jlink`** (transformação `JdkImageTransform`). O JRE 25 embutido na extensão Java da Red Hat para o VS Code (`.vscode/extensions/redhat.java-…/jre/…`) não serve: tem `javac`, mas não tem `jlink`.

### Como o Gradle escolhe a JVM do daemon

Segundo a documentação oficial do Gradle 9.6 ([Gradle Daemon › Daemon JVM criteria](https://docs.gradle.org/9.6.0/userguide/gradle_daemon.html) e [Toolchains](https://docs.gradle.org/9.6.0/userguide/toolchains.html)):

1. **Critérios do daemon**, em `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=25`, qualquer fornecedor). Têm prioridade sobre `org.gradle.java.home`, `JAVA_HOME` e o "Gradle JDK" do Android Studio.
2. O Gradle procura instalações compatíveis com a mesma lógica das toolchains. Entre as compatíveis, **"a instalação que está a correr o Gradle é preferida a qualquer outra"**. É a JVM do cliente: o Android Studio, o terminal ou a extensão do VS Code.
3. **Um daemon já em execução e compatível é reutilizado por qualquer cliente.** Se um daemon arrancar num JRE sem `jlink`, todos os builds seguintes falham até ele parar.

### Causa do erro `jlink.exe does not exist`

Os registos dos daemons (`%USERPROFILE%\.gradle\daemon\9.6.0\daemon-<pid>.out.log`) mostram:

- todos os daemons no JRE da Red Hat foram arrancados por **processos do VS Code**:
  - o servidor Java da Red Hat (jdt.ls), que é o processo pai do daemon;
  - o servidor da extensão "Gradle for Java", cujo diretório de trabalho é `vscjava.vscode-gradle-…\lib` e que tem a variável `GRADLE_SERVER_OPTS`;
- os daemons arrancados pelo Android Studio usam sempre o JBR do próprio Android Studio;
- o erro aparece quando o Android Studio **reutiliza** um daemon arrancado pelo VS Code (2026-10-21: o daemon 23304, arrancado às 09:39 pelo jdt.ls, recebeu às 09:47 os builds do Android Studio).

Os clientes do VS Code correm no JRE da Red Hat, que é versão 25 e reporta o fornecedor `Eclipse Adoptium`, tal como o Temurin. Por isso cumpre os critérios e é escolhido por ser a JVM do cliente. **Nenhum critério do daemon o distingue do Temurin**: nem a versão, nem o fornecedor (`--jvm-vendor=adoptium` não ajudaria). A correção tem de impedir que o VS Code seja cliente Gradle deste projeto.

A primeira correção (2026-10-20) não chegou, por duas razões:

- `java.import.gradle.enabled: false` não impede o jdt.ls de **sincronizar projetos Gradle que já tinha importado antes**. `PortoFit` e `app` continuam registados no espaço de trabalho dele.
- `gradle.autoDetect: off` só desliga a lista de tarefas. A extensão "Gradle for Java" 3.18.1 carrega o projeto sempre que arranca o seu servidor de linguagem, e esse servidor corre sempre no JRE embutido da Red Hat. Nenhuma definição muda isto.

### Correção (só para este projeto, fora do Git)

1. **`.vscode/settings.json` local** (`.vscode/` está no `.gitignore`):

   ```json
   {
       "java.server.launchMode": "LightWeight",
       "java.configuration.updateBuildConfiguration": "disabled",
       "java.import.gradle.enabled": false,
       "java.gradle.buildServer.enabled": "off",
       "gradle.autoDetect": "off"
   }
   ```

   No modo `LightWeight`, a extensão da Red Hat só arranca o servidor de sintaxe, sem suporte Maven/Gradle. O servidor completo, que sincronizava os projetos antigos, deixa de arrancar. O projeto é Kotlin, por isso o servidor Java completo não traz nada aqui.
2. **Desativar a extensão "Gradle for Java" só neste espaço de trabalho:** no VS Code, *Extensions* › "Gradle for Java" › ⚙ › **Disable (Workspace)**. Se o VS Code pedir, desativar também, só no espaço de trabalho, as extensões que dependem dela (por exemplo, o "Extension Pack for Java"). Fica guardado no estado deste espaço de trabalho do VS Code: não é global e não entra no Git.
3. **Recarregar a janela do VS Code** (*Developer: Reload Window*). Até lá, os processos antigos continuam a correr e podem arrancar outro daemon.
4. Parar o daemon errado, se existir: `.\gradlew.bat --stop` (para os daemons desta versão do Gradle; não toca em dados, caches nem volumes).

Nada mudou nos ficheiros partilhados do Gradle, nas versões, nas dependências nem nas configurações globais. O contorno antigo, com `-Porg.gradle.java.installations.…` em cada comando ([app-android.md §16](app-android.md)), continua desnecessário.

**Se um dia for preciso o servidor Java completo no VS Code** (*Switch to Standard Mode*): correr antes *Java: Clean Java Language Server Workspace*, para ele não voltar a sincronizar os projetos Gradle antigos.

### Verificar

- `.\gradlew.bat --status`: daemons ativos desta versão do Gradle.
- `.\gradlew.bat javaToolchains`: instalações detetadas. Não deve aparecer nenhuma dentro de `.vscode`.
- O `javaHome` de cada daemon está no início do seu `daemon-<pid>.out.log`. A linha "Configuring env variables" mostra quem o pediu: com `VSCODE_PID` e o diretório `c:\…` em minúsculas, ou com `GRADLE_SERVER_OPTS`, foi o VS Code.
- No Android Studio, manter o **Gradle JDK** na opção dos critérios do daemon (o projeto não define `gradleJvm` em `.idea/gradle.xml`). A entrada da lista de JDKs do Android Studio que aponta para `.vscode\extensions\redhat.java-…` pode ser removida em *Project Structure › SDKs*. Não é a causa, mas evita confusões; é uma definição global do Android Studio e fica à escolha do utilizador.

### Histórico

- **2026-10-20:** primeira correção (definições locais do VS Code). O Run no Android Studio foi confirmado pelo utilizador nesse dia.
- **2026-10-21:** o erro voltou. Os daemons tinham sido arrancados pelo jdt.ls e pela "Gradle for Java" (ver causa acima). Acrescentou-se o modo `LightWeight` e a desativação da "Gradle for Java" no espaço de trabalho.
  - **Verificado no terminal:** daemon errado parado; `assembleDebug` sem parâmetros de JDK concluído; daemon novo no Temurin 25.0.3, com `jlink`; `javaToolchains` só com os Temurin 17 e 25.
  - **Por confirmar pelo utilizador:** o Run no Android Studio, depois de desativar a extensão e recarregar o VS Code.
