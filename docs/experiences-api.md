# experiences-service — Contrato da API

> Contrato das experiências (publicações), das avaliações agregadas e, nas etapas seguintes, das fotografias, comentários, gostos, visualizações, feeds e estatísticas. Contexto geral em [arquitetura.md](arquitetura.md) (§3.5 cópias, §3.6 métricas, §3.7 avaliação, §3.8 autenticação, §3.9 fotografias, §3.10 feeds, §4 falhas).
> Estado: **E1 implementada** (backend e app, etapa 6) **E2 implementada** (fotografias, etapa 7, backend e app) e **E3 implementada** (comentários, gostos e visualizações, etapa 8, backend e app) e **E4 implementada no backend** (feeds e dashboard, etapa 9; a app ainda não os usa). Estado em [§20](#20-estado-da-implementação); decisões em [§19](#19-decisões-da-revisão) e lacunas da E2 resolvidas em [§12.1](#121-endpoints). Lacunas da E3 resolvidas em [§13.7](#137-lacunas-resolvidas-na-revisão); a E4 fica definida e é revista no início da etapa.
> Mesmas convenções do [catalog-api.md](catalog-api.md) e do [users-api.md](users-api.md): JSON UTF-8, nomes de campos em inglês (camelCase), textos em português, datas ISO 8601 UTC, erros em Problem Details com `code`, envelope de paginação comum.

## Índice

1. [Responsabilidade e âmbito](#1-responsabilidade-e-âmbito)
2. [Entidades e responsabilidade pelos dados](#2-entidades-e-responsabilidade-pelos-dados)
3. [Autenticação e autorização](#3-autenticação-e-autorização)
4. [Endpoints da etapa E1](#4-endpoints-da-etapa-e1)
5. [Publicação sem duplicados (idempotência)](#5-publicação-sem-duplicados-idempotência)
6. [Validação da atividade no catálogo](#6-validação-da-atividade-no-catálogo)
7. [Avaliações agregadas](#7-avaliações-agregadas)
8. [Erros](#8-erros)
9. [Paginação e ordenações](#9-paginação-e-ordenações)
10. [Privacidade](#10-privacidade)
11. [Dependências e falhas](#11-dependências-e-falhas)
12. [Etapa E2: fotografias](#12-etapa-e2-fotografias)
13. [Etapa E3: comentários, gostos e visualizações](#13-etapa-e3-comentários-gostos-e-visualizações)
14. [Etapa E4: feeds e dashboard](#14-etapa-e4-feeds-e-dashboard)
15. [Relação com locais reais e substituição do catálogo de demonstração](#15-relação-com-locais-reais-e-substituição-do-catálogo-de-demonstração)
16. [Dados e infraestrutura](#16-dados-e-infraestrutura)
17. [Testes e demonstração](#17-testes-e-demonstração)
18. [Plano incremental](#18-plano-incremental)
19. [Decisões da revisão](#19-decisões-da-revisão)
20. [Estado da implementação](#20-estado-da-implementação)

---

## 1. Responsabilidade e âmbito

O experiences-service é o dono das **experiências** (o conteúdo principal do PortoFit) e de tudo o que delas deriva: estrelas, avaliações agregadas, fotografias, comentários, gostos, visualizações, feeds e estatísticas do autor.

O contrato é completo, mas a implementação divide-se em quatro etapas do serviço:

| Etapa do serviço | Conteúdo | Etapa do projeto ([arquitetura.md §5](arquitetura.md#5-etapas-de-implementação)) |
|---|---|---|
| **E1** | Experiências de texto e estrelas; consulta e pesquisa públicas; edição e eliminação pelo autor; avaliações agregadas ligadas ao catálogo | 6 |
| **E2** | Fotografia opcional: galeria (Photo Picker) e câmara do sistema chamada pela app | 7 |
| **E3** | Comentários, gostos, visualizações e ordenações por gostos e interações | 8 |
| **E4** | Feeds (popular e pessoal) e dashboard do autor | 9 |

As secções 2–11 descrevem em detalhe a etapa **E1**. As secções 12–14 fixam o contrato das etapas seguintes, para que E1 não tome decisões que as impeçam; serão revistas no início de cada uma.

**Fora do âmbito deste serviço:** moderação de conteúdos, denúncias, rascunhos guardados no servidor, mais de uma fotografia por experiência, vídeo, menções e etiquetas, localização GPS da publicação (opcional do projeto, [arquitetura.md §1.3](arquitetura.md#13-âmbito)), partilha (é uma mensagem do messaging-service), listas (collections-service), notificações (eventos via outbox a partir da etapa 10).

- Base URL em desenvolvimento: `http://localhost:8083/api/v1` (no emulador: `http://10.0.2.2:8083/api/v1`).
- Porta 8083, publicada só em `127.0.0.1`, como os outros serviços.

## 2. Entidades e responsabilidade pelos dados

```
Experience N ──── 1 ActivityOffering (catálogo, por offeringId)
Experience N ──── 1 Place            (catálogo, por placeId)
Experience N ──── 1 User             (users-service, por authorId = userId)
Experience 1 ──── N PublishRequest   (idempotência, E1)
Experience 1 ──── 0..1 Media         (fotografia, E2)
Experience 1 ──── N Comment, Like, View (E3)
```

O serviço só lê e escreve a sua base de dados (`experiences_db`). Tudo o que pertence a outros serviços chega por API ou pelo token, nunca por acesso às suas tabelas ([arquitetura.md §3.4](arquitetura.md#34-isolamento-dos-dados)).

### 2.1 Experience — experiência

| Campo | Tipo | Origem | Regras |
|---|---|---|---|
| `id` | UUID | servidor | UUID versão 7 gerado pela base de dados ao publicar, com a função nativa `uuidv7()` do PostgreSQL 18 (não há gerador próprio); ordenado pelo instante de criação; **nunca muda nem é reutilizado**. |
| `authorId` | UUID | token (`sub`) | Referência ao autor. Nunca vem do corpo do pedido. |
| `authorUsername` | string | token (`username`) | **Cópia de apresentação** feita ao publicar. Todos os access tokens do users-service têm a claim `username` (registo, login e renovação). O username não é editável nesta fase ([users-api.md §2.1](users-api.md#21-user--conta-e-perfil)). |
| `offeringId` | UUID | pedido, validado no catálogo | Referência à atividade oferecida. **Não editável.** |
| `placeId` | UUID | pedido, validado no catálogo | Referência ao local; tem de ser o local da atividade (§6). **Não editável.** |
| `sportId` | string | catálogo | Referência copiada ao publicar, para filtrar por modalidade sem chamar o catálogo. |
| `placeName`, `placeDemo`, `offeringName`, `sportName` | string, booleano | catálogo | **Cópias de apresentação** feitas ao publicar ([arquitetura.md §3.5](arquitetura.md#35-cópias-de-dados-desnormalização)). |
| `text` | string | pedido | 1–2000 caracteres, texto simples (§4.1). |
| `stars` | inteiro | pedido | 1 a 5. Avaliação **do local/atividade** pelo autor (não da publicação). |
| `createdAt` | data | servidor | Instante da publicação. Define a ordem `recent` e a avaliação vigente; **nunca muda**, nem ao editar. |
| `editedAt` | data ou null | servidor | Última edição que alterou algum valor; `null` se nunca foi editada. |
| `deletedAt` | data ou null | servidor | Interno: marca de eliminação (§4.5). Nunca aparece nas respostas. |

**Referências e cópias** ([arquitetura.md §3.5](arquitetura.md#35-cópias-de-dados-desnormalização)). `authorId`, `placeId`, `offeringId` e `sportId` são **referências**: identificam, filtram e decidem a autorização e as avaliações, e nunca ficam erradas, porque os ids são estáveis. `authorUsername`, `placeName`, `placeDemo`, `offeringName` e `sportName` são **cópias de apresentação** do momento da publicação. Podem ficar desatualizadas (ex.: o catálogo renomeia uma atividade) e isso só afeta o que se vê. Nunca são usadas para identificar, filtrar por igualdade, autorizar nem calcular avaliações. A pesquisa textual usa-as apenas para encontrar texto (§4.2).

### 2.2 PublishRequest — tentativa de publicação

Registo interno que torna a publicação idempotente (§5). Não é exposto pela API.

| Campo | Regras |
|---|---|
| `authorId`, `idempotencyKey` | Par único. A chave é um UUID gerado pela app por tentativa lógica. |
| `requestHash` | SHA-256 do conteúdo normalizado do pedido (`placeId`, `offeringId`, `text`, `stars` e, a partir de E2, `mediaId`). |
| `experienceId` | Experiência criada por esta tentativa. |
| `createdAt` | Os registos são apagados ao fim de **7 dias** (configurável); justificação em §5. |

### 2.3 Dados de outros serviços

| Dado | Dono | Como chega | Quando |
|---|---|---|---|
| `authorId`, `authorUsername` | users-service | claims `sub` e `username` do access token, validado localmente | ao publicar |
| atividade, local e modalidade (ids e nomes) | catalog-service | `GET /api/v1/offerings/{offeringId}?includeRating=false` | ao publicar |
| seguidos e interesses do utilizador do token | users-service | `GET /internal/me/feed-context` (token do utilizador + `X-Internal-Key`, §14.3) | feed pessoal (E4) |

Consequências:

- **As leituras nunca chamam outro serviço**: listas, detalhe e avaliações respondem só com os dados próprios e as cópias ([arquitetura.md §4.1](arquitetura.md#41-princípios)).
- **O nome do autor nunca é inventado.** Vem do token assinado no momento da publicação e fica guardado. Se o users-service estiver indisponível, a experiência mostra-se na mesma com esse username; o serviço não precisa do users-service para mostrar autores. Não há texto de substituição ("Utilizador desconhecido") nem nomes gerados. Se um token não tiver a claim `username`, a publicação é recusada (`401`), em vez de se guardar um autor sem nome.
- Se o catálogo deixar de conhecer um id, as experiências continuam a mostrar as cópias (§15).

### 2.4 Entidades das etapas seguintes

| Entidade | Etapa | Resumo |
|---|---|---|
| `Media` | E2 | Fotografia: `mediaId` (UUID gerado), `ownerId`, `experienceId` (null enquanto pendente), largura, altura, tamanho, `createdAt`. O ficheiro vive num volume; a base de dados guarda só o id (§12). |
| `Comment` | E3 | `id`, `experienceId`, `authorId`, `authorUsername` (cópia do token), `text` (1–1000), `createdAt`, `editedAt`, `deletedAt`; tentativas de comentário com chave de idempotência, como `PublishRequest` (§13.1). |
| `Like` | E3 | Par único (`experienceId`, `userId`), `createdAt`. |
| `View` | E3 | `experienceId`, hash de quem vê (pessoa ou chave de instalação), início do período, `createdAt` (§13.5). |
| `Outbox` | etapa 10 | Eventos para o notifications-service ([arquitetura.md §3.11](arquitetura.md#311-notificações)). |

## 3. Autenticação e autorização

- **Validação local do JWT.** O serviço valida os access tokens emitidos pelo users-service com a **chave pública**, recebida por configuração (ficheiro montado só de leitura a partir de `secrets/users-jwt/public.pem`). Verifica a assinatura RS256 (outros algoritmos, incluindo `none`, são rejeitados), `iss = portofit-users`, `aud = portofit-api` e `exp` (e `nbf`/`iat` se presentes), com tolerância de 60 s, como em [users-api.md §6.3](users-api.md#63-access-token-jwt). **Não consulta o users-service** em nenhum pedido; nunca recebe a chave privada.
- **Públicos (sem sessão):** consultar, listar e pesquisar experiências; avaliações agregadas. A partir de E2, fotografias de experiências publicadas; a partir de E3, comentários e contagens; a partir de E4, o feed popular.
- **Exigem access token:** publicar, editar e eliminar (E1); fotografias (E2); comentar, gostar e registar visualizações autenticadas (E3); feed pessoal e dashboard (E4).
- **Token num endpoint público:** pedido sem token → visitante. Token inválido ou expirado → `401 INVALID_ACCESS_TOKEN`, como no users-service ([users-api.md §3.8](users-api.md#38-get-usersuserid--perfil-público)); um token inválido nunca é tratado como visitante. Em E1 nenhuma leitura depende de quem pede, por isso a app pode pedir sem token.
- **Autorização por dono:** só o autor (`sub` do token igual a `authorId`) edita ou elimina a sua experiência. O `authorId` vem sempre do token, nunca do corpo.
- **Limites aceites** ([arquitetura.md §3.8](arquitetura.md#38-autenticação-e-autorização)): um access token continua aceite até expirar (máx. 15 min), mesmo depois do logout; sem users-service, quem tem token válido pode publicar até ele expirar.
- **Endpoints internos:** o experiences-service não expõe nenhum. Em E4 passa a **chamar** um do users-service (`/internal/me/feed-context`), com o token do utilizador e `X-Internal-Key` (§14.3).

## 4. Endpoints da etapa E1

| Método | Caminho | Acesso | Descrição |
|---|---|---|---|
| POST | `/experiences` | access token + `Idempotency-Key` | Publicar |
| GET | `/experiences` | público | Listar e pesquisar, com filtros, paginado |
| GET | `/experiences/{experienceId}` | público | Detalhe |
| PATCH | `/experiences/{experienceId}` | access token (autor) | Editar texto e/ou estrelas |
| DELETE | `/experiences/{experienceId}` | access token (autor) | Eliminar |
| GET | `/ratings/places?ids=…&includeOfferings=…` | público | Avaliações agregadas em lote (para o catálogo) |
| GET | `/actuator/health` | público | Estado do serviço (fora de `/api/v1`) |

Exemplos com os dados de demonstração do catálogo: local "Clube de Combate Ribeira (fictício)" (`3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01`) e atividade "Boxe iniciação" (`8a7b6c5d-4e3f-4a1b-9c8d-000000000101`). Utilizador e ids de experiência ilustrativos.

### 4.1 `POST /experiences` — publicar

```http
POST /api/v1/experiences
Authorization: Bearer eyJ…
Idempotency-Key: 5d0c7f5e-2a41-4b8e-9f13-6c2b8e1d4a70
Content-Type: application/json

{
  "placeId": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01",
  "offeringId": "8a7b6c5d-4e3f-4a1b-9c8d-000000000101",
  "text": "Primeira aula de boxe. Muito trabalho de pés e explicações claras.",
  "stars": 4
}
```

**`201 Created`**, com o cabeçalho `Location: /api/v1/experiences/{id}`:

```json
{
  "id": "019a0c4e-5b2f-7a31-8c4d-2e6f8a0b1c3d",
  "author": { "userId": "6f9a2c1e-3b7d-4e8a-9c51-2d4f6a8b0e13", "username": "ana.silva" },
  "place": { "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01", "name": "Clube de Combate Ribeira (fictício)", "demo": true },
  "offering": { "id": "8a7b6c5d-4e3f-4a1b-9c8d-000000000101", "name": "Boxe iniciação" },
  "sport": { "id": "boxe", "name": "Boxe" },
  "text": "Primeira aula de boxe. Muito trabalho de pés e explicações claras.",
  "stars": 4,
  "createdAt": "2026-10-20T18:30:00Z",
  "editedAt": null
}
```

Uma repetição da mesma tentativa responde `200` com o mesmo formato (§5). Este é o formato `Experience` usado em todas as respostas. Campos acrescentados nas etapas seguintes (a app ignora campos desconhecidos): `photo` (E2); `likesCount`, `commentsCount`, `likedByMe` (E3).

**Validações**

| Campo | Regra | Mensagem |
|---|---|---|
| `Idempotency-Key` (cabeçalho) | obrigatório; UUID | "Falta a chave de idempotência do pedido." |
| `placeId` | obrigatório; UUID | "Indica o local." |
| `offeringId` | obrigatório; UUID | "Indica a atividade." |
| `text` | obrigatório; depois de converter `\r\n` e `\r` em `\n` e de remover espaços nas pontas, entre 1 e 2000 caracteres (pontos de código Unicode). Texto só com espaços ou mudanças de linha fica vazio e é rejeitado. Sem caracteres de controlo além de `\n` e `\t` | "O texto deve ter entre 1 e 2000 caracteres." |
| `stars` | obrigatório; número inteiro de 1 a 5 (`4.5`, `"4"`, `0`, `6` e `null` são rejeitados) | "As estrelas devem ser um número inteiro de 1 a 5." |
| outros campos | não permitidos (ex.: `authorId`, `createdAt`, `id`) | "Este campo não pode ser enviado." |
| corpo | até 16 KiB | `413` |

- O texto é **texto simples**: é guardado tal como foi escrito (depois da normalização acima) e a app mostra-o como texto, sem interpretar HTML ou Markdown.
- Ordem do processamento: autenticação → validação do pedido → repetição de uma chave já usada (§5, sem consultar o catálogo) → limite de publicações → validação no catálogo (§6) → gravação.
- Erros possíveis: `400 VALIDATION_FAILED`, `400 UNKNOWN_OFFERING`, `400 OFFERING_PLACE_MISMATCH`, `400 OFFERING_RETIRED`, `401`, `409 IDEMPOTENCY_CONFLICT`, `413`, `415`, `429 TOO_MANY_REQUESTS`, `503 CATALOG_UNAVAILABLE` (§8).

A mesma pessoa pode publicar várias vezes sobre a mesma atividade; só a mais recente conta para a avaliação (§7).

**Limite de publicações por autor.** Cada autor pode criar no máximo **30 experiências por hora** (configurável: `portofit.publishing.max-per-author` e `portofit.publishing.window`). Acima disso: `429 TOO_MANY_REQUESTS` com `Retry-After` (segundos até ao fim da janela), sem criar nada nem gastar a chave de idempotência. Só contam publicações efetivamente criadas: repetições idempotentes, pedidos recusados, edições e eliminações não contam.

O contador é **em memória**, como os do users-service ([users-api.md §6.8](users-api.md#68-proteção-contra-tentativas-repetidas)), com as mesmas limitações:

- perde-se ao reiniciar o serviço;
- não é partilhado entre instâncias;
- a janela fixa permite até ao dobro do limite em torno da mudança de janela;
- pedidos simultâneos do mesmo autor podem ultrapassar o limite em poucas unidades, porque a verificação e a contagem não são atómicas com a gravação.

Serve para travar abusos e erros da app, não como garantia exata. Um contador na base de dados fica para quando houver mais de uma instância.

### 4.2 `GET /experiences` — listar e pesquisar

| Parâmetro | Tipo | Por omissão | Validação | Descrição |
|---|---|---|---|---|
| `placeId` | UUID | — | UUID válido | Experiências de um local |
| `offeringId` | UUID | — | UUID válido | Experiências de uma atividade |
| `sportId` | string | — | formato de slug do catálogo | Experiências de uma modalidade (categoria) |
| `authorId` | UUID | — | UUID válido | Publicações de um perfil |
| `q` | string | — | 2–100 caracteres após remover espaços nas pontas | Pesquisa textual |
| `sort` | enum | `recent` | em E1: `recent`, `stars` | Ordenação (§9) |
| `page` | inteiro | `0` | ≥ 0 | Página |
| `size` | inteiro | `20` | 1–50 | Elementos por página |

- Todos os filtros são opcionais e combinam-se com **E**. Sem filtros, devolve todas as experiências (usado pela pesquisa unificada da app).
- Parâmetros vazios são tratados como ausentes, como no catálogo.
- **Ids desconhecidos não são erro:** um `placeId`, `sportId` ou `authorId` sem experiências devolve `items: []`. O serviço não chama o catálogo nem o users-service para confirmar que existem.
- `q` procura no **texto** e nas **cópias** do nome do local, da atividade e da modalidade, **sem distinguir maiúsculas/minúsculas nem acentos** ([arquitetura.md §3.2](arquitetura.md#32-responsabilidades)): `q=boxe` encontra experiências de Boxe mesmo que o texto não diga "boxe". `%` e `_` são literais. Não procura no username do autor (para isso existe a pesquisa de utilizadores).
- `sort=likes` e `sort=interactions` só existem a partir de E3; em E1 dão `400 VALIDATION_FAILED` no campo `sort`.
- Experiências eliminadas nunca aparecem.

```http
GET /api/v1/experiences?placeId=3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01&sort=stars&size=2
```

```json
{
  "items": [
    {
      "id": "019a0c4e-5b2f-7a31-8c4d-2e6f8a0b1c3d",
      "author": { "userId": "6f9a2c1e-3b7d-4e8a-9c51-2d4f6a8b0e13", "username": "ana.silva" },
      "place": { "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01", "name": "Clube de Combate Ribeira (fictício)", "demo": true },
      "offering": { "id": "8a7b6c5d-4e3f-4a1b-9c8d-000000000101", "name": "Boxe iniciação" },
      "sport": { "id": "boxe", "name": "Boxe" },
      "text": "Primeira aula de boxe. Muito trabalho de pés e explicações claras.",
      "stars": 4,
      "createdAt": "2026-10-20T18:30:00Z",
      "editedAt": null
    },
    {
      "id": "019a0b11-0d7e-7c52-a3f9-5b1e2c3d4f60",
      "author": { "userId": "0c4e8a2b-1d3f-4b6a-8e9c-7f1a2b3c4d5e", "username": "joana_corre" },
      "place": { "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01", "name": "Clube de Combate Ribeira (fictício)", "demo": true },
      "offering": { "id": "8a7b6c5d-4e3f-4a1b-9c8d-000000000102", "name": "Kickboxing aberto" },
      "sport": { "id": "kickboxing", "name": "Kickboxing" },
      "text": "Ambiente descontraído, mas o aquecimento é curto.",
      "stars": 3,
      "createdAt": "2026-10-19T09:12:00Z",
      "editedAt": "2026-10-19T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 2,
  "totalItems": 5,
  "totalPages": 3
}
```

### 4.3 `GET /experiences/{experienceId}` — detalhe

`200` com o formato `Experience`. `404 NOT_FOUND` se não existir ou tiver sido eliminada (a resposta é a mesma nos dois casos). `400 VALIDATION_FAILED` se o id não for um UUID. Em E1 este pedido não tem efeitos (as visualizações são registadas à parte em E3, §13).

### 4.4 `PATCH /experiences/{experienceId}` — editar

```http
PATCH /api/v1/experiences/019a0c4e-5b2f-7a31-8c4d-2e6f8a0b1c3d
Authorization: Bearer eyJ…
Content-Type: application/json

{ "stars": 5 }
```

`200` com a experiência atualizada.

- Editáveis: `text` e `stars`, com as regras do §4.1. Pelo menos um deles tem de vir no pedido. A partir de E2, também `mediaId` (§12).
- `placeId`, `offeringId`, `authorId`, `createdAt` e outros campos dão `400 VALIDATION_FAILED` no campo respetivo. Para `placeId`/`offeringId` a mensagem é "A atividade não pode ser alterada. Apaga a experiência e publica uma nova."
- `editedAt` passa a ser o instante do pedido só se algum valor mudar. Um pedido com os valores atuais responde `200` sem alterar nada, o que torna a repetição segura (o pedido é idempotente e não precisa de chave).
- `createdAt` nunca muda: editar não faz subir a experiência em `recent` nem a torna vigente (§7.3).
- Edições concorrentes do mesmo autor: prevalece a última a chegar (sem controlo de versões nesta fase).
- `401` sem token; `403 FORBIDDEN` se o token for de outra pessoa ("Só o autor pode alterar esta experiência."); `404` se não existir ou estiver eliminada.

### 4.5 `DELETE /experiences/{experienceId}` — eliminar

`204 No Content`.

- Só o autor. Outra pessoa recebe `403 FORBIDDEN` (a experiência é pública, por isso não se esconde que existe). Sem token: `401`.
- **Idempotente para o autor:** eliminar de novo uma experiência já eliminada dá `204`, para que a app possa repetir quando a resposta se perde. Para outra pessoa, uma experiência eliminada dá `404`.
- **Eliminação com marca:** a linha fica com `deletedAt` e o conteúdo é **apagado** (texto, estrelas e, a partir de E2, a fotografia). Ficam só os identificadores e as datas, necessários para a idempotência e para que listas e mensagens que a referenciam mostrem "conteúdo removido". A experiência deixa de aparecer em qualquer resposta pública e deixa logo de contar para as avaliações (§7.3).
- A partir de E3, comentários, gostos e visualizações da experiência são apagados com ela.

### 4.6 `GET /ratings/places` — avaliações agregadas em lote

Usado pelo catalog-service para acrescentar `rating` às suas respostas com **um único pedido por resposta** ([catalog-api.md §5](catalog-api.md#5-dependência-do-experiences-service)). Público: as avaliações são dados públicos.

| Parâmetro | Validação | Descrição |
|---|---|---|
| `ids` | 1–50 UUIDs separados por vírgulas; repetições ignoradas | Locais pedidos (o máximo coincide com o tamanho máximo de página do catálogo) |
| `includeOfferings` | `true` ou `false` (por omissão `false`) | Acrescenta a avaliação de cada atividade |

```http
GET /api/v1/ratings/places?ids=3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01,3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e06&includeOfferings=true
```

```json
{
  "items": [
    {
      "placeId": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01",
      "rating": { "status": "AVAILABLE", "average": 3.2, "ratingsCount": 3 },
      "offerings": [
        { "offeringId": "8a7b6c5d-4e3f-4a1b-9c8d-000000000101", "rating": { "status": "AVAILABLE", "average": 3.5, "ratingsCount": 2 } },
        { "offeringId": "8a7b6c5d-4e3f-4a1b-9c8d-000000000102", "rating": { "status": "AVAILABLE", "average": 3.0, "ratingsCount": 2 } }
      ]
    },
    {
      "placeId": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e06",
      "rating": { "status": "NO_RATINGS", "average": null, "ratingsCount": 0 },
      "offerings": []
    }
  ]
}
```

(Valores do exemplo de [arquitetura.md §3.7](arquitetura.md#37-avaliação-geral-sem-distorção), com Kickboxing no lugar de Ioga.)

- A resposta tem um item por id pedido, pela ordem do pedido. Um local sem avaliações vigentes vem com `NO_RATINGS`, e o mesmo acontece com um id que o serviço não conhece: o serviço não sabe se o local existe e não o pergunta ao catálogo.
- `offerings` (com `includeOfferings=true`) tem só as atividades com pelo menos uma avaliação vigente. Para as outras, o catálogo usa `NO_RATINGS` (regra "ausente na resposta → `NO_RATINGS`" do [catalog-api.md §5](catalog-api.md#5-dependência-do-experiences-service)). Com `includeOfferings=false` o campo é omitido.
- Este serviço só devolve `AVAILABLE` ou `NO_RATINGS`. **`UNAVAILABLE` é decidido pelo catálogo** quando este pedido falha ou excede o prazo.
- A avaliação de uma única atividade (detalhe `GET /offerings/{id}` do catálogo) obtém-se com `ids=<placeId>&includeOfferings=true`; não é preciso outro endpoint.

## 5. Publicação sem duplicados (idempotência)

**Problema:** a app envia `POST /experiences`, o servidor publica, mas a resposta perde-se (timeout, ligação cortada, app terminada). Sem proteção, a nova tentativa criaria uma segunda experiência igual.

**Regra:** cada **tentativa lógica** de publicação tem uma chave `Idempotency-Key` (UUID gerado pela app). Repetir a mesma tentativa usa a mesma chave; o servidor cria no máximo uma experiência por (`authorId`, chave).

| Situação | Resposta |
|---|---|
| Chave nova, pedido válido | Publica: **`201 Created`**. Experiência e registo da tentativa ficam gravados **na mesma transação**. |
| Mesma chave, mesmo conteúdo (`requestHash` igual), experiência existe | **Não publica de novo.** **`200 OK`** com a mesma experiência (no estado atual, incluindo edições feitas entretanto), o mesmo `Location` e o cabeçalho **`Idempotent-Replayed: true`**. O catálogo não é consultado e o limite de publicações não conta. |
| Mesma chave, experiência entretanto eliminada (qualquer conteúdo) | `409 IDEMPOTENCY_CONFLICT` com `"reason": "EXPERIENCE_DELETED"`. **Não recria** a experiência nem devolve o conteúdo apagado (nem `experienceId`). |
| Mesma chave, conteúdo diferente, experiência existe | `409 IDEMPOTENCY_CONFLICT` com `"reason": "DIFFERENT_CONTENT"` e o `experienceId` da experiência já criada por essa chave. Nada é alterado. |
| Pedido recusado (validação, catálogo indisponível, limite, erro) | Nada é gravado, nem a chave: a mesma chave pode ser usada na nova tentativa. |
| Dois pedidos simultâneos com a mesma chave | A chave primária (`authorId`, chave) garante uma só publicação: o segundo pedido fica à espera do primeiro na própria inserção e, quando este confirma, é tratado como repetição (`200` ou `409`). Se o primeiro falhar e for desfeito, o segundo publica. |
| A mesma chave usada por outra pessoa | Independente: as chaves são por autor. |

- O `requestHash` é calculado sobre o conteúdo **depois da normalização** do §4.1, por isso diferenças só de espaços nas pontas ou de fim de linha não contam como conteúdo diferente.
- `PATCH` e `DELETE` são idempotentes por natureza (§4.4, §4.5) e não usam chave.

**Retenção de 7 dias e o que fica fora dela.** A garantia contra duplicação só existe enquanto o registo da tentativa existir. Passados 7 dias, a mesma chave é tratada como nova e **pode criar um duplicado**. O prazo é um compromisso:

- cobre com folga as repetições reais: a app só repete uma tentativa a pedido do utilizador, com o formulário ainda aberto ou restaurado depois de a app ter sido terminada, o que acontece em minutos ou horas;
- mantém a tabela pequena (uma linha por publicação recente, apagada por uma tarefa periódica);
- não é configurado em horas para não depender de o utilizador voltar à app no mesmo dia.

Para não depender de o servidor ainda ter a chave, a app guarda a data em que gerou a chave. Se o rascunho por publicar tiver uma chave com mais de 6 dias, a app não a reutiliza: pede ao utilizador para confirmar no seu perfil se a publicação já existe e só depois publica com uma chave nova.

**Comportamento da app** (implementado na etapa 6; [app-android.md §14](app-android.md#14-experiências-etapa-6-e1-sem-fotografias)):

- Gera a chave no primeiro envio e guarda-a com o conteúdo enviado e a data no `SavedStateHandle` **antes** de enviar. Assim sobrevive à rotação e à recriação do processo, e uma app terminada a meio do pedido fica com a tentativa por confirmar.
- Resultado desconhecido (sem resposta, timeout, `5xx` que não seja `CATALOG_UNAVAILABLE`, ou `2xx` ilegível): mantém a chave e **bloqueia o texto e as estrelas**. **Não repete sozinha**: só "Tentar novamente" (um toque do utilizador) envia de novo, com a mesma chave e o mesmo conteúdo. A app **nunca envia a mesma chave com conteúdo diferente**.
- Para alterar o conteúdo depois de um resultado desconhecido, o utilizador tem de descartar a tentativa, com uma confirmação que o avisa para ver primeiro no perfil se a publicação já existe. Só então pode editar e publicar com uma chave nova.
- Depois de um `201` ou de um `200` com `Idempotent-Replayed: true`, descarta a chave e o rascunho. Uma nova publicação gera uma chave nova.
- Uma chave com mais de 6 dias não é reutilizada: a app pede para confirmar no perfil e descartar a tentativa.
- `409 DIFFERENT_CONTENT` (só aconteceria com outra versão da app): mostra "A tua publicação anterior já tinha sido aceite", com a opção de a abrir, e descarta a chave.
- `409 EXPERIENCE_DELETED`: a publicação tinha sido feita e foi eliminada entretanto. A app descarta a chave (sem recriar nada); o texto fica no formulário e o utilizador pode publicar de novo, como uma tentativa nova.
- `400`, `401`, `429` e `503 CATALOG_UNAVAILABLE` não gravaram nada: a app descarta a chave e mantém o formulário para corrigir ou tentar de novo (com uma chave nova).

## 6. Validação da atividade no catálogo

### 6.1 Endpoint do catálogo

A validação usa `GET /api/v1/offerings/{offeringId}`, já previsto e **pendente** no catálogo ([catalog-api.md §3.4](catalog-api.md#34-get-offeringsofferingid)). Tem de ser implementado no início da etapa 6, antes da publicação. Para este uso o experiences-service envia **`includeRating=false`**: o catálogo responde sem `rating` e **não chama o experiences-service**. Assim, uma publicação nunca desencadeia uma chamada de volta ao serviço que a está a processar.

Campos usados da resposta: `id`, `name`, `status`, `sport.id`, `sport.name`, `place.id`, `place.name`, `place.demo`. O `status` é `ACTIVE` ou `RETIRED`. Uma atividade é `RETIRED` se ela ou o seu local tiverem sido retirados do catálogo ([catalog-api.md §2.3](catalog-api.md#23-activityoffering--atividade-oferecida), §15).

### 6.2 Regras ao publicar

1. Pedido validado (§4.1).
2. Atividade obtida do catálogo (ou da memória, ver abaixo).
3. Catálogo responde `404` → `400 UNKNOWN_OFFERING` no campo `offeringId` ("Esta atividade não existe no catálogo.").
4. `place.id` da atividade diferente do `placeId` enviado → `400 OFFERING_PLACE_MISMATCH` nos campos `placeId` e `offeringId` ("Esta atividade não pertence ao local indicado."). Nada é corrigido automaticamente.
5. `status` = `RETIRED` → `400 OFFERING_RETIRED` no campo `offeringId` ("Esta atividade já não está disponível no catálogo.").
6. Tudo certo → copia `sportId` e os nomes (§2.1) e grava.

- **Prazos:** ligação ~0,5 s e resposta ~2 s, **sem novas tentativas automáticas** ([arquitetura.md §4.1](arquitetura.md#41-princípios)).
- **Memória:** cada resposta `200` do catálogo (atividade ativa ou retirada) fica em memória até **10 minutos** (`portofit.catalog.cache-ttl`), como as modalidades no users-service. Uma entrada mais antiga não é usada. Os `404` e as falhas não ficam em memória.

**Compromisso da memória: dados com até 10 minutos de atraso.** Durante esse tempo, uma alteração no catálogo pode ainda não ser vista ao publicar:

- uma atividade retirada pode ainda aceitar publicações;
- uma atividade reativada pode ainda ser recusada;
- uma atividade mudada de local seria validada contra o local antigo;
- os nomes copiados podem ser os anteriores.

Aceita-se porque o catálogo é só de leitura nesta fase (as alterações são raras e feitas por quem gere os dados, não pelos utilizadores), e porque a memória permite publicar com o catálogo momentaneamente indisponível. As referências gravadas continuam corretas, porque os ids não mudam. Quem retirar dados do catálogo deve contar com até 10 minutos até o efeito chegar às publicações (ou reiniciar o experiences-service, que esvazia a memória).

### 6.3 Catálogo indisponível

Sem resposta no prazo, erro de ligação ou resposta `5xx`, e sem a atividade em memória:

| Pedido | Resultado |
|---|---|
| Publicar | `503 CATALOG_UNAVAILABLE`, sem criar nada nem gastar a chave de idempotência; a app mantém o formulário e mostra "Não é possível publicar agora: o catálogo está indisponível. Tenta novamente daqui a pouco." |
| Publicar numa atividade validada há menos de 10 min | aceite ou recusada conforme a resposta em memória (incluindo `RETIRED`) |
| Repetir uma publicação já aceite (mesma chave) | `200` com a experiência; a repetição não consulta o catálogo (§5) |
| Editar texto/estrelas, eliminar, listar, pesquisar, detalhe, avaliações | não dependem do catálogo |

## 7. Avaliações agregadas

### 7.1 Regra

Mantém-se a regra de [arquitetura.md §3.7](arquitetura.md#37-avaliação-geral-sem-distorção) (peso 1 por pessoa):

1. **Avaliação vigente** de uma pessoa P numa atividade A: as estrelas da experiência **não eliminada** de P sobre A com o `createdAt` mais recente.
2. **Atividade:** média das avaliações vigentes das pessoas com avaliação vigente em A; `ratingsCount` = número dessas pessoas.
3. **Contribuição de P para o local L:** média das avaliações vigentes de P nas atividades de L.
4. **Local:** média das contribuições das pessoas com pelo menos uma avaliação vigente em L; `ratingsCount` = pessoas distintas.
5. Sem arredondamentos intermédios; arredondamento a **uma casa decimal só na resposta, meio para cima** (3,25 → 3,3; 3,249… → 3,2).
6. Sem avaliações vigentes → `NO_RATINGS` (nunca 0 estrelas).

Os gostos, comentários e visualizações (E3) nunca entram nesta conta.

### 7.2 Desempate determinístico

Se duas experiências de P sobre A tiverem exatamente o mesmo `createdAt` (raro: pedidos simultâneos, ou importação de dados), a vigente é a de **`id` maior**, comparando os UUID como o PostgreSQL os compara (byte a byte). Os ids são UUID versão 7, gerados pelo servidor e ordenados pelo instante de criação, por isso este desempate costuma coincidir com a ordem de criação. Em qualquer caso o resultado é sempre o mesmo para os mesmos dados.

A mesma ordem (`createdAt` e depois `id`, ambos decrescentes) é usada em `sort=recent`.

### 7.3 Efeito de editar e eliminar

| Ação do autor | Efeito |
|---|---|
| Edita o texto (ou, em E2, a fotografia) | Nenhum |
| Edita as estrelas da experiência **vigente** em A | O novo valor conta de imediato, na atividade e no local |
| Edita as estrelas de uma experiência **mais antiga** sobre A | Nenhum enquanto existir uma mais recente; o valor editado passa a contar se a mais recente for eliminada |
| Quer mudar a atividade | Não é permitido (§4.4): elimina e publica de novo. A nova é a mais recente e passa a ser a vigente na nova atividade |
| Elimina a experiência vigente em A | Passa a contar a anterior mais recente de P sobre A, se existir; senão P deixa de contar em A (e no local, se não tiver outra atividade avaliada lá) |
| Elimina uma experiência que não é a vigente | Nenhum |

A vigente escolhe-se sempre pelo `createdAt`, que não muda ao editar.

### 7.4 Cálculo

- **Calculado em cada pedido** a partir dos dados atuais (sem tabela de agregados nem cache): qualquer publicação, edição ou eliminação reflete-se no pedido seguinte. Volume esperado num projeto académico: dezenas a centenas de experiências.
- Uma consulta por pedido em lote, qualquer que seja o número de locais (até 50): seleciona as vigentes por (pessoa, atividade) **entre os locais pedidos**, com `DISTINCT ON` e a ordem do §7.2, e agrega por atividade e por local. Índice parcial sobre experiências não eliminadas, por (`place_id`, `author_id`, `offering_id`, `created_at`, `id`).
- Se for preciso, uma cache de poucos segundos no catálogo ([catalog-api.md §5](catalog-api.md#5-dependência-do-experiences-service)) é a primeira melhoria; o experiences-service não guarda agregados nesta fase.

### 7.5 Integração com o catálogo

| Ecrã do catálogo | Pedido do catálogo ao experiences-service |
|---|---|
| Pesquisa de locais (`GET /places`) | `ids` = locais da página, `includeOfferings=false` |
| Detalhe do local (`GET /places/{id}`) | `ids` = o local, `includeOfferings=true` |
| Detalhe da atividade (`GET /offerings/{id}`) | igual ao detalhe do local, ou nenhum pedido com `includeRating=false` |

O catálogo mantém os três estados `AVAILABLE`, `NO_RATINGS` e `UNAVAILABLE`. Se este pedido falhar (prazo ~1 s, erro ou `5xx`), o catálogo responde `200` com os seus dados completos e todos os `rating` a `UNAVAILABLE`. **A pesquisa e o detalhe do catálogo nunca falham por causa das avaliações.**

**Sem dependência circular obrigatória:** o catálogo funciona sem o experiences-service (avaliações `UNAVAILABLE`), e o experiences-service funciona sem o catálogo para tudo exceto validar publicações novas. O pedido de validação (`includeRating=false`) não leva o catálogo a chamar de volta, e o endpoint de avaliações não chama o catálogo. Nenhum dos dois espera pelo outro no arranque.

## 8. Erros

Problem Details (`application/problem+json`) com `code` estável e, nos erros de validação, a lista `errors` (`field`, `message`), como no users-service ([users-api.md §4](users-api.md#4-erros)). Nenhuma resposta de erro inclui tokens nem o texto enviado.

| HTTP | `code` | Quando | Etapa |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | parâmetros, cabeçalhos ou corpo inválidos (com `errors`) | E1 |
| 400 | `UNKNOWN_OFFERING` | atividade inexistente no catálogo (com `errors`) | E1 |
| 400 | `OFFERING_PLACE_MISMATCH` | a atividade não pertence ao local indicado (com `errors`) | E1 |
| 400 | `OFFERING_RETIRED` | a atividade (ou o seu local) foi retirada do catálogo (com `errors`) | E1 |
| 400 | `MEDIA_NOT_USABLE` | `mediaId` inexistente, de outra pessoa, já usado ou expirado | E2 |
| 400 | `INVALID_IMAGE` | o ficheiro não é JPEG/PNG, está danificado ou fora das dimensões (§12.2) | E2 |
| 400 | `CANNOT_LIKE_OWN` | gostar da própria experiência (§13) | E3 |
| 401 | `AUTHENTICATION_REQUIRED` | falta o access token | E1 |
| 401 | `INVALID_ACCESS_TOKEN` | token inválido ou expirado (`WWW-Authenticate: Bearer error="invalid_token"`) | E1 |
| 403 | `FORBIDDEN` | não é o autor (da experiência ou do comentário); chave interna errada | E1 |
| 404 | `NOT_FOUND` | experiência (ou comentário, fotografia) inexistente ou eliminada | E1 |
| 405 | `METHOD_NOT_ALLOWED` | método não suportado | E1 |
| 409 | `IDEMPOTENCY_CONFLICT` | chave já usada: `reason` = `DIFFERENT_CONTENT` (com `experienceId`) ou `EXPERIENCE_DELETED` (§5); nos comentários, `DIFFERENT_CONTENT` (com `commentId`) ou `COMMENT_DELETED` (§13.1) | E1 |
| 413 | `PAYLOAD_TOO_LARGE` | corpo acima do limite (16 KiB em JSON; 10 MiB em fotografias) | E1 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | tipo de conteúdo do pedido não suportado | E1 |
| 429 | `TOO_MANY_REQUESTS` | limite de publicações por autor (§4.1), com `Retry-After`; a partir de E2, também o limite de fotografias pendentes (§12); em E3, comentários e mudanças de gosto (§13.6) | E1 |
| 500 | `INTERNAL_ERROR` | erro inesperado, sem detalhes internos | E1 |
| 503 | `CATALOG_UNAVAILABLE` | não foi possível validar a atividade (§6.3) | E1 |
| 503 | `SERVICE_UNAVAILABLE` | base de dados indisponível; em E2, também o armazenamento de fotografias, o ficheiro de uma fotografia em falta, ou demasiadas imagens a processar | E1 |

**Validação ao publicar**

```json
{
  "title": "Pedido inválido",
  "status": 400,
  "detail": "Um ou mais campos são inválidos.",
  "instance": "/api/v1/experiences",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "text", "message": "O texto deve ter entre 1 e 2000 caracteres." },
    { "field": "stars", "message": "As estrelas devem ser um número inteiro de 1 a 5." }
  ]
}
```

**Atividade de outro local**

```json
{
  "title": "Pedido inválido",
  "status": 400,
  "detail": "Esta atividade não pertence ao local indicado.",
  "instance": "/api/v1/experiences",
  "code": "OFFERING_PLACE_MISMATCH",
  "errors": [
    { "field": "placeId", "message": "Esta atividade não pertence ao local indicado." },
    { "field": "offeringId", "message": "Esta atividade não pertence ao local indicado." }
  ]
}
```

**Catálogo indisponível**

```json
{
  "title": "Serviço indisponível",
  "status": 503,
  "detail": "Não é possível publicar agora: o catálogo está indisponível. Tenta novamente daqui a pouco.",
  "instance": "/api/v1/experiences",
  "code": "CATALOG_UNAVAILABLE"
}
```

**Chave reutilizada com outro conteúdo**

```json
{
  "title": "Publicação já feita",
  "status": 409,
  "detail": "Esta tentativa de publicação já tinha sido aceite com outro conteúdo.",
  "instance": "/api/v1/experiences",
  "code": "IDEMPOTENCY_CONFLICT",
  "reason": "DIFFERENT_CONTENT",
  "experienceId": "019a0c4e-5b2f-7a31-8c4d-2e6f8a0b1c3d"
}
```

**Chave de uma publicação entretanto eliminada**

```json
{
  "title": "Publicação já feita",
  "status": 409,
  "detail": "Esta publicação já tinha sido feita e foi apagada.",
  "instance": "/api/v1/experiences",
  "code": "IDEMPOTENCY_CONFLICT",
  "reason": "EXPERIENCE_DELETED"
}
```

**Limite de publicações**

```http
HTTP/1.1 429
Retry-After: 1740
Content-Type: application/problem+json
```

```json
{
  "title": "Demasiadas publicações",
  "status": 429,
  "detail": "Atingiste o limite de publicações por agora. Tenta novamente mais tarde.",
  "instance": "/api/v1/experiences",
  "code": "TOO_MANY_REQUESTS"
}
```

**Editar a experiência de outra pessoa**

```json
{
  "title": "Sem permissão",
  "status": 403,
  "detail": "Só o autor pode alterar esta experiência.",
  "instance": "/api/v1/experiences/019a0c4e-5b2f-7a31-8c4d-2e6f8a0b1c3d",
  "code": "FORBIDDEN"
}
```

## 9. Paginação e ordenações

Envelope e limites comuns (`items`, `page`, `size`, `totalItems`, `totalPages`; `page` ≥ 0; `size` 1–50, por omissão 20; página para além do fim → `items: []`). Paginação por *offset*, como no catálogo e no users-service.

| `sort` | Etapa | Ordem | Desempate |
|---|---|---|---|
| `recent` (por omissão) | E1 | `createdAt` mais recente primeiro | `id` decrescente |
| `stars` | E1 | estrelas (do autor ao local/atividade) maior primeiro | `createdAt` e `id` decrescentes |
| `likes` | E3 | gostos maior primeiro | `createdAt` e `id` decrescentes |
| `interactions` | E3 | gostos + comentários ativos maior primeiro | `createdAt` e `id` decrescentes |

- Todas as ordenações são totais (terminam no `id`) e estão disponíveis a visitantes ([arquitetura.md §3.6](arquitetura.md#36-métricas-e-ordenações)).
- Como em [users-api.md §5](users-api.md#5-paginação), ordem total não significa páginas imutáveis: uma publicação nova entra no topo de `recent` e desloca as páginas seguintes, e uma eliminação desloca-as para trás. A app remove repetições pelo `id`. Paginação por cursor fica como melhoria possível.
- `stars` ordena opiniões sobre o **local/atividade**; não é uma ordenação de qualidade da publicação. A app mostra-a como "Melhor avaliação do local".

## 10. Privacidade

- As experiências são **públicas**: texto, estrelas, datas, local e atividade, e o autor como `userId` e `username` (dados já públicos no users-service). **Nunca** email nem outros dados privados: o serviço nem os recebe (o token não tem email).
- Eliminar apaga o conteúdo (§4.5); a marca restante não é exposta.
- Fotografias (E2): os metadados (incluindo localização GPS) são removidos antes de guardar (§12).
- Visualizações (E3): guardam só o hash de quem viu (pessoa ou chave de instalação) e o período, nunca o IP; nunca são expostas por pessoa, só como totais no dashboard do próprio autor (§13.5, §14.4). Os feeds nunca as usam nem mostram.
- Logs: nunca tokens nem o cabeçalho `Authorization`; o texto das experiências e comentários também não é registado. Utilizadores identificados pelo `userId`.
- O `authorId` permite listar as publicações de uma pessoa, o mesmo que o perfil público mostra; não há publicações privadas nesta fase.

## 11. Dependências e falhas

| Situação | Efeito no experiences-service | Como a app mostra |
|---|---|---|
| catalog-service em baixo | Publicar falha com `503 CATALOG_UNAVAILABLE` (salvo atividade em memória); ler, pesquisar, editar, eliminar e avaliações continuam | Formulário mantido com a mensagem do §6.3 |
| users-service em baixo | Nenhum: tokens válidos continuam aceites (validação local); autores mostrados pela cópia do username. Só o feed pessoal (E4) passa a popular | — (até o token expirar; ver [arquitetura.md §3.8](arquitetura.md#38-autenticação-e-autorização)) |
| experiences-service em baixo | — | Catálogo com avaliações `UNAVAILABLE`; secções "Experiências indisponíveis" no local e no perfil; pesquisa de locais e utilizadores continua |
| notifications-service em baixo (etapa 10) | Nenhum: eventos ficam na outbox | — |
| PostgreSQL em baixo | `503 SERVICE_UNAVAILABLE` | Erro geral |

O serviço arranca mesmo sem catálogo e sem users-service; só depende do PostgreSQL (e, em E2, do volume das fotografias).

## 12. Etapa E2: fotografias

Concretiza [arquitetura.md §3.9](arquitetura.md#39-fotografias-e-câmara). **Opcional e no máximo uma por experiência.**

### 12.1 Endpoints

| Método | Caminho | Acesso | Descrição |
|---|---|---|---|
| POST | `/media` | access token | Enviar uma fotografia (`multipart/form-data`, campo `file`) → `201` com `{ "mediaId", "width", "height" }`. Fica **pendente** e pertence a quem a enviou |
| GET | `/media/{mediaId}` | público, só se associada a uma experiência não eliminada | Ficheiro JPEG recodificado |
| POST | `/experiences` | como em E1 | Aceita `"mediaId"` opcional, incluído no `requestHash` |
| PATCH | `/experiences/{id}` | autor | `"mediaId"` ausente: não muda; `"mediaId": "<novo>"`: associa ou substitui; `"mediaId": null`: remove |

Na experiência: `"photo": { "mediaId": "…", "url": "/api/v1/media/…", "width": 1600, "height": 1200 }` ou `"photo": null`. O `url` é relativo ao serviço.

**Lacunas resolvidas na revisão da E2 (2026-10-19):**

| # | Lacuna | Decisão |
|---|---|---|
| 1 | WebP | **Não aceite.** O JDK não traz descodificador de WebP e a app envia sempre JPEG (§3.9 da arquitetura); aceitar WebP obrigaria a outra biblioteca de imagem só para esse caso. Aceites: **JPEG e PNG** (ex.: capturas de ecrã). |
| 2 | Memória ao descodificar | Dimensões lidas do cabeçalho antes de descodificar. Descodificação com **subamostragem**: o lado maior descodificado fica até ~3200 px, mesmo de uma imagem de 40 MP. No máximo **2 imagens processadas ao mesmo tempo** (as outras esperam até 10 s e depois recebem `503`). Pior caso de memória: ~0,1 GB, dentro do limite atual do contentor (512 MiB), que não é alterado. |
| 3 | Cache vs. eliminação | O `mediaId` é **opaco e aleatório** (UUID v4) e **muda em cada substituição**: o conteúdo de um URL nunca muda, por isso uma substituição nunca mostra a imagem antiga. Cabeçalhos: `Cache-Control: private, max-age=86400`, `ETag` igual ao `mediaId`. `private` impede caches partilhadas; depois de remover ou eliminar, o servidor responde `404` **de imediato**. Uma cópia já guardada na cache HTTP de um aparelho pode durar até 24 h nesse aparelho, mas a app deixa de ter o URL na experiência. |
| 4 | Fotografia pendente | Invisível para todos, incluindo quem a enviou (`404`): a app mostra a imagem local até publicar. |
| 5 | `mediaId` na idempotência | Entra no `requestHash` só quando existe, por isso as chaves de publicações sem fotografia continuam válidas. A repetição de uma publicação com fotografia não volta a validar o `mediaId` (já está associado). |
| 6 | Autorização | Qualquer sessão pode enviar fotografias (fica dona delas). Associar, substituir ou remover só na **própria** experiência e só com uma fotografia **própria**, pendente e não expirada. |

### 12.2 Validação e tratamento

| Limite | Valor | Fora do limite |
|---|---|---|
| Pedido `multipart` | 10 MiB + 64 KiB | `413 PAYLOAD_TOO_LARGE` |
| Ficheiro | 1 byte a 10 MiB | `413` (acima) / `400 INVALID_IMAGE` (vazio) |
| Formato | JPEG ou PNG pela **assinatura do conteúdo** (`FF D8 FF` / `89 50 4E 47 0D 0A 1A 0A`); extensão, nome e `Content-Type` enviados são ignorados | `400 INVALID_IMAGE` |
| Dimensões (do cabeçalho, antes de descodificar) | cada lado de 200 a 8000 px; no máximo 40 MP | `400 INVALID_IMAGE` |
| Conteúdo | tem de descodificar sem erros | `400 INVALID_IMAGE` |
| Fotografias pendentes | 20 por pessoa (24 h cada) | `429 TOO_MANY_REQUESTS` com `Retry-After` |

Os erros `INVALID_IMAGE` dizem o problema (formato, dimensões, ficheiro danificado) sem repetir dados do ficheiro nem caminhos internos.

- **Orientação:** a orientação EXIF (valores 1–8) é lida com a biblioteca `metadata-extractor` e aplicada aos píxeis. Metadados ilegíveis são ignorados, porque são descartados de qualquer forma.
- **Recodificação (`javax.imageio` do JDK):** lado maior até 1600 px, transparência sobre fundo branco, JPEG com qualidade 0,85. Só a imagem é escrita, sem metadados: são descartados EXIF (incluindo GPS), XMP, IPTC, comentários, perfis e miniaturas embutidas. O original nunca é escrito no volume.
- A app já reduz a imagem antes de enviar ([arquitetura.md §3.9](arquitetura.md#39-fotografias-e-câmara)). O servidor aplica as mesmas regras na mesma, porque não confia no cliente.

### 12.3 Armazenamento

- Volume Docker próprio e persistente (`experiences-media`), montado em `/data/media`, só neste serviço e fora da imagem. Preserva-se como o volume do PostgreSQL; as cópias de segurança têm de incluir os dois juntos.
- **Nomes gerados pelo servidor:** `<mediaId>.jpg`, numa subpasta com os dois primeiros caracteres do id; temporários em `tmp/`. O nome original não é guardado.
- **Sem acesso arbitrário a caminhos:**
  - `GET /media/{mediaId}` aceita só um UUID canónico e procura-o na base de dados;
  - o caminho é construído só a partir do id e confirmado dentro da pasta do volume;
  - nenhum nome, caminho ou extensão vindo do pedido chega ao sistema de ficheiros.
- Resposta com `Content-Type: image/jpeg`, `X-Content-Type-Options: nosniff`, `Cache-Control: private, max-age=86400` e `ETag` (§12.1, lacuna 3).
- Tabela `media` com o dono, o estado (`PENDING` → `ATTACHED` → `DELETED`), as dimensões e o tamanho. Na experiência, `photo_media_id` aponta para a fotografia atual (no máximo uma).

### 12.4 Falhas parciais, substituição e eliminação

**Escrita segura:**
1. Recodificar em memória.
2. Escrever num ficheiro temporário e forçar a escrita no disco.
3. Mudar o nome para o definitivo, de forma atómica.
4. Inserir a linha `PENDING`.

Se a base de dados falhar, o ficheiro é apagado logo. Se o serviço parar entre os passos, fica um ficheiro sem linha, que a limpeza apaga.

| Situação | Comportamento |
|---|---|
| Upload falha | O formulário mantém texto, estrelas e a imagem local. O utilizador escolhe "Tentar novamente" ou "Publicar sem fotografia". **Nunca publica sem fotografia sem o utilizador escolher.** |
| Upload aceite, publicação falha | O `mediaId` fica pendente; a nova tentativa usa a mesma chave de idempotência e o mesmo `mediaId` |
| Fotografia pendente nunca usada | Ao fim de 24 h, a limpeza apaga o ficheiro e depois a linha |
| Substituir (`PATCH` com novo `mediaId`) | Numa transação, com a experiência bloqueada: a nova passa a `ATTACHED` e a antiga a `DELETED`. **A antiga só deixa de ser servida quando a nova está confirmada.** Se a transação falhar, nada muda e a antiga continua. O ficheiro antigo é apagado depois de confirmar (ou pela limpeza) |
| Remover (`mediaId: null`) ou eliminar a experiência | Na mesma transação, a fotografia passa a `DELETED`: o `GET /media` responde `404` **de imediato**. O ficheiro é apagado depois (ou pela limpeza) |
| Ficheiro de uma fotografia associada em falta ou ilegível | `GET /media` → `503 SERVICE_UNAVAILABLE`. A experiência (texto, estrelas, avaliações) continua disponível, com `photo` como estava |
| Volume indisponível | `POST /media` → `503`; experiências e listas não dependem do volume |

**Limpeza** (tarefa a cada 10 min; também apaga logo depois de cada substituição, remoção ou eliminação):
- fotografias `DELETED` e `PENDING` com mais de 24 h: apaga o ficheiro e depois a linha (se o ficheiro não puder ser apagado, a linha fica para a próxima vez);
- ficheiros `<uuid>.jpg` sem linha na base de dados, com mais de 1 h;
- temporários em `tmp/` com mais de 1 h.

A limpeza só considera ficheiros regulares com nomes gerados pelo serviço dentro da pasta do volume; não segue ligações simbólicas.

- O `mediaId` usado ao publicar ou editar tem de ser do próprio, pendente e não expirado; caso contrário → `400 MEDIA_NOT_USABLE`. Dois pedidos simultâneos com o mesmo `mediaId`: a linha é bloqueada e só um o associa.
- No máximo **20 fotografias pendentes** por pessoa; acima disso → `429 TOO_MANY_REQUESTS`, com `Retry-After` até a mais antiga expirar.

### 12.5 Câmara dentro da app (requisito R4)

- O ecrã de publicação (e o de edição) tem sempre duas opções: **"Tirar fotografia"** e **"Escolher da galeria"** (Photo Picker, sem permissão de armazenamento).
- "Tirar fotografia": Captura pela **aplicação de câmara do sistema**, chamada a partir do ecrã de publicação (Activity Result API `TakePicture`, com uma URI do FileProvider: fotografia em resolução completa, não a miniatura). Não exige a permissão `CAMERA` nem CameraX. Sem aplicação de câmara, ou se for cancelada, o texto e as estrelas mantêm-se, e a galeria e a publicação sem fotografia continuam disponíveis.
- A fotografia capturada segue o mesmo caminho da galeria: reduzida na app, enviada com `POST /media` e associada na publicação. Para o servidor, uma fotografia da câmara e uma da galeria são iguais.
- A demonstração inclui obrigatoriamente publicar uma fotografia tirada com a câmara dentro da app (§17.2).

## 13. Etapa E3: comentários, gostos e visualizações

Revisto em 2026-10-19, antes da implementação (lacunas resolvidas em §13.7).

| Método | Caminho | Acesso | Resposta |
|---|---|---|---|
| GET | `/experiences/{id}/comments` | público | `200`, comentários ativos, mais antigos primeiro, paginado |
| POST | `/experiences/{id}/comments` | access token + `Idempotency-Key` | `201` (criado) ou `200` + `Idempotent-Replayed: true` (repetição) |
| PATCH | `/comments/{commentId}` | autor do comentário | `200` com o comentário |
| DELETE | `/comments/{commentId}` | autor do comentário | `204` (idempotente para o autor) |
| PUT | `/experiences/{id}/like` | access token | `204` (idempotente) |
| DELETE | `/experiences/{id}/like` | access token | `204` (idempotente) |
| POST | `/experiences/{id}/views` | público (token opcional; visitantes enviam `X-Viewer-Key`) | `204`, conte ou não |

Regras gerais:

- **Leituras públicas, escritas autenticadas.** Quem comenta, gosta ou vê vem sempre do `sub` do token, nunca do corpo; o `username` dos comentários é copiado do token, como nas experiências (§2.3).
- **Experiência inexistente ou eliminada → `404 NOT_FOUND`** em todos estes endpoints (listar, comentar, gostar, retirar o gosto e visualizar). Para a app, uma experiência eliminada deixa de existir, com tudo o que lhe pertencia.
- Gostos e comentários **não** alteram as avaliações (§7). Moderação fica fora do âmbito: o autor da experiência não apaga comentários de outros.
- A partir da etapa 10, gostos e comentários geram eventos na outbox.

### 13.1 Comentários

```json
{
  "id": "019a0c51-0d1e-7f20-9a3b-4c5d6e7f8a9b",
  "experienceId": "019a0c4e-5b2f-7a31-8c4d-2e6f8a0b1c3d",
  "author": { "userId": "8f1c…", "username": "ana_run" },
  "text": "Também gostei muito!",
  "createdAt": "2026-10-19T10:15:00Z",
  "editedAt": null
}
```

- **Texto simples de 1 a 1000 caracteres** (pontos de código Unicode), com as mesmas regras do texto da experiência (§4.1): `\r\n` passa a `\n`, pontas aparadas, **só espaços → rejeitado**, caracteres de controlo (exceto `\n` e `\t`) recusados. Corpo `{"text": "…"}`; outros campos → `400`. A app mostra o texto como texto, sem HTML nem Markdown.
- **Lista:** só comentários ativos, por `createdAt` crescente e desempate `id` crescente (`uuidv7()`, por isso a ordem é a de criação). Envelope e limites do §9. Comentários eliminados não aparecem nem contam.
- **Publicar** exige `Idempotency-Key` (UUID), com a mesma política do §5, por autor:
  - mesma chave e mesmo conteúdo (experiência + texto normalizado) → `200`, o mesmo comentário e `Idempotent-Replayed: true`;
  - mesma chave com outro conteúdo → `409 IDEMPOTENCY_CONFLICT`, `reason: DIFFERENT_CONTENT`, com `commentId`;
  - chave de um comentário entretanto eliminado → `409 IDEMPOTENCY_CONFLICT`, `reason: COMMENT_DELETED`, sem recriar nem devolver conteúdo;
  - pedidos simultâneos com a mesma chave → um só comentário; chaves guardadas 7 dias.
- **Editar** (`PATCH`, corpo `{"text": "…"}`): só o autor (`403 FORBIDDEN` para outra pessoa); `editedAt` só muda se o texto mudar; comentário eliminado → `404`.
- **Eliminar:** só o autor; o texto é apagado e fica só uma marca (ids e datas), que nunca é exposta. Repetir → `204` para o autor; para outra pessoa, um comentário eliminado não existe (`404`), como em §4.5.
- **Eliminar a experiência** elimina, na mesma transação, os seus comentários (texto apagado), gostos e visualizações. Repetir a chave de um desses comentários → `409 COMMENT_DELETED`.

### 13.2 Gostos

- `PUT …/like` cria o gosto e `DELETE …/like` retira-o; repetir qualquer um → `204` sem mudar nada.
- **Um gosto por (pessoa, experiência)**, garantido pela chave primária na base de dados; pedidos simultâneos da mesma pessoa deixam um único gosto.
- **Não é possível gostar da própria experiência** → `400 CANNOT_LIKE_OWN`, para que "Mais gostadas" reflita a opinião de outras pessoas. `DELETE` na própria → `204` (não há nada a retirar).

### 13.3 Contagens nas experiências

Todas as respostas com o formato `Experience` (§4.1: listas, detalhe, publicar, editar) passam a ter:

| Campo | Significado |
|---|---|
| `likesCount` | número de gostos |
| `commentsCount` | número de comentários **ativos** |
| `likedByMe` | `true`/`false` só quando o pedido traz um token válido de **outra** pessoa; **omitido** sem sessão e para o próprio autor |

- `likedByMe` ausente quer dizer "relação desconhecida" (visitante) ou "não aplicável" (o autor); nunca é `false` por falta de sessão. É o mesmo critério de `followedByMe` no users-service. Para o saber, a app envia o token também nas leituras; um token inválido continua a dar `401` (§3).
- Campos só acrescentados: clientes que os ignoram continuam a funcionar.
- Contagens calculadas em cada pedido a partir das tabelas (sem contadores duplicados que possam divergir).

### 13.4 Ordenações

- `sort=likes` (gostos) e `sort=interactions` (**interações = gostos + comentários ativos**), maior primeiro, desempate `createdAt` e `id` decrescentes (§9). As visualizações não entram em nenhuma ordenação ([arquitetura.md §3.6](arquitetura.md#36-métricas-e-ordenações)).

### 13.5 Visualizações

- **Por pedido explícito:** a app chama `POST …/views` ao abrir o detalhe. **O `GET` nunca escreve nada**, por isso pré-visualizações feitas por outros serviços e repetições do `GET` não contam.
- **Quem conta:**
  - com token, a pessoa é o `sub` (um `X-Viewer-Key` enviado junto é ignorado);
  - sem token, a app envia `X-Viewer-Key`: um UUID aleatório gerado uma vez por instalação, sem ligação à conta nem ao aparelho; **não se usa o IP**;
  - visitante sem chave → `204`, não contado; chave que não seja um UUID → `400 VALIDATION_FAILED`.
- **O autor não conta** (pedido com o token do autor → `204`, não contado). Um visitante não pode ser reconhecido como o autor; aceita-se.
- **Eliminação de repetições por período fixo:** no máximo **uma visualização contada por (experiência, pessoa ou chave) em cada período**, de 24 h por omissão (configurável), alinhado a 00:00 UTC. A unicidade (experiência, quem vê, início do período) está na base de dados, por isso pedidos simultâneos contam uma vez. Duas aberturas às 23:59 e às 00:01 contam duas vezes; aceita-se, em troca de uma regra simples e sem condições de corrida.
- **Guardado:** só o hash SHA-256 de `user:<sub>` ou `key:<X-Viewer-Key>`, o início do período e a data. Não se guarda o IP, o `User-Agent` nem a chave original. O hash de um `userId` é uma pseudonimização (quem conhece os ids pode recalculá-lo), não anonimização.
- **Não são públicas:** nenhuma resposta pública mostra visualizações; só o próprio autor vê totais no dashboard (E4, §14.4).
- **Limitações (documentadas, aceites):** a chave é manipulável (o cliente pode enviar chaves novas em cada pedido) e apagar os dados da app gera outra; a mesma pessoa conta uma vez com sessão e outra como visitante. **Não mede pessoas únicas**: é uma métrica de alcance aproximada para o dashboard, não resistente a fraude ([arquitetura.md §3.12](arquitetura.md#312-dashboard)).

### 13.6 Limites e erros

- Limites configuráveis, em memória por pessoa e com janela fixa, como o de publicações (§4.1, com as mesmas limitações): **comentários criados** (60 por hora) e **mudanças de gosto** (300 por hora; só contam pedidos que mudam alguma coisa, não repetições). Acima → `429 TOO_MANY_REQUESTS` com `Retry-After`. As visualizações não têm limite próprio: as repetições já não contam (§13.5).
- Erros com o formato e os códigos do §8; nenhuma resposta pública inclui dados privados (email, hash de quem viu, chaves). Logs sem tokens, sem `X-Viewer-Key` nem texto dos comentários.

### 13.7 Lacunas resolvidas na revisão

| # | Lacuna | Decisão |
|---|---|---|
| 1 | Comprimento dos comentários (o contrato dizia 1–500) | 1–1000 caracteres, só espaços rejeitado |
| 2 | Comentários, gostos e visualizações numa experiência eliminada | `404`; eliminar a experiência apaga-os na mesma transação |
| 3 | Eliminar comentários | Marca com texto apagado; `DELETE` idempotente para o autor; `404` para outros |
| 4 | Repetir a chave de um comentário eliminado | `409 IDEMPOTENCY_CONFLICT`, `reason: COMMENT_DELETED` |
| 5 | "Em cada 24 horas" (janela deslizante ou fixa) | Período fixo alinhado a 00:00 UTC, configurável; unicidade na base de dados |
| 6 | Chave de visitante inválida | `400`; sem chave → `204` não contado |
| 7 | `likedByMe` para visitantes | Omitido (relação desconhecida), nunca `false` |
| 8 | Limites contra repetições | Comentários e mudanças de gosto por pessoa, `429` + `Retry-After` |
| 9 | Interações | Gostos + comentários **ativos**; visualizações à parte |

## 14. Etapa E4: feeds e dashboard

Revisto em 2026-10-19 e aprovado com ajustes (decisões em §14.8). Só usa dados que o experiences-service já guarda, mais o contexto do feed pedido ao users-service. Não há aprendizagem automática, filas, caches partilhadas nem serviços novos.

| Método | Caminho | Acesso | Descrição |
|---|---|---|---|
| GET | `/feed/popular` | público (token opcional, só para `likedByMe`) | Feed popular (§14.1) |
| GET | `/feed` | access token | Feed pessoal, com degradação para o popular (§14.2) |
| GET | `/me/stats` | access token | Totais e histograma das estrelas dadas, do próprio autor (§14.4) |
| GET | `/me/stats/experiences` | access token | Métricas por publicação do próprio autor, paginadas (§14.4) |

Os dois feeds e a lista por publicação usam o envelope de paginação comum (§9: `page` ≥ 0, `size` 1–50, por omissão 20, página para além do fim → `items: []`). Os itens dos feeds têm o formato `Experience` (§4.1, com as contagens e o `likedByMe` da §13.3). Nenhuma resposta destes endpoints inclui visualizações por pessoa, hashes, chaves de instalação ou dados de quem viu.

### 14.1 Feed popular (`GET /feed/popular`)

- **Fórmula:** `interações = gostos + comentários ativos`, a mesma métrica de `sort=interactions` (§13.4). As visualizações não entram: são fáceis de inflacionar e não exprimem opinião ([arquitetura.md §3.6](arquitetura.md#36-métricas-e-ordenações)).
- **Recência em dois blocos:**
  - primeiro, as experiências publicadas desde `recentSince = inícioDoDiaUTC(hoje) − 30 dias`;
  - depois, as mais antigas;
  - dentro de cada bloco: `interações DESC, createdAt DESC, id DESC`.

  O limite muda só uma vez por dia UTC; o segundo bloco garante que a lista nunca fica vazia enquanto houver experiências.
- **Determinismo:** a ordem é total (termina no `id`) e só depende dos dados e do dia UTC do pedido. Gostos e comentários novos mudam a ordem entre pedidos; a app remove repetições pelo `id`, como nas outras listas (§9).
- **Filtro opcional:** `sportId` (uma modalidade, com o formato e a validação de `GET /experiences`). Não há outros filtros: as listas por local, por autor e a pesquisa continuam em `GET /experiences`.
- **Inclui as do próprio utilizador:** é uma lista pública, igual para todos; `likedByMe` só aparece com o token de outra pessoa.
- **Modo de demonstração** (§14.6): com `portofit.feed.include-demo-places=true` (valor atual), entram as experiências de locais de demonstração.
- **Autenticação:** pedido sem token ou com token válido → `200`; token inválido → `401`, como em todas as leituras (§3).

Resposta (os mesmos campos de modo da §14.2, para a app tratar os dois feeds da mesma forma):

```json
{
  "items": [ { "id": "019a…", "likesCount": 4, "commentsCount": 2, "…": "formato Experience" } ],
  "page": 0, "size": 20, "totalItems": 37, "totalPages": 2,
  "mode": "POPULAR",
  "fallbackReason": null,
  "recentSince": "2026-09-19T00:00:00Z",
  "generatedAt": "2026-10-19T21:30:00Z"
}
```

### 14.2 Feed pessoal (`GET /feed`)

1. O `userId` vem do token (`sub`); não há parâmetro para pedir o feed de outra pessoa.
2. O experiences-service pede ao users-service o contexto **do utilizador do token** (`GET /internal/me/feed-context`, [users-api.md §3.11](users-api.md#311-get-internalmefeed-context)): ids das pessoas seguidas (todas, no máximo 500 pelo limite de produto de [users-api.md §3.9](users-api.md#39-seguir)) e ids das modalidades de interesse (0–10).
3. Seleciona as experiências não eliminadas em que `author_id` está nos seguidos **ou** `sport_id` está nos interesses, **excluindo as do próprio**. Aplica o filtro opcional `sportId` e o modo de demonstração (§14.6).
4. A ordem é `createdAt DESC, id DESC` (cronológica, sem popularidade). Cada item traz `matchedBy`: `["FOLLOWING"]`, `["INTEREST"]` ou os dois. É informação do próprio utilizador, para a app explicar porque aparece.

**Modo e motivo** (sempre presentes; `fallbackReason` é `null` em `PERSONAL`):

| Situação | `mode` | `fallbackReason` | Conteúdo |
|---|---|---|---|
| O conjunto pessoal filtrado (todas as páginas) tem pelo menos uma experiência | `PERSONAL` | `null` | seguidos/interesses, por data |
| Não segue ninguém e não tem interesses | `POPULAR` | `NO_PREFERENCES` | feed popular (§14.1), com o mesmo `sportId` |
| Tem seguidos/interesses, mas o conjunto pessoal filtrado **completo** está vazio (`totalItems = 0`) | `POPULAR` | `NO_MATCHES` | feed popular, com o mesmo `sportId` |
| users-service temporariamente indisponível (§14.3) | `POPULAR_FALLBACK` | `USERS_UNAVAILABLE` | feed popular, com o mesmo `sportId` |

- **A decisão usa o conjunto completo, nunca a página:** uma página para além do fim de um feed pessoal com resultados devolve `PERSONAL` com `items: []`, não o popular.
- **Nunca há mistura:** uma página é toda pessoal ou toda popular, e um resultado popular nunca é marcado como `PERSONAL`.
- **Mudança de modo entre páginas:** o modo é decidido em cada pedido. Se uma página seguinte vier com `mode` ou `fallbackReason` diferentes dos da primeira (ex.: o users-service falhou a meio), a app **descarta a lista e recomeça na página 0** com o modo novo, sem juntar resultados.
- **Aviso na app:** em `POPULAR_FALLBACK` a app mostra "Não foi possível personalizar agora; a mostrar publicações populares", com **Tentar novamente**.
- **Sem cache do contexto** (nem em memória): seguir alguém ou mudar interesses reflete-se no pedido seguinte.
- **Falha do catálogo:** os feeds nunca consultam o catalog-service. Mostram os nomes copiados no momento da publicação e funcionam com o catálogo parado.
- **Locais/atividades retirados:** as experiências continuam nos feeds com as cópias de apresentação, como registo histórico. O experiences-service não conhece o estado atual do catálogo e não o consulta numa leitura (§11). O detalhe continua a indicar "atividade retirada" quando o catálogo responde.

Resposta: como em §14.1, com `mode`, `fallbackReason`, `generatedAt`, `recentSince` (só nos modos populares; `null` em `PERSONAL`) e `matchedBy` em cada item (só em `PERSONAL`).

### 14.3 Chamada ao users-service

| Aspeto | Decisão |
|---|---|
| Pedido | `GET http://users-service:8082/api/v1/internal/me/feed-context`, pela rede interna do Compose |
| Autorização | o **access token do utilizador** reencaminhado (`Authorization`) **e** `X-Internal-Key` (chave partilhada só entre os dois serviços). O users-service devolve só o contexto do `sub` do token: a chave sozinha não chega para ler o contexto de ninguém, e o token sozinho (a app) não chega para chamar `/internal` |
| Tempos | ligação 0,5 s, resposta 1 s, sem novas tentativas e sem cache (como o catálogo, §6, e [arquitetura.md §4.1](arquitetura.md#41-princípios)) |
| Dados | só ids (seguidos e modalidades), lista completa; nenhum username, email ou outro dado de perfil |
| Bases de dados | nenhuma consulta direta à `users_db`: só esta API |
| Configuração | `portofit.users.base-url` e `portofit.users.internal-key`. A chave vem da variável `USERS_INTERNAL_KEY` no `.env` (fora do Git e das imagens), passada só ao users-service e ao experiences-service, e é comparada em tempo constante |

**Resultados do pedido ao users-service:**

| Resposta | Resultado do `/feed` | Registo (sem token nem chave) |
|---|---|---|
| `200` com corpo válido | `PERSONAL` ou `POPULAR` (`NO_PREFERENCES`/`NO_MATCHES`) | — |
| `401` (o users-service rejeitou o token: expirado entretanto, assinatura, etc.) | **`401 INVALID_ACCESS_TOKEN`** para a app, como qualquer token inválido; não é degradação | `INFO` |
| `403` (chave interna em falta ou errada) | `POPULAR_FALLBACK` / `USERS_UNAVAILABLE`; **nunca** apresentado como "sem preferências" | **`ERROR`**: "chave interna recusada pelo users-service (configuração)" |
| timeout, ligação recusada, `5xx`, outro estado, corpo inválido ou ids mal formados | `POPULAR_FALLBACK` / `USERS_UNAVAILABLE` | `WARN` com o tipo da falha |

### 14.4 Dashboard (`GET /me/stats` e `GET /me/stats/experiences`)

Só o próprio utilizador: o autor é o `sub` do token, sem parâmetro `userId`. Sem token → `401`. Mostra contagens agregadas, nunca quem gostou, comentou ou viu.

**Período:** parâmetro `period` = `ALL` ("Sempre", por omissão), `LAST_7_DAYS` ("7 dias") ou `LAST_30_DAYS` ("30 dias"). Não há intervalos livres.

- **Fuso e limites:** dias UTC, os mesmos das visualizações.
  - `LAST_7_DAYS`: `from = inícioDoDiaUTC(hoje) − 6 dias`, ou seja, 7 dias UTC contando o de hoje;
  - `LAST_30_DAYS`: `from = inícioDoDiaUTC(hoje) − 29 dias`;
  - `ALL`: `from = null`;
  - `to = generatedAt`, o instante do pedido.

  As respostas incluem `period`, `from`, `to`, `generatedAt` e `timezone: "UTC"`.
- **O que cada métrica conta no período:**

| Métrica | Data usada | Conjunto |
|---|---|---|
| `publications` | data de criação da publicação | publicações **ativas** do autor criadas no período |
| `likes` | data do gosto | gostos atuais em publicações ativas do autor, com o gosto dado no período, **qualquer que seja a data da publicação** |
| `comments` | data de criação do comentário | comentários ativos em publicações ativas do autor, criados no período, qualquer que seja a data da publicação, **incluindo os do próprio autor** |
| `interactions` | — | `likes + comments` |
| `views` | dia UTC em que a visualização foi contada | visualizações contadas em publicações ativas do autor nesse período, qualquer que seja a data da publicação |
| `starsGiven` | data de criação da publicação | **histograma das estrelas que o autor deu aos locais/atividades** nas publicações ativas criadas no período (cinco chaves `1`–`5`, sempre presentes) |

  Uma interação recente numa publicação antiga conta, por isso, no período recente.
- **Efeito de alterações:**

| Alteração | Efeito |
|---|---|
| Editar texto ou fotografia | nenhum (a data de criação não muda) |
| Editar estrelas | o histograma passa a usar o valor atual |
| Retirar um gosto | deixa de contar (o registo é apagado) |
| Voltar a dar gosto | conta uma vez, na data do novo gosto |
| Editar um comentário | conta pela data de criação |
| Eliminar um comentário | deixa de contar |
| Eliminar a publicação | os seus gostos e visualizações são apagados e os comentários retirados (§13.1): a publicação e tudo o que recebeu saem de todas as métricas, também de períodos passados |

  O dashboard descreve o estado atual, não um histórico de eventos.
- **Visualizações:** são uma **soma**, não pessoas únicas. Cada pessoa com sessão, ou instalação sem sessão, conta no máximo uma vez por publicação e por dia UTC, e o autor nunca conta (§13.5). Quem abre em três dias diferentes conta três vezes, e a mesma pessoa conta à parte como visitante. A resposta indica-o com `viewsRule: "ONE_PER_VIEWER_PER_UTC_DAY"`.
- **Comentários do próprio autor:** contam, para os totais baterem com o `commentsCount` de cada publicação. A app escreve "Comentários (incluindo as tuas respostas)".

`GET /me/stats?period=LAST_30_DAYS`:

```json
{
  "period": "LAST_30_DAYS",
  "from": "2026-09-20T00:00:00Z",
  "to": "2026-10-19T21:30:00Z",
  "generatedAt": "2026-10-19T21:30:00Z",
  "timezone": "UTC",
  "received": { "likes": 14, "comments": 5, "interactions": 19, "views": 42, "viewsRule": "ONE_PER_VIEWER_PER_UTC_DAY", "commentsIncludeOwn": true },
  "publications": 3,
  "starsGiven": { "1": 0, "2": 1, "3": 0, "4": 2, "5": 0 }
}
```

O objeto `received` separa o que as publicações **receberam** (gostos, comentários, visualizações) do que o autor **deu** (`starsGiven`, opiniões sobre locais/atividades).

`GET /me/stats/experiences?period=…&sort=recent|interactions|views&page=&size=` devolve uma linha por publicação **ativa** do autor, de qualquer data. As métricas de cada linha usam o período; as publicações antigas aparecem com o que receberam no período (possivelmente 0).

```json
{
  "items": [
    {
      "experience": { "id": "019a…", "place": { "id": "…", "name": "…", "demo": true }, "offering": { "id": "…", "name": "Boxe iniciação" }, "sport": { "id": "boxe", "name": "Boxe" }, "stars": 4, "createdAt": "…", "photo": null },
      "likes": 3, "comments": 1, "interactions": 4, "views": 10
    }
  ],
  "page": 0, "size": 20, "totalItems": 6, "totalPages": 1,
  "period": "LAST_30_DAYS", "from": "2026-09-20T00:00:00Z", "to": "2026-10-19T21:30:00Z", "generatedAt": "2026-10-19T21:30:00Z", "timezone": "UTC"
}
```

- **Ordenação:** `recent` (por omissão) = `createdAt DESC, id DESC`; `interactions` e `views` = `valor DESC, createdAt DESC, id DESC`.
- **Coerência:** a soma das linhas é igual aos totais `received` do mesmo período.
- **Falha:** se a consulta falhar (base de dados indisponível) → `503 SERVICE_UNAVAILABLE`, **nunca** uma resposta com zeros. A app mostra "Estatísticas indisponíveis" com **Tentar novamente**; os totais e a lista por publicação têm estados separados.
- **Sem dados:** um utilizador sem publicações recebe `200` com zeros, que a app mostra como estado vazio, não como erro.
- "Vezes guardada" (collections-service, etapas 11/13) é juntada pela app mais tarde, com o seu próprio estado "—" se falhar.

### 14.5 Erros e limites

| Situação | Resposta |
|---|---|
| `page`, `size`, `sportId`, `period` ou `sort` inválidos | `400 VALIDATION_FAILED` com `errors` |
| `/feed`, `/me/stats*` sem token / token inválido | `401 AUTHENTICATION_REQUIRED` / `401 INVALID_ACCESS_TOKEN` |
| users-service recusa o token no `/feed` | `401 INVALID_ACCESS_TOKEN` |
| users-service indisponível ou chave interna recusada no `/feed` | `200` com `mode: POPULAR_FALLBACK` (§14.3) |
| PostgreSQL em falha | `503 SERVICE_UNAVAILABLE` |

**Limites e desempenho:**

- páginas de 1–50;
- contexto com até 500 seguidos (limite aplicado no ato de seguir) e 10 interesses;
- consultas parametrizadas, uma por página e outra para o total, com as contagens de todas as linhas da página calculadas na mesma consulta (nunca um pedido por publicação);
- índices existentes (`experience_recent_idx`, `experience_author_recent_idx`, `experience_sport_recent_idx`, `comment_experience_idx`, chaves de `experience_like`/`experience_view`); a implementação não precisou de migração nova. À escala do projeto, as contagens continuam calculadas em cada pedido (§20, limitações da E3).

### 14.6 Modo de demonstração e catálogo real

- **Hoje** só existe o catálogo fictício ([arquitetura.md §1.4](arquitetura.md#14-dados-de-demonstração)): todas as experiências são de locais `demo`. O experiences-service corre em **modo de demonstração**, com `portofit.feed.include-demo-places=true`, e os feeds incluem essas experiências (identificadas com `place.demo`).
- **Quando o catálogo real entrar** (§15, decisão explícita do grupo), a propriedade passa a `false`: as publicações de locais de demonstração **ficam fora dos dois feeds**. Continuam acessíveis no perfil do autor, por referência direta (`GET /experiences/{id}`), nas listas do próprio local e no dashboard do autor. Nada é apagado nem migrado.
- A importação de locais reais **não** faz parte da E4.

### 14.7 App Android: "Descobrir" e "Estatísticas" (proposta para a etapa da app)

**Pesquisar continua a ser o destino inicial.** Uma barra de navegação inferior (Material 3 `NavigationBar`) acrescenta dois destinos; cada destino guarda o seu estado e a sua pilha (`saveState`/`restoreState`):

| Destino | Conteúdo | Sem sessão |
|---|---|---|
| **Pesquisar** (início) | O ecrã atual, com os separadores Locais e Pessoas, sem alterações | igual |
| **Descobrir** | Ver a lista abaixo | só "Populares", com "Entra para um feed personalizado" |
| **Estatísticas** | Ver a lista abaixo | "Entra para ver as estatísticas das tuas publicações", com **Entrar** |

**Descobrir:**

- separadores "Para ti" (`/feed`) e "Populares" (`/feed/popular`); sem sessão, só "Populares";
- faixa conforme o `mode`/`fallbackReason`:
  - `PERSONAL` → "De quem segues e dos teus interesses";
  - `NO_PREFERENCES` → cartão "Segue pessoas ou escolhe interesses para personalizar", com atalhos para Pessoas e O meu perfil;
  - `NO_MATCHES` → "Ainda não há publicações de quem segues nem dos teus interesses";
  - `POPULAR_FALLBACK` → aviso com **Tentar novamente**;
- "Populares" explica a ordem: "Mais interações nos últimos 30 dias, depois as restantes";
- cada cartão diz porque aparece ("Segues @ana" / "Interesse: Boxe");
- filtro opcional por modalidade;
- mudança de modo entre páginas → a lista recomeça (§14.2).

**Estatísticas:**

- seletor "Sempre" / "7 dias" / "30 dias", com a nota "dias contados em UTC" e o intervalo `from`–`to` da resposta;
- secção **"Recebido pelas tuas publicações"**: gostos, comentários (incluindo as tuas respostas) e visualizações, com a nota "aberturas contadas, no máximo uma por pessoa e por dia; não são pessoas únicas";
- secção separada **"Estrelas que deste aos locais"**: histograma das publicações criadas no período, com a nota "são as tuas avaliações dos locais/atividades, não notas recebidas";
- lista por publicação com ordenação e "Carregar mais".

**Regras comuns:**

- O menu de conta da barra superior (`@username` → O meu perfil, Terminar sessão) mantém-se em todos os destinos.
- Abrir uma experiência, um local ou um perfil empilha o ecrã por cima do destino atual; voltar regressa com a posição e o separador intactos.
- Ao voltar do detalhe, as contagens atualizam o item já carregado (`ExperienceChanges.counts`, como na E3).
- Login a partir do feed ou das estatísticas regressa ao mesmo destino, sem executar ações automaticamente.
- Falhas por secção: um feed indisponível não afeta Pesquisar; estatísticas indisponíveis mostram "—", nunca zeros.

### 14.8 Decisões

| # | Decisão | Alternativa afastada |
|---|---|---|
| 1 | Popularidade = gostos + comentários ativos, em dois blocos (últimos 30 dias UTC, depois o resto), desempate por data e `id` | pesos diferentes, decaimento contínuo (menos explicável) |
| 2 | Feed pessoal cronológico, com seguidos **ou** interesses, sem o próprio, e `matchedBy` | ordenar por popularidade; misturar com populares |
| 3 | Fallback decidido pelo conjunto filtrado completo; `mode` e `fallbackReason` em todas as respostas dos feeds; a app recomeça a lista se o modo mudar | decidir pela página; juntar páginas de modos diferentes |
| 4 | Contexto pedido com o token do utilizador + chave interna, sem `userId` no caminho; `401` do users-service → `401`; chave recusada → degradação com registo `ERROR` | chave interna sozinha com `userId` no caminho |
| 5 | Limite de produto de 500 seguidos, aplicado ao seguir (`409 FOLLOW_LIMIT_REACHED`); o contexto devolve sempre a lista completa | truncar silenciosamente o contexto |
| 6 | Sem cache do contexto | memória de 1 min |
| 7 | Feeds sem chamadas ao catálogo; atividades retiradas continuam visíveis; modo de demonstração explícito, com as publicações de demonstração fora dos feeds quando houver catálogo real | filtrar por estado do catálogo em cada pedido |
| 8 | Dashboard por dias UTC, "Sempre"/7/30 dias; publicações e histograma pela data de criação, interações pela data da interação, visualizações pelo dia contado; estado atual | intervalo livre; fuso Europe/Lisbon; histórico de eventos |
| 9 | Visualizações como soma diária deduplicada, sem "pessoas únicas" | `count(distinct viewer_hash)` |
| 10 | Comentários do próprio autor contam, com indicação explícita na resposta e na app | contar só os de outras pessoas |
| 11 | Pesquisar continua como início; Descobrir e Estatísticas como destinos adicionais | feed como ecrã inicial |

## 15. Relação com locais reais e substituição do catálogo de demonstração

As experiências referem-se ao catálogo por ids. Para os ids das publicações continuarem válidos:

1. **Ids estáveis dos dois lados.** O catálogo nunca reutiliza nem reatribui um id de local ou de atividade (os dados de demonstração têm ids fixos), e o experiences-service nunca altera o `placeId`/`offeringId` de uma experiência (§4.4).
2. **As experiências não dependem do catálogo para serem mostradas.** As cópias de nomes (§2.1) mantêm-nas legíveis mesmo que o catálogo deixe de conhecer o id.
3. **Nada é apagado em silêncio.** Não há chaves estrangeiras entre serviços nem eliminações em cascata. O catálogo não deve apagar locais ou atividades referenciados por experiências: remover linhas `is_demo = true` ([catalog-api.md §6](catalog-api.md#6-dados-de-demonstração)) só é aceitável enquanto não houver experiências sobre elas.

**Substituição futura dos dados de demonstração por locais reais** (fora das etapas E1–E4; exige uma decisão explícita do grupo):

| Passo | Regra |
|---|---|
| Locais reais | Entram com **ids novos**. Nunca se reaproveita o id de um local fictício para um local real. |
| Locais fictícios | **Retirados, não apagados**: o estado `status` de locais e atividades (`ACTIVE`/`RETIRED`, existente desde a etapa 6) passa a `RETIRED`. O catálogo deixa de os mostrar na pesquisa pública, mas continua a responder pelo id, com o estado. Atividades retiradas deixam de aceitar publicações novas (`400 OFFERING_RETIRED`, com o atraso da memória do §6.2), e a app não oferece publicar nelas. |
| Experiências existentes | **Nunca são passadas automaticamente** de um local fictício para um real: isso atribuiria opiniões sobre um sítio inventado a um negócio real, o que o [arquitetura.md §1.4](arquitetura.md#14-dados-de-demonstração) quer evitar. Continuam ligadas ao local fictício retirado e a app indica "Local de demonstração retirado". |
| Experiências de demonstração (contas `demo_`) | Removidas só por um procedimento explícito no experiences-service, com a lista dos ids afetados registada e revista antes de executar. Nunca por um efeito secundário de recarregar o catálogo. |
| Verificação | Antes e depois da substituição, um relatório lista as experiências cujo `offeringId` o catálogo já não resolve. O relatório só informa e nunca apaga. |

## 16. Dados e infraestrutura

- Base de dados `experiences_db`, com utilizador próprio sem privilégios de administração, criada por um script `03-experiences-db.sh` idempotente, como o dos utilizadores ([infraestrutura.md §3.1](infraestrutura.md#31-acrescentar-a-base-de-um-serviço-novo-a-um-volume-existente)). O volume existente é preservado.
- Tabelas: `experience` e `publish_request` (E1); `media` (E2); `comment`, `experience_like`, `experience_view` (E3); `outbox` (etapa 10). Esquema gerido por Flyway, com uma migração nova por etapa e sem alterar as anteriores.
- `experience.id` gerado com `uuidv7()` (função nativa do PostgreSQL 18). Unicidade (`author_id`, `idempotency_key`) em `publish_request`. Restrições na base de dados para o conteúdo: estrelas de 1 a 5 e texto de 1 a 2000 caracteres numa experiência ativa; texto e estrelas nulos numa eliminada.
- Configuração:
  - URL do catálogo pela rede interna do Compose (`http://catalog-service:8081`);
  - **só o ficheiro da chave pública** (`secrets/users-jwt/public.pem`) é montado, só de leitura; a chave privada nunca é montada neste contentor;
  - porta `127.0.0.1:8083`.
- O catálogo recebe a URL do experiences-service (`http://experiences-service:8083`) para pedir as avaliações. Até à E3, o experiences-service não recebe nenhuma configuração do users-service; em E4 recebe a URL interna (`http://users-service:8082`) e a chave `USERS_INTERNAL_KEY` (§14.3), nunca credenciais da `users_db`.
- **Sem publicações de demonstração** nesta etapa: nem nas migrações nem num perfil `demo`. Os testes criam utilizadores e experiências temporários (tokens assinados por uma chave de teste). Experiências fictícias de contas `demo_` só serão consideradas quando existirem contas de demonstração.

## 17. Testes e demonstração

### 17.1 Testes automáticos (E1)

Integração com PostgreSQL real (Testcontainers), catálogo falso por HTTP e par de chaves RSA gerado no próprio teste, como no users-service:

- **Autenticação:** sem token → `401 AUTHENTICATION_REQUIRED`; token expirado, com outra chave, outro `iss`/`aud`, sem assinatura ou com `alg` diferente → `401 INVALID_ACCESS_TOKEN`; token sem `username` → recusado. Nenhum pedido ao users-service, em nenhum teste.
- **Publicar:** `201` com autor do token, cópias do catálogo e datas do servidor; `authorId`/`createdAt` no corpo → `400`; estrelas `0`, `6`, `4.5`, `"4"`, `null` → `400`; texto vazio, só com espaços ou com 2001 caracteres → `400`; 2000 caracteres (incluindo emojis) → aceite; falta de `Idempotency-Key` → `400`.
- **Catálogo:** atividade inexistente → `UNKNOWN_OFFERING`; atividade de outro local → `OFFERING_PLACE_MISMATCH`; atividade retirada → `OFFERING_RETIRED`; catálogo parado, lento (acima do prazo) ou com `5xx` → `503 CATALOG_UNAVAILABLE` sem criar nada; atividade em memória → aceite com o catálogo parado; memória com mais de 10 min → não usada; o pedido ao catálogo leva `includeRating=false`.
- **Idempotência:**
  - mesma chave e conteúdo duas vezes → uma experiência, `201` e depois `200` com o mesmo `id` e `Idempotent-Replayed: true`;
  - a repetição funciona com o catálogo parado;
  - 8 pedidos simultâneos com a mesma chave → 1 experiência;
  - mesma chave com outro conteúdo → `409 DIFFERENT_CONTENT` com `experienceId`;
  - chave de uma experiência eliminada → `409 EXPERIENCE_DELETED`, sem conteúdo nem recriação;
  - chave de uma tentativa recusada com `503` reutilizada depois → publicada uma vez;
  - a mesma chave em dois autores → duas experiências.
- **Limite:** acima do limite configurado → `429` com `Retry-After`; repetições não contam.
- **Listar:** cada filtro e combinações; `q` sem maiúsculas nem acentos e sobre os nomes copiados; `%` literal; ordenações `recent` e `stars` com desempates; `sort=likes` → `400` em E1; paginação; eliminadas excluídas; ids desconhecidos → lista vazia.
- **Editar/eliminar:** só o autor (`403` para outra pessoa, `401` sem token); atividade não editável; `editedAt` só quando algo muda; `createdAt` inalterado; eliminar duas vezes → `204`; conteúdo apagado; `404` depois.
- **Avaliações:**
  - o exemplo de [arquitetura.md §3.7](arquitetura.md#37-avaliação-geral-sem-distorção), em que a mesma pessoa avalia duas atividades do local (3,5 / 3,0 / 3,2; depois de apagar a vigente "Boxe 3★" → 4,5 e 3,5);
  - desempate com `createdAt` igual;
  - editar as estrelas da vigente e de uma antiga;
  - arredondamento meio para cima, calculado com frações exatas;
  - `NO_RATINGS` para locais sem dados;
  - 50 locais num só pedido; mais de 50 → `400`.
- **Privacidade:** nenhuma resposta tem email.
- **Catálogo** (no catalog-service):
  - `GET /offerings/{id}` com `200` (incluindo `status`), `404` e `400`;
  - com `includeRating=false`, nenhum pedido ao experiences-service;
  - avaliações `AVAILABLE`/`NO_RATINGS` com o experiences-service falso, e `UNAVAILABLE` com ele parado ou lento, sempre `200`;
  - um só pedido por resposta.

E2–E4 acrescentam os seus testes no início de cada etapa. Em E2 incluem: imagem com GPS no EXIF → sem metadados depois de guardada; orientação aplicada; ficheiro não-imagem com extensão `.jpg` → `400`; imagem gigante → `400` sem a descodificar; `mediaId` alheio → `400`; caminho com `../` → `400`; tarefa de limpeza.

### 17.2 Demonstração

**E1 (etapa 6)**, com duas contas criadas na demonstração:

1. Sem sessão, abrir um local: atividades com "Sem avaliações" e lista de experiências vazia.
2. A publica sobre "Boxe iniciação" com 4★; a experiência aparece no local e no perfil de A; o local passa a "★ 4,0 (1)".
3. B publica 2★ na mesma atividade → 3,0 (2). A publica outra vez sobre a mesma atividade com 2★ → só a mais recente de A conta: 2,0 (2). A elimina essa → volta a 3,0 (2).
4. A edita as estrelas da vigente para 5 → 3,5 (2). Ordenar por "Mais recentes" e "Melhor avaliação do local" dá ordens diferentes e explicáveis.
5. B tenta editar a experiência de A pela API → `403`.
6. `docker compose stop catalog-service`: publicar mostra "catálogo indisponível" e mantém o formulário; ler experiências funciona. Reiniciar.
7. `docker compose stop experiences-service`: pesquisa e detalhe de locais funcionam, com "Avaliação indisponível" (não 0 estrelas). Reiniciar.
8. `docker compose stop users-service`: com sessão recente, publicar continua a funcionar e o autor aparece com o username.
9. Resposta perdida: repetir pela API o mesmo `POST` com a mesma chave → a mesma experiência, sem duplicado.

**E2 (etapa 7):** publicar com uma fotografia **tirada com a câmara dentro da app** (obrigatório, R4) e outra da galeria; mostrar que a fotografia guardada não tem localização; substituir e remover a fotografia; publicar sem fotografia depois de um upload falhado.

**E3 (etapa 8):** gostos mudam o contador da publicação mas não a avaliação do local; as quatro ordenações dão ordens diferentes sem sessão.

**E4 (etapa 9):** Pesquisar continua a abrir a app; em Descobrir, sem sessão, "Populares"; com sessão e sem preferências, o cartão para seguir pessoas ou escolher interesses; depois de seguir alguém, "Para ti" mostra as publicações dessa pessoa e explica porquê; com o users-service parado, "Para ti" mostra o aviso de populares (nunca como personalizado); com o catálogo parado, os feeds continuam; o dashboard (totais, histograma das estrelas dadas e por publicação) bate com as ações feitas, e com a base de dados parada mostra "indisponível", não zeros.

## 18. Plano incremental

| Etapa | Backend | App | Pronto quando |
|---|---|---|---|
| **E1** (6) | `GET /offerings/{id}` no catálogo; experiences-service com experiências, idempotência, validação no catálogo e avaliações; catálogo passa a pedir avaliações (sai o `UnavailableRatingProvider`) | Experiências no local (por atividade, ordenações `recent`/`stars`) e no perfil público ("Publicações"); publicar (com login), editar e eliminar as próprias; avaliações reais no catálogo | Testes do §17.1 e demonstração E1 |
| **E2** (7) | `POST /media`, `GET /media/{id}`, `mediaId` em publicar/editar, limpeza periódica, volume | Galeria (Photo Picker) e câmara do sistema no ecrã de publicação e na edição | Demonstração E2, incluindo a câmara |
| **E3** (8) | Comentários, gostos, visualizações, `sort=likes`/`interactions` | Detalhe com comentários e gosto; seletor com as quatro ordenações | Demonstração E3 |
| **E4** (9) | Users: `GET /internal/me/feed-context`. Experiences: `GET /feed/popular`, `GET /feed`, `GET /me/stats`, `GET /me/stats/experiences` (§14) | Navegação Pesquisar (início) / Descobrir / Estatísticas (§14.7) | Demonstração E4 |

Cada etapa acrescenta uma migração Flyway, mantém os endpoints anteriores compatíveis (só acrescenta campos) e termina com testes e verificação no emulador.

## 19. Decisões da revisão

Revisão de 2026-10-19, antes da implementação da E1.

| # | Decisão | Onde |
|---|---|---|
| 1 | Experiência liga-se a `authorId` (do token), `placeId` e `offeringId` (validados no catálogo); atividade não editável | §2.1, §4.4 |
| 2 | Texto simples de 1 a 2000 caracteres; texto só com espaços é rejeitado; estrelas inteiras de 1 a 5 | §4.1 |
| 3 | Publicação criada → `201`; repetição idempotente → `200` com o mesmo recurso e `Idempotent-Replayed: true` | §5 |
| 4 | Chave de uma experiência eliminada → `409 IDEMPOTENCY_CONFLICT` (`EXPERIENCE_DELETED`), sem recriar nem devolver conteúdo | §5 |
| 5 | Chaves guardadas 7 dias; fora desse prazo não há garantia contra duplicação; a app não reutiliza chaves com mais de 6 dias | §5 |
| 6 | Limite configurável de publicações por autor (30 por hora), `429` com `Retry-After`, contador em memória com limitações documentadas | §4.1 |
| 7 | O JWT atual tem a claim `username` (registo, login e renovação) e é a fonte da cópia do autor; um token sem ela é recusado | §2.1, §2.3 |
| 8 | Validação no catálogo com `includeRating=false` (sem chamada de volta); estado `ACTIVE`/`RETIRED` verificado; memória de 10 min com o atraso aceite e documentado | §6 |
| 9 | Ids por `uuidv7()` nativo do PostgreSQL 18, sem gerador próprio; desempate da avaliação vigente pelo `id` maior | §2.1, §7.2 |
| 10 | Referências (ids) separadas das cópias de apresentação, que podem ficar desatualizadas | §2.1 |
| 11 | Pesquisa no texto e nos nomes copiados do local, da atividade e da modalidade, sem maiúsculas nem acentos | §4.2 |
| 12 | Avaliações calculadas em cada pedido, em lote até 50 locais; o serviço só devolve `AVAILABLE`/`NO_RATINGS`, o catálogo decide `UNAVAILABLE` | §4.6, §7 |
| 13 | Eliminação com marca e conteúdo apagado; `DELETE` idempotente para o autor | §4.5 |
| 14 | Em E1, ordenações `recent` e `stars`; `likes` e `interactions` em E3 | §9 |
| 15 | Sem publicações de demonstração nesta etapa; dados temporários só nos testes | §16 |
| 16 | E3: não é possível gostar da própria experiência; visualizações por `POST …/views`, sem escrita no `GET`, uma por pessoa ou chave em cada período fixo de 24 h, sem contar o autor (lacunas resolvidas em §13.7) | §13 |
| 17 | Fotografias (E2) recodificadas para JPEG (remove metadados, aplica orientação), nomes gerados, acesso só por UUID | §12 |
| 18 | Locais fictícios retirados e não apagados; experiências nunca passadas automaticamente para locais reais | §15 |
| 19 | E4 (revisão aprovada de 2026-10-19): popularidade = gostos + comentários ativos em dois blocos (30 dias UTC, depois o resto); feed pessoal cronológico por seguidos ou interesses, com fallback decidido pelo conjunto completo; contexto pedido com o token do utilizador e chave interna; limite de 500 seguidos aplicado ao seguir; dashboard só do próprio, por dias UTC, com datas de criação/interação/visualização, sem zeros em falhas; modo de demonstração explícito | §14.8 |

**Em aberto** (não bloqueiam a E1): paginação por cursor (§9); contadores partilhados quando houver mais de uma instância (§4.1).

## 20. Estado da implementação

Etapa E1 (backend), `backend/experiences-service`, com as alterações correspondentes no catalog-service. Operação em [infraestrutura.md](infraestrutura.md).

**Implementado:**

- **experiences-service:**
  - `POST /experiences` (idempotente, com limite por autor);
  - `GET /experiences` (filtros `placeId`, `offeringId`, `sportId`, `authorId`, pesquisa `q`, `sort=recent|stars`, paginação);
  - `GET`, `PATCH` e `DELETE /experiences/{id}`;
  - `GET /ratings/places`;
  - `/actuator/health`.
- **Base de dados:** `experiences_db` com utilizador próprio (`03-experiences-db.sh`) e migração `V1__experiences_schema.sql`.
- **catalog-service:**
  - `GET /offerings/{id}` com `includeRating` e `status`;
  - migração `V4__catalog_status.sql` (estado `ACTIVE`/`RETIRED` em locais e atividades; os dados existentes ficaram `ACTIVE`);
  - avaliações reais pedidas ao experiences-service (sai o `UnavailableRatingProvider`).

**App Android (etapa 6):** experiências no detalhe do local e nos perfis, publicar, editar e eliminar ([app-android.md §14](app-android.md#14-experiências-etapa-6-e1-sem-fotografias)).

**E2 (backend, etapa 7):**
- `POST /media`, `GET /media/{id}`, e `mediaId` em `POST /experiences` e `PATCH /experiences/{id}`;
- migração `V2__media.sql` (tabela `media` e `experience.photo_media_id`);
- volume `experiences-media` montado em `/data/media`;
- limpeza periódica (§12).

**E2 na app (etapa 7):** escolher, tirar, pré-visualizar e remover antes de publicar; fotografias nas listas e no detalhe; adicionar, substituir e remover ao editar ([app-android.md §15](app-android.md#15-fotografias-etapa-7-e2)).

**E3 (backend, etapa 8):**
- `GET`/`POST /experiences/{id}/comments`, `PATCH`/`DELETE /comments/{id}`;
- `PUT`/`DELETE /experiences/{id}/like`; `POST /experiences/{id}/views`;
- `likesCount`, `commentsCount` e `likedByMe` no formato `Experience`; `sort=likes|interactions`;
- migração `V3__interactions.sql` (tabelas `comment`, `comment_request`, `experience_like`, `experience_view`);
- configuração `portofit.interactions.*` (limites e período das visualizações).

**E3 na app (etapa 8):** comentários e gosto no detalhe, contagens e quatro ordenações nas listas do local e dos perfis, visualização registada ao abrir o detalhe ([app-android.md §16](app-android.md#16-comentários-gostos-e-visualizações-etapa-8-e3)).

**E4 (backend, etapa 9):**
- users-service: `GET /api/v1/internal/me/feed-context` (token do utilizador + `X-Internal-Key`) e limite de 500 seguidos aplicado ao seguir ([users-api.md §3.9, §3.11](users-api.md#311-get-internalmefeed-context));
- experiences-service: `GET /feed/popular`, `GET /feed`, `GET /me/stats`, `GET /me/stats/experiences` (§14);
- configuração `portofit.users.*` e `portofit.feed.*` (modo de demonstração); chave `USERS_INTERNAL_KEY` só no `.env`;
- sem migração nova: as consultas usam os índices existentes.

**Ainda não implementado:** a app da E4 (Descobrir e Estatísticas, §14.7).

**Notas da implementação**

- **Idempotência:** o registo da tentativa é inserido com `INSERT … ON CONFLICT (author_id, idempotency_key) DO NOTHING` antes da experiência, na mesma transação, e a referência à experiência só é verificada no fim (`DEFERRABLE INITIALLY DEFERRED`). Com dois pedidos simultâneos, o segundo fica à espera na inserção e, quando o primeiro confirma, é tratado como repetição. Uma tarefa horária apaga os registos com mais de 7 dias.
- **Avaliações:** a consulta escolhe as vigentes com `DISTINCT ON (author_id, offering_id) … ORDER BY created_at DESC, id DESC`. As médias são calculadas com frações exatas (`BigInteger`) e arredondadas uma única vez, meio para cima. Por isso casos como 3,25 → 3,3 não dependem de erros de vírgula flutuante.
- **Autor:** `sub` e `username` do token; um token sem `username` dá `401`. O JWT atual do users-service já tem esta claim (registo, login e renovação).
- **Catálogo:** cliente HTTP do JDK com ligação 0,5 s e resposta 2 s, sem novas tentativas; memória de 10 min só para respostas `200`. No catálogo, o pedido de avaliações tem ligação 0,5 s e resposta 1 s.
- **Corpo:** os pedidos JSON são limitados a 16 KiB (`413`), pelo `Content-Length` e durante a leitura; um corpo que não seja JSON dá `415`.
- **Logs:** nunca têm tokens nem o texto das experiências. Uma falha do catálogo ou das avaliações regista o tipo do erro e a causa (ex.: `HttpTimeoutException`).

**Notas da implementação da E2**

- **Bibliotecas:** `javax.imageio` do JDK para ler JPEG/PNG e escrever o JPEG; `com.drewnoakes:metadata-extractor` 2.19.0 (única dependência nova) só para ler a orientação EXIF.
- **Ordem do processamento:**
  1. assinatura do conteúdo;
  2. dimensões do cabeçalho (`ImageReader.getWidth/getHeight`), sem descodificar;
  3. descodificação com subamostragem; avisos do descodificador (ex.: JPEG truncado) → recusa;
  4. orientação EXIF e redução numa única transformação para uma imagem RGB nova;
  5. JPEG escrito sem metadados (`IIOImage(imagem, null, null)`).
- **Transações:**
  - associar, substituir e remover acontecem na transação da experiência (`SELECT … FOR UPDATE OF e` e `FOR UPDATE` na fotografia);
  - o `GET /media` só serve uma fotografia `ATTACHED` que ainda é a `photo_media_id` de uma experiência não eliminada;
  - os ficheiros são apagados só depois de confirmada a transação; o que falhar fica para a limpeza.
- **Testes:** uma base comum (`ApiIntegrationTest`) faz as duas classes partilharem um único contexto Spring e um único PostgreSQL. O `MediaRepository` é um *spy* do Mockito, para simular a falha da base de dados depois de gravar o ficheiro.

**Testes automáticos**

- **experiences-service, E2** (`MediaApiIntegrationTest`, **13 testes**), executados depois da última alteração ao código:
  - fotografia com EXIF (orientação 6 + GPS): orientação aplicada (dimensões trocadas, cores no sítio certo), sem EXIF nem GPS no ficheiro servido, invisível enquanto pendente, cabeçalhos (`image/jpeg`, `nosniff`, `private, max-age=86400`, `ETag`) e `304` com `If-None-Match`;
  - PNG com texto embutido e transparência → JPEG sem o texto, com fundo branco;
  - recusados pelo conteúdo real: texto com nome e `Content-Type` de JPEG, GIF com nome de JPEG, JPEG truncado, ficheiro vazio, 100×100, cabeçalho de 9000×9000 e de 48 MP (recusados sem descodificar, em < 3 s); sem o campo `file` → `400`; JSON → `415`; sem sessão → `401`;
  - ficheiro de 10 MiB + 1 byte por HTTP real (Tomcat) → `413`;
  - autorização: fotografia de outra pessoa → `MEDIA_NOT_USABLE`; outra pessoa a alterar ou remover → `403`; fotografia já usada; a mesma fotografia de novo não altera nada; `mediaId` inválido (incluindo `../../etc/passwd`) → `400`;
  - substituição: uma falhada não muda nada e a antiga continua; uma bem-sucedida dá um URL novo, a antiga → `404` e o ficheiro antigo é apagado;
  - remover a fotografia mantém o texto;
  - eliminar a experiência → `404` imediato; com a limpeza física pendente, `404` na mesma e a limpeza apaga depois;
  - idempotência com fotografia: `mediaId` inutilizável não gasta a chave; a repetição devolve `200`; outro conteúdo → `409`;
  - limite de pendentes → `429` com `Retry-After`;
  - falha da base de dados depois de gravar o ficheiro → o ficheiro é apagado e o erro não expõe caminhos;
  - ficheiro em falta → `GET /media` `503` sem caminhos, experiência `200`;
  - limpeza de pendentes expiradas, órfãos e temporários antigos, sem tocar nos recentes, nas associadas nem em ficheiros alheios.

  Total do serviço: **30 testes, 0 falhas** (17 da E1 e 13 da E2).

- **experiences-service, E1** (`.\gradlew.bat test` em `backend/experiences-service`): **17 testes**, contra PostgreSQL 18.6 real (Testcontainers), com um catálogo falso por HTTP, chaves RSA geradas no teste (o serviço só recebe a pública) e um relógio controlável. Cobrem:
  - autenticação local: sem token, expirado, outra chave, outro `iss`/`aud`, mal formado, sem `username`, token inválido numa leitura pública;
  - validações (estrelas `0`, `6`, `4.5`, `4.0`, `"4"`, `null`; texto vazio, só com espaços ou NBSP, 2001 caracteres, caracteres de controlo; 2000 com emojis aceite; campos proibidos; chave em falta; JSON inválido; `415`; `413`);
  - atividade inexistente, de outro local e retirada;
  - catálogo parado sem e com memória válida, lento (limitado pelo prazo) e memória expirada;
  - repetição `200` com `Idempotent-Replayed`, também sem catálogo; `409 DIFFERENT_CONTENT` e `EXPERIENCE_DELETED` sem conteúdo;
  - 8 pedidos simultâneos com a mesma chave → 1 experiência;
  - chave de uma tentativa recusada reutilizada; chaves por autor;
  - limite `429` com `Retry-After`;
  - filtros, pesquisa no texto e nos nomes copiados sem maiúsculas nem acentos, `%`/`_` literais, ordenações e paginação;
  - edição e eliminação só pelo autor;
  - avaliações: o exemplo de [arquitetura.md §3.7](arquitetura.md#37-avaliação-geral-sem-distorção), em que a mesma pessoa avalia duas atividades do local, incluindo a eliminação da vigente; editar a vigente e uma antiga; datas empatadas; arredondamento; lote de 50; validações.
- **catalog-service:** **30 testes** (23 anteriores e 7 novos; ver [infraestrutura.md §6](infraestrutura.md#6-usar-a-api-do-catálogo)).

**Verificação por HTTP (2026-10-19)**, nos contentores, com três contas temporárias registadas no users-service (palavras-passe aleatórias, não mostradas; apagadas no fim, com as suas experiências):

| Verificação | Resultado |
|---|---|
| Local sem experiências | primeiro pedido depois do arranque: `UNAVAILABLE` (excedeu o prazo de 1 s, arranque a frio); seguintes: `NO_RATINGS` |
| A publica 4★ no "Boxe iniciação" da Ribeira | `201`, autor e nomes copiados; catálogo: local 4,0 (1), Boxe 4,0 (1), Kickboxing `NO_RATINGS` |
| Mesma chave | `200`, `Idempotent-Replayed: true`, mesmo id |
| B publica 2★ | 3,0 (2) |
| A publica de novo 2★ na mesma atividade | 2,0 (2): só a mais recente de A conta |
| A edita a antiga para 5★ | sem efeito (2,0) |
| A elimina a vigente | 3,5 (2): conta a antiga, com o valor editado |
| A avalia também o Kickboxing 3★ | local 3,0 (2) (A conta uma vez: (5 + 3) / 2 = 4; B 2), Boxe 3,5 (2), Kickboxing 3,0 (1) |
| `GET /offerings/{Boxe}` / `?includeRating=false` | `status: ACTIVE`, 3,5 (2) / sem `rating` |
| Pesquisa do catálogo `q=boxe` | Ribeira 3,0 (2); outros `NO_RATINGS` |
| B edita a experiência de A; atividade de outro local; eliminada | `403`; `400 OFFERING_PLACE_MISMATCH`; `404` |
| experiences-service parado | pesquisa e detalhe do catálogo `200` (~0,5 s), avaliações `UNAVAILABLE`; ao reiniciar, voltam os valores |
| catalog-service parado | publicar numa atividade validada há < 10 min: `201`; numa nunca validada: `503 CATALOG_UNAVAILABLE`; ler experiências: `200` |
| users-service parado (token ainda válido) | publicar: `201`, com o username do autor |

Na primeira execução, a verificação do catálogo parado veio logo depois de reiniciar o experiences-service, cuja memória fica vazia ao reiniciar: daí o `503`, o que está correto. Também houve um `503` ao publicar logo depois de reiniciar o catálogo (prazo esgotado no arranque a frio). As duas verificações foram repetidas pela ordem certa, com os resultados da tabela.

**Verificação por HTTP da E2 (2026-10-19)**, nos contentores, com duas contas temporárias (apagadas no fim com as suas experiências e fotografias; `pamplona` e o registo de experiência já existente preservados; volume vazio no fim):

| Verificação | Resultado |
|---|---|
| Upload de PNG 400×200 com EXIF (orientação 6 + GPS) e texto embutido | `201`, 200×400 (orientação aplicada) |
| Consultar enquanto pendente | `404` |
| Texto com nome `.jpg` e `Content-Type: image/jpeg` | `400 INVALID_IMAGE` "Só são aceites fotografias JPEG ou PNG." |
| Publicar com a fotografia e consultar | `201` com `photo.url`; `GET` `200`, `image/jpeg`, `max-age=86400, private`, `ETag`, `nosniff`; JPEG 200×400 sem `Exif` nem o texto |
| Outra conta remove a fotografia | `403` |
| Substituir | URL novo; o antigo `404` e o ficheiro antigo já não está no volume; o novo `200` |
| Reiniciar o contentor | a fotografia continua com o mesmo conteúdo (SHA-256 igual) |
| Remover a fotografia | `photo: null`, texto intacto; `404`; ficheiro apagado |
| Eliminar a experiência com fotografia | `204`; `404`; ficheiro apagado |

**Notas da implementação da E3**

- **Contagens:** calculadas em cada consulta com `CROSS JOIN LATERAL (SELECT count(*) …)` por experiência (gostos e comentários ativos), usando a chave primária de `experience_like` e o índice parcial `comment_experience_idx`. As ordenações usam as mesmas expressões: `likes` → `gostos DESC, created_at DESC, id DESC`; `interactions` → `gostos + comentários DESC, created_at DESC, id DESC`. `likedByMe` vem de uma única consulta por página (`user_id = :quem AND experience_id IN (…)`).
- **Concorrência:**
  - gostos e visualizações: `INSERT … ON CONFLICT DO NOTHING` sobre a chave primária (`(experience_id, user_id)`; `(experience_id, viewer_hash, period_start)`);
  - comentários: tentativa registada antes do comentário, como na publicação (`comment_request`, referência `DEFERRABLE INITIALLY DEFERRED`);
  - comentar, gostar e visualizar bloqueiam a experiência com `FOR SHARE`; eliminar usa `FOR UPDATE`. Por isso uma eliminação espera pelos pedidos em curso, e os pedidos seguintes já veem a experiência eliminada (`404`): nada fica associado a uma experiência eliminada.
- **Período das visualizações:** início = `floor(segundos desde a época / período) × período`, com o relógio do serviço (os testes adiantam-no). Com 24 h fica alinhado a 00:00 UTC.
- **Limites:** `WindowLimiter` em memória (janela fixa por pessoa), como o de publicações; comentários contam ao ser criados e gostos só quando mudam.
- **Leituras com sessão:** `GET /experiences` e `GET /experiences/{id}` leem o token opcional só para `likedByMe`; sem token continuam iguais (mais as contagens).

**Testes automáticos da E3** (`InteractionApiIntegrationTest`, **10 testes**), executados depois da última alteração ao código:

- comentar: `201` com autor do token e texto normalizado; repetição `200` + `Idempotent-Replayed`; lista mais antigos primeiro, paginada; `editedAt` só quando o texto muda; experiência inexistente → `404`;
- validação e autorização: sem sessão `401`; vazio, só espaços, 1001 caracteres, caracteres de controlo, texto não textual, campos extra, corpo não objeto, chave em falta → `400`; 1000 emojis aceite; editar/eliminar o de outra pessoa → `403`; ids inválidos → `400`;
- idempotência: outro texto ou outra experiência com a mesma chave → `409 DIFFERENT_CONTENT` (com `commentId`); chave por autor; comentário eliminado → `409 COMMENT_DELETED` sem texto; 8 pedidos simultâneos com a mesma chave → 1 comentário;
- eliminar comentário: `204`, repetir `204`, outra pessoa `404`, editar depois `404`; deixa de aparecer e de contar; fica só a marca;
- gostos: repetir `204` sem duplicar; própria → `400 CANNOT_LIKE_OWN`; retirar idempotente; 8 pedidos simultâneos da mesma pessoa → 1 gosto; `likedByMe` ausente para visitantes e para o autor, `true`/`false` para outra pessoa (detalhe e lista); token inválido numa leitura → `401`; avaliação inalterada;
- 8 pessoas diferentes em simultâneo (gosto + comentário cada) → contagens exatas 8 e 8;
- ordenações `likes` e `interactions` com empates resolvidos pela data, paginação, visualizações sem efeito, e um comentário eliminado a deixar de contar;
- eliminar a experiência: comentários (só marca), gostos e visualizações apagados; depois listar, comentar, gostar, retirar e visualizar → `404`; repetir a chave do comentário → `409 COMMENT_DELETED`;
- visualizações: pessoa ×3 → 1; chave ×2 → 1; outra chave → 1; sem chave, autor e token com chave inválida → não contam; chave inválida sem sessão → `400`; token inválido `401`; `GET` não escreve; só hashes guardados; 8 pedidos simultâneos com a mesma chave → 1; no período seguinte (relógio +24 h) a mesma pessoa e a mesma chave contam de novo e o autor continua sem contar;
- limites: comentários acima do limite → `429` com `Retry-After`, repetições continuam `200`; gostos: só as mudanças contam, acima do limite → `429`.

Total do serviço: **40 testes, 0 falhas** (17 da E1, 13 da E2 e 10 da E3); o teste da E1 que esperava `sort=likes` → `400` passou a usar uma ordenação inexistente.

**Verificação por HTTP da E3 (2026-10-19)**, no contentor reconstruído (migração V3 aplicada ao volume existente), com três contas temporárias (palavras-passe aleatórias, não mostradas; apagadas no fim com as suas experiências, comentários e gostos; `pamplona`, os dados existentes e os volumes preservados). 27 verificações, 0 falhas:

| Verificação | Resultado |
|---|---|
| Publicar | `201` com `likesCount: 0`, `commentsCount: 0`, sem `likedByMe` |
| Comentar; repetir a chave; outro texto com a mesma chave | `201` (texto normalizado, autor do token); `200` + `Idempotent-Replayed`; `409 DIFFERENT_CONTENT` com `commentId` |
| Só espaços; 1001 caracteres; sem sessão | `400`; `400`; `401` |
| Editar o comentário de outra pessoa; o próprio | `403`; `200` com `editedAt` |
| Lista pública com `size=1`, páginas 0 e 1 | mais antigo primeiro, `totalItems: 2` |
| Gostar ×2; a própria | `204`, `204`; `400 CANNOT_LIKE_OWN` |
| `likedByMe` | visitante ausente; quem gostou `true`; autor ausente; outra pessoa noutra experiência `false` |
| Token inválido numa leitura | `401` |
| Retirar o gosto ×2 | `204`, `204`; contagem desce |
| `sort=likes`, `sort=interactions`; `sort=views` | ordem esperada; `400` |
| Visualizações: pessoa ×3, chave ×2, autor, sem chave, 3 `GET` | todas `204`; 2 contadas; os `GET` não escrevem |
| Chave inválida | `400` |
| Base de dados | só hashes de 64 caracteres, sem a chave nem o id |
| Catálogo (detalhe do local) | `200`, avaliação `AVAILABLE` (gostos não mexem na avaliação) |
| Eliminar comentário ×2; outra pessoa | `204`, `204`; `404`; contagem desce |
| Eliminar a experiência; depois listar, comentar, gostar, visualizar | `204`; `404` ×4; gostos, visualizações e textos de comentários: 0 |
| Repetir a chave do comentário | `409 COMMENT_DELETED`, sem texto |
| Logs do serviço | sem chave de visitante, tokens nem texto dos comentários |

**Limitações da E3**

- As visualizações são uma métrica aproximada e manipulável (§13.5): a chave de instalação é escolhida pelo cliente e não se usa o IP.
- Os limites de comentários e de gostos são em memória e por instância, como o de publicações.
- Contagens calculadas em cada pedido: suficiente para a escala do projeto. Com muitos dados, `sort=likes|interactions` sem filtros percorre todas as experiências ativas. Contadores materializados ficam como melhoria possível.

**Notas da implementação da E4**

- **Contexto do feed:**
  - `UsersClient` com o `RestClient` sobre o cliente HTTP do JDK (ligação 0,5 s, resposta 1 s), sem novas tentativas nem cache;
  - reencaminha o token recebido (`jwt.tokenValue`) e a chave interna;
  - classifica a resposta em contexto obtido, token recusado (→ `401`) ou indisponível (→ `POPULAR_FALLBACK`);
  - um `403` é registado como `ERROR` ("chave interna recusada… configuração"); timeouts e `5xx` como `WARN`, só com o tipo da falha;
  - um corpo com ids mal formados conta como indisponível, nunca como "sem preferências".
- **Consultas:** uma para o total e outra para a página, com as contagens de todas as linhas calculadas na mesma consulta (`CROSS JOIN LATERAL`).
  - Feed popular: `ORDER BY (created_at >= :recentSince) DESC, gostos + comentários DESC, created_at DESC, id DESC`.
  - Feed pessoal: `author_id IN (:following) OR sport_id IN (:interests)`, só com as listas não vazias, e `author_id <> :me`.
  - O modo pessoal/popular decide-se pelo total do conjunto filtrado.
- **Dashboard:**
  - **Contagens:** as mesmas subconsultas (gostos e comentários ativos `>= from` pela data, visualizações `period_start >= from`) servem os totais (`sum`) e as linhas, por isso a soma das linhas bate com os totais.
  - **Publicações e histograma:** contam pela data de criação.
  - **Período:** calculado com o relógio do serviço; "Sempre" usa a época como limite. Não há limite superior: nada existe depois do pedido.
- **Limite de seguidos:** no users-service, `SELECT … FOR UPDATE` na linha do seguidor, verificação de "já segue", contagem e inserção na mesma transação.
- **Modo de demonstração:** `portofit.feed.include-demo-places` (hoje `true`) acrescenta `NOT place_demo` às duas consultas de feed quando passa a `false`.

**Testes automáticos da E4**

- **users-service** (`UsersApiIntegrationTest`, +2; **26 testes, 0 falhas**):
  - limite de seguidos (`409 FOLLOW_LIMIT_REACHED`, voltar a seguir no limite → `204`, deixar de seguir abre espaço, 6 pedidos simultâneos com limite 3 → exatamente 3);
  - `feed-context`: sem token `401`, token inválido `401`, sem chave/chave errada/chave vazia `403`, `no-store`, só ids, todos os seguidos (mais recentes primeiro) e interesses pela ordem, contexto sempre do token, caminho antigo com `userId` inexistente.
- **experiences-service** (+7; **47 testes, 0 falhas**: 17 da E1, 13 da E2, 10 da E3 e 7 da E4), com um users-service falso (lê o `sub` do token e confirma a chave):
  - `FeedApiIntegrationTest` (5):
    - popular em dois blocos com desempates, paginação e página para além do fim;
    - `likedByMe` só com sessão, sem visualizações nem dados privados, sem pedidos ao users-service;
    - validação; eliminadas fora;
    - modo de demonstração (locais fictícios fora dos feeds, mas no perfil e por referência direta);
    - pessoal por seguidos ou interesses, sem as próprias, com `matchedBy`, filtro, paginação e página para além do fim ainda `PERSONAL`, e o token e a chave reencaminhados;
    - `NO_PREFERENCES`, `NO_MATCHES` (também quando só o filtro esvazia o conjunto);
    - erro, lentidão (< 2,5 s), chave recusada e corpo inválido → `POPULAR_FALLBACK`; token recusado → `401`;
    - catálogo parado sem efeito.
  - `StatsApiIntegrationTest` (2):
    - "Sempre", 7 e 30 dias com datas fixadas: publicação antiga com interações recentes, comentário do autor, comentário eliminado, gosto retirado e voltado a dar, visualizações em dias UTC diferentes, publicação eliminada, editar texto e estrelas;
    - linhas por publicação com zeros, três ordenações, paginação e soma igual aos totais;
    - só o próprio (`userId` ignorado), sem identidades, validação, e base de dados em falha → `503` sem `received`.

Executados depois da última alteração ao código dos dois serviços.

**Verificação por HTTP da E4 (2026-10-19)**, com os dois serviços reconstruídos e seis contas temporárias (apagadas no fim com os seus dados; `pamplona`, os dados existentes e os volumes preservados):

| Verificação | Resultado |
|---|---|
| Feed popular sem sessão (`sportId=boxe`) | `200`, `POPULAR`, `fallbackReason: null`, `recentSince`, `generatedAt`; só boxe; sem visualizações nem emails |
| `/feed` sem sessão; token inválido | `401`; `401 INVALID_ACCESS_TOKEN` |
| Conta sem seguidos nem interesses | `POPULAR` / `NO_PREFERENCES` (ver nota) |
| Interesse "kickboxing" | `PERSONAL`, com a experiência de kickboxing (`matchedBy: INTEREST`) e sem a de boxe |
| Seguir a conta A | as duas, a mais recente primeiro (`FOLLOWING`/`INTEREST`) |
| Página para além do fim | `PERSONAL`, `items: []` |
| Interesse com uma experiência de outra conta e outra própria | só a da outra conta |
| Preferências sem resultados no filtro | `POPULAR` / `NO_MATCHES` |
| `feed-context` a partir do computador | sem token `401`; token sem chave `403`; chave errada `403`; caminho antigo com `userId` `404` |
| users-service parado | `/feed` `200` `POPULAR_FALLBACK` / `USERS_UNAVAILABLE` em ~0,5 s, sem `matchedBy`; popular e dashboard continuam |
| Dashboard 7 dias (2 publicações, 2 gostos dos quais 1 retirado, comentário de B e do autor, visualizações de B ×2, de um visitante e do autor) | 2 publicações; 1 gosto, 2 comentários, 3 interações, 2 visualizações; estrelas 4★ e 5★; `from` à meia-noite UTC, `to = generatedAt` |
| Por publicação (`sort=interactions`) | ordem esperada, soma igual aos totais |
| Outra conta com `?userId=` da A; sem sessão | os seus zeros; `401` |
| Depois de eliminar as publicações | tudo a zero, também no passado |
| Logs dos dois serviços | sem tokens nem a chave |

Nota: o primeiro `/feed` logo depois de reconstruir o users-service recebeu `POPULAR_FALLBACK` (prazo de 1 s esgotado no arranque a frio, `HttpTimeoutException` no registo). É a degradação prevista; repetido com o serviço a responder, deu `NO_PREFERENCES`. Do mesmo modo, a verificação "o autor não vê as suas" foi refeita com uma conta com interesses, porque sem preferências a resposta é o feed popular (público, inclui as próprias, como definido).

**Limitações da E4**

- O primeiro pedido ao users-service depois de um arranque pode exceder 1 s e devolver o popular; a app mostra o aviso e "Tentar novamente".
- Contagens e ordem do popular calculadas em cada pedido sobre todas as experiências ativas; adequado à escala do projeto.
- O limite de seguidos e o modo de demonstração são configuração do serviço, não da app.

**Limitações conhecidas**

- Fotografias: o limite de 2 imagens processadas ao mesmo tempo é por instância. Uma cópia já guardada na cache HTTP de um aparelho pode durar até 24 h depois de remover a fotografia (só nesse aparelho). Não há cópia de segurança automática do volume. O `503` por excesso de processamento simultâneo não tem teste automático.

- Logo depois de um arranque, os primeiros pedidos entre serviços podem exceder os prazos curtos. Medido no catálogo: 2,4 s e 1,0 s, depois 0,14 s. O resultado é `UNAVAILABLE` nas avaliações ou `503 CATALOG_UNAVAILABLE` ao publicar, ambos corretos e passageiros. Um aquecimento no arranque fica como melhoria possível.
- O limite de publicações e a memória de atividades são por instância e perdem-se ao reiniciar (§4.1, §6.2).
- O estado `RETIRED` existe no catálogo, mas nenhum dado está retirado nem há forma de o alterar pela API (o catálogo é só de leitura; não há endpoints de administração). Desde a integração na app, a pesquisa pública só mostra locais ativos e só considera atividades ativas, e o detalhe por id indica o estado ([catalog-api.md §3.2–3.3](catalog-api.md#32-get-places)).
