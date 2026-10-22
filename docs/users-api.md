# users-service — Contrato da API

> Contrato de contas, sessões, perfis e relações de seguir. Contexto geral em [arquitetura.md](arquitetura.md) (§3.8 autenticação, §3.10 feeds, §4 falhas).
> Estado: **v1 aprovada** (etapa 5), com as decisões da revisão registadas em §11. Estado da implementação em §12.
> Mesmas convenções do [catalog-api.md](catalog-api.md): JSON UTF-8, nomes de campos em inglês, textos em português, datas ISO 8601 UTC, erros em Problem Details.

## 1. Responsabilidade e âmbito

O users-service é o dono das **contas** (credenciais), das **sessões** (refresh tokens), dos **perfis** (username, descrição, interesses) e das **relações de seguir**. É também quem **emite** os tokens de acesso (JWT) que os outros serviços validam.

**Incluído:** registo, login, renovação de sessão, logout, perfil público, edição do próprio perfil e interesses, pesquisa de utilizadores, seguir/deixar de seguir, listas de seguidores e seguidos, endpoint interno para os feeds.

**Fora desta etapa:** login social, recuperação ou alteração de palavra-passe, alteração de email ou username, verificação de email, eliminação de conta, fotografia de perfil, contas privadas ou bloqueio de utilizadores, dados de demonstração (utilizadores `demo_` virão num perfil de demonstração próprio, como no catálogo).

**Publicações do perfil:** não pertencem a este serviço. A app obtê-las-á ao experiences-service (`GET /api/v1/experiences?authorId=<userId>`, [experiences-api.md §4.2](experiences-api.md#42-get-experiences--listar-e-pesquisar)). O users-service não guarda contagens de publicações nem depende do experiences-service.

- Base URL em desenvolvimento: `http://localhost:8082/api/v1` (no emulador: `http://10.0.2.2:8082/api/v1`)
- Porta 8082, publicada só em `127.0.0.1`, como o catálogo.

## 2. Entidades

```
User 1 ──── N UserInterest (sportId do catálogo)
User 1 ──── N RefreshToken (sessões)
User N ──── N User  através de Follow (seguidor → seguido)
```

### 2.1 User — conta e perfil

| Campo | Tipo | Visível a | Regras |
|---|---|---|---|
| `userId` | UUID | todos | Gerado no registo; **nunca muda**. É a única referência a utilizadores usada entre serviços. |
| `username` | string | todos | 3–30 caracteres `a-z`, `0-9`, `.` e `_`; começa e acaba em letra ou número; guardado em minúsculas; único. Não editável nesta fase. |
| `email` | string | **só o próprio** | Até 254 caracteres; formato `local@domínio.tld`; guardado em minúsculas; único. Nunca aparece em respostas públicas, listas nem logs. |
| `passwordHash` | string | ninguém | Nunca sai do serviço (ver §6.2). |
| `description` | string ou null | todos | 0–300 caracteres, texto simples (sem HTML). Vazio é guardado como null. |
| `interests` | lista de strings | todos | 0–10 ids de modalidade do catálogo, sem repetições (ver §7). |
| `followersCount`, `followingCount` | inteiros | todos | Calculados a partir de `Follow`. |
| `createdAt` | data | **só o próprio** | Data de registo; aparece em `GET /me`, não no perfil público. |

Usernames reservados (rejeitados no registo): começados por `demo_` (dados de demonstração) e `admin`, `portofit`, `suporte`, `api`, `me`.

### 2.2 Follow — relação de seguir

| Campo | Regras |
|---|---|
| `followerId`, `followedId` | userIds existentes; par único; `followerId ≠ followedId` |
| `createdAt` | data em que começou a seguir |

### 2.3 RefreshToken — sessão

| Campo | Regras |
|---|---|
| `id` | UUID |
| `userId` | dono da sessão |
| `tokenHash` | SHA-256 do refresh token; **o token em claro nunca é guardado** |
| `familyId` | identifica a cadeia de renovações de um mesmo login (para detetar reutilização) |
| `createdAt` | emissão deste token |
| `expiresAt` | **prazo máximo da sessão**: 30 dias desde o login, igual para todos os tokens da família; a rotação não o prolonga (§6.4) |
| `usedAt` | preenchido quando é trocado por um novo (rotação) |
| `revokedAt` | preenchido no logout ou quando a família é revogada |

## 3. Endpoints

| Método | Caminho | Acesso | Descrição |
|---|---|---|---|
| POST | `/auth/register` | público | Criar conta e iniciar sessão |
| POST | `/auth/login` | público | Iniciar sessão com email e palavra-passe |
| POST | `/auth/refresh` | refresh token | Renovar a sessão (rotação) |
| POST | `/auth/logout` | refresh token | Terminar esta sessão |
| GET | `/me` | access token | O próprio perfil, com email |
| PATCH | `/me` | access token | Editar a descrição |
| PUT | `/me/interests` | access token | Substituir os interesses |
| GET | `/users?q=` | público | Pesquisar utilizadores por username |
| GET | `/users/{userId}` | público (token opcional) | Perfil público |
| PUT | `/users/{userId}/follow` | access token | Seguir (idempotente) |
| DELETE | `/users/{userId}/follow` | access token | Deixar de seguir (idempotente) |
| GET | `/users/{userId}/followers` | público | Seguidores, paginado |
| GET | `/users/{userId}/following` | público | Pessoas seguidas, paginado |
| GET | `/.well-known/jwks.json` | público | Chaves públicas de verificação (fora de `/api/v1`) |
| GET | `/internal/me/feed-context` | access token do utilizador **e** chave interna | Seguidos e interesses do próprio utilizador, só para o experiences-service (E4) |
| GET | `/actuator/health` | público | Estado do serviço |

"access token" = cabeçalho `Authorization: Bearer <accessToken>`. "token opcional" = funciona sem sessão; com sessão acrescenta campos (ex.: `followedByMe`).

### 3.1 `POST /auth/register`

```http
POST /api/v1/auth/register
Content-Type: application/json

{ "username": "ana.silva", "email": "ana@example.org", "password": "uma frase longa e minha" }
```

**`201 Created`** — a conta fica criada e a sessão iniciada (mesmo formato do login, §3.2). Cabeçalho `Location: /api/v1/users/{userId}`.

Validações:

| Campo | Regra | Mensagem |
|---|---|---|
| `username` | §2.1; não reservado | "O nome de utilizador deve ter 3 a 30 caracteres: letras minúsculas, números, '.' ou '_'." |
| `email` | §2.1 | "Indica um email válido." |
| `password` | 10–64 caracteres e no máximo 72 bytes em UTF-8; diferente do username e do email | "A palavra-passe deve ter entre 10 e 64 caracteres." |

Conflitos (`409`): username já usado → erro no campo `username` ("Este nome de utilizador já está a ser usado."); email já registado → erro no campo `email` ("Não é possível criar uma conta com este email."). Ver §6.7 sobre o que isto revela.

O username e o email são normalizados (espaços nas pontas removidos, minúsculas) antes de validar. A palavra-passe **não** é alterada (espaços incluídos).

### 3.2 `POST /auth/login`

```http
POST /api/v1/auth/login
Content-Type: application/json

{ "email": "ana@example.org", "password": "uma frase longa e minha" }
```

**`200 OK`**

```json
{
  "tokenType": "Bearer",
  "accessToken": "eyJhbGciOiJSUzI1NiIsImtpZCI6InVzZXJzLTIwMjYtMTAifQ.…",
  "accessTokenExpiresIn": 900,
  "refreshToken": "q0b1u7X2…(43 caracteres base64url)…",
  "refreshTokenExpiresIn": 2592000,
  "user": {
    "userId": "6f9a2c1e-3b7d-4e8a-9c51-2d4f6a8b0e13",
    "username": "ana.silva",
    "email": "ana@example.org",
    "description": null,
    "interests": [],
    "followersCount": 0,
    "followingCount": 0,
    "createdAt": "2026-10-18T10:15:00Z"
  }
}
```

(Tokens truncados; valores ilustrativos.) `*ExpiresIn` em segundos.

Credenciais erradas — email inexistente **ou** palavra-passe errada, com a mesma resposta e tempo semelhante (§6.7):

```json
{
  "title": "Não foi possível iniciar sessão",
  "status": 401,
  "detail": "Email ou palavra-passe incorretos.",
  "instance": "/api/v1/auth/login",
  "code": "INVALID_CREDENTIALS"
}
```

Demasiadas tentativas: `429` com `Retry-After` e `"code": "TOO_MANY_ATTEMPTS"` (§6.8).

### 3.3 `POST /auth/refresh`

```http
POST /api/v1/auth/refresh
Content-Type: application/json

{ "refreshToken": "q0b1u7X2…" }
```

**`200 OK`** — **novo** access token **e novo** refresh token (mesmo formato do login, sem o objeto `user`). O refresh token enviado deixa de ser válido.

`401` com `"code": "INVALID_REFRESH_TOKEN"` se o token não existir, tiver expirado, sido revogado ou **já tiver sido usado** (neste último caso a família inteira é revogada, §6.4). A resposta é a mesma nos quatro casos.

- `refreshTokenExpiresIn` é o tempo que **falta** até ao prazo máximo da sessão; diminui a cada renovação e nunca volta a 30 dias.
- **Renovação atómica:** consumir o token antigo e criar o novo acontecem numa única transação, com uma atualização condicional (`... WHERE used_at IS NULL AND revoked_at IS NULL AND expires_at > now()`). Se dois pedidos chegarem com o mesmo token, só um o consome; o outro é tratado como reutilização (§6.4).
- **Resposta perdida:** se o servidor renovou mas a resposta não chegou à app (timeout, ligação cortada depois do envio), o token que a app tem **já foi consumido**. Reenviá-lo seria uma reutilização e terminaria a sessão. Por isso a app **não repete automaticamente** uma renovação cujo resultado é desconhecido (§8.3); a consequência aceite é que, nesse caso raro, o utilizador terá de voltar a entrar.

### 3.4 `POST /auth/logout`

```http
POST /api/v1/auth/logout
Content-Type: application/json

{ "refreshToken": "q0b1u7X2…" }
```

**`204 No Content`** — revoga a família desse refresh token (esta sessão, neste dispositivo). Idempotente: um token desconhecido ou já revogado também devolve 204. Não exige access token (um access token expirado não deve impedir o logout). Limites em §6.5.

### 3.5 `GET /me`, `PATCH /me`

`GET /api/v1/me` → `200` com o perfil privado (objeto `user` do §3.2, incluindo `email`).

```http
PATCH /api/v1/me
Authorization: Bearer eyJ…
Content-Type: application/json

{ "description": "Corro ao fim de semana e estou a começar no boxe." }
```

`200` com o perfil privado atualizado. Só `description` é editável; campos desconhecidos ou não editáveis (`username`, `email`, `userId`) dão `400` com erro no campo respetivo. `"description": null` ou `""` apaga a descrição.

### 3.6 `PUT /me/interests`

```http
PUT /api/v1/me/interests
Authorization: Bearer eyJ…
Content-Type: application/json

{ "interests": ["boxe", "corrida"] }
```

`200` com o perfil privado atualizado. Substitui a lista completa (a ordem enviada é guardada). Regras e comportamento com o catálogo indisponível em §7.

### 3.7 `GET /users` — pesquisa

| Parâmetro | Por omissão | Validação |
|---|---|---|
| `q` | — (obrigatório) | 2–50 caracteres após remover espaços nas pontas |
| `page` | 0 | ≥ 0 |
| `size` | 20 | 1–50 |

Procura `q` **dentro do username**, sem distinguir maiúsculas/minúsculas. Os usernames só usam `a-z`, `0-9`, `.` e `_`, por isso a questão dos acentos não se põe; um `q` com caracteres fora desse conjunto simplesmente não encontra ninguém. Ordem: correspondência exata primeiro, depois os que começam por `q`, depois os restantes; em cada grupo por username e, por fim, `userId`.

```http
GET /api/v1/users?q=ana&size=2
```

```json
{
  "items": [
    { "userId": "6f9a2c1e-3b7d-4e8a-9c51-2d4f6a8b0e13", "username": "ana.silva", "description": null },
    { "userId": "0c4e8a2b-1d3f-4b6a-8e9c-7f1a2b3c4d5e", "username": "joana_corre", "description": "Corrida no Parque da Cidade." }
  ],
  "page": 0,
  "size": 2,
  "totalItems": 2,
  "totalPages": 1
}
```

(Exemplo ilustrativo; não serão criados utilizadores fictícios nesta etapa.) O resumo de utilizador (`UserSummary`) tem só `userId`, `username` e `description`; **nunca** o email.

### 3.8 `GET /users/{userId}` — perfil público

```json
{
  "userId": "6f9a2c1e-3b7d-4e8a-9c51-2d4f6a8b0e13",
  "username": "ana.silva",
  "description": "Corro ao fim de semana e estou a começar no boxe.",
  "interests": ["boxe", "corrida"],
  "followersCount": 3,
  "followingCount": 5,
  "followedByMe": false
}
```

- `followedByMe` só aparece com um access token válido de **outro** utilizador. Sem token (visitante), ou no próprio perfil, o campo é **omitido** — não é `false`: a relação simplesmente não foi consultada, e a app não a deve apresentar como uma resposta autenticada.
- Um access token **inválido ou expirado** num pedido a este endpoint público dá `401 INVALID_ACCESS_TOKEN` (não é tratado como visitante); a app renova a sessão e repete, ou pede sem token.
- `interests` são ids do catálogo. A app mostra os nomes com a lista de `GET /api/v1/sports` do catalog-service, que já carrega; se o catálogo estiver indisponível ou um id não existir nessa lista, mostra um nome feito a partir do id (ex.: `artes-marciais` → "Artes marciais"), para os interesses continuarem legíveis. O users-service **não** chama o catálogo para ler perfis.
- `404` se o utilizador não existir; `400` se o id não for um UUID válido.

### 3.9 Seguir

`PUT /api/v1/users/{userId}/follow` → `204` (já seguido também dá 204). `DELETE /api/v1/users/{userId}/follow` → `204` (não seguido também dá 204).

- `400` com `"code": "CANNOT_FOLLOW_SELF"` se `userId` for o do próprio.
- `404` se o utilizador não existir.
- **Limite de produto (E4): cada pessoa segue no máximo 500 outras.** Seguir uma pessoa nova com 500 já seguidas → `409` com `"code": "FOLLOW_LIMIT_REACHED"` e nada é gravado. Seguir alguém já seguido continua `204`, mesmo no limite, e deixar de seguir nunca é limitado. A contagem e a inserção acontecem na mesma transação, com a linha do seguidor bloqueada, por isso pedidos simultâneos não ultrapassam o limite. O limite existe para o contexto do feed (§3.11) ser sempre a lista completa, sem truncagem.
- O `followerId` vem sempre do token, nunca do pedido.
- Começar a seguir (relação nova) grava, na mesma transação, um evento `USER_FOLLOWED` na outbox, entregue depois ao notifications-service ([notifications-api.md §3, §7](notifications-api.md#3-eventos-e-destinatários)). Confirmar uma relação existente não grava nada. Os ids dos seguidores, para as notificações de novas experiências, vêm de um endpoint interno só para o notifications-service ([notifications-api.md §8.1](notifications-api.md#81-fonte-os-seguidores-do-autor-no-users-service)). Ver também [arquitetura.md §3.11](arquitetura.md#311-notificações).

### 3.10 Seguidores e seguidos

`GET /api/v1/users/{userId}/followers` e `GET /api/v1/users/{userId}/following`, com `page` (≥ 0, por omissão 0) e `size` (1–50, por omissão 20). Respondem com o envelope paginado do catálogo e itens com o resumo de utilizador mais a data da relação:

```json
{
  "items": [
    { "userId": "0c4e8a2b-1d3f-4b6a-8e9c-7f1a2b3c4d5e", "username": "joana_corre", "description": "Corrida no Parque da Cidade.", "since": "2026-10-18T11:02:00Z" }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 1,
  "totalPages": 1
}
```

Ordem: relação mais antiga primeiro (`since` ascendente), desempate por `userId`. Ver §5 sobre estabilidade.

### 3.11 `GET /internal/me/feed-context`

Contexto do feed pessoal (E4, [experiences-api.md §14.2–14.3](experiences-api.md#142-feed-pessoal-get-feed)). Revisto em 2026-10-19: substitui a proposta anterior `/internal/users/{userId}/feed-context`, que permitia a quem tivesse a chave interna ler o contexto de qualquer pessoa.

**Autorização (as duas condições):**

- `Authorization: Bearer <access token do utilizador>`, reencaminhado pelo experiences-service a partir do pedido da app. O contexto devolvido é sempre o do `sub` do token; **não há `userId` no caminho nem nos parâmetros**. Token em falta, inválido ou expirado → `401`.
- `X-Internal-Key: <chave do experiences-service>` ([arquitetura.md §3.8](arquitetura.md#38-autenticação-e-autorização)), comparada em tempo constante. Em falta ou errada → `403` (mesmo com um token válido): a app, que só tem o token, não consegue usar este endpoint.

**Resposta `200`** (só ids; nenhum username, email ou outro dado de perfil):

```json
{
  "following": ["0c4e8a2b-1d3f-4b6a-8e9c-7f1a2b3c4d5e"],
  "interests": ["boxe", "corrida"]
}
```

- `following`: **todos** os ids das pessoas seguidas (`since` descendente, desempate pelo `userId`). São no máximo 500 pelo limite aplicado ao seguir (§3.9); não há truncagem.
- `interests`: os ids guardados (0–10), pela ordem do perfil, mesmo que uma modalidade tenha deixado de existir no catálogo (o users-service não consulta o catálogo neste pedido).
- Leitura só da `users_db`, sem chamadas a outros serviços. Sem cache: seguir ou mudar interesses reflete-se logo.
- `Cache-Control: no-store`. Logs sem o token nem a chave; o utilizador identificado pelo `userId`.
- **Exposição:** o caminho fica fora dos endpoints usados pela app. Os serviços só escutam em `127.0.0.1` no computador ([infraestrutura.md](infraestrutura.md)), e o experiences-service chama-o pela rede interna do Compose.

Comportamento do experiences-service ([experiences-api.md §14.3](experiences-api.md#143-chamada-ao-users-service)):

- espera no máximo 0,5 s pela ligação e 1 s pela resposta, sem novas tentativas;
- um `401` daqui chega à app como `401 INVALID_ACCESS_TOKEN`;
- um `403` (chave errada) é registado como erro de configuração e o feed passa ao popular com `mode: POPULAR_FALLBACK`, nunca como "sem preferências";
- indisponibilidade → `POPULAR_FALLBACK`.

O feed nunca é apresentado como personalizado sem este contexto.

## 4. Erros

Problem Details (`application/problem+json`), como no catálogo, com uma extensão: o campo **`code`**, estável e em inglês, para a app decidir o que fazer sem depender do texto. Erros de validação mantêm a lista `errors` (`field`, `message`).

| HTTP | `code` | Quando |
|---|---|---|
| 400 | `VALIDATION_FAILED` | parâmetros ou corpo inválidos (com `errors`) |
| 400 | `CANNOT_FOLLOW_SELF` | seguir-se a si próprio |
| 400 | `UNKNOWN_INTERESTS` | ids de modalidade inexistentes no catálogo (com `errors`) |
| 401 | `AUTHENTICATION_REQUIRED` | falta o access token num endpoint protegido |
| 401 | `INVALID_ACCESS_TOKEN` | access token inválido ou expirado (cabeçalho `WWW-Authenticate: Bearer error="invalid_token"`) |
| 401 | `INVALID_CREDENTIALS` | login falhado (§6.7) |
| 401 | `INVALID_REFRESH_TOKEN` | refresh token inválido, expirado, revogado ou reutilizado |
| 403 | `FORBIDDEN` | chave interna em falta ou errada |
| 404 | `NOT_FOUND` | utilizador inexistente |
| 405 | `METHOD_NOT_ALLOWED` | método não suportado |
| 409 | `CONFLICT` | username ou email já usados no registo (com `errors`) |
| 409 | `FOLLOW_LIMIT_REACHED` | já segue 500 pessoas (§3.9) |
| 429 | `TOO_MANY_ATTEMPTS` | limite de tentativas (cabeçalho `Retry-After` em segundos) |
| 500 | `INTERNAL_ERROR` | erro inesperado, sem detalhes internos |
| 503 | `CATALOG_UNAVAILABLE` | não foi possível validar interesses (§7) |
| 503 | `SERVICE_UNAVAILABLE` | base de dados indisponível |

Exemplo de validação no registo:

```json
{
  "title": "Pedido inválido",
  "status": 400,
  "detail": "Um ou mais campos são inválidos.",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "username", "message": "O nome de utilizador deve ter 3 a 30 caracteres: letras minúsculas, números, '.' ou '_'." },
    { "field": "password", "message": "A palavra-passe deve ter entre 10 e 64 caracteres." }
  ]
}
```

Nenhuma resposta de erro repete a palavra-passe, tokens ou o email recebidos.

## 5. Paginação

Mesmo envelope e limites do catálogo (`items`, `page`, `size`, `totalItems`, `totalPages`; `page` ≥ 0; `size` 1–50, por omissão 20; página para além do fim → `items: []`). Nesta versão a paginação é por *offset* (`page` × `size`).

Todas as ordenações são **totais** (terminam no `userId`): a mesma consulta sobre os mesmos dados devolve sempre a mesma ordem. Isso **não torna as páginas imutáveis**: se entre dois pedidos houver inserções ou remoções antes da posição já lida, os elementos seguintes deslocam-se e um utilizador pode aparecer em duas páginas ou não aparecer em nenhuma.

- As listas de seguidores e seguidos ordenam pela relação mais antiga primeiro, para que **novas** relações entrem no fim e não desloquem as páginas já lidas.
- **Remoções** (deixar de seguir) deslocam os elementos seguintes uma posição para trás: um elemento pode ficar por mostrar.
- A pesquisa ordena por username, pelo que um registo novo pode entrar em qualquer posição.
- A app remove repetições pelo `userId`, como já faz na pesquisa de locais. Paginação por cursor (que evitaria estes casos) fica como melhoria possível.

## 6. Segurança

### 6.1 Identificador estável

O `userId` (UUID) é a referência usada por todos os serviços (autor, seguidor, destinatário, dono de lista). O username é dado de apresentação e não é editável nesta fase ([arquitetura.md §3.5](arquitetura.md#35-cópias-de-dados-desnormalização)).

### 6.2 Palavras-passe

- Guardadas com **BCrypt, custo 12** como valor inicial, através do `DelegatingPasswordEncoder` do Spring Security, que guarda o algoritmo junto do hash (`{bcrypt}…`) e permite migrar mais tarde (ex.: Argon2id) sem invalidar contas. O tempo de cálculo é medido no ambiente de desenvolvimento e registado em §12; se for excessivo, o custo é revisto.
- **Antes do BCrypt**, a validação confirma 10–64 caracteres **e** no máximo 72 bytes em UTF-8. Uma palavra-passe fora destes limites é **rejeitada** (`400`), nunca truncada: o BCrypt ignora tudo a partir do 73.º byte, e sem esta verificação duas palavras-passe diferentes podiam dar o mesmo hash.
- O hash nunca é devolvido em nenhuma resposta nem escrito em logs.
- Sem regras de composição (maiúsculas, símbolos): o comprimento mínimo de 10 caracteres é o requisito principal; frases são incentivadas.

### 6.3 Access token (JWT)

- Assinado com **RS256** (par de chaves RSA de 2048 bits ou mais). O users-service assina com a chave privada; os outros serviços só têm a chave pública.
- Cabeçalho com `kid` (identificador da chave) para permitir rodar chaves.
- Claims:

| Claim | Valor |
|---|---|
| `iss` | `portofit-users` |
| `aud` | `portofit-api` |
| `sub` | `userId` |
| `username` | username (só para apresentação) |
| `iat`, `exp` | emissão e expiração |
| `jti` | id único do token |

- **Validade: 15 minutos.**
- Todos os serviços validam **assinatura, `iss`, `aud`, `exp`** (e `nbf`/`iat` se presentes), com tolerância de 60 s para diferenças de relógio. Tokens com algoritmo diferente de RS256 (incluindo `none`) são rejeitados.
- O token não contém email nem dados pessoais além do username.

**Distribuição da chave pública:** cada serviço recebe a chave pública por configuração (ficheiro montado só de leitura), e por isso valida tokens **sem chamar o users-service** ([arquitetura.md §3.8](arquitetura.md#38-autenticação-e-autorização)). O `/.well-known/jwks.json` publica as mesmas chaves, útil para inspeção e para uma futura configuração automática. Para rodar a chave: publicar a nova chave pública nos serviços, depois passar a assinar com ela; a antiga continua aceite até expirarem os tokens que assinou (15 min).

### 6.4 Refresh tokens: duração, rotação e revogação

- Valor aleatório opaco de 256 bits (base64url), gerado com `SecureRandom`; **guardado só como hash SHA-256**.
- **Prazo máximo da sessão: 30 dias desde o login.** O prazo é fixado no login e herdado por todos os tokens da mesma família; renovar **não** o prolonga. Ao fim de 30 dias é preciso voltar a entrar, mesmo com uso diário.
- **Rotação:** cada `POST /auth/refresh` consome o token apresentado e devolve um novo na mesma família. Cada token só pode ser usado uma vez.
- **Atomicidade:** o consumo é uma atualização condicional numa única transação (§3.3); dois pedidos simultâneos com o mesmo token nunca obtêm dois tokens novos.
- **Deteção de reutilização:** apresentar um token já consumido indica que pode ter sido copiado — ou que a app o reenviou por engano. Em ambos os casos o serviço **revoga toda a família** (incluindo o token mais recente) e responde `401 INVALID_REFRESH_TOKEN`. Por isso a app tem de partilhar uma única renovação entre pedidos concorrentes e não repetir renovações de resultado desconhecido (§8.3).
- **Revogação:** o logout revoga a família da sessão atual. Tokens expirados ou revogados são apagados periodicamente.
- Cada dispositivo com login tem a sua família; terminar sessão num não afeta os outros. "Terminar sessão em todos os dispositivos" fica para depois.

### 6.5 Limites do logout e da revogação

- O logout revoga o **refresh token**; o **access token** já emitido **continua válido até expirar (no máximo 15 min)**, porque os outros serviços validam-no localmente e não consultam uma lista de revogados. A app apaga-o de imediato, por isso o efeito prático só existe se o token tiver sido copiado.
- Se o logout falhar (sem rede, users-service em baixo), a app apaga os tokens locais na mesma; o refresh token no servidor continua válido até expirar ou até ser revogado por reutilização. A app pode tentar o logout de novo mais tarde, mas nesta fase não o faz.
- Uma alteração feita depois da emissão do access token (ex.: futura desativação de conta) só tem efeito quando ele expirar. É o compromisso da validação local, já descrito em [arquitetura.md §3.8](arquitetura.md#38-autenticação-e-autorização).

### 6.6 Chaves e credenciais

- A chave privada de assinatura, a chave pública e as palavras-passe da base de dados ficam **fora do Git e das imagens Docker**: numa pasta local `secrets/` (a acrescentar ao `.gitignore`) e no `.env`, montadas no contentor em tempo de execução. Só o users-service recebe a chave privada.
- Cada pessoa gera o seu par de chaves de desenvolvimento; as instruções entram na documentação de infraestrutura na etapa de implementação.
- Logs: nunca registar palavras-passe, tokens, o cabeçalho `Authorization` nem corpos de pedidos de `/auth/*`. Identificar utilizadores nos logs pelo `userId`, não pelo email.
- HTTP sem TLS só em desenvolvimento local ([app-android.md §3](app-android.md#3-ligação-ao-backend)). Uma instalação real tem de usar HTTPS: tokens e palavras-passe em claro seriam expostos na rede.

### 6.7 Respostas de login e registo

- Login falhado devolve **sempre** `401 INVALID_CREDENTIALS` com a mesma mensagem, quer o email não exista quer a palavra-passe esteja errada. Quando o email não existe, o serviço compara a palavra-passe com um hash fictício fixo, para o tempo de resposta ser semelhante.
- O mesmo limite de tentativas (§6.8) aplica-se em ambos os casos.
- **Limitação aceite:** o registo tem de dizer quando um email já está usado (`409`), o que permite descobrir se um email tem conta. Evitá-lo exige verificação por email, fora do âmbito. Mitigação: limite de registos por origem (§6.8).

### 6.8 Proteção contra tentativas repetidas

Contadores em memória no users-service, com janela fixa configurável. Há contadores **por conta** (email normalizado) e **por origem** (endereço IP de onde chega o pedido), independentes entre si: atingir qualquer um deles basta para recusar.

| Contador | O que conta | Por omissão | Propriedade |
|---|---|---|---|
| Login, por conta | logins **falhados** para o mesmo email, de qualquer origem | 5 | `portofit.rate-limit.login-failures-per-account` |
| Login, por origem | **todas** as tentativas de login da mesma origem, para qualquer conta | 30 | `portofit.rate-limit.login-attempts-per-origin` |
| Registo, por origem | pedidos de registo da mesma origem | 10 | `portofit.rate-limit.register-per-origin` |
| Refresh, por origem | pedidos de renovação da mesma origem | 60 | `portofit.rate-limit.refresh-per-origin` |
| Janela | duração de cada contador | 15 min | `portofit.rate-limit.window` |

- Ao exceder: `429 TOO_MANY_ATTEMPTS` com `Retry-After` (segundos até ao fim da janela). No login, o bloqueio por conta aplica-se **mesmo com a palavra-passe certa** até a janela terminar; um login bem-sucedido antes do limite repõe o contador dessa conta.
- É um **atraso temporário**, não um bloqueio permanente (um bloqueio permanente permitiria a qualquer pessoa bloquear contas alheias). A app mostra "Demasiadas tentativas. Tenta novamente daqui a N minutos."
- **Privacidade:** a chave do contador por conta é um hash SHA-256 do email normalizado, não o email. Os logs registam só o tipo de contador e a origem, nunca emails, palavras-passe ou tokens.
- **Limitações dos contadores em memória:** perdem-se ao reiniciar o serviço; não são partilhados entre instâncias (com várias réplicas, cada uma conta à parte); a janela fixa permite até ao dobro do limite em torno da mudança de janela; a origem é o endereço visto pelo serviço — em desenvolvimento todos os pedidos do emulador e do PC chegam pela rede do Docker com o mesmo endereço, e atrás de um proxy seria preciso tratar `X-Forwarded-For` (não é feito, para não confiar em cabeçalhos enviados pelo cliente). Contadores partilhados (ex.: na base de dados ou num Redis) ficam para quando houver mais de uma instância.

## 7. Interesses

- Os interesses são **ids de modalidade do catálogo** (`boxe`, `natacao`…), os mesmos de `GET /api/v1/sports` ([catalog-api.md §2.1](catalog-api.md#21-sport--modalidade)).
- Regras: 0–10 ids, sem repetições, cada um com o formato de slug do catálogo.
- **Validação:** em `PUT /me/interests`, o users-service pede `GET /api/v1/sports` ao catalog-service (timeout ~2 s) e confirma que todos os ids **novos** (os que o utilizador ainda não tinha) existem. Ids inexistentes → `400 UNKNOWN_INTERESTS`, com um erro por id.
- Para reduzir chamadas, a lista de modalidades obtida é guardada em memória até 10 minutos (proposta). Uma lista mais antiga não é usada para validar.

**Catálogo indisponível** (sem resposta, erro ou timeout, e sem lista recente em memória):

| Pedido | Resultado |
|---|---|
| Acrescentar algum interesse novo | `503 CATALOG_UNAVAILABLE`; os interesses ficam como estavam; a app mostra "Não foi possível guardar os interesses agora. Tenta novamente." |
| Só remover interesses (todos os enviados já estavam guardados) ou lista vazia | aceite: não é preciso validar nada |
| Ler perfis, registar, entrar, editar a descrição, seguir | não dependem do catálogo |

Se uma modalidade deixar de existir no catálogo, os perfis que a tinham continuam a guardá-la; a app não a mostra e o utilizador pode removê-la. Não há limpeza automática nesta fase.

## 8. App Android: ecrãs e sessão (proposta)

### 8.1 Princípios

- **Pesquisa de locais e detalhe continuam acessíveis sem login**, como agora. Perfis públicos, pesquisa de utilizadores e listas de seguidores também.
- A sessão é opcional: o login só é pedido quando o utilizador tenta uma ação que o exige (ex.: seguir), e depois de entrar volta ao ecrã onde estava.

### 8.2 Ecrãs

| Ecrã | Acesso | Conteúdo |
|---|---|---|
| Pesquisa (existente) | todos | ação na barra superior: "Entrar" (visitante) ou "O meu perfil" (com sessão); secção ou separador "Utilizadores" com resultados de `GET /users` |
| Entrar | visitante | email, palavra-passe (teclado de palavra-passe, sem correção automática, preenchimento automático), "Entrar", ligação para "Criar conta" |
| Criar conta | visitante | username, email, palavra-passe, com as regras do §3.1 visíveis; erros por campo |
| O meu perfil | com sessão | username, email (só aqui), descrição, interesses, contagens, ligações para seguidores e seguidos, "Editar perfil", "Terminar sessão" |
| Editar perfil | com sessão | descrição (máx. 300) e interesses como chips das modalidades de `/sports`; guardar descrição e interesses separadamente, para que uma falha do catálogo não impeça guardar a descrição |
| Perfil público | todos | username, descrição, interesses, contagens, botão "Seguir"/"A seguir" (pede login se for visitante); espaço reservado para as publicações, preenchido quando existir o experiences-service |
| Seguidores / A seguir | todos | lista paginada com "Carregar mais", como a pesquisa de locais |

### 8.3 Comportamento da sessão

- Um `SessionRepository` (no `AppContainer`) expõe o estado da sessão como `StateFlow`: **Visitante** ou **Com sessão** (`userId`, `username`).
- **Access token só em memória.** **Refresh token** guardado cifrado: chave AES-GCM no **Android Keystore** (não exportável) e o texto cifrado num **ficheiro privado da app** (`files/session/`; na implementação optou-se por um ficheiro com escrita atómica em vez do DataStore, sem dependências novas). O ficheiro fica excluído das cópias de segurança (`backup_rules.xml` / `data_extraction_rules.xml`), porque a chave do Keystore não é restaurada noutro aparelho. (Não se usa `EncryptedSharedPreferences`, que foi descontinuado.)
- Pedidos autenticados levam `Authorization: Bearer`. Perante um `401 INVALID_ACCESS_TOKEN`, um `Authenticator` do OkHttp renova a sessão e repete o pedido **uma vez**.
- **Uma única renovação partilhada:** a renovação está protegida por exclusão mútua. Se vários pedidos recebem `401` ao mesmo tempo, só o primeiro renova; os outros esperam e usam o novo access token (se, ao obterem a vez, o token guardado já for mais recente do que o que causou o `401`, não renovam de novo).
- O cliente HTTP da renovação não faz novas tentativas automáticas (`retryOnConnectionFailure = false`).

Resultado de uma renovação e o que a app faz:

| Resultado | Significado | Sessão local |
|---|---|---|
| `200` | renovada | guarda os novos tokens **antes** de repetir os pedidos |
| `401 INVALID_REFRESH_TOKEN` | inválido, expirado, revogado ou reutilizado | **apaga** os tokens, passa a Visitante, mostra "A tua sessão terminou. Entra novamente." |
| Falha **antes** de o pedido ser enviado (ex.: sem rede, ligação recusada) | o token não foi consumido | mantém a sessão; mostra o erro com "Tentar novamente" |
| Resultado desconhecido (timeout ou ligação cortada **depois** do envio) | o servidor pode já ter consumido o token | mantém a sessão, mas **não repete automaticamente**; mostra o erro. Uma nova tentativa do utilizador pode terminar em `401` (resposta perdida, §3.3) e aí pede novo login |
| `429` / `5xx` | limite de tentativas ou servidor em baixo | mantém a sessão; mostra o erro com "Tentar novamente" (não apaga a sessão) |
- Ao abrir a app com um refresh token guardado, mostra logo o estado Com sessão (username guardado) e renova só no primeiro pedido autenticado; o arranque não espera pelo users-service.
- "Terminar sessão" chama `POST /auth/logout` (timeout curto) e apaga os tokens locais **aconteça o que acontecer** (§6.5).
- Palavras-passe e tokens nunca vão para o `SavedStateHandle`, logs ou mensagens de erro. O registo de pedidos HTTP (se for acrescentado para depuração) só existe em debug e oculta o cabeçalho `Authorization` e os corpos de `/auth/*`.
- O users-service no emulador usa `http://10.0.2.2:8082/`, abrangido pela configuração de rede de debug já existente (o mesmo endereço; a permissão de rede local também se aplica).

## 9. Dependências e falhas

| Situação | Efeito |
|---|---|
| catalog-service em baixo | só acrescentar interesses novos falha (§7); tudo o resto funciona |
| experiences-service em baixo | sem efeito no users-service; na app, a secção de publicações do perfil mostra "indisponível" |
| notifications-service em baixo (a partir da etapa 10) | seguir continua a funcionar; o evento fica na outbox |
| users-service em baixo | login, registo, renovação, perfis e seguir indisponíveis; outros serviços continuam a aceitar access tokens ainda válidos ([arquitetura.md §4.3](arquitetura.md#43-comportamento-perante-falhas)) |

O users-service não chama nenhum outro serviço para autenticar, ler perfis ou seguir.

## 10. Dados (users_db)

Base de dados `users_db` com utilizador próprio sem privilégios de administração, como o catálogo. Tabelas: `app_user`, `user_interest`, `follow`, `refresh_token` e `outbox` (etapa 10, `V3__outbox.sql`). Esquema gerido por Flyway; sem dados de demonstração nas migrações.

## 11. Decisões da revisão (2026-10-18)

| # | Decisão |
|---|---|
| 1 | Login só por email |
| 2 | Pesquisa pública só por username, sem distinguir maiúsculas/minúsculas |
| 3 | A data de registo não aparece no perfil público (só em `GET /me`) |
| 4 | Access token de 15 min; refresh token com prazo máximo de 30 dias por sessão, que a rotação não prolonga |
| 5 | BCrypt custo 12 como valor inicial, com o tempo medido no ambiente disponível |
| 6 | Limites de caracteres e bytes verificados antes do BCrypt, sem truncar |
| 7 | Renovação atómica no servidor; na app, uma única renovação partilhada e sem novas tentativas automáticas de resultado desconhecido |
| 8 | Contadores de tentativas por conta e por origem, configuráveis, sem emails nem tokens em logs |
| 9 | Paginação por offset nesta versão, sem garantia de páginas imutáveis quando há alterações |
| 10 | Refresh inválido ou revogado → novo login; falha temporária de rede ou servidor → a sessão mantém-se |

## 12. Estado da implementação

Etapa 5 (backend), `backend/users-service`. Operação em [infraestrutura.md](infraestrutura.md).

**Implementado:** `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`; `GET /me`, `PATCH /me`, `PUT /me/interests`; `GET /users?q=`, `GET /users/{userId}`; `GET /.well-known/jwks.json`; `/actuator/health`. Erros, validações, limites de tentativas e interesses como descrito neste contrato.

**Seguir (implementado depois, migração `V2__follows.sql`):** `PUT`/`DELETE /users/{userId}/follow` (idempotentes, `204`; `CANNOT_FOLLOW_SELF`; `404` para utilizador inexistente), `GET /users/{userId}/followers` e `/following` (públicos, paginados, relação mais antiga primeiro e desempate pelo `userId`, só `userId`, `username`, `description` e `since`), `followersCount`/`followingCount` em `GET /me` e no perfil público, `followedByMe` como descrito em §3.8. Unicidade garantida pela chave primária `(follower_id, followed_id)` e `INSERT … ON CONFLICT DO NOTHING`; seguir-se a si próprio também é impedido por uma restrição na base de dados.

Na app: botão Seguir / Deixar de seguir, contagens e listas, ver [app-android.md §13](app-android.md#13-seguir-utilizadores-e-listas-de-seguidoresseguidos-etapa-5-continuação).

**E4 (etapa 9):** `GET /api/v1/internal/me/feed-context` (§3.11: token do utilizador e chave interna `USERS_INTERNAL_KEY`, só ids, `no-store`) e o limite de 500 seguidos aplicado ao seguir (§3.9, `409 FOLLOW_LIMIT_REACHED`, com a linha do seguidor bloqueada na transação). Testes e verificação em [experiences-api.md §20](experiences-api.md#20-estado-da-implementação).

**Notificações (etapa 10):** evento `USER_FOLLOWED` na outbox (`V3__outbox.sql`) e `GET /api/v1/internal/users/{userId}/followers` com `USERS_FOLLOWERS_KEY` (só ids, cursor estável, recorte por `followedBefore`). Regras, testes (29 no total) e verificação em [notifications-api.md §20](notifications-api.md#20-estado-da-implementação).

**Notas da implementação**

- JWT com a biblioteca Nimbus através do Spring Security (`NimbusJwtEncoder` / `NimbusJwtDecoder`); `kid` = impressão digital RFC 7638 da chave pública; chave RSA de 3072 bits gerada uma vez (`scripts/generate-jwt-keys.sh`).
- Palavras-passe com `DelegatingPasswordEncoder` + `BCryptPasswordEncoder` do Spring Security.
- Renovação: `UPDATE refresh_token SET used_at = now() WHERE token_hash = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > now() RETURNING …`, e inserção do novo token na mesma transação; a revogação por reutilização é gravada antes de responder `401`.
- Os pedidos a `/api/v1/auth/` ignoram o cabeçalho `Authorization` (um access token expirado não impede login, renovação nem logout).
- Cache das modalidades do catálogo: 10 min (`portofit.catalog.cache-ttl`).

**Medição do BCrypt (custo 12)** no ambiente de desenvolvimento (contentor Docker; PC com ~8 GB de RAM, quase sem memória livre durante a medição): login com cerca de **2,3 s** por pedido, quando um pedido simples ao mesmo serviço demora ~0,22 s — ou seja, ~2 s só no BCrypt. É bastante acima dos ~0,25 s habituais para custo 12 num computador sem esta pressão de memória. O custo mantém-se em 12 (decisão da revisão) e é configurável em `portofit.security.bcrypt-strength`; baixar para 10 dividiria o tempo por cerca de 4. Os login com email inexistente e com palavra-passe errada demoraram o mesmo (2,31 s vs 2,29 s).

**Testes** (`.\gradlew.bat test` em `backend/users-service`, PostgreSQL 18.6 real via Testcontainers, BCrypt custo 4 só nos testes): 24 testes — os 19 iniciais e 5 de seguir (sessão obrigatória, seguir-se a si próprio, utilizador inexistente e id inválido; idempotência e contagens; 8 pedidos simultâneos → 1 relação; `followedByMe` ausente para visitantes e no próprio perfil, `true`/`false` para outra pessoa, token inválido → 401; listas paginadas, ordenadas e sem email). Os 19 iniciais: — registo e validações (incluindo 72 bytes), unicidade sem distinguir maiúsculas, 8 registos simultâneos com o mesmo username (1 criado, 7 recusados), login correto, respostas iguais para email inexistente e palavra-passe errada, limite por conta, access token ausente/inválido/expirado/emissor ou destinatário errados/outra chave/sem assinatura, edição só do próprio perfil e só da descrição, privacidade do perfil público e da pesquisa, rotação, reutilização (família revogada), logout idempotente com access token expirado no cabeçalho, sessão expirada, renovações simultâneas (no máximo uma com sucesso), interesses com o catálogo disponível e em falha, JWKS sem parâmetros privados, e contadores de tentativas por origem e por conta.
