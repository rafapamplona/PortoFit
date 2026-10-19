# experiences-service — Contrato da API

> Contrato das experiências (publicações), das avaliações agregadas e, nas etapas seguintes, das fotografias, comentários, gostos, visualizações, feeds e estatísticas. Contexto geral em [arquitetura.md](arquitetura.md) (§3.5 cópias, §3.6 métricas, §3.7 avaliação, §3.8 autenticação, §3.9 fotografias, §3.10 feeds, §4 falhas).
> Estado: **E1 implementada no backend** (etapa 6; estado em [§20](#20-estado-da-implementação)), segundo a v1 aprovada na revisão de 2026-10-19 ([§19](#19-decisões-da-revisão)). A app ainda não usa este serviço. E2–E4 ficam definidas e são revistas no início de cada etapa.
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
| **E2** | Fotografia opcional: galeria (Photo Picker) e câmara dentro da app (CameraX) | 7 |
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
| seguidos e interesses | users-service | `GET /internal/users/{userId}/feed-context` | feed pessoal (E4) |

Consequências:

- **As leituras nunca chamam outro serviço**: listas, detalhe e avaliações respondem só com os dados próprios e as cópias ([arquitetura.md §4.1](arquitetura.md#41-princípios)).
- **O nome do autor nunca é inventado.** Vem do token assinado no momento da publicação e fica guardado. Se o users-service estiver indisponível, a experiência mostra-se na mesma com esse username; o serviço não precisa do users-service para mostrar autores. Não há texto de substituição ("Utilizador desconhecido") nem nomes gerados. Se um token não tiver a claim `username`, a publicação é recusada (`401`), em vez de se guardar um autor sem nome.
- Se o catálogo deixar de conhecer um id, as experiências continuam a mostrar as cópias (§15).

### 2.4 Entidades das etapas seguintes

| Entidade | Etapa | Resumo |
|---|---|---|
| `Media` | E2 | Fotografia: `mediaId` (UUID gerado), `ownerId`, `experienceId` (null enquanto pendente), largura, altura, tamanho, `createdAt`. O ficheiro vive num volume; a base de dados guarda só o id (§12). |
| `Comment` | E3 | `id`, `experienceId`, `authorId`, `authorUsername` (cópia do token), `text` (1–500), `createdAt`, `editedAt`. |
| `Like` | E3 | Par único (`experienceId`, `userId`), `createdAt`. |
| `View` | E3 | `experienceId`, `viewerId` (null para visitantes), `createdAt`. |
| `Outbox` | etapa 10 | Eventos para o notifications-service ([arquitetura.md §3.11](arquitetura.md#311-notificações)). |

## 3. Autenticação e autorização

- **Validação local do JWT.** O serviço valida os access tokens emitidos pelo users-service com a **chave pública**, recebida por configuração (ficheiro montado só de leitura a partir de `secrets/users-jwt/public.pem`). Verifica a assinatura RS256 (outros algoritmos, incluindo `none`, são rejeitados), `iss = portofit-users`, `aud = portofit-api` e `exp` (e `nbf`/`iat` se presentes), com tolerância de 60 s, como em [users-api.md §6.3](users-api.md#63-access-token-jwt). **Não consulta o users-service** em nenhum pedido; nunca recebe a chave privada.
- **Públicos (sem sessão):** consultar, listar e pesquisar experiências; avaliações agregadas. A partir de E2, fotografias de experiências publicadas; a partir de E3, comentários e contagens; a partir de E4, o feed popular.
- **Exigem access token:** publicar, editar e eliminar (E1); fotografias (E2); comentar, gostar e registar visualizações autenticadas (E3); feed pessoal e dashboard (E4).
- **Token num endpoint público:** pedido sem token → visitante. Token inválido ou expirado → `401 INVALID_ACCESS_TOKEN`, como no users-service ([users-api.md §3.8](users-api.md#38-get-usersuserid--perfil-público)); um token inválido nunca é tratado como visitante. Em E1 nenhuma leitura depende de quem pede, por isso a app pode pedir sem token.
- **Autorização por dono:** só o autor (`sub` do token igual a `authorId`) edita ou elimina a sua experiência. O `authorId` vem sempre do token, nunca do corpo.
- **Limites aceites** ([arquitetura.md §3.8](arquitetura.md#38-autenticação-e-autorização)): um access token continua aceite até expirar (máx. 15 min), mesmo depois do logout; sem users-service, quem tem token válido pode publicar até ele expirar.
- **Endpoints internos** (`/internal`, a partir de E4, se forem precisos) usam `X-Internal-Key`, como no users-service.

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

**Comportamento da app** (a detalhar em app-android.md na etapa 6):

- Gera a chave na primeira tentativa de publicar e guarda-a com o formulário (`SavedStateHandle`), para sobreviver à rotação e à recriação do processo.
- Numa falha de rede, timeout ou `5xx`, mantém o texto, as estrelas e a chave. **Não repete sozinha**: o utilizador toca em "Tentar novamente", com a mesma chave.
- Depois de um `201` ou de um `200` com `Idempotent-Replayed: true`, descarta a chave e o rascunho. Uma nova publicação gera uma chave nova.
- Se o utilizador alterar o conteúdo depois de uma falha de resultado desconhecido, a app mantém a chave. Se a primeira tentativa tinha sido aceite, o servidor responde `409` (`DIFFERENT_CONTENT`) com o `experienceId`. A app mostra então "A tua publicação anterior já tinha sido aceite", com a opção de a abrir (e editar). Assim nunca ficam duas.
- `409` com `EXPERIENCE_DELETED`: a publicação tinha sido feita e foi apagada entretanto (por exemplo, noutro dispositivo). A app descarta o rascunho e a chave, e o utilizador pode publicar de novo como uma tentativa nova.
- `400`, `401`, `429` e `503 CATALOG_UNAVAILABLE` não consumiram a chave: corrigir e tentar de novo com a mesma é seguro.

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
| 400 | `INVALID_IMAGE` | o ficheiro não é uma imagem aceite ou está fora dos limites (§12) | E2 |
| 400 | `CANNOT_LIKE_OWN` | gostar da própria experiência (§13) | E3 |
| 401 | `AUTHENTICATION_REQUIRED` | falta o access token | E1 |
| 401 | `INVALID_ACCESS_TOKEN` | token inválido ou expirado (`WWW-Authenticate: Bearer error="invalid_token"`) | E1 |
| 403 | `FORBIDDEN` | não é o autor; chave interna errada | E1 |
| 404 | `NOT_FOUND` | experiência (ou comentário, fotografia) inexistente ou eliminada | E1 |
| 405 | `METHOD_NOT_ALLOWED` | método não suportado | E1 |
| 409 | `IDEMPOTENCY_CONFLICT` | chave já usada: `reason` = `DIFFERENT_CONTENT` (com `experienceId`) ou `EXPERIENCE_DELETED` (§5) | E1 |
| 413 | `PAYLOAD_TOO_LARGE` | corpo acima do limite (16 KiB em JSON; 10 MiB em fotografias) | E1 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | tipo de conteúdo do pedido não suportado | E1 |
| 429 | `TOO_MANY_REQUESTS` | limite de publicações por autor (§4.1), com `Retry-After`; a partir de E2, também o limite de fotografias pendentes (§12) | E1 |
| 500 | `INTERNAL_ERROR` | erro inesperado, sem detalhes internos | E1 |
| 503 | `CATALOG_UNAVAILABLE` | não foi possível validar a atividade (§6.3) | E1 |
| 503 | `SERVICE_UNAVAILABLE` | base de dados (ou, em E2, o armazenamento de fotografias) indisponível | E1 |

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
| `interactions` | E3 | gostos + comentários maior primeiro | `createdAt` e `id` decrescentes |

- Todas as ordenações são totais (terminam no `id`) e estão disponíveis a visitantes ([arquitetura.md §3.6](arquitetura.md#36-métricas-e-ordenações)).
- Como em [users-api.md §5](users-api.md#5-paginação), ordem total não significa páginas imutáveis: uma publicação nova entra no topo de `recent` e desloca as páginas seguintes, e uma eliminação desloca-as para trás. A app remove repetições pelo `id`. Paginação por cursor fica como melhoria possível.
- `stars` ordena opiniões sobre o **local/atividade**; não é uma ordenação de qualidade da publicação. A app mostra-a como "Melhor avaliação do local".

## 10. Privacidade

- As experiências são **públicas**: texto, estrelas, datas, local e atividade, e o autor como `userId` e `username` (dados já públicos no users-service). **Nunca** email nem outros dados privados: o serviço nem os recebe (o token não tem email).
- Eliminar apaga o conteúdo (§4.5); a marca restante não é exposta.
- Fotografias (E2): os metadados (incluindo localização GPS) são removidos antes de guardar (§12).
- Visualizações (E3): guardam o `viewerId` só para excluir o autor e eventuais contagens futuras; nunca são expostas por pessoa, só como totais no dashboard do autor.
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
| POST | `/media` | access token | Enviar uma fotografia (`multipart/form-data`, campo `file`) → `201` com `{ "mediaId", "width", "height" }`; fica **pendente** |
| GET | `/media/{mediaId}` | público, só se associada a uma experiência publicada | Ficheiro JPEG |
| POST | `/experiences` | como em E1 | Aceita `"mediaId"` opcional (incluído no `requestHash`) |
| PATCH | `/experiences/{id}` | autor | `"mediaId": "<novo>"` substitui; `"mediaId": null` remove |

Na experiência: `"photo": { "mediaId": "…", "url": "/api/v1/media/…", "width": 1600, "height": 1200 }` ou `"photo": null`.

### 12.2 Validação e tratamento

- **Limites:** pedido até 10 MiB; formatos aceites **JPEG, PNG e WebP**, identificados pelo **conteúdo real** (assinatura do ficheiro e descodificação completa), nunca pela extensão nem pelo `Content-Type` enviado. Dimensões lidas do cabeçalho **antes** de descodificar: cada lado entre 200 e 8000 px e no máximo 40 megapíxeis (proteção contra imagens que ocupam muita memória ao descodificar). Fora disto → `400 INVALID_IMAGE`.
- **Orientação:** a orientação EXIF é aplicada aos píxeis antes de qualquer outra operação.
- **Metadados removidos:** a imagem é **recodificada** para JPEG (qualidade ~85, lado maior até 1600 px). A recodificação descarta todos os metadados (EXIF, incluindo localização GPS, XMP, IPTC e miniaturas embutidas). Só se guarda a imagem recodificada; o original nunca é escrito em disco.
- A app já reduz a imagem antes de enviar ([arquitetura.md §3.9](arquitetura.md#39-fotografias-e-câmara)); o servidor aplica as mesmas regras na mesma, porque não confia no cliente.

### 12.3 Armazenamento

- Volume Docker próprio e persistente (`experiences-media`), montado só neste serviço. Preserva-se como o volume do PostgreSQL; cópias de segurança têm de incluir os dois juntos.
- **Nomes gerados pelo servidor:** cada ficheiro chama-se `<mediaId>.jpg` (UUID), numa subpasta com os dois primeiros caracteres do id. O nome original enviado é ignorado e não é guardado.
- **Sem acesso arbitrário a caminhos:** `GET /media/{mediaId}` aceita só um UUID válido, procura-o na base de dados e constrói o caminho a partir do id. Nenhum caminho, nome ou extensão vindo do pedido chega ao sistema de ficheiros.
- Resposta com `Content-Type: image/jpeg`, `X-Content-Type-Options: nosniff` e cache longa (uma substituição cria um `mediaId` novo, por isso o conteúdo de um id nunca muda).
- Escrita segura: ficheiro temporário → escrita completa → mudança de nome atómica → linha na base de dados. Se a base de dados falhar, o ficheiro é apagado.

### 12.4 Falhas parciais, substituição e eliminação

| Situação | Comportamento |
|---|---|
| Upload falha | O formulário mantém texto, estrelas e a imagem local. O utilizador escolhe: "Tentar novamente" ou "Publicar sem fotografia". **Nunca publica sem fotografia sem o utilizador escolher.** |
| Upload aceite, publicação falha | O `mediaId` fica pendente; a nova tentativa usa a mesma chave de idempotência e o mesmo `mediaId` |
| Fotografia pendente nunca usada | Apagada (linha e ficheiro) por uma tarefa periódica ao fim de 24 h |
| Substituir (`PATCH` com novo `mediaId`) | Na mesma transação, a nova fica associada e a antiga é marcada para remoção; o ficheiro antigo é apagado depois de confirmar a transação (se falhar, a tarefa periódica apaga-o) |
| Remover (`mediaId: null`) ou eliminar a experiência | A linha é marcada e o ficheiro apagado depois da transação, da mesma forma |
| Ficheiros sem linha na base de dados (ex.: o serviço parou a meio) | A tarefa periódica apaga-os ao fim de 1 h |
| Volume indisponível | `POST /media` e `GET /media` → `503 SERVICE_UNAVAILABLE`; experiências sem fotografia continuam a funcionar |

- `mediaId` usado ao publicar ou editar tem de ser do próprio, pendente e não expirado; caso contrário → `400 MEDIA_NOT_USABLE`.
- No máximo **20 fotografias pendentes** por pessoa; acima disso → `429 TOO_MANY_REQUESTS` até as antigas expirarem ou serem usadas.

### 12.5 Câmara dentro da app (requisito R4)

- O ecrã de publicação tem sempre duas opções: **"Tirar fotografia"** (pré-visualização e captura com CameraX, dentro da app) e **"Escolher da galeria"** (Photo Picker, sem permissão de armazenamento).
- A permissão `CAMERA` só é pedida quando o utilizador toca em "Tirar fotografia". Se recusar, a galeria e a publicação sem fotografia continuam disponíveis.
- A fotografia capturada segue o mesmo caminho da galeria: reduzida na app, enviada com `POST /media` e associada na publicação. Para o servidor, uma fotografia da câmara e uma da galeria são iguais.
- A demonstração inclui obrigatoriamente publicar uma fotografia tirada com a câmara dentro da app (§17.2).

## 13. Etapa E3: comentários, gostos e visualizações

| Método | Caminho | Acesso | Descrição |
|---|---|---|---|
| GET | `/experiences/{id}/comments` | público | Comentários, mais antigos primeiro (desempate `id`), paginado |
| POST | `/experiences/{id}/comments` | access token + `Idempotency-Key` | Comentar (1–500 caracteres, texto simples). Mesma política de idempotência do §5, por comentário |
| PATCH | `/comments/{commentId}` | autor do comentário | Editar o texto |
| DELETE | `/comments/{commentId}` | autor do comentário | Eliminar (idempotente para o autor) |
| PUT | `/experiences/{id}/like` | access token | Gostar (idempotente, `204`); da própria experiência → `400 CANNOT_LIKE_OWN` |
| DELETE | `/experiences/{id}/like` | access token | Retirar o gosto (idempotente, `204`) |
| POST | `/experiences/{id}/views` | público (token opcional; visitantes enviam `X-Viewer-Key`) | Registar uma visualização (`204`) |

- A experiência passa a ter `likesCount` e `commentsCount`, e `likedByMe` apenas com token de outra pessoa (omitido para visitantes e no próprio, como `followedByMe` no users-service).
- Ficam disponíveis `sort=likes` e `sort=interactions` (§9). Interações = gostos + comentários; as visualizações não contam ([arquitetura.md §3.6](arquitetura.md#36-métricas-e-ordenações)).
- **Não é possível gostar da própria experiência** (`400 CANNOT_LIKE_OWN`), para que "Mais gostadas" reflita a opinião de outras pessoas.
- **Visualizações por pedido explícito:** a app chama `POST …/views` ao abrir o detalhe. **O `GET` nunca escreve nada**, por isso pré-visualizações feitas por outros serviços (mensagens, listas) e repetições do `GET` não contam.
- **Quem conta:**
  - com token, a pessoa é o `sub`;
  - sem token, a app envia `X-Viewer-Key`: um UUID aleatório gerado uma vez por instalação, sem ligação à conta; o servidor guarda só o seu hash SHA-256;
  - um visitante sem chave recebe `204`, mas a visualização não é contada.
- **Exclusão do autor:** um pedido com o token do autor não conta. Um visitante não identificado não pode ser reconhecido como o autor; aceita-se.
- **Eliminação de repetições:** no máximo **uma visualização contada por (experiência, pessoa ou chave) em cada 24 horas**; os pedidos seguintes nesse período respondem `204` e não contam.
- **Limitações:** apagar os dados da app gera outra chave; a mesma pessoa conta uma vez com sessão e outra como visitante. É uma métrica de alcance aproximada para o dashboard, não uma medida resistente a fraude ([arquitetura.md §3.12](arquitetura.md#312-dashboard)).
- Implementação apenas na etapa E3.
- Gostos e comentários **não** alteram as avaliações (§7).
- Moderação fica fora do âmbito: o autor da experiência não pode apagar comentários de outros.
- A partir da etapa 10, gostos e comentários geram eventos na outbox.

## 14. Etapa E4: feeds e dashboard

| Método | Caminho | Acesso | Descrição |
|---|---|---|---|
| GET | `/feed/popular` | público | Experiências dos últimos 30 dias por interações e data; completa com mais antigas ([arquitetura.md §3.10](arquitetura.md#310-feeds)) |
| GET | `/feed` | access token | Pessoas seguidas **ou** modalidades de interesse, excluindo as próprias, por data; vazio → popular |
| GET | `/me/stats` | access token | Por publicação e no total: visualizações, gostos, comentários, interações e estrelas ([arquitetura.md §3.12](arquitetura.md#312-dashboard)) |

- O feed pessoal pede ao users-service `GET /internal/users/{userId}/feed-context` ([users-api.md §3.11](users-api.md#311-get-internalusersuseridfeed-context)) com timeout curto. Se falhar, responde com o feed popular e `"mode": "POPULAR_FALLBACK"` (caso normal: `"mode": "PERSONAL"`; sem seguidos nem resultados: `"mode": "POPULAR"`).
- Os dois feeds usam o envelope de paginação comum, mais `mode`.
- "Vezes guardada" vem do collections-service (etapa 11) e é juntada pela app.

## 15. Relação com locais reais e substituição do catálogo de demonstração

As experiências referem-se ao catálogo por ids. Para os ids das publicações continuarem válidos:

1. **Ids estáveis dos dois lados.** O catálogo nunca reutiliza nem reatribui um id de local ou de atividade (os dados de demonstração têm ids fixos), e o experiences-service nunca altera o `placeId`/`offeringId` de uma experiência (§4.4).
2. **As experiências não dependem do catálogo para serem mostradas.** As cópias de nomes (§2.1) mantêm-nas legíveis mesmo que o catálogo deixe de conhecer o id.
3. **Nada é apagado em silêncio.** Não há chaves estrangeiras entre serviços nem eliminações em cascata. O catálogo não deve apagar locais ou atividades referenciados por experiências: remover linhas `is_demo = true` ([catalog-api.md §6](catalog-api.md#6-dados-de-demonstração)) só é aceitável enquanto não houver experiências sobre elas.

**Substituição futura dos dados de demonstração por locais reais** (fora das etapas E1–E4; exige uma decisão explícita do grupo):

| Passo | Regra |
|---|---|
| Locais reais | Entram com **ids novos**. Nunca se reaproveita o id de um local fictício para um local real. |
| Locais fictícios | **Retirados, não apagados**: o estado `status` de locais e atividades (`ACTIVE`/`RETIRED`, existente desde a etapa 6) passa a `RETIRED` e o catálogo continua a responder pelo id. Atividades retiradas deixam de aceitar publicações novas (`400 OFFERING_RETIRED`, com o atraso da memória do §6.2). Esconder locais retirados da pesquisa do catálogo é decidido nessa altura (por agora não há dados retirados). |
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
- O catálogo recebe a URL do experiences-service (`http://experiences-service:8083`) para pedir as avaliações. O experiences-service não recebe nenhuma configuração do users-service.
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

**E4 (etapa 9):** o feed muda depois de seguir alguém e escolher interesses; com o users-service parado aparece "A mostrar publicações populares"; o dashboard bate com as ações feitas.

## 18. Plano incremental

| Etapa | Backend | App | Pronto quando |
|---|---|---|---|
| **E1** (6) | `GET /offerings/{id}` no catálogo; experiences-service com experiências, idempotência, validação no catálogo e avaliações; catálogo passa a pedir avaliações (sai o `UnavailableRatingProvider`) | Experiências no local (por atividade, ordenações `recent`/`stars`) e no perfil público ("Publicações"); publicar (com login), editar e eliminar as próprias; avaliações reais no catálogo | Testes do §17.1 e demonstração E1 |
| **E2** (7) | `POST /media`, `GET /media/{id}`, `mediaId` em publicar/editar, limpeza periódica, volume | Galeria (Photo Picker) e câmara (CameraX) no ecrã de publicação | Demonstração E2, incluindo a câmara |
| **E3** (8) | Comentários, gostos, visualizações, `sort=likes`/`interactions` | Detalhe com comentários e gosto; seletor com as quatro ordenações | Demonstração E3 |
| **E4** (9) | Feeds e `/me/stats` | Feed na abertura da app; dashboard | Demonstração E4 |

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
| 16 | E3: não é possível gostar da própria experiência; visualizações por `POST …/views`, sem escrita no `GET`, uma por pessoa ou chave em 24 h, sem contar o autor | §13 |
| 17 | Fotografias (E2) recodificadas para JPEG (remove metadados, aplica orientação), nomes gerados, acesso só por UUID | §12 |
| 18 | Locais fictícios retirados e não apagados; experiências nunca passadas automaticamente para locais reais | §15 |

**Em aberto** (não bloqueiam a E1): esconder locais retirados da pesquisa do catálogo (§15); paginação por cursor (§9); contadores partilhados quando houver mais de uma instância (§4.1).

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

**Ainda não implementado:** E2–E4 (fotografias, comentários, gostos, visualizações, feeds, dashboard) e a integração na app Android.

**Notas da implementação**

- **Idempotência:** o registo da tentativa é inserido com `INSERT … ON CONFLICT (author_id, idempotency_key) DO NOTHING` antes da experiência, na mesma transação, e a referência à experiência só é verificada no fim (`DEFERRABLE INITIALLY DEFERRED`). Com dois pedidos simultâneos, o segundo fica à espera na inserção e, quando o primeiro confirma, é tratado como repetição. Uma tarefa horária apaga os registos com mais de 7 dias.
- **Avaliações:** a consulta escolhe as vigentes com `DISTINCT ON (author_id, offering_id) … ORDER BY created_at DESC, id DESC`. As médias são calculadas com frações exatas (`BigInteger`) e arredondadas uma única vez, meio para cima. Por isso casos como 3,25 → 3,3 não dependem de erros de vírgula flutuante.
- **Autor:** `sub` e `username` do token; um token sem `username` dá `401`. O JWT atual do users-service já tem esta claim (registo, login e renovação).
- **Catálogo:** cliente HTTP do JDK com ligação 0,5 s e resposta 2 s, sem novas tentativas; memória de 10 min só para respostas `200`. No catálogo, o pedido de avaliações tem ligação 0,5 s e resposta 1 s.
- **Corpo:** os pedidos JSON são limitados a 16 KiB (`413`), pelo `Content-Length` e durante a leitura; um corpo que não seja JSON dá `415`.
- **Logs:** nunca têm tokens nem o texto das experiências. Uma falha do catálogo ou das avaliações regista o tipo do erro e a causa (ex.: `HttpTimeoutException`).

**Testes automáticos**

- **experiences-service** (`.\gradlew.bat test` em `backend/experiences-service`): **17 testes**, contra PostgreSQL 18.6 real (Testcontainers), com um catálogo falso por HTTP, chaves RSA geradas no teste (o serviço só recebe a pública) e um relógio controlável. Cobrem:
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

**Limitações conhecidas**

- Logo depois de um arranque, os primeiros pedidos entre serviços podem exceder os prazos curtos. Medido no catálogo: 2,4 s e 1,0 s, depois 0,14 s. O resultado é `UNAVAILABLE` nas avaliações ou `503 CATALOG_UNAVAILABLE` ao publicar, ambos corretos e passageiros. Um aquecimento no arranque fica como melhoria possível.
- O limite de publicações e a memória de atividades são por instância e perdem-se ao reiniciar (§4.1, §6.2).
- O estado `RETIRED` existe no catálogo, mas nenhum dado está retirado nem há forma de o alterar pela API (o catálogo é só de leitura). A pesquisa e o detalhe do catálogo ainda não escondem dados retirados (§15).
