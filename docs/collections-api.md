# collections-service — Contrato da API

> Listas pessoais com nomes personalizados e experiências guardadas (requisito R8, etapa 11). Contexto geral em [arquitetura.md](arquitetura.md) (§3.2 responsabilidades, §3.5 cópias, §3.8 autenticação, §4 falhas).
> Estado: contrato revisto em 2026-10-20; **backend implementado** (etapa 11, [§13](#13-estado-da-implementação)). A app ainda não usa este serviço.
> Mesmas convenções do [catalog-api.md](catalog-api.md), do [users-api.md](users-api.md) e do [experiences-api.md](experiences-api.md): JSON UTF-8, campos em inglês (camelCase), textos em português, datas ISO 8601 UTC, erros em Problem Details com `code`, envelope de paginação comum.

## Índice

1. [Responsabilidade e âmbito](#1-responsabilidade-e-âmbito)
2. [Entidades](#2-entidades)
3. [Autenticação e privacidade](#3-autenticação-e-privacidade)
4. [Endpoints](#4-endpoints)
5. [Listas](#5-listas)
6. [Itens](#6-itens)
7. [Consulta das experiências (experiences-service)](#7-consulta-das-experiências-experiences-service)
8. [Erros](#8-erros)
9. [Limites](#9-limites)
10. [Dependências e falhas](#10-dependências-e-falhas)
11. [Dados e infraestrutura](#11-dados-e-infraestrutura)
12. [Decisões](#12-decisões)
13. [Estado da implementação](#13-estado-da-implementação)

---

## 1. Responsabilidade e âmbito

O collections-service é o dono das **listas pessoais** e das **referências** às experiências guardadas nelas. Não guarda conteúdo das experiências: só o `experienceId` e a data em que foi guardado.

**Nesta etapa:**

- criar, listar, consultar, renomear e eliminar as próprias listas;
- guardar, listar e remover experiências numa lista.

**Fora desta etapa:** guardar locais, partilhar listas, listas públicas, notificações, "vezes guardada" no dashboard (etapa 13) e a app Android.

## 2. Entidades

| Entidade | Campos | Notas |
|---|---|---|
| `Collection` | `id` (UUID v7), `ownerId` (do token), `name` (1–60), `createdAt`, `updatedAt` | Nome único por dono, sem distinguir maiúsculas/minúsculas |
| `CollectionItem` | `collectionId`, `experienceId`, `addedAt` | Par único (`collectionId`, `experienceId`) |
| `CollectionRequest` | `ownerId`, `idempotencyKey`, `requestHash`, `collectionId` (null depois de eliminada), `createdAt` | Tentativas de criação (§5.2), guardadas 7 dias |

Referências: `ownerId` é o `userId` do users-service; `experienceId` é o id do experiences-service. Não há chaves estrangeiras para outros serviços nem cópias de apresentação ([arquitetura.md §3.5](arquitetura.md#35-cópias-de-dados-desnormalização)).

## 3. Autenticação e privacidade

- **Todos os endpoints públicos exigem access token.** O serviço valida-o **localmente** com a chave pública do users-service: RS256, `iss = portofit-users`, `aud = portofit-api`, `exp`, com 60 s de tolerância, como o experiences-service. **Não recebe chaves privadas** e não chama o users-service.
- O dono é sempre o `sub` do token; nenhum pedido aceita `ownerId` ou `userId` no corpo ou nos parâmetros.
- **As listas são privadas.** Uma lista de outra pessoa responde exatamente como uma lista inexistente (`404 NOT_FOUND`), em qualquer operação: não se revela se existe, o nome, o número de itens nem o dono.
- **Logs:** nunca tokens, nomes de listas nem chaves internas; só o `userId`, ids e tipos de erro.

## 4. Endpoints

Prefixo `/api/v1`. Todos com `Authorization: Bearer <access token>`.

| Método | Caminho | Descrição | Sucesso |
|---|---|---|---|
| GET | `/collections` | As minhas listas, paginadas | `200` |
| POST | `/collections` | Criar (com `Idempotency-Key`) | `201`; repetição `200` |
| GET | `/collections/{collectionId}` | Detalhe de uma lista minha | `200` |
| PATCH | `/collections/{collectionId}` | Renomear | `200` |
| DELETE | `/collections/{collectionId}` | Eliminar a lista (não elimina experiências) | `204` |
| GET | `/collections/{collectionId}/items` | Itens, paginados, com o estado de cada experiência | `200` |
| PUT | `/collections/{collectionId}/items/{experienceId}` | Guardar (idempotente) | `204` |
| DELETE | `/collections/{collectionId}/items/{experienceId}` | Remover (idempotente) | `204` |
| GET | `/actuator/health` | Estado (público) | `200` |

Formato de uma lista:

```json
{
  "id": "019a1f20-7c3d-7e11-9a2b-3c4d5e6f7a8b",
  "name": "Para experimentar",
  "itemCount": 3,
  "createdAt": "2026-10-20T10:00:00Z",
  "updatedAt": "2026-10-20T10:05:00Z"
}
```

Paginação: envelope comum (`items`, `page`, `size`, `totalItems`, `totalPages`), `page` ≥ 0, `size` 1–50 (por omissão 20), página para além do fim → `items: []`.

## 5. Listas

### 5.1 Nome

- Remove espaços e mudanças de linha nas pontas; depois tem de ter **1 a 60 caracteres** (pontos de código Unicode). Só espaços → `400`. Caracteres de controlo → `400`.
- **Único por dono, sem distinguir maiúsculas/minúsculas** ("Treinos" e "treinos" são o mesmo nome). Garantido por um índice único em (`owner_id`, `lower(name)`), também com pedidos simultâneos. Nome repetido → `409 NAME_TAKEN`. Donos diferentes podem usar o mesmo nome.
- Guarda-se e devolve-se o nome como foi escrito (sem as pontas).

### 5.2 Criar (`POST /collections`)

Corpo `{"name": "Para experimentar"}`; outros campos → `400`. Exige o cabeçalho **`Idempotency-Key`** (UUID), uma chave por tentativa lógica, como na publicação de experiências ([experiences-api.md §5](experiences-api.md#5-publicação-sem-duplicados-idempotência)):

| Situação | Resposta |
|---|---|
| Chave nova | `201` com a lista e `Location` |
| Mesma chave e mesmo nome (resposta perdida, repetição) | `200` com a mesma lista e `Idempotent-Replayed: true`, mesmo que entretanto tenha sido renomeada |
| Mesma chave com outro nome | `409 IDEMPOTENCY_CONFLICT`, `reason: DIFFERENT_CONTENT`, com `collectionId` |
| Mesma chave de uma lista entretanto eliminada | `409 IDEMPOTENCY_CONFLICT`, `reason: COLLECTION_DELETED` (nada é recriado) |
| Pedidos simultâneos com a mesma chave | uma só lista; os outros recebem a repetição |

- A comparação do "mesmo nome" é feita com o nome normalizado (pontas removidas).
- Chaves guardadas **7 dias**; depois disso, não há garantia contra duplicação (a app não reutiliza chaves com mais de 6 dias).
- A tentativa é registada antes da lista, na mesma transação. Uma recusa (`400`, `409 NAME_TAKEN`, limite) não gasta a chave.

### 5.3 Listar, consultar, renomear, eliminar

- `GET /collections`: as minhas listas, **mais recentes primeiro** (`createdAt DESC, id DESC`). Ordem total.
- `GET /collections/{id}`: a lista, com `itemCount`.
- `PATCH /collections/{id}` com `{"name": "…"}`: renomeia; `updatedAt` muda se o nome mudar. Mudar só maiúsculas/minúsculas do próprio nome é permitido. Nome de outra lista minha → `409 NAME_TAKEN`. Repetir o mesmo pedido dá o mesmo resultado.
- `DELETE /collections/{id}`: elimina a lista e os seus itens (referências). **As experiências não são afetadas.** Repetir depois de eliminada → `404` (a app trata como "já eliminada").

## 6. Itens

### 6.1 Guardar (`PUT /collections/{id}/items/{experienceId}`)

1. A lista tem de ser minha (senão `404`).
2. Se a experiência **já está na lista** → `204`, sem pedir nada ao experiences-service: repetir é seguro mesmo com ele parado.
3. Senão, pergunta ao experiences-service se a experiência existe (§7):
   - `AVAILABLE` → guarda e responde `204`;
   - `DELETED` ou `NOT_FOUND` → `404 EXPERIENCE_NOT_FOUND`, nada guardado;
   - sem resposta, erro ou resposta inválida → `503 EXPERIENCES_UNAVAILABLE`, nada guardado (a app mostra "não foi possível guardar agora", nunca "a experiência não existe").
4. A inserção usa a chave primária (`collection_id`, `experience_id`) com `ON CONFLICT DO NOTHING`: pedidos simultâneos deixam **um** item. O limite de itens é verificado com a lista bloqueada (`FOR UPDATE`).

O experiences-service é consultado **fora** da transação; entre a consulta e a gravação a experiência pode ser eliminada, caso em que o item aparece depois como `DELETED` (§6.3).

### 6.2 Remover (`DELETE /collections/{id}/items/{experienceId}`)

`204` quer o item exista quer não (idempotente). Não consulta o experiences-service, por isso funciona com ele parado e também para experiências eliminadas.

### 6.3 Listar (`GET /collections/{id}/items`)

Ordem: **guardados mais recentemente primeiro** (`addedAt DESC, experienceId DESC`). Para cada página, **um único pedido em lote** ao experiences-service com os ids dessa página (no máximo 50):

```json
{
  "items": [
    { "experienceId": "019a…", "addedAt": "2026-10-20T10:05:00Z", "availability": "AVAILABLE", "experience": { "id": "019a…", "…": "formato Experience do experiences-service" } },
    { "experienceId": "019b…", "addedAt": "2026-10-20T10:01:00Z", "availability": "DELETED", "experience": null }
  ],
  "page": 0, "size": 20, "totalItems": 2, "totalPages": 1,
  "experiencesStatus": "AVAILABLE"
}
```

| `availability` | Quando | Conteúdo |
|---|---|---|
| `AVAILABLE` | a experiência existe | `experience` com o formato público `Experience` ([experiences-api.md §4.1](experiences-api.md#41-post-experiences--publicar)) |
| `DELETED` | o experiences-service diz que foi eliminada ou não existe | `experience: null`; **nenhum conteúdo apagado**. O dono pode removê-la (§6.2) |
| `UNKNOWN` | o experiences-service não respondeu ou respondeu de forma inválida | `experience: null`; **não** significa eliminada |

- `experiencesStatus` é `AVAILABLE` ou `UNAVAILABLE`. Com o experiences-service em falha, a resposta continua `200`: a lista, as datas e os ids aparecem; todos os itens ficam `UNKNOWN` e a app mostra "conteúdo indisponível de momento", com "Tentar novamente".
- O `itemCount` da lista conta todos os itens, incluindo os `DELETED`.

## 7. Consulta das experiências (experiences-service)

Novo endpoint **interno** no experiences-service, só para o collections-service ([experiences-api.md §4.7](experiences-api.md#47-get-internalexperiences--consulta-em-lote-collections-service)):

`GET /api/v1/internal/experiences?ids=<id>,<id>,…` com `X-Internal-Key: <COLLECTIONS_INTERNAL_KEY>`

- 1 a **50** ids (UUID), sem repetições relevantes (repetidos são ignorados); mais de 50 ou ids inválidos → `400`.
- Resposta `200`: uma entrada por id pedido, pela ordem pedida, com `status` = `AVAILABLE` (e `experience` no formato público, sem `likedByMe`), `DELETED` ou `NOT_FOUND`. Nunca devolve conteúdo de experiências eliminadas.
- Chave em falta ou errada → `403`. Sem chave configurada, o endpoint recusa sempre.
- **Porquê interno** (e não `GET /experiences` público com vários ids): a distinção "eliminada" / "inexistente" só interessa ao collections-service; a chave limita quem faz consultas em lote; e o tamanho do lote fica limitado do lado de quem responde.
- O collections-service usa ligação 0,5 s e resposta 2 s, sem novas tentativas nem cache.

## 8. Erros

Problem Details (`application/problem+json`) com `code` estável e, nos erros de validação, `errors` (`field`, `message`). Nunca incluem nomes de listas de outras pessoas, tokens nem dados internos.

| HTTP | `code` | Quando |
|---|---|---|
| 400 | `VALIDATION_FAILED` | nome, ids, paginação, cabeçalhos ou corpo inválidos (com `errors`) |
| 401 | `AUTHENTICATION_REQUIRED` | sem token |
| 401 | `INVALID_ACCESS_TOKEN` | token inválido ou expirado |
| 404 | `NOT_FOUND` | lista inexistente **ou de outra pessoa** |
| 404 | `EXPERIENCE_NOT_FOUND` | guardar uma experiência eliminada ou inexistente |
| 405 | `METHOD_NOT_ALLOWED` | método não suportado |
| 409 | `NAME_TAKEN` | já tenho uma lista com esse nome (sem distinguir maiúsculas) |
| 409 | `COLLECTION_LIMIT_REACHED` | já tenho o máximo de listas |
| 409 | `ITEM_LIMIT_REACHED` | a lista já tem o máximo de itens |
| 409 | `IDEMPOTENCY_CONFLICT` | chave já usada: `reason` = `DIFFERENT_CONTENT` (com `collectionId`) ou `COLLECTION_DELETED` |
| 413 | `PAYLOAD_TOO_LARGE` | corpo acima de 4 KiB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | corpo que não é JSON |
| 500 | `INTERNAL_ERROR` | erro inesperado, sem detalhes |
| 503 | `EXPERIENCES_UNAVAILABLE` | não foi possível confirmar a experiência ao guardar |
| 503 | `SERVICE_UNAVAILABLE` | base de dados indisponível |

## 9. Limites

| Limite | Valor por omissão | Configuração |
|---|---|---|
| Listas por pessoa | 50 | `portofit.collections.max-per-owner` |
| Itens por lista | 500 | `portofit.collections.max-items` |
| Nome | 1–60 caracteres | fixo (contrato) |
| Página | 1–50 | fixo (contrato) |
| Ids por consulta em lote | 50 | fixo (contrato) |
| Retenção das chaves de criação | 7 dias | `portofit.collections.idempotency-retention` |

O limite de listas é verificado com um bloqueio transacional por dono (`pg_advisory_xact_lock`) e o de itens com a lista bloqueada, por isso pedidos simultâneos não o ultrapassam.

## 10. Dependências e falhas

| Situação | Efeito |
|---|---|
| experiences-service em baixo | Criar, listar, renomear e eliminar listas e **remover** itens continuam. Guardar uma experiência nova → `503 EXPERIENCES_UNAVAILABLE` (guardar uma já guardada → `204`). Listar itens → `200` com `experiencesStatus: UNAVAILABLE` e itens `UNKNOWN` |
| users-service em baixo | Nenhum: os tokens são validados localmente (até expirarem) |
| collections-service em baixo | Na app, só as listas ficam indisponíveis; o resto continua ([arquitetura.md §4.3](arquitetura.md#43-comportamento-perante-falhas)) |
| PostgreSQL em baixo | `503 SERVICE_UNAVAILABLE` |

O serviço arranca sem o experiences-service e sem o users-service; só depende do PostgreSQL e da chave pública.

## 11. Dados e infraestrutura

- Base `collections_db` com utilizador próprio `COLLECTIONS_DB_USER`, sem privilégios de administração, criada pelo script idempotente `backend/postgres/init/04-collections-db.sh` (num volume existente, corrido à mão: [infraestrutura.md §3.1](infraestrutura.md#31-acrescentar-a-base-de-um-serviço-novo-a-um-volume-existente)). O volume existente é preservado.
- Esquema Flyway: `collection` (índice único `owner_id, lower(name)`), `collection_item` (chave primária `collection_id, experience_id`, eliminação em cascata com a lista), `collection_request` (chave primária `owner_id, idempotency_key`; `collection_id` passa a null quando a lista é eliminada).
- Contentor `collections-service`, porta **`127.0.0.1:8084`**, rede interna para o PostgreSQL e o experiences-service; monta **só** `secrets/users-jwt/public.pem`, só de leitura.
- Chave interna `COLLECTIONS_INTERNAL_KEY` no `.env` (fora do Git e das imagens), passada só ao collections-service e ao experiences-service.
- Nenhuma consulta direta a bases de outros serviços.

## 12. Decisões

| # | Decisão | Alternativa afastada |
|---|---|---|
| 1 | Listas de outra pessoa = `404`, como inexistentes | `403` (revela que a lista existe) |
| 2 | Nome único por dono sem distinguir maiúsculas, garantido por índice | só verificação na aplicação (falha com pedidos simultâneos) |
| 3 | Criação com `Idempotency-Key` e tabela de tentativas, como nas experiências | sem chave (uma resposta perdida criaria a lista duas vezes ou daria `NAME_TAKEN` sem a lista na app) |
| 4 | Guardar e remover itens com `PUT`/`DELETE` idempotentes e chave primária | `POST` com corpo (exigiria chave de idempotência) |
| 5 | Consulta interna em lote no experiences-service (um pedido por página), com chave própria | um pedido por item; cópias de apresentação nas listas (ficariam desatualizadas e conteriam conteúdo depois de eliminado) |
| 6 | Itens de experiências eliminadas ficam como `DELETED`, sem conteúdo, removíveis | apagar itens automaticamente (exigiria eventos entre serviços) |
| 7 | Falha do experiences-service: `UNKNOWN`/`503`, nunca "eliminada" | tratar a falha como inexistência |
| 8 | Listas mais recentes primeiro; itens guardados mais recentemente primeiro; desempate por id | ordem alfabética (muda ao renomear) |
| 9 | Limites de 50 listas e 500 itens, configuráveis | sem limites |

## 13. Estado da implementação

Etapa 11 (backend), `backend/collections-service`, mais a consulta interna em lote no experiences-service ([experiences-api.md §4.7](experiences-api.md#47-get-internalexperiences--consulta-em-lote-collections-service)). A app ainda não usa este serviço.

**Implementado:**

- todos os endpoints do §4, com as regras do §5 e do §6;
- migração `V1__collections_schema.sql` (`collection`, `collection_item`, `collection_request`);
- base `collections_db` e utilizador próprio (`04-collections-db.sh`, idempotente, corrido à mão no volume existente);
- contentor em `127.0.0.1:8084`, só com a chave pública;
- `COLLECTIONS_INTERNAL_KEY` no `.env`, partilhada só com o experiences-service;
- limpeza horária das tentativas de criação com mais de 7 dias.

**Notas da implementação:**

- **Projeto:** as mesmas versões e dependências do experiences-service (Spring Boot 4.1.1, Kotlin 2.3.21, JDK 25, Flyway, Spring JDBC), sem dependências novas.
- **Criação idempotente:** tentativa inserida com `ON CONFLICT DO NOTHING` antes da lista, na mesma transação. O nome repetido (`DuplicateKeyException` do índice único) desfaz tudo e dá `409 NAME_TAKEN`, sem gastar a chave.
  - O limite de listas usa `pg_advisory_xact_lock(hashtextextended(dono))`, e a tentativa é procurada outra vez depois do bloqueio, para uma repetição simultânea no limite responder como repetição.
- **Itens:** a lista é bloqueada (`FOR UPDATE`) para contar os itens; a inserção usa `ON CONFLICT DO NOTHING`. Guardar e remover atualizam `updatedAt` só quando algo muda.
- **Cliente do experiences-service:**
  - `RestClient` sobre o cliente HTTP do JDK (ligação 0,5 s, resposta 2 s), sem novas tentativas nem cache;
  - uma resposta incompleta, com estados desconhecidos ou sem um dos ids pedidos é tratada como indisponível;
  - um `403` é registado como erro de configuração; registos sem a chave nem nomes de listas.

**Testes automáticos:**

- **collections-service** (`CollectionsApiIntegrationTest`, **9 testes, 0 falhas**), com PostgreSQL 18.6 real (Testcontainers), um experiences-service falso (confirma a chave e simula erro, lentidão, chave recusada e resposta inválida) e chaves RSA só dos testes:
  - **autenticação:** sem token, outra chave, outro `aud` → `401`;
  - **privacidade:** lista de outra pessoa ≡ inexistente (`404` igual) em todas as operações, sem o nome nem o dono;
  - **validação do nome:** vazio, só espaços, 61 caracteres, controlo, mudança de linha, não texto, campos extra, corpo não objeto, chave em falta; 60 emojis aceites;
  - **idempotência da criação:** `201` → `200` + `Idempotent-Replayed`, também depois de renomear; outro nome → `DIFFERENT_CONTENT` com `collectionId`; eliminada → `COLLECTION_DELETED`;
  - **unicidade do nome:** sem distinguir maiúsculas; outro dono pode repetir; `NAME_TAKEN` não gasta a chave; renomear só maiúsculas permitido; nome de outra lista → `409`;
  - **concorrência:** 8 criações com a mesma chave → 1 lista; 8 com o mesmo nome → 1 + 7 `409`; 6 criações com limite 3 → 3;
  - **listas:** paginação mais recentes primeiro; eliminar → `204`, depois `404`, sem pedidos ao experiences-service e com os itens removidos;
  - **itens:**
    - guardar e remover idempotentes, também a remoção de itens inexistentes;
    - já guardada → `204` sem consultar o experiences-service (também com ele parado);
    - ordem e paginação, com **um pedido em lote por página** com os ids dessa página;
    - 8 guardados simultâneos → 1 item; limite de itens com pedidos simultâneos;
  - **experiência eliminada vs. serviço indisponível:**
    - guardar eliminada ou inexistente → `404 EXPERIENCE_NOT_FOUND`;
    - eliminada depois → `DELETED` sem conteúdo;
    - erro, lentidão (> 2 s), chave recusada e resposta inválida → itens `UNKNOWN`, `experiencesStatus: UNAVAILABLE` e guardar → `503` sem gravar;
    - remover continua a funcionar.
- **experiences-service** (`InternalLookupApiIntegrationTest`, +1; **48 testes, 0 falhas**):
  - sem chave, chave errada ou só com token → `403`;
  - ordem pedida, repetidos ignorados, `AVAILABLE`/`DELETED`/`NOT_FOUND`, sem conteúdo da eliminada nem `likedByMe`;
  - 0, inválido ou 51 ids → `400`; 50 → `200`.

Executados depois da última alteração ao código.

**Verificação por HTTP (2026-10-20)**, nos contentores. O PostgreSQL foi recriado com as variáveis novas (o volume manteve-se), `collections_db` criada pelo script e o experiences-service reconstruído. Usaram-se duas contas temporárias, apagadas no fim com as suas experiências e listas; `pamplona`, a segunda conta do utilizador, as suas publicações e os volumes foram preservados. 19 verificações, 0 falhas:

| Verificação | Resultado |
|---|---|
| Sem token | `401` |
| Criar "  Para experimentar  "; repetir a chave | `201`, nome sem as pontas, sem `ownerId`; `200` + `Idempotent-Replayed`, mesma lista |
| "PARA EXPERIMENTAR"; só espaços; renomear | `409 NAME_TAKEN`; `400`; `200` |
| Outra conta: a lista da primeira, uma inexistente, guardar nela | `404` iguais, sem o nome; a sua lista vazia |
| Guardar duas experiências e repetir uma | `204` ×3 |
| Itens | dois `AVAILABLE`, o mais recente primeiro, com a experiência |
| Guardar uma experiência inexistente | `404 EXPERIENCE_NOT_FOUND` |
| A autora elimina uma experiência guardada | item `DELETED` sem conteúdo; a outra `AVAILABLE` |
| experiences-service parado | itens `UNKNOWN`, `experiencesStatus: UNAVAILABLE` (`200`); guardar nova `503 EXPERIENCES_UNAVAILABLE`; já guardada `204`; remover `204`; listas `200` |
| Serviço de volta | item `AVAILABLE` |
| Consulta interna em lote a partir do computador | sem chave `403`; com token de utilizador `403` |
| Eliminar a lista; repetir; a experiência; repetir a chave de criação | `204`; `404`; `200`; `409 COLLECTION_DELETED` |
| `collections_user` | recusado em `experiences_db`, aceite em `collections_db` |
| Logs do collections-service | sem tokens nem nomes de listas |

**Ainda não implementado:**
- listas na app Android;
- "vezes guardada" no dashboard (etapa 13), que exigirá uma contagem agregada (proposta em [arquitetura.md §3.12](arquitetura.md#312-dashboard)).
