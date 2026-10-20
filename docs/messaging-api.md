# messaging-service — Contrato da API

> Conversas privadas entre duas pessoas, mensagens de texto e partilha de experiências (requisito R9, etapa 12). Contexto geral em [arquitetura.md](arquitetura.md) (§3.2 responsabilidades, §3.3 "a partilha é uma mensagem", §3.5 cópias, §3.8 autenticação, §4 falhas).
> Estado: **contrato aprovado** (2026-10-20, com os ajustes da revisão, [§17](#17-decisões)).
> Mesmas convenções do [catalog-api.md](catalog-api.md), do [users-api.md](users-api.md), do [experiences-api.md](experiences-api.md) e do [collections-api.md](collections-api.md): JSON UTF-8, campos em inglês (camelCase), textos em português, datas ISO 8601 UTC, erros em Problem Details com `code`.

## Índice

1. [Responsabilidade e âmbito](#1-responsabilidade-e-âmbito)
2. [Entidades](#2-entidades)
3. [Autenticação e privacidade](#3-autenticação-e-privacidade)
4. [Endpoints](#4-endpoints)
5. [Conversas](#5-conversas)
6. [Mensagens](#6-mensagens)
7. [Partilha de experiências](#7-partilha-de-experiências)
8. [Participantes e users-service](#8-participantes-e-users-service)
9. [Erros](#9-erros)
10. [Limites](#10-limites)
11. [Dependências e falhas](#11-dependências-e-falhas)
12. [Consulta periódica e consumo de recursos](#12-consulta-periódica-e-consumo-de-recursos)
13. [Conservação dos dados](#13-conservação-dos-dados)
14. [Dados e infraestrutura](#14-dados-e-infraestrutura)
15. [App Android (proposta)](#15-app-android-proposta)
16. [Critérios de teste](#16-critérios-de-teste)
17. [Decisões](#17-decisões)
18. [Decisões da revisão](#18-decisões-da-revisão-2026-10-20)
19. [Estado da implementação](#19-estado-da-implementação)

---

## 1. Responsabilidade e âmbito

O messaging-service é o dono das **conversas**, das **mensagens** e do **estado de leitura** de cada participante. Uma partilha é uma mensagem com `experienceId` ([arquitetura.md §3.3](arquitetura.md#33-ajustes-à-divisão-inicial)). O serviço guarda só a referência à experiência, nunca o seu conteúdo.

**Nesta etapa:**

- uma conversa privada por par de pessoas, iniciada por qualquer uma delas;
- mensagens de texto;
- partilha de uma experiência numa conversa, com texto opcional;
- histórico paginado e consulta periódica de mensagens novas, só com o ecrã aberto;
- contagem de mensagens por ler em cada conversa.

**Fora desta etapa:** WebSockets ou outro tempo real, grupos, anexos e fotografias próprias, indicadores de escrita, confirmações de leitura visíveis para a outra pessoa ("visto"), editar ou eliminar mensagens e conversas (§13), bloquear ou denunciar pessoas, notificações (etapa 10) e contagens de partilhas no dashboard.

## 2. Entidades

| Entidade | Campos | Notas |
|---|---|---|
| `Conversation` | `id` (UUID v7), `userLow`, `userHigh`, `userLowUsername`, `userHighUsername`, `lastSeq`, `lastMessageAt`, `createdAt` | Par ordenado (`userLow < userHigh`), **único**: uma conversa por par, seja quem for que a inicia |
| `ConversationMember` | `userId`, `conversationId`, `lastReadSeq`, `lastMessageAt` | Uma linha por participante, para a caixa de conversas e o cursor de leitura de cada pessoa (§5.4) |
| `Message` | `conversationId`, `seq`, `id` (UUID v7), `senderId`, `text` (ou null), `experienceId` (ou null), `createdAt`, `idempotencyKey`, `requestHash` | `seq`: número da mensagem na conversa, único e crescente, atribuído na transação que grava a mensagem (§6.3). Pode ter lacunas. Pelo menos um de `text` e `experienceId` |

**Referências:** `userLow`, `userHigh`, `userId` e `senderId` são `userId` do users-service; `experienceId` é o id do experiences-service. Não há chaves estrangeiras para outros serviços ([arquitetura.md §3.4](arquitetura.md#34-isolamento-dos-dados)).

**Cópias de apresentação:** só os usernames dos dois participantes, copiados ao criar a conversa (§8). Servem apenas para mostrar; a participação e a autorização usam sempre os `userId`. **A experiência partilhada não é copiada**: é consultada ao experiences-service sempre que a mensagem é mostrada (§7). Isto substitui a proposta anterior de copiar "título e miniatura" ([arquitetura.md §3.5](arquitetura.md#35-cópias-de-dados-desnormalização)).

## 3. Autenticação e privacidade

- **Todos os endpoints públicos exigem access token.** O serviço valida-o **localmente** com a chave pública do users-service: RS256, `iss = portofit-users`, `aud = portofit-api`, `exp`, com 60 s de tolerância, como o experiences-service e o collections-service. Não recebe chaves privadas.
- **A identidade vem só do token.** O remetente é sempre o `sub`; nenhum pedido aceita `senderId`, `userId` ou o dono no corpo ou nos parâmetros. O único id de pessoa que a app envia é o da outra pessoa, ao iniciar uma conversa (§5.1).
- **Só os dois participantes** veem uma conversa, as suas mensagens e o seu estado. Para qualquer outra pessoa, uma conversa existente responde **exatamente como uma inexistente** (`404 NOT_FOUND`, mesmo corpo), em todas as operações: não se revela se existe, quem participa, quantas mensagens tem nem quando foi usada.
- **Sem confirmações de leitura visíveis:** o `lastReadSeq` de cada pessoa só é devolvido a ela própria.
- **Logs:** nunca o texto das mensagens, tokens, emails nem chaves internas; também não os usernames. Só `userId`, ids de conversa e de mensagem, `seq`, tipos de erro e tempos. Os Problem Details nunca repetem o texto enviado. O serviço nunca recebe emails (o token não os tem).
- **Limitação aceite:** o texto fica em claro na base de dados (sem cifra ponta a ponta), acessível a quem administra o PostgreSQL. Aceitável em desenvolvimento local; documentado.

## 4. Endpoints

Prefixo `/api/v1`. Todos com `Authorization: Bearer <access token>`.

| Método | Caminho | Descrição | Sucesso |
|---|---|---|---|
| POST | `/conversations` | Abrir a conversa com uma pessoa: cria-a ou devolve a existente | `201`; já existia `200` |
| GET | `/conversations` | Caixa de conversas (só as que têm mensagens), por cursor | `200` |
| GET | `/conversations/{conversationId}` | Uma conversa minha | `200` |
| GET | `/conversations/{conversationId}/messages` | Histórico (`before`) ou mensagens novas (`after`) | `200` |
| POST | `/conversations/{conversationId}/messages` | Enviar (com `Idempotency-Key`) | `201`; repetição `200` |
| GET | `/conversations/{conversationId}/shares?seqs=…` | Atualizar o conteúdo de partilhas já recebidas | `200` |
| PUT | `/conversations/{conversationId}/read` | Marcar como lidas até um `seq` (idempotente) | `200` |
| GET | `/actuator/health` | Estado (público) | `200` |

Todas as respostas levam `Cache-Control: no-store`.

### 4.1 Formato de uma conversa

```json
{
  "id": "019a2b30-1c2d-7e3f-8a4b-5c6d7e8f9a0b",
  "otherParticipant": { "userId": "0c4e8a2b-1d3f-4b6a-8e9c-7f1a2b3c4d5e", "username": "joana_corre" },
  "createdAt": "2026-10-21T09:00:00Z",
  "lastSeq": 13,
  "lastReadSeq": 11,
  "unreadCount": 2,
  "lastMessage": {
    "seq": 13,
    "senderId": "0c4e8a2b-1d3f-4b6a-8e9c-7f1a2b3c4d5e",
    "kind": "SHARE",
    "textPreview": "Olha esta, parece-me boa para ti",
    "createdAt": "2026-10-21T09:05:12Z"
  }
}
```

- `otherParticipant.username` é a cópia de apresentação (§8). O próprio não aparece: é sempre o `sub`.
- `lastMessage` é null numa conversa sem mensagens. `kind` é `TEXT` ou `SHARE`. `textPreview` tem os primeiros 100 caracteres do texto, ou é null numa partilha sem texto (a app mostra "Partilhou uma experiência"). A caixa **não consulta o experiences-service**.
- `lastReadSeq` e `unreadCount`: §5.4. Só os meus; os da outra pessoa nunca são devolvidos.

### 4.2 Formato de uma mensagem

```json
{
  "id": "019a2b31-4d5e-7f60-8172-93a4b5c6d7e8",
  "seq": 13,
  "senderId": "0c4e8a2b-1d3f-4b6a-8e9c-7f1a2b3c4d5e",
  "text": "Olha esta, parece-me boa para ti",
  "sharedExperience": {
    "experienceId": "019a1f20-7c3d-7e11-9a2b-3c4d5e6f7a8b",
    "availability": "AVAILABLE",
    "experience": { "id": "019a1f20-…", "…": "formato Experience do experiences-service" }
  },
  "createdAt": "2026-10-21T09:05:12Z",
  "clientKey": null
}
```

- `text` é null numa partilha sem texto; `sharedExperience` é null numa mensagem só de texto.
- `clientKey` é a `Idempotency-Key` com que a mensagem foi enviada, **só nas mensagens do próprio** (null nas da outra pessoa). Permite à app confirmar sozinha um envio cuja resposta se perdeu, quando a mensagem aparece na consulta seguinte (§6.4).
- `availability`: §7.2.

## 5. Conversas

### 5.1 Abrir (`POST /conversations`)

Corpo `{"participantId": "<userId da outra pessoa>"}`; outros campos → `400`.

1. `participantId` igual ao `sub` → `400 CANNOT_MESSAGE_SELF`, sem mais nada.
2. **Já existe** conversa com esse par → `200` com ela, **sem chamar o users-service**. Funciona com ele em baixo.
3. Senão, conta para o limite de conversas novas (§10) e **valida o destinatário no users-service** (§8.1):
   - existe → cria e responde `201` com `Location`;
   - não existe → `404 USER_NOT_FOUND`, nada criado;
   - sem resposta, erro ou resposta inválida → `503 USERS_UNAVAILABLE`, nada criado. A app mostra "Não é possível iniciar a conversa agora", nunca "esta pessoa não existe".
4. **Sem duplicados com pedidos simultâneos**, também quando A abre com B e B abre com A ao mesmo tempo. O par é guardado ordenado (`userLow < userHigh`) com um índice único. A inserção usa `ON CONFLICT DO NOTHING` e volta a ler: todos recebem a mesma conversa, uma `201` e as outras `200`.

Não precisa de `Idempotency-Key`: o par já é a chave natural, e repetir dá sempre a mesma conversa.

Abrir uma conversa **não avisa ninguém**. Uma conversa sem mensagens não aparece na caixa de nenhum dos dois (§5.2), mas pode ser aberta pelo id por qualquer um deles.

### 5.2 Caixa de conversas (`GET /conversations`)

`GET /conversations?limit=20&cursor=<opaco>`; `limit` 1–50, por omissão 20.

```json
{ "items": [ { "…": "formato Conversation" } ], "nextCursor": "eyJ0IjoiMjAyNi0xMC0yMVQwOTowNToxMloiLCJpZCI6IjAxOWEyYjMwLi4uIn0" }
```

- Só as minhas conversas **com pelo menos uma mensagem**, pela mensagem mais recente: `lastMessageAt DESC, conversationId DESC`. É uma ordem total: o id desempata as datas iguais.
- O cursor é opaco (Base64 URL de `lastMessageAt` e `conversationId` da última conversa da página). Cursor inválido → `400`. `nextCursor` null = fim. É diferente do envelope `page`/`size` dos outros serviços, porque a ordem muda sempre que chega uma mensagem.
- Uma conversa que recebe uma mensagem sobe para o topo. Durante a navegação, as páginas seguintes nunca a repetem: a data nova fica acima do cursor. Uma conversa ainda não carregada que suba aparece no topo na consulta seguinte da primeira página (§12). A app junta as conversas pelo `id`.

### 5.3 Consultar

`GET /conversations/{id}`: a conversa, se eu participar; senão `404`.

### 5.4 Mensagens por ler e marcar como lidas

**Cursor de leitura.** Cada participante tem o seu `lastReadSeq` (começa em 0):

- só avança, nunca recua;
- nunca fica acima do `seq` da última mensagem existente na conversa (`lastSeq`).

**Mensagens por ler** (`unreadCount`) = mensagens **recebidas** (`senderId` ≠ eu) com `seq > lastReadSeq`. As minhas nunca contam. A contagem vai até 100 (100 quer dizer "100 ou mais"; a app mostra "99+" acima de 99).

**Enviar não marca como lidas as recebidas.** As minhas mensagens já não contam, por isso o meu `lastReadSeq` não muda ao enviar. Uma mensagem recebida antes da minha continua por ler até eu a ver.

**`PUT /conversations/{id}/read`** com `{"lastReadSeq": 13}`, idempotente:

- guarda `max(atual, min(valor, lastSeq))` numa única instrução `UPDATE`;
- um valor acima de `lastSeq` fica em `lastSeq`, sem erro: a app nunca marca mensagens que ainda não existem;
- um valor abaixo do atual não muda nada;
- `lastReadSeq` < 0, não inteiro ou em falta → `400`.

Resposta `200`:

```json
{ "lastReadSeq": 13, "unreadCount": 0 }
```

O `PUT` é idempotente: repetir o mesmo pedido, ou recebê-lo fora de ordem, dá o mesmo estado final.

**Várias instâncias da app** (dois telemóveis, ou um processo recriado com um pedido antigo ainda a chegar) com a mesma conta:

- cada uma envia o `seq` mais alto que mostrou;
- o servidor fica com o maior, por isso uma instância atrasada nunca faz recuar o que outra já marcou;
- a resposta traz o valor efetivo: uma instância que envie 10 quando outra já marcou 13 recebe `13`;
- as outras instâncias veem o novo valor na consulta seguinte da caixa (`GET /conversations`) ou da conversa (`GET /conversations/{id}`).

A app só envia este pedido com o ecrã da conversa visível, depois de mostrar mensagens recebidas novas.

## 6. Mensagens

### 6.1 Conteúdo

Corpo `{"text": "…", "experienceId": "…"}`; outros campos → `400`.

- `text`: remove espaços e mudanças de linha nas pontas e converte `\r\n` em `\n`. Depois tem de ter de 1 a 1000 caracteres (pontos de código Unicode; limite configurável, §10). Mudanças de linha e tabulações são permitidas; outros caracteres de controlo → `400`. Só espaços conta como sem texto.
- `experienceId`: UUID, opcional.
- **Pelo menos um dos dois.** Sem texto e sem experiência → `400 VALIDATION_FAILED`.

### 6.2 Enviar (`POST /conversations/{id}/messages`)

Exige o cabeçalho **`Idempotency-Key`** (UUID), uma chave por tentativa lógica, como na publicação de experiências e na criação de listas ([experiences-api.md §5](experiences-api.md#5-publicação-sem-duplicados-idempotência)). O `requestHash` cobre a conversa, o texto normalizado e o `experienceId`.

Por ordem:

1. Token e corpo válidos; a conversa existe e eu participo (senão `404`).
2. **A chave já foi usada por mim:**
   - mesmo `requestHash` → `200` com a mesma mensagem e `Idempotent-Replayed: true`, sem contar para os limites e sem validar de novo a experiência;
   - outro conteúdo ou outra conversa → `409 IDEMPOTENCY_CONFLICT`, `reason: DIFFERENT_CONTENT`, com `conversationId` e `messageId` da mensagem já criada.
3. Limite de frequência (§10) → `429 TOO_MANY_REQUESTS` com `Retry-After`.
4. Com `experienceId`, valida a experiência no experiences-service (§7.1). Se falhar, nada é gravado e a chave não é gasta. **Uma mensagem só de texto nunca chama o experiences-service.**
5. Numa transação:
   - bloqueia a linha da conversa (`FOR UPDATE`) e procura outra vez a chave; se um pedido simultâneo já a usou, responde como no passo 2;
   - verifica o limite de mensagens por conversa;
   - `seq = lastSeq + 1`; insere a mensagem com a chave e o `requestHash`;
   - atualiza `lastSeq` e `lastMessageAt` na conversa e nas duas linhas de `ConversationMember`. Os cursores de leitura não mudam (§5.4).
6. `201` com a mensagem e `Location: /api/v1/conversations/{id}/messages/{messageId}`. Este caminho é só identificador e não tem `GET` nesta versão.

| Situação | Resposta |
|---|---|
| Chave nova, pedido válido | `201` |
| Resposta perdida, repetição com a mesma chave e o mesmo conteúdo | `200` com a mesma mensagem e `Idempotent-Replayed: true` |
| Dois pedidos simultâneos com a mesma chave na mesma conversa | um insere; o outro espera pelo bloqueio da conversa e recebe a repetição (`200`) |
| A mesma chave, ao mesmo tempo, em duas conversas | o índice único (`senderId`, chave) deixa passar um; o outro é desfeito e recebe `409 DIFFERENT_CONTENT` |
| Recusa (`400`, `404 EXPERIENCE_NOT_FOUND`, `409 CONVERSATION_FULL`, `429`, `503`) | nada gravado, **chave não consumida**: a mesma chave pode ser reutilizada |
| A mesma chave usada por outra pessoa | independente: as chaves são por remetente |

**Retenção:** a chave fica na própria linha da mensagem, e as mensagens não são eliminadas nesta versão (§13). Por isso, a proteção contra duplicados **não expira**, ao contrário dos 7 dias das experiências e das listas. Não há tarefa de limpeza.

### 6.3 Histórico e mensagens novas (`GET /conversations/{id}/messages`)

O cursor é o **`seq`**, um número da conversa e não uma data. **Em todas as respostas, os itens vêm por `seq` crescente** (a mais antiga primeiro), seja qual for o pedido.

| Pedido | Devolve |
|---|---|
| sem `before` nem `after` | as últimas `limit` mensagens |
| `before=<seq>` | as `limit` mensagens imediatamente anteriores a esse `seq` (carregar mais antigas) |
| `after=<seq>` | as primeiras `limit` mensagens com `seq` maior (consulta de novas) |

```json
{
  "items": [ { "…": "formato Message" } ],
  "lastSeq": 13,
  "hasMoreBefore": true,
  "hasMoreAfter": false,
  "experiencesStatus": "AVAILABLE"
}
```

- `limit` 1–50, por omissão 30. `before` e `after` juntos, valores negativos ou não numéricos → `400`. `after` ≥ `lastSeq` → `items: []` (a resposta típica da consulta periódica).
- `lastSeq` é o `seq` da última mensagem da conversa no momento da leitura.
- `hasMoreBefore` / `hasMoreAfter`: há mensagens fora desta página, antes do primeiro item ou depois do último.

**Mais novidades do que o tamanho da página.** Com `after=n`, a resposta traz as `limit` mensagens seguintes e `hasMoreAfter: true`.

- O cursor avança **só até ao `seq` do último item recebido**, nunca até `lastSeq`. Se avançasse até `lastSeq`, saltaria as mensagens entre os dois.
- A app pede logo `after=<seq do último item>`, até ficar com `hasMoreAfter: false`.
- Para não prender o ecrã, faz no máximo 10 páginas seguidas por ciclo e continua do mesmo cursor no ciclo seguinte. Nada se perde, só chega mais tarde.
- Sem itens (`items: []`), o cursor não muda.
- `experiencesStatus`: §7.2.

**Garantias do `seq`:**

- **único** na conversa: chave primária (`conversationId`, `seq`);
- **crescente** pela ordem de gravação;
- **atribuído na mesma transação** que grava a mensagem, com a linha da conversa bloqueada até ao fim dessa transação.

Não se promete que os números sejam seguidos: podem aparecer lacunas (por exemplo, com uma mudança futura da forma de atribuir o número). A app nunca deduz mensagens em falta a partir de uma lacuna.

**Porque `after` não salta mensagens.** Como o número é atribuído com a conversa bloqueada até ao fim da transação, uma mensagem só fica visível depois de todas as de número menor já estarem gravadas. Não há uma transação mais lenta que apareça mais tarde com um número abaixo de um já lido. Assim, `after=<último seq recebido>` vê todas as mensagens gravadas depois.

Com datas como cursor isto não seria garantido: duas mensagens podem ter a mesma data, e uma transação mais lenta pode gravar uma data anterior depois de outra mais recente já ter sido lida.

### 6.4 Resposta perdida, do lado da app

- A app gera a chave no primeiro envio e guarda-a com o texto e a experiência no `SavedStateHandle` **antes** de enviar.
- Com resultado desconhecido (rede, timeout, `5xx` que não seja `503 EXPERIENCES_UNAVAILABLE`, `2xx` ilegível), a mensagem fica "Por confirmar". A app **não repete sozinha**.
- A consulta periódica confirma-a: quando aparece uma mensagem minha com `clientKey` igual à chave pendente, a app marca-a como enviada e descarta a tentativa.
- "Tentar novamente" (um toque) repete com a mesma chave e o mesmo conteúdo. "Descartar" só é oferecido com o aviso de que a mensagem pode já ter sido enviada.
- Enquanto houver uma mensagem a enviar ou por confirmar nessa conversa, o campo de escrita fica bloqueado. Assim, as mensagens chegam pela ordem escrita e a mesma chave nunca é usada com outro conteúdo.

## 7. Partilha de experiências

### 7.1 Ao enviar

Partilhar é enviar uma mensagem com `experienceId`, com ou sem texto. Qualquer experiência pública pode ser partilhada, incluindo as do próprio. O messaging-service pede ao experiences-service o estado dessa única experiência (§7.4):

- `AVAILABLE` → envia;
- `DELETED` ou `NOT_FOUND` → **recusada** com `404 EXPERIENCE_NOT_FOUND`. Nada é enviado e a chave não é consumida. A app mostra "Esta experiência já não existe";
- sem resposta, erro ou resposta inválida → **erro temporário** `503 EXPERIENCES_UNAVAILABLE`. Nada é enviado e **a chave não é consumida**: "Tentar novamente" pode usar a mesma chave. A app mantém o texto e a experiência escolhida e mostra "Não foi possível partilhar agora". Pode tentar de novo ou enviar só o texto, com uma chave nova.

A consulta é feita **fora** da transação. Se a experiência for eliminada entre a consulta e a gravação, a mensagem fica gravada e aparece depois como `DELETED`.

### 7.2 Ao mostrar

Para cada resposta de mensagens (§6.3) com partilhas, há **um único pedido em lote** com os `experienceId` distintos dessa resposta (no máximo 50, porque `limit` ≤ 50):

| `availability` | Quando | Conteúdo |
|---|---|---|
| `AVAILABLE` | a experiência existe | `experience` no formato público `Experience`, sem `likedByMe` |
| `DELETED` | o experiences-service diz que foi eliminada ou não existe | `experience: null`. **Nenhum conteúdo antigo**: o serviço nunca o guardou. A app mostra "Esta experiência foi eliminada." |
| `UNKNOWN` | o experiences-service não respondeu ou respondeu de forma inválida | `experience: null`; **não** quer dizer eliminada. A app mostra "Experiência temporariamente indisponível." com "Tentar novamente" |

- `experiencesStatus` é `AVAILABLE`, `UNAVAILABLE` ou `NOT_NEEDED` (resposta sem partilhas, sem pedido ao experiences-service).
- **Uma falha do experiences-service nunca impede receber mensagens.** A resposta continua `200`, com todas as mensagens, o texto, o `experienceId` de cada partilha e os mesmos `hasMoreAfter`/`lastSeq`. A app avança o cursor como sempre (§6.3); só as partilhas ficam `UNKNOWN`.
- O texto que acompanha uma partilha **nunca** é escondido, nem quando a experiência foi eliminada.

### 7.3 Atualizar partilhas já recebidas (`GET /conversations/{id}/shares`)

O conteúdo de uma partilha `UNKNOWN` (ou de uma `AVAILABLE`, que entretanto pode ter sido eliminada) é atualizado sem voltar a pedir mensagens nem mexer no cursor:

`GET /conversations/{id}/shares?seqs=12,15,20`

```json
{
  "items": [
    { "seq": 12, "sharedExperience": { "experienceId": "019a…", "availability": "AVAILABLE", "experience": { "…": "formato Experience" } } },
    { "seq": 15, "sharedExperience": { "experienceId": "019b…", "availability": "DELETED", "experience": null } }
  ],
  "experiencesStatus": "AVAILABLE"
}
```

- 1 a 50 `seq` (repetidos ignorados). Devolve, por `seq` crescente, só as mensagens desta conversa com partilha; um `seq` sem mensagem ou só de texto é omitido. Formato inválido ou mais de 50 → `400`. Conversa alheia → `404`.
- Faz um único pedido em lote ao experiences-service. Com ele em baixo → `200`, `UNKNOWN`, `UNAVAILABLE`.
- **Na app:** a atualização corre com "Tentar novamente" numa partilha `UNKNOWN`. Também corre sozinha, no máximo de 60 em 60 s, enquanto a conversa estiver visível e houver partilhas `UNKNOWN` carregadas.
- O texto que acompanha uma partilha **nunca** é escondido, nem quando a experiência foi eliminada.

### 7.4 Consulta em lote no experiences-service (adaptação)

Reutiliza-se o endpoint interno existente `GET /api/v1/internal/experiences?ids=…` ([experiences-api.md §4.7](experiences-api.md#47-get-internalexperiences--consulta-em-lote-collections-service)), sem mudar o formato nem os limites (1–50 ids, `AVAILABLE`/`DELETED`/`NOT_FOUND`).

**Adaptação:** o endpoint passa a aceitar **uma chave por serviço cliente**, cada uma comparada em tempo constante:

- `COLLECTIONS_INTERNAL_KEY` (collections-service, já existe);
- `MESSAGING_INTERNAL_KEY` (novo, só no `.env` do messaging-service e do experiences-service).

O experiences-service regista nos logs o nome do cliente (`collections` ou `messaging`), nunca a chave. Uma chave em falta ou desconhecida → `403`, como hoje. Cada serviço pode ser cortado sem afetar o outro, trocando só a sua chave. Não se cria um segundo endpoint igual.

Cliente no messaging-service: ligação 0,5 s, resposta 2 s, sem novas tentativas e **sem cache**. Uma cache mostraria como disponível uma experiência já eliminada. Uma resposta incompleta, com estados desconhecidos ou sem um dos ids pedidos é tratada como indisponível. Um `403` é registado como erro de configuração.

## 8. Participantes e users-service

### 8.1 Validação do destinatário

Feita **só ao criar uma conversa** (§5.1), com o endpoint público existente `GET /api/v1/users/{userId}` ([users-api.md §3.8](users-api.md#38-get-usersuserid--perfil-público)), sem token: não é preciso endpoint interno nem chave nova.

- Ligação 0,5 s, resposta 1 s, sem novas tentativas.
- `200` com o mesmo `userId` → existe; usa-se o `username` da resposta.
- `404` → `USER_NOT_FOUND`.
- Qualquer outra coisa (timeout, `5xx`, `400`, corpo ilegível ou `userId` diferente) → `USERS_UNAVAILABLE`.

O username do próprio vem da claim `username` do token validado.

Enviar mensagens numa conversa existente **não volta a validar** ninguém: não há eliminação nem desativação de contas nesta fase ([users-api.md §6.5](users-api.md#65-limites-do-logout-e-da-revogação)). Quando houver, este ponto tem de ser revisto (§18).

### 8.2 Referências estáveis e nomes de apresentação

| Dado | Tipo | Usado para |
|---|---|---|
| `userId` (participantes, `senderId`) | referência estável | participação, autorização, ordenação do par, "minha"/"dela" em cada mensagem |
| `username` copiado ao criar a conversa | cópia de apresentação | só para mostrar o nome na caixa e no cabeçalho |

- **Com o users-service em baixo**, a caixa, o histórico, a consulta de novas e o envio em conversas existentes continuam a funcionar (com token ainda válido), com os nomes copiados. Só abrir uma conversa **nova** falha (`503 USERS_UNAVAILABLE`).
- Os usernames não são editáveis nesta fase, por isso a cópia não fica desatualizada. Se passarem a sê-lo, a app pode pedir o nome atual ao users-service pelo `userId` e usar a cópia só com ele em baixo ([arquitetura.md §3.5](arquitetura.md#35-cópias-de-dados-desnormalização)). A conversa continua ligada às mesmas pessoas.
- As mensagens não levam username, só `senderId`. A app mostra "Tu" ou o nome da outra pessoa.

## 9. Erros

Problem Details (`application/problem+json`) com `code` estável e, nos erros de validação, `errors` (`field`, `message`). Nunca incluem o texto das mensagens, a existência de conversas alheias, tokens nem dados internos.

| HTTP | `code` | Quando |
|---|---|---|
| 400 | `VALIDATION_FAILED` | corpo, texto, ids, cursor, `before`/`after`, `limit` ou cabeçalhos inválidos (com `errors`) |
| 400 | `CANNOT_MESSAGE_SELF` | abrir conversa consigo próprio |
| 401 | `AUTHENTICATION_REQUIRED` | sem token |
| 401 | `INVALID_ACCESS_TOKEN` | token inválido ou expirado |
| 404 | `NOT_FOUND` | conversa inexistente **ou em que não participo** (resposta idêntica) |
| 404 | `USER_NOT_FOUND` | abrir conversa com um `userId` que não existe |
| 404 | `EXPERIENCE_NOT_FOUND` | partilhar uma experiência eliminada ou inexistente |
| 405 | `METHOD_NOT_ALLOWED` | método não suportado |
| 409 | `IDEMPOTENCY_CONFLICT` | chave já usada com outro conteúdo ou noutra conversa: `reason: DIFFERENT_CONTENT`, com `conversationId` e `messageId` |
| 409 | `CONVERSATION_FULL` | a conversa atingiu o máximo de mensagens |
| 413 | `PAYLOAD_TOO_LARGE` | corpo acima de 8 KiB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | corpo que não é JSON |
| 429 | `TOO_MANY_REQUESTS` | limite de mensagens ou de conversas novas, com `Retry-After` |
| 500 | `INTERNAL_ERROR` | erro inesperado, sem detalhes |
| 503 | `USERS_UNAVAILABLE` | não foi possível validar o destinatário ao criar a conversa |
| 503 | `EXPERIENCES_UNAVAILABLE` | não foi possível confirmar a experiência ao partilhar |
| 503 | `SERVICE_UNAVAILABLE` | base de dados indisponível |

## 10. Limites

| Limite | Por omissão | Configuração |
|---|---|---|
| Texto de uma mensagem | 1000 caracteres (aceita de 1 a 2000) | `portofit.messaging.max-text-length` |
| Mensagens enviadas por pessoa | 120 por janela | `portofit.messaging.max-messages-per-window` |
| Conversas novas por pessoa | 20 por janela | `portofit.messaging.max-new-conversations-per-window` |
| Janela dos dois contadores | 1 h | `portofit.messaging.rate-window` |
| Mensagens por conversa | 10 000 | `portofit.messaging.max-messages-per-conversation` |
| Corpo do pedido | 8 KiB | fixo (contrato) |
| `limit` de mensagens e de conversas | 1–50 | fixo (contrato) |
| Ids por consulta em lote | 50 | fixo (endpoint existente) |

- Os contadores de frequência são **em memória**, com janela fixa, como os de publicações e comentários ([experiences-api.md §4.1](experiences-api.md#41-post-experiences--publicar)), com as mesmas limitações: perdem-se ao reiniciar e não são partilhados entre instâncias.
- Só contam envios e conversas **efetivamente criados**. Repetições idempotentes, recusas e "abrir" uma conversa já existente não contam.
- O limite por conversa é verificado com a conversa bloqueada, por isso pedidos simultâneos não o ultrapassam.

## 11. Dependências e falhas

| Situação | Efeito |
|---|---|
| users-service em baixo | Caixa, histórico, novas mensagens, marcar como lida e enviar em conversas existentes continuam (com token válido). Abrir uma conversa nova → `503 USERS_UNAVAILABLE`, exceto se já existir (`200`). Quando o token expirar, só volta a funcionar quando o users-service regressar ([arquitetura.md §3.8](arquitetura.md#38-autenticação-e-autorização)) |
| experiences-service em baixo | Tudo o que é texto continua. Partilhar → `503 EXPERIENCES_UNAVAILABLE`, nada enviado. Mostrar → partilhas `UNKNOWN`, `experiencesStatus: UNAVAILABLE`, com o texto visível |
| messaging-service em baixo | Na app, só a caixa, as conversas e o botão "Partilhar" ficam indisponíveis; o resto continua ([arquitetura.md §4.3](arquitetura.md#43-comportamento-perante-falhas)) |
| PostgreSQL em baixo | `503 SERVICE_UNAVAILABLE` |

O serviço arranca sem o users-service e sem o experiences-service; só depende do PostgreSQL e da chave pública.

## 12. Consulta periódica e consumo de recursos

**Só com o ecrã visível.** A consulta corre num ciclo ligado ao ciclo de vida do ecrã (`repeatOnLifecycle(STARTED)`): para quando a app vai para segundo plano, o ecrã se desliga ou se navega para outro ecrã, e retoma com um pedido imediato ao voltar. **Não há WorkManager, serviço em primeiro plano, alarmes nem consulta noutros ecrãs.** Por isso não há indicador de mensagens novas no menu da conta: exigiria consultar fora destes ecrãs.

| Ecrã | Pedido | Intervalo |
|---|---|---|
| Conversa aberta | `GET …/messages?after=<último seq>` | 10 s; logo depois de enviar e ao voltar ao ecrã |
| Caixa de conversas | `GET /conversations` (primeira página) | 30 s; ao voltar ao ecrã |
| Seletor de partilha | nenhuma consulta periódica | só ao abrir |

- **Com erros**, o intervalo duplica até 60 s, sem mensagens repetidas no ecrã (um aviso discreto: "A atualização falhou; a tentar de novo"). Volta ao normal com a primeira resposta boa. `401` segue a renovação normal da sessão. Se a sessão terminar, a consulta para.
- **Nunca há dois pedidos de consulta em simultâneo** no mesmo ecrã; um pedido em curso adia o seguinte.

**Atraso das mensagens:** a consulta periódica **não garante um prazo máximo de entrega**. O intervalo é o tempo normal entre consultas com o ecrã aberto e a ligação a funcionar. O atraso real depende da rede, do servidor, dos erros (o intervalo sobe até 60 s) e do estado do ecrã: com a app fechada ou noutro ecrã, nada é consultado até lá voltar.

**Custo no servidor (hipóteses a medir, não resultados verificados):**

- Uma consulta sem novidades deve fazer duas leituras indexadas (participação e `seq > n`, pela chave primária `conversationId, seq`) e devolver poucas centenas de bytes.
- Não chama nenhum outro serviço; só as respostas com partilhas consultam o experiences-service, e só pelas mensagens novas.
- Com uma conversa aberta, são cerca de 360 pedidos por hora. Espera-se que o serviço e o PostgreSQL os aguentem sem esforço, mas isso tem de ser medido.
- No telemóvel, a ligação HTTP é reutilizada (pool própria, como nas listas) e nada corre com o ecrã fechado. O consumo de bateria e de dados deve ficar limitado ao tempo de utilização.

**Memória no computador (hipótese a medir):** o contentor segue o padrão dos outros (limite de 512 MiB, heap até 70 %). Por comparação com o collections-service, estima-se cerca de 220 MiB, ou seja, cerca de 1,4 GiB para seis contentores. Os valores medidos ficam no [§19](#19-estado-da-implementação). Para poupar memória, o messaging-service pode ficar parado quando não está a ser usado (`docker compose stop messaging-service`): a app trata-o como qualquer outro serviço em falha.

## 13. Conservação dos dados

- **Nesta versão, as mensagens não se editam nem se eliminam, e as conversas também não.** Nenhum requisito documentado o exige ([arquitetura.md](arquitetura.md) R9: "mensagens simples"). Assim não é preciso tratar mensagens "eliminadas" na consulta incremental, na contagem de não lidas nem no histórico, e a chave de idempotência não precisa de limpeza (§6.2).
- As mensagens ficam guardadas enquanto o serviço existir. Não há limpeza automática por idade; o crescimento é limitado pelos limites de frequência e de mensagens por conversa (§10).
- Uma experiência eliminada não apaga mensagens: a partilha passa a `DELETED` e o texto mantém-se.
- **Eliminação de contas:** ainda não existe, nem pela app nem pela API. Quando existir, vai exigir uma **política de conservação ou anonimização** das mensagens dessa pessoa, decidida antes de implementar. Por exemplo: manter as mensagens para a outra pessoa com o remetente anónimo, ou apagá-las ao fim de um prazo.
- **Sem eliminação em cascata por omissão.** O messaging-service não tem chaves estrangeiras para contas (estão noutro serviço) nem processos que apaguem mensagens quando uma conta desaparece. Uma conta que deixe de existir não faz desaparecer as conversas da outra pessoa.

## 14. Dados e infraestrutura

- **Responsabilidade:** o messaging-service é o único que lê e escreve conversas e mensagens. Para pessoas usa a API pública do users-service, e para experiências a consulta interna do experiences-service. **Nenhuma consulta direta a bases de outros serviços.**
- **Base `messaging_db`**, com utilizador próprio `MESSAGING_DB_USER` e sem privilégios de administração. É criada por um script idempotente, `backend/postgres/init/05-messaging-db.sh`, como o `04-collections-db.sh`. Num volume existente, é corrida à mão ([infraestrutura.md §3.1](infraestrutura.md#31-acrescentar-a-base-de-um-serviço-novo-a-um-volume-existente)). **O volume existente é preservado**; nada é recriado.
- **Esquema Flyway:**

  | Tabela | Chaves e índices |
  |---|---|
  | `conversation` | chave primária `id`; único (`user_low`, `user_high`); `CHECK (user_low < user_high)` |
  | `conversation_member` | chave primária (`user_id`, `conversation_id`); índice (`user_id`, `last_message_at DESC`, `conversation_id DESC`) só com `last_message_at` preenchido, para a caixa |
  | `message` | chave primária (`conversation_id`, `seq`); único `id`; único (`sender_id`, `idempotency_key`); `CHECK (text IS NOT NULL OR experience_id IS NOT NULL)` |

- **Contentor `messaging-service`**, porta **`127.0.0.1:8085`**, na rede interna com o PostgreSQL, o users-service e o experiences-service. Monta **só** `secrets/users-jwt/public.pem`, só de leitura.
- **Chave `MESSAGING_INTERNAL_KEY`** no `.env`, fora do Git e das imagens, passada só ao messaging-service e ao experiences-service.
- **Versões:** as mesmas do collections-service (Spring Boot, Kotlin, JDK 25, Flyway, Spring JDBC), sem dependências novas.

## 15. App Android (proposta)

Mesma estrutura das listas ([app-android.md §18](app-android.md#18-listas-pessoais-etapa-11)): `MessagingApi`, um repositório com renovação única depois de um `401` (no envio, com a mesma chave), um `HttpClients.messaging` próprio e `MESSAGING_BASE_URL` só em debug (`http://10.0.2.2:8085/`).

### 15.1 Percursos

- **Iniciar conversa a partir de um perfil:** botão **"Enviar mensagem"** no perfil público de outra pessoa (não no próprio).
  - Com sessão → `POST /conversations` → abre a conversa.
  - `503 USERS_UNAVAILABLE` → "Não é possível iniciar a conversa agora", com "Tentar novamente".
- **Caixa de conversas:** **"Mensagens"** no menu da conta (Pesquisar, Descobrir, Estatísticas, ao lado de "As minhas listas").
  - Mostra o nome da outra pessoa, o início da última mensagem ou "Partilhou uma experiência", a data e as mensagens por ler.
  - "Carregar mais" pelo cursor; tocar abre a conversa.
- **Conversa:**
  - **histórico:** as últimas 30 mensagens e "Carregar anteriores"; a posição mantém-se ao abrir uma experiência e voltar;
  - **campo de escrita:** com contador e estados "A enviar…", "Por confirmar" (com "Tentar novamente") e recusa com o texto mantido;
  - **partilhas** com os três estados do §7.2: o cartão disponível abre a experiência e o autor;
  - **leitura:** ao mostrar mensagens recebidas novas, com o ecrã visível, envia o `seq` mais alto mostrado (§5.4).
- **Partilhar a partir do detalhe de uma experiência:** botão **"Partilhar"** abre um seletor.
  - O seletor tem as conversas recentes (primeira página da caixa) e "Procurar pessoa", que usa a pesquisa de pessoas existente e depois `POST /conversations`.
  - Escolhida a conversa, aparece a pré-visualização da experiência com um campo de texto opcional. **Só um toque em "Enviar" envia.**
  - Depois de enviar, oferece "Abrir conversa".
- **Login com regresso ao contexto:** sem sessão, "Enviar mensagem", "Mensagens" e "Partilhar" mostram o convite para entrar.
  - O login regressa ao mesmo ecrã (o perfil, a caixa ou o seletor, com a experiência escolhida) **sem criar conversas nem enviar nada sozinho**.
  - O texto já escrito mantém-se.

### 15.2 Regras

- **Consulta periódica:** só com o ecrã visível (§12), com uma geração por pedido. Respostas tardias de outra conversa, de outra conta ou de antes de terminar a sessão são ignoradas.
- **Envio idempotente:** como no §6.4. Uma mensagem de cada vez por conversa; sem repetição automática; confirmação pelo `clientKey`.
- **Privacidade entre contas:** ao terminar sessão ou trocar de conta, a caixa, as conversas, os rascunhos e as tentativas pendentes da conta anterior são apagados logo. Os rascunhos e as tentativas ficam associados à conta que os criou.
- **Falhas:**
  - messaging-service em baixo → caixa e conversa com "Mensagens indisponíveis" e "Tentar novamente";
  - experiences-service em baixo → as partilhas aparecem "temporariamente indisponíveis" e o texto continua visível;
  - users-service em baixo → as conversas existentes continuam a funcionar.

## 16. Critérios de teste

**Backend (integração, PostgreSQL 18.6 real com Testcontainers, users-service e experiences-service falsos, chaves RSA só dos testes):**

- **Autenticação:** sem token, outra chave, outro `aud` ou token expirado → `401`. O remetente vem sempre do `sub`: um `senderId` no corpo → `400`.
- **Privacidade:** para uma terceira pessoa, uma conversa alheia responde como uma inexistente (`404` igual) em consultar, mensagens, enviar e marcar como lida, sem nomes, contagens nem datas. Uma conversa sem mensagens não aparece na caixa.
- **Abrir conversa:**
  - consigo próprio → `400`;
  - destinatário inexistente → `404 USER_NOT_FOUND`;
  - users-service com erro, lento (> 1 s) ou com resposta inválida → `503`, nada criado;
  - conversa existente → `200` sem pedidos ao users-service, também com ele parado;
  - 8 pedidos simultâneos, metade A→B e metade B→A → uma só conversa (uma `201`, o resto `200`).
- **Conteúdo:** vazio, só espaços, 1001 caracteres, caracteres de controlo, campos extra, nem texto nem experiência → `400`; 1000 emojis aceites; `\r\n` normalizado; limite configurável respeitado.
- **Idempotência:**
  - `201`, depois `200` com `Idempotent-Replayed` e a mesma mensagem;
  - outro texto ou outra conversa → `409 DIFFERENT_CONTENT` com os ids;
  - 8 envios simultâneos com a mesma chave → 1 mensagem;
  - a mesma chave em duas conversas ao mesmo tempo → 1 mensagem e `409`;
  - recusas não gastam a chave;
  - a repetição de uma partilha não consulta o experiences-service.
- **Ordem e cursores:**
  - 20 envios simultâneos na mesma conversa → 20 mensagens com `seq` únicos e crescentes pela ordem de gravação;
  - `before`/`after` paginam por `seq` crescente sem saltos nem duplicados, também com mensagens a chegar entre pedidos e com mais novidades do que `limit` (avançar pelo último item);
  - `after` ≥ `lastSeq` → vazio;
  - `before`+`after`, valores negativos ou `limit` 51 → `400`;
  - caixa por `lastMessageAt DESC, id DESC`, com o cursor estável e cursor inválido → `400`.
- **Leitura:**
  - `lastReadSeq` não recua (valor menor) nem passa de `lastSeq` (valor maior fica no fim);
  - pedidos simultâneos com valores diferentes → fica o maior;
  - `unreadCount` conta só as mensagens recebidas depois do cursor, até 100; enviar não muda o cursor.
- **Partilhas:**
  - `AVAILABLE` → enviada;
  - eliminada ou inexistente → `404 EXPERIENCE_NOT_FOUND`, nada gravado;
  - experiences-service com erro, lento, com chave recusada ou com resposta inválida → `503`, nada gravado, e as mensagens só de texto continuam a ser enviadas;
  - ao mostrar: um pedido em lote por resposta com os ids distintos; eliminada depois → `DELETED` sem conteúdo e com o texto mantido; falha → `UNKNOWN` com `experiencesStatus: UNAVAILABLE`, com todas as mensagens novas e o mesmo `lastSeq`; sem partilhas → `NOT_NEEDED`, sem pedidos;
  - `GET …/shares`: atualiza para `AVAILABLE` depois de o serviço voltar; omite `seq` só de texto ou inexistentes; conversa alheia → `404`.
- **Limites:** mensagens e conversas novas por janela → `429` com `Retry-After`, sem contar as repetições; `CONVERSATION_FULL` com envios simultâneos no limite; corpo > 8 KiB → `413`.
- **Logs:** o texto enviado, o token e a chave interna não aparecem nos logs capturados durante os testes.
- **experiences-service:** a consulta em lote aceita a chave das listas e a das mensagens, e recusa sem chave, com uma chave desconhecida e com um token de utilizador (`403`). Os testes existentes das listas continuam a passar.

**App (JVM):**

- ViewModels da caixa, da conversa e do seletor: visitante sem pedidos; paginação; consulta só com o ecrã ativo, parando ao sair e retomando ao voltar; intervalo crescente com erros; envio sem duplicados; por confirmar mantido depois da recriação; confirmação pelo `clientKey` sem novo envio; recusas com o texto mantido; partilhas nos três estados; login com regresso sem envio; troca de conta e sessão terminada com respostas tardias ignoradas.
- Repositório: token, chave, `401` renovado com a mesma chave e classificação dos resultados (rede e `5xx` → por confirmar; `503 EXPERIENCES_UNAVAILABLE` e `USERS_UNAVAILABLE` à parte; `404`; `409`; `429`).

**Verificação manual (com dois utilizadores):**

- A abre uma conversa com B a partir do perfil e envia uma mensagem; B vê-a na caixa e na conversa numa das consultas seguintes, com o ecrã aberto (sem prazo garantido, §12).
- A partilha uma experiência com texto. Depois, a autora elimina-a e a mensagem mostra "eliminada" com o texto.
- Com o experiences-service parado, as partilhas aparecem "indisponíveis" e o texto continua a ser enviado. Com o users-service parado, as conversas existentes continuam a funcionar e uma conversa nova mostra o erro.
- Login a partir de "Partilhar" regressa ao seletor sem enviar.

## 17. Decisões

| # | Decisão | Alternativa afastada |
|---|---|---|
| 1 | Uma conversa por par, guardado ordenado com índice único; abrir = criar ou devolver | conversa criada no primeiro envio (exigiria idempotência a abranger duas entidades); verificação só na aplicação (duplica com pedidos simultâneos) |
| 2 | Conversa alheia = `404`, como inexistente | `403` (revela que existe) |
| 3 | Cursor das mensagens = `seq` por conversa, único e crescente, atribuído com a conversa bloqueada; lacunas possíveis; avançar só até ao último item recebido | datas (empates, transações lentas) ou sequência global (números visíveis fora de ordem entre transações) |
| 4 | Envio com `Idempotency-Key` guardada na própria mensagem, sem prazo | tabela de tentativas com 7 dias (desnecessária sem eliminação de mensagens) |
| 5 | `clientKey` nas mensagens do próprio, para confirmar envios por confirmar pela consulta | só "Tentar novamente" manual |
| 6 | Destinatário validado só ao criar a conversa, pelo perfil público existente | validar em cada envio (o users-service em baixo bloquearia todas as conversas); endpoint interno novo |
| 7 | Usernames copiados ao criar a conversa, só para apresentação | pedir os nomes ao users-service em cada caixa (falha e custo a cada consulta) |
| 8 | Partilha = referência; conteúdo consultado em lote ao mostrar, sem cache | cópia de título e miniatura (ficaria depois de eliminada) |
| 9 | Consulta em lote existente, com uma chave por cliente | endpoint duplicado; reutilizar a chave das listas |
| 10 | Falha do experiences-service → `UNKNOWN`/`503` nas partilhas; texto nunca bloqueado | tratar como eliminada; recusar a mensagem inteira ao mostrar |
| 11 | Sem editar nem eliminar mensagens e conversas nesta versão | apagar com marcação "eliminada" (complica a consulta incremental e as contagens) |
| 12 | Consulta só com o ecrã visível (10 s / 30 s, até 60 s com erros); sem indicador global | WorkManager ou consulta em todos os ecrãs (bateria, dados e memória) |
| 13 | Cursor de leitura por participante, monotónico e limitado a `lastSeq`; `PUT` idempotente que devolve o valor efetivo; só para o próprio, sem "visto" | confirmações de leitura visíveis (privacidade e âmbito); marcar mensagem a mensagem |
| 15 | Atualizar partilhas por `GET …/shares`, sem mexer no cursor; uma falha do experiences-service nunca atrasa mensagens | voltar a pedir as mensagens; bloquear a resposta até as partilhas carregarem |
| 16 | Sem eliminação em cascata quando houver eliminação de contas; política de conservação ou anonimização a decidir antes | apagar mensagens por omissão |
| 14 | Limites de frequência em memória e limite por conversa, configuráveis | sem limites |

## 18. Decisões da revisão (2026-10-20)

- **Iniciar conversas:** qualquer pessoa com sessão pode iniciar uma conversa com outra, sem ter de a seguir, sujeita aos limites do §10. Bloquear e denunciar ficam fora do âmbito.
- **Consulta periódica:** 10 s na conversa e 30 s na caixa, só com o ecrã ativo, com recuo até 60 s nos erros (§12). Não há prazo máximo de entrega garantido.
- **Editar e eliminar:** sem edição nem eliminação de mensagens ou conversas nesta versão (§13). É uma **decisão de âmbito do grupo**, não uma exigência confirmada da disciplina.
- **Notificações:** ficam para a etapa própria. Nesta etapa não há outbox nem eventos.
- **Limites:** os valores propostos (§10) mantêm-se, todos configuráveis.
- **Ajustes ao contrato:** cursor de leitura monotónico (§5.4), `seq` sem promessa de números seguidos (§6.3), recuperação com mais novidades do que a página (§6.3), partilhas atualizáveis à parte (§7.3), validação ao partilhar sem consumir a chave (§7.1), contas (§13), estimativas como hipóteses (§12).

## 19. Estado da implementação

Ainda não implementado.
