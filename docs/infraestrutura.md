# PortoFit — Infraestrutura local

> Como executar o backend em desenvolvimento. Desenho geral em [arquitetura.md](arquitetura.md). Ligação da app Android ao backend em [app-android.md](app-android.md).
> Estado: etapa 6 (backend da E1) — PostgreSQL, catalog-service (modalidades, locais, atividades; avaliações vindas do experiences-service), users-service (contas, sessões, perfis e seguir; usado pela app) e experiences-service (experiências de texto e estrelas e avaliações agregadas; ainda sem integração na app).

## 1. O que existe

| Componente | Função | Acesso |
|---|---|---|
| `postgres` | PostgreSQL com as bases `catalog_db`, `users_db` e `experiences_db`, cada uma com o seu utilizador | Só na rede interna do Compose (sem porta no computador) |
| `catalog-service` | API do catálogo ([catalog-api.md](catalog-api.md)) sobre `catalog_db`, com migrações Flyway, dados fictícios do perfil `demo` e verificação de saúde | `http://localhost:8081` (no emulador Android: `http://10.0.2.2:8081`) |
| `users-service` | Contas, sessões (JWT + refresh tokens), perfis e seguir ([users-api.md](users-api.md)) sobre `users_db`; valida interesses no catalog-service | `http://localhost:8082` |
| `experiences-service` | Experiências e avaliações agregadas ([experiences-api.md](experiences-api.md)) sobre `experiences_db`; valida atividades no catalog-service; valida os JWT só com a chave pública | `http://localhost:8083` |

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
│   │   └── 03-experiences-db.sh  cria experiences_db e o seu utilizador (idempotente; ver §3.1)
│   ├── users-service/            projeto Gradle independente: auth/, user/, security/, catalog/, api/
│   ├── experiences-service/      projeto Gradle independente: experience/, rating/, catalog/, security/, api/
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
   O `.env` é ignorado pelo Git e não entra nas imagens Docker.
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

Num volume que já tem dados (ex.: criado antes do users-service ou do experiences-service), os scripts de inicialização não voltam a correr. O `02-users-db.sh` e o `03-experiences-db.sh` são **idempotentes** e podem ser corridos à mão sem apagar nada. Exemplo para o users-service (para o experiences-service, o mesmo com `EXPERIENCES_DB_*`, `03-experiences-db.sh` e `experiences-service`):

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
| Reconstruir após alterar o código de um serviço | `docker compose up -d --build --wait catalog-service` (ou `users-service`, `experiences-service`); um de cada vez, para poupar memória |
| Estado e saúde | `docker compose ps` |
| Logs (seguir em direto) | `docker compose logs -f catalog-service` (ou `users-service`) |
| Logs da base de dados | `docker compose logs postgres` |
| Reiniciar só um serviço | `docker compose restart users-service` |
| Parar tudo (mantém contentores e dados) | `docker compose stop` |
| Parar e remover contentores (mantém dados) | `docker compose down` |
| ⚠️ Remover contentores **e apagar os dados** | `docker compose down -v` |

**Dados:** a base de dados vive no volume Docker `portofit_postgres-data`. `stop`, `restart` e `down` **preservam** os dados. Só `down -v` (ou `docker volume rm`) remove o volume e **apaga todos os dados**; na próxima subida o PostgreSQL é inicializado de novo e o script de criação volta a correr.

## 5. Verificar os serviços

```powershell
curl.exe http://localhost:8081/actuator/health   # catalog-service
curl.exe http://localhost:8082/actuator/health   # users-service
curl.exe http://localhost:8083/actuator/health   # experiences-service
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

- 30 testes de integração (`CatalogApiIntegrationTest`) contra **PostgreSQL 18.6 real**, arrancado pelo Testcontainers (exige o Docker Desktop a correr). Aplicam as migrações Flyway e os dados do perfil `demo`.
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
- Testes: `cd backend\users-service; .\gradlew.bat test` (24 testes; PostgreSQL real via Testcontainers; chaves e catálogo falsos gerados pelos próprios testes).

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
- Testes: `cd backend\experiences-service; .\gradlew.bat test` (17 testes; PostgreSQL real via Testcontainers; catálogo falso, chaves e relógio dos próprios testes).

## 7. Configuração relevante

| Aspeto | Valor | Onde |
|---|---|---|
| Portas publicadas | `127.0.0.1:8081` (catálogo), `127.0.0.1:8082` (users), `127.0.0.1:8083` (experiences), só no próprio computador | `docker-compose.yml` |
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

Valores medidos na etapa 3: catalog-service ≈ 190 MiB e PostgreSQL ≈ 80 MiB, com 3,7 GiB atribuídos ao Docker Desktop. Na etapa 6 (2026-10-19), com quatro contentores: catalog-service ≈ 206 MiB, users-service ≈ 222 MiB, experiences-service ≈ 232 MiB, PostgreSQL ≈ 61 MiB (≈ 0,7 GiB no total). Com seis serviços esse total poderá ser apertado; reavaliar os limites quando houver mais serviços.

## 8. Limitações conhecidas

- **Uma única instância PostgreSQL** para todos os serviços (quando existirem): é um ponto comum de falha (ver [arquitetura.md §3.4](arquitetura.md#34-isolamento-dos-dados)).
- As credenciais de administrador do PostgreSQL e a palavra-passe do `catalog_user` ficam nas variáveis de ambiente do contentor `postgres` (visíveis com `docker inspect`). Aceitável apenas em desenvolvimento local.
- Sem HTTPS: tráfego em claro, limitado ao próprio computador.
- O build do serviço foi verificado apenas dentro do Docker. Um build local (`.\gradlew.bat bootJar` em `backend/catalog-service`) precisa de um JDK 25 completo; o JRE embutido da extensão Java do VS Code não serve (ver o problema encontrado na etapa 0).
- Nos testes, o Testcontainers usa o utilizador administrador do contentor; os privilégios reduzidos do `catalog_user` e do `users_user` só são verificados no ambiente Docker Compose.
- `secrets/users-jwt/private.pem` fica no disco sem cifra; no Windows as permissões de ficheiro do script (`umask 077`) não se aplicam. Aceitável só em desenvolvimento local.
- Com o PC quase sem memória livre, o BCrypt (custo 12) leva cerca de 2 s por login (ver [users-api.md §12](users-api.md#12-estado-da-implementação)).
- A pesquisa por texto usa `LIKE '%…%'` sobre nomes normalizados, sem índice de texto: adequado para dezenas ou centenas de locais, não para catálogos grandes.
- A normalização de acentos cobre os caracteres do português e de línguas próximas; não trata sinónimos (ex.: "yoga" não encontra "Ioga").
