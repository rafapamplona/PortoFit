# notifications-service — Contrato da API

> Caixa de notificações privada e notificações do sistema Android (requisito R10, etapa 10). Contexto geral em [arquitetura.md](arquitetura.md) (§3.2 responsabilidades, §3.4 isolamento, §3.8 autenticação, §3.11 notificações e *transactional outbox*, §4 falhas).
> Estado: **contrato aprovado** (2026-10-22, decisões no [§19](#19-decisões-da-revisão-2026-10-22)). Implementação em duas partes: a **primeira parte do backend está feita** (serviço, caixa e mensagens); a segunda parte e a app estão por fazer ([§20](#20-estado-da-implementação)).
> Mesmas convenções dos outros contratos ([catalog-api.md](catalog-api.md), [users-api.md](users-api.md), [experiences-api.md](experiences-api.md), [collections-api.md](collections-api.md), [messaging-api.md](messaging-api.md)): JSON UTF-8, campos em inglês (camelCase), textos em português, datas ISO 8601 UTC, erros em Problem Details com `code`.

## Índice

1. [Responsabilidade e âmbito](#1-responsabilidade-e-âmbito)
2. [Entidades](#2-entidades)
3. [Eventos e destinatários](#3-eventos-e-destinatários)
4. [Autenticação e privacidade](#4-autenticação-e-privacidade)
5. [Endpoints públicos (a caixa)](#5-endpoints-públicos-a-caixa)
6. [Entrega interna de eventos](#6-entrega-interna-de-eventos)
7. [Outbox nos produtores](#7-outbox-nos-produtores)
8. [Destinatários das novas experiências](#8-destinatários-das-novas-experiências)
9. [Conteúdo e apresentação](#9-conteúdo-e-apresentação)
10. [Conteúdos eliminados e serviços indisponíveis](#10-conteúdos-eliminados-e-serviços-indisponíveis)
11. [Erros](#11-erros)
12. [Limites e retenção](#12-limites-e-retenção)
13. [Dependências e falhas](#13-dependências-e-falhas)
14. [Dados e infraestrutura](#14-dados-e-infraestrutura)
15. [App Android (proposta)](#15-app-android-proposta)
16. [Critérios de teste](#16-critérios-de-teste)
17. [Decisões](#17-decisões)
18. [Complexidade](#18-complexidade)
19. [Decisões da revisão](#19-decisões-da-revisão-2026-10-22)
20. [Estado da implementação](#20-estado-da-implementação)

---

## 1. Responsabilidade e âmbito

O notifications-service é o dono das **notificações** de cada pessoa e do seu **estado de leitura**. Recebe eventos dos serviços que os produzem, cria as notificações e serve a caixa à app. Não é dono de nenhum dos conteúdos referidos (mensagens, comentários, experiências, pessoas): guarda só referências e o mínimo para mostrar a notificação (§9).

**Nesta etapa:**

- quatro tipos de notificação (§3): nova mensagem (incluindo partilha), novo comentário numa experiência minha, novo seguidor, nova experiência de alguém que sigo;
- a caixa na app, paginada, com contagem por ler e marcação idempotente de leitura;
- notificações do sistema Android por **consulta periódica com WorkManager**, sem Firebase.

**Sem garantia de entrega imediata.** Com a app fechada, o Android só corre a consulta periodicamente (no mínimo de 15 em 15 min) e pode adiá-la bastante mais (Doze, *App Standby Buckets*, poupança de bateria, restrições do fabricante, falta de rede). Com a app aberta, a caixa e a contagem são consultadas enquanto o ecrã está visível. O atraso não tem limite garantido. Ver §15.

**Fora desta etapa:** gostos (para limitar o ruído), *push* (Firebase Cloud Messaging; se é exigido está por confirmar com a docente, §19), e-mail, preferências por tipo (silenciar), agrupar várias mensagens numa só notificação no servidor, notificações de listas e de visualizações.

## 2. Entidades

| Entidade | Campos | Notas |
|---|---|---|
| `Notification` | `id` (UUID v7), `recipientId`, `seq`, `type`, `actorId`, `actorUsername`, referências (`experienceId`, `commentId`, `conversationId`, `messageSeq`, `messageKind`), `eventId`, `occurredAt`, `createdAt`, `readAt` | `seq` é o número da notificação na caixa da pessoa: único e crescente, atribuído na transação que a cria (§5.1). Única por (`eventId`, `recipientId`) |
| `Inbox` | `recipientId`, `lastSeq` | Uma linha por pessoa, bloqueada ao criar notificações, para atribuir o `seq` |
| `ReceivedEvent` | `eventId`, `producer`, `type`, `payloadHash`, `receivedAt` | Deduplicação (§6.3), guardada durante a retenção dos eventos (§12) |
| `FanOutJob` | `eventId`, `authorId`, `experienceId`, `occurredAt`, `cursor`, `status`, `attempts`, `nextAttemptAt`, `recipients`, `lastError` | Resolução dos destinatários de uma nova experiência (§8) |

**Referências:** `recipientId`, `actorId` e `authorId` são `userId` do users-service; `experienceId` e `commentId` são do experiences-service; `conversationId` e `messageSeq` são do messaging-service. Não há chaves estrangeiras para outros serviços ([arquitetura.md §3.4](arquitetura.md#34-isolamento-dos-dados)).

**Cópia de apresentação:** só `actorUsername`, vinda no evento. Os usernames não são editáveis nesta fase; a pessoa é sempre identificada pelo `actorId` ([arquitetura.md §3.5](arquitetura.md#35-cópias-de-dados-desnormalização)).

## 3. Eventos e destinatários

| Tipo (`type`) | Produtor | Quando | Destinatário | Referências |
|---|---|---|---|---|
| `MESSAGE_RECEIVED` | messaging-service | mensagem criada (`201`; nunca numa repetição idempotente) | a outra pessoa da conversa | `conversationId`, `messageSeq`, `messageKind` (`TEXT` ou `SHARE`) |
| `COMMENT_CREATED` | experiences-service | comentário criado (`201`; nunca numa repetição) | o autor da experiência, se não for quem comentou | `experienceId`, `commentId` |
| `USER_FOLLOWED` | users-service | relação nova criada (o `PUT` que só confirma uma relação existente não conta) | a pessoa seguida | — |
| `EXPERIENCE_PUBLISHED` | experiences-service | experiência criada (`201`; nunca numa repetição) | **quem segue o autor**, resolvido pelo notifications-service (§8) | `experienceId` |

- **O destinatário vem do produtor**, que já o sabe, nos três primeiros tipos. A exceção é `EXPERIENCE_PUBLISHED`: quem segue o autor é do users-service, não do experiences-service, e pode ser uma lista grande. Em vez de o experiences-service gravar um evento por seguidor, grava **um** evento, e o notifications-service resolve os destinatários por páginas (§8). Esta é a única exceção ao princípio de que o notifications-service não decide destinatários ([arquitetura.md §3.3](arquitetura.md#33-ajustes-à-divisão-inicial), ponto 8).
- **Ninguém é notificado das próprias ações.** O produtor não grava o evento quando o ator é o destinatário, e o notifications-service volta a filtrar.
- **Seguir, deixar de seguir e voltar a seguir** dentro de 24 h não gera uma segunda notificação para o mesmo par (§6.3). Deixar de seguir não apaga a notificação já criada.
- **Editar ou eliminar** uma mensagem, comentário ou experiência não gera eventos. A notificação existente aponta para um conteúdo que pode já não existir (§10).

### 3.1 Formato dos eventos

Comum: `eventId` (UUID, gerado pelo produtor e gravado na outbox), `type`, `occurredAt` (data da ação no produtor), `actor` (`userId`, `username`).

```json
{
  "eventId": "019a3c10-2b3c-7d4e-8f50-6172839405a6",
  "type": "MESSAGE_RECEIVED",
  "occurredAt": "2026-10-22T10:15:03Z",
  "actor": { "userId": "0c4e8a2b-1d3f-4b6a-8e9c-7f1a2b3c4d5e", "username": "joana_corre" },
  "recipientId": "6f9a2c1e-3b7d-4e8a-9c51-2d4f6a8b0e13",
  "conversationId": "019a2b30-1c2d-7e3f-8a4b-5c6d7e8f9a0b",
  "messageSeq": 14,
  "messageKind": "SHARE"
}
```

```json
{ "eventId": "…", "type": "COMMENT_CREATED", "occurredAt": "…", "actor": { "userId": "…", "username": "rui" },
  "recipientId": "…", "experienceId": "…", "commentId": "…" }
```

```json
{ "eventId": "…", "type": "USER_FOLLOWED", "occurredAt": "…", "actor": { "userId": "…", "username": "rui" }, "recipientId": "…" }
```

```json
{ "eventId": "…", "type": "EXPERIENCE_PUBLISHED", "occurredAt": "…", "actor": { "userId": "…", "username": "ana.silva" }, "experienceId": "…" }
```

**Nunca vão no evento:** o texto da mensagem, o texto do comentário, o texto ou a fotografia da experiência, emails ou tokens.

## 4. Autenticação e privacidade

- **Endpoints públicos** (§5): exigem access token, validado **localmente** com a chave pública do users-service: RS256, `iss = portofit-users`, `aud = portofit-api`, `exp` com 60 s de tolerância, como nos outros serviços. Não recebem chaves privadas.
- **Só as próprias notificações.** O destinatário é sempre o `sub` do token; nenhum pedido aceita `recipientId`. Uma notificação de outra pessoa responde como inexistente (`404 NOT_FOUND`, mesmo corpo), em qualquer operação.
- **Endpoint interno** (§6): só com `X-Internal-Key`, **uma chave por produtor** (§6.1), comparada em tempo constante. Um token de utilizador não serve. As chaves só vêm do `.env`.
- **Logs:** nunca tokens, chaves, usernames nem conteúdos; só ids, tipos, estados e tempos.
- **Sem conteúdo privado guardado** (§9): mesmo quem administre a base de dados vê que houve uma mensagem de X para Y, mas não o que dizia. A existência da relação continua a ser um dado pessoal: está sujeita à retenção (§12).

## 5. Endpoints públicos (a caixa)

Prefixo `/api/v1`. Todos com `Authorization: Bearer <access token>`. Todas as respostas com `Cache-Control: no-store`.

| Método | Caminho | Descrição | Sucesso |
|---|---|---|---|
| GET | `/notifications` | A caixa: histórico (`before`) ou novas (`after`), por `seq` | `200` |
| GET | `/notifications/unread-count` | Contagem por ler | `200` |
| PUT | `/notifications/{notificationId}/read` | Marcar uma como lida (idempotente) | `200` |
| PUT | `/notifications/read` | Marcar como lidas todas até um `seq` (idempotente) | `200` |
| GET | `/actuator/health` | Estado (público) | `200` |

### 5.1 Formato e paginação

```json
{
  "items": [
    {
      "id": "019a3c11-0000-7000-8000-000000000001",
      "seq": 42,
      "type": "MESSAGE_RECEIVED",
      "actor": { "userId": "0c4e8a2b-1d3f-4b6a-8e9c-7f1a2b3c4d5e", "username": "joana_corre" },
      "conversationId": "019a2b30-1c2d-7e3f-8a4b-5c6d7e8f9a0b",
      "messageSeq": 14,
      "messageKind": "SHARE",
      "occurredAt": "2026-10-22T10:15:03Z",
      "createdAt": "2026-10-22T10:15:05Z",
      "read": false
    },
    {
      "id": "…", "seq": 41, "type": "COMMENT_CREATED",
      "actor": { "userId": "…", "username": "rui" },
      "experienceId": "…", "commentId": "…",
      "occurredAt": "…", "createdAt": "…", "read": true
    }
  ],
  "lastSeq": 42,
  "hasMoreBefore": true,
  "hasMoreAfter": false
}
```

- **Cursor = `seq`**, como nas mensagens: único e crescente por pessoa, atribuído com a linha `Inbox` da pessoa bloqueada até ao fim da transação. Uma notificação só fica visível depois de todas as de `seq` menor, por isso `after=<último recebido>` não salta nenhuma.
- **Lacunas são normais.** A retenção (§12) e o limite por pessoa apagam notificações antigas, e os números nunca são reutilizados.
  - Um cliente nunca trata uma lacuna como erro nem como notificações em falta.
  - `after=<n>` com `n` anterior à mais antiga que resta devolve a partir da mais antiga que resta.
  - `before=<n>` devolve as que existem abaixo de `n`; `hasMoreBefore: false` quando já não há nenhuma.
  - Um cursor maior do que `lastSeq` devolve `items: []`.
- `GET /notifications?limit=20` → as mais recentes; `before=<seq>` → as anteriores (histórico); `after=<seq>` → as novas, **por `seq` crescente** (consulta do WorkManager). Na caixa (sem `after`), os itens vêm por `seq` decrescente (a mais recente primeiro). `before` e `after` juntos → `400`. `limit` 1–50, por omissão 20.
- Com mais novas do que `limit`, o cursor avança só até à última recebida (`hasMoreAfter: true`), como nas mensagens.
- `read` é o estado na caixa da app; não tem nada a ver com a notificação do sistema ter sido vista ou dispensada (§15.5).

### 5.2 Por ler

`GET /notifications/unread-count` → `{"unreadCount": 3}`. Conta até 100 (100 quer dizer "100 ou mais"; a app mostra "99+" acima de 99). É uma consulta indexada pelas não lidas da pessoa.

### 5.3 Marcar como lidas

- `PUT /notifications/{id}/read` (sem corpo) → `200` com `{"id": "…", "read": true, "unreadCount": 2}`. Repetir dá o mesmo resultado. Uma notificação de outra pessoa ou inexistente → `404`.
- `PUT /notifications/read` com `{"upToSeq": 42}` → marca como lidas todas as minhas com `seq ≤ min(upToSeq, lastSeq)` e responde `200` com `{"unreadCount": 0}`. Repetir, ou chegar fora de ordem, é inofensivo. Um valor maior do que `lastSeq` fica em `lastSeq`: nunca marca notificações que ainda não existem. `upToSeq` < 0 ou não inteiro → `400`.
- Não há "marcar como não lida" nem eliminar notificações nesta versão.

## 6. Entrega interna de eventos

### 6.1 `POST /api/v1/internal/events`

Só para os produtores, com `X-Internal-Key`. Cada produtor tem a sua chave e só pode entregar os seus tipos:

| Chave (`.env`) | Produtor | Tipos aceites |
|---|---|---|
| `NOTIFY_USERS_KEY` | users-service | `USER_FOLLOWED` |
| `NOTIFY_EXPERIENCES_KEY` | experiences-service | `COMMENT_CREATED`, `EXPERIENCE_PUBLISHED` |
| `NOTIFY_MESSAGING_KEY` | messaging-service | `MESSAGE_RECEIVED` |

Chave em falta ou desconhecida → `403`. Tipo de outro produtor → resultado `REJECTED` para esse evento. Se duas chaves forem iguais (erro de configuração), nenhuma é aceite, como na consulta em lote do experiences-service. As chaves são diferentes das já existentes (`USERS_INTERNAL_KEY`, `COLLECTIONS_INTERNAL_KEY`, `MESSAGING_INTERNAL_KEY`).

Corpo: até 100 eventos.

```json
{ "events": [ { "eventId": "…", "type": "USER_FOLLOWED", "occurredAt": "…", "actor": { "userId": "…", "username": "rui" }, "recipientId": "…" } ] }
```

Resposta `200`, um resultado por evento, pela ordem pedida:

```json
{
  "results": [
    { "eventId": "…", "status": "ACCEPTED" },
    { "eventId": "…", "status": "DUPLICATE" },
    { "eventId": "…", "status": "REJECTED", "reason": "INVALID_EVENT" }
  ]
}
```

| `status` | Significado | O produtor |
|---|---|---|
| `ACCEPTED` | gravado (notificação criada, ou trabalho de resolução criado, §8) | marca `SENT` |
| `DUPLICATE` | o mesmo `eventId` com o mesmo conteúdo já tinha sido recebido | marca `SENT` |
| `REJECTED` | nunca vai ser aceite: mal formado, tipo de outro produtor, ou o mesmo `eventId` com outro conteúdo (`reason: CONFLICT`) | marca `FAILED` logo, sem gastar tentativas |

- **Corpo inválido no todo** (não JSON, mais de 100 eventos, acima de 256 KiB) → `400`.
- **Base de dados indisponível** → `503`. O produtor repete mais tarde (§7).
- **Um evento por transação:** cada evento é gravado na sua própria transação, por isso um evento rejeitado não estraga os outros do lote.

### 6.2 O que se grava por evento

Na mesma transação: a linha de deduplicação (`ReceivedEvent`) e a notificação, com o `seq` atribuído com a linha `Inbox` do destinatário bloqueada. Para `EXPERIENCE_PUBLISHED`, grava-se o `FanOutJob` em vez da notificação (§8).

### 6.3 Deduplicação

- **Por `eventId`:** chave primária em `ReceivedEvent`. Um evento repetido (a resposta anterior perdeu-se e o produtor reenviou) responde `DUPLICATE` e não cria nada. Com pedidos simultâneos com o mesmo `eventId`, a chave primária deixa passar um.
- **Por notificação:** único (`eventId`, `recipientId`). Garante que a resolução de destinatários (§8) nunca cria duas notificações para a mesma pessoa, mesmo que retome uma página.
- **Seguidores repetidos:** `USER_FOLLOWED` do mesmo par (ator, destinatário) dentro de 24 h (configurável) é aceite como `DUPLICATE`, sem nova notificação. Evita ruído com "seguir / deixar de seguir / seguir".
- A deduplicação por `eventId` dura a retenção dos eventos recebidos (§12), que é **sempre maior** do que a idade máxima aceite de um evento (§6.4). Apagar notificações antigas (retenção ou limite por pessoa) **não** apaga a deduplicação: um evento antigo reenviado responde `DUPLICATE`, ou `REJECTED/EXPIRED` se já passou da idade máxima, e nunca volta a criar a notificação.

### 6.4 Eventos antigos e reenvio manual

- **Idade máxima aceite:** um evento com `occurredAt` há mais de 30 dias (configurável, `portofit.notifications.event-max-age`) é `REJECTED` com `reason: EXPIRED`, sem notificação. Um `occurredAt` mais de 5 min no futuro é `REJECTED/INVALID_EVENT`.
- **Retenção da deduplicação** = idade máxima + 5 dias (35 dias por omissão). O serviço recusa arrancar se `event-retention` ≤ `event-max-age`. Um `eventId` só é esquecido quando o evento já não pode ser aceite.
- **Reenvio manual no produtor:** só de eventos `FAILED` com menos de 30 dias (os mais antigos já foram limpos, §7). O procedimento (SQL documentado em [infraestrutura.md](infraestrutura.md)) repõe `PENDING`, `attempts = 0` e `nextAttemptAt = agora`, e não altera o `eventId` nem o conteúdo. Um evento reenviado que já tinha chegado responde `DUPLICATE`; um demasiado antigo, `EXPIRED` (passa a `FAILED` com esse motivo).

## 7. Outbox nos produtores

Igual nos três produtores (users-service, experiences-service, messaging-service), conforme [arquitetura.md §3.11](arquitetura.md#311-notificações):

1. **Na mesma transação da ação:** o produtor grava a ação (seguir, comentário, mensagem, experiência) **e** uma linha na sua tabela `outbox`: `eventId`, `type`, `payload` (JSON do §3.1), `createdAt`, `status = PENDING`, `attempts = 0`, `nextAttemptAt = createdAt`, `lastError`. Ou ficam as duas, ou nenhuma. Uma repetição idempotente da ação não grava outro evento.
2. **Envio posterior:** uma tarefa agendada (de 5 em 5 s) lê até 100 eventos `PENDING` com `nextAttemptAt` já passado, por ordem de criação. Usa `FOR UPDATE SKIP LOCKED`, para duas instâncias nunca enviarem o mesmo lote ao mesmo tempo. Envia o lote (`POST /internal/events`, ligação 0,5 s, resposta 5 s) e trata cada resultado (§6.1).
3. **Tentativas com recuo:** sem resposta, timeout ou `5xx` → `attempts + 1` e `nextAttemptAt = agora + recuo`. O recuo começa em 5 s e duplica até 10 min (5, 10, 20, 40 s… 10 min).
   - **`401`/`403`, ou chave por configurar:** é um erro de configuração do produtor, não do evento.
     - **Sem repetição intensa:** o lote inteiro é adiado com um recuo próprio de configuração (5 s, 10 s… até 10 min) e os eventos não gastam tentativas.
     - **Registo:** "falha de configuração na entrega de notificações" com nível de erro, uma vez por período de recuo, só com o estado HTTP (nunca a chave).
     - **Recuperação:** depois de corrigida a chave (e reiniciado o produtor, para ler o `.env`), o envio seguinte entrega tudo o que esperava, pela ordem de criação.
4. **Falha persistente:** ao fim de 20 tentativas (cerca de 2,5 h), ou com mais de 24 h, o evento passa a `FAILED` com o último código de erro. Não é apagado e fica registado em log (ids e tipo, sem conteúdo). Pode voltar a `PENDING` manualmente, depois de corrigida a causa: o procedimento SQL fica na infraestrutura. Um `REJECTED` passa logo a `FAILED`.
5. **Limpeza:** `SENT` apagados ao fim de 3 dias; `FAILED` guardados 30 dias. Ambos os prazos são configuráveis. O prazo dos `FAILED` nunca passa da idade máxima aceite pelo notifications-service (§6.4).

### 7.1 Observar a outbox (sem conteúdo)

- **Estados observáveis por evento:** `PENDING` (com `attempts`, `nextAttemptAt` e `lastError`), `SENT` (com `sentAt`) e `FAILED` (com `lastError`: `REJECTED/<motivo>`, `MAX_ATTEMPTS` ou `MAX_AGE`). `lastError` é só um código, nunca o corpo da resposta.
- **Resumo periódico no log do produtor** (de minuto a minuto, só quando há pendentes ou falhados):
  - número de `PENDING` e idade do mais antigo;
  - número de `FAILED`;
  - número de `SENT` na última hora.

  Nunca inclui ids de pessoas, texto ou chaves.
- **Consultas SQL de diagnóstico** documentadas na infraestrutura: contagens por estado e por `lastError`, e os ids dos eventos `FAILED`. Nunca o `payload`.
- **Critérios usados na verificação:**
  - entregue = `SENT` e uma notificação com esse `eventId` no notifications-service;
  - pendente = `PENDING` com `nextAttemptAt` a avançar segundo o recuo;
  - falhado = `FAILED` com o motivo.

**Garantias e limites:**

- **Entrega pelo menos uma vez** enquanto o evento estiver `PENDING`, com deduplicação no destino para não haver notificações repetidas.
- **Não é absoluta:** um evento pode acabar `FAILED`, e perde-se se a base do produtor se perder antes do envio.
- **A ordem não é garantida** entre produtores. Dentro de um produtor, a ordem de envio segue a de criação, mas um evento que falhe pode ser ultrapassado pelos seguintes.
- **O notifications-service parado nunca impede** seguir, comentar, publicar nem enviar mensagens: os eventos esperam na outbox.

## 8. Destinatários das novas experiências

### 8.1 Fonte: os seguidores do autor no users-service

Novo endpoint **interno** no users-service, só para o notifications-service:

`GET /api/v1/internal/users/{userId}/followers?followedBefore=<instante>&cursor=<opaco>&limit=500`, com `X-Internal-Key` = `USERS_FOLLOWERS_KEY` (só no `.env` do users-service e do notifications-service).

```json
{ "items": ["6f9a2c1e-…", "0c4e8a2b-…"], "nextCursor": "eyJzIjoi…" }
```

- **Só ids**, sem usernames nem outros dados.
- **Paginação estável:** ordem total por (`since`, `followerId`), com cursor opaco com esses dois valores (não por número de página). Começar ou deixar de seguir entre páginas não faz repetir nem saltar quem já foi lido ou ainda vai ser.
- **Limite temporal:** só relações com `since ≤ followedBefore`, que é sempre o `occurredAt` da publicação.
- `followedBefore`: só relações criadas até esse instante (o `occurredAt` do evento).
- `limit` 1–500. Pessoa inexistente → `404`. Chave em falta ou errada → `403`.
- É um pedido **por página**, não por destinatário.

### 8.2 Resolução por páginas (`FanOutJob`)

1. Ao aceitar `EXPERIENCE_PUBLISHED`, o notifications-service grava um `FanOutJob` (`status = PENDING`, `cursor = null`) na mesma transação da deduplicação. O produtor recebe `ACCEPTED` logo; a resolução é feita depois.
2. Uma tarefa agendada processa os trabalhos `PENDING`, página a página. Em cada página, numa transação:
   - cria as notificações dos ids recebidos, exceto o autor, com `ON CONFLICT (eventId, recipientId) DO NOTHING`;
   - guarda o `nextCursor` e soma os destinatários.

   Se o processo parar a meio, retoma do último cursor guardado, sem duplicar notificações.
3. Sem `nextCursor`, o trabalho passa a `DONE`.

### 8.3 Falhas: nunca "sem destinatários"

- **Falha temporária** do users-service (sem resposta, timeout, `5xx`, resposta ilegível) **nunca** é tratada como "não há seguidores":
  - o trabalho fica `PENDING` no mesmo cursor, com `attempts + 1` e recuo (10 s a 10 min);
  - ao fim de 48 h (configurável) passa a `FAILED` com o motivo; fica registado e pode ser reposto manualmente.
- **`200` com `items: []`** é a única forma de concluir que não há (mais) seguidores.
- **`404`** (autor inexistente; não há eliminação de contas nesta fase) → `DONE` sem notificações, com o motivo registado.
- **`403`** → erro de configuração: repete sem contar tentativas, como no §7.
- **Sem truncagem silenciosa:**
  - todas as páginas são processadas;
  - existe um limite de proteção configurável (`portofit.notifications.max-fan-out`, por omissão 10 000 destinatários por evento). Se for atingido, o trabalho termina com `status = TRUNCATED`, o número processado e um aviso em log, nunca como `DONE`.

### 8.4 Mudanças nos seguidores durante o atraso

O conjunto de destinatários é: **quem segue o autor desde antes da publicação (`followedBefore = occurredAt`) e continua a segui-lo quando a sua página é lida**. Consequências, aceites e documentadas:

- **Começou a seguir depois da publicação:** não é notificado, mesmo que a resolução corra mais tarde.
- **Deixou de seguir antes de a sua página ser lida:** não é notificado. Se deixou de seguir depois, a notificação já criada mantém-se.
- **Atraso grande** (users-service ou notifications-service em baixo durante horas): o conjunto reflete o estado no momento da resolução, não o da publicação.
- **Não existe uma fotografia transacional dos seguidores entre serviços.** A publicação é gravada no experiences-service e os seguidores vivem no users-service, em bases diferentes e sem transação comum. Ter essa fotografia exigiria que o experiences-service guardasse a lista ou que o users-service guardasse o histórico das relações; nenhum dos dois o faz.

## 9. Conteúdo e apresentação

- **O que se guarda:** tipo, ator (`userId` e cópia do `username`), referências (§2) e datas. **Nunca** o texto da mensagem, do comentário ou da experiência, nem fotografias. O tipo `SHARE` diz só que a mensagem tinha uma experiência partilhada.
- **Texto mostrado:** é construído pela app a partir do tipo (`strings.xml`):

  | Tipo | Texto na caixa e na notificação do sistema |
  |---|---|
  | `MESSAGE_RECEIVED` | "Nova mensagem de @joana_corre" ou "@joana_corre partilhou uma experiência contigo" |
  | `COMMENT_CREATED` | "@rui comentou a tua experiência" |
  | `USER_FOLLOWED` | "@rui começou a seguir-te" |
  | `EXPERIENCE_PUBLISHED` | "@ana.silva publicou uma experiência" |

- **Ecrã bloqueado:** a notificação do sistema tem visibilidade privada e uma versão pública **genérica** ("PortoFit: tens novidades"), sem nomes nem tipo (§15.4).

## 10. Conteúdos eliminados e serviços indisponíveis

- **O notifications-service não consulta outros serviços para mostrar a caixa.** As notificações respondem sempre a partir da sua base, mesmo com os outros serviços em baixo.
- **Conteúdo eliminado depois da notificação:** a notificação mantém-se, só com as referências. Ao abri-la, a app pede o conteúdo ao dono e mostra o estado real, sem recuperar nada apagado (não há nada guardado para recuperar):
  - **experiência eliminada** (comentário ou nova experiência): "Esta experiência foi eliminada";
  - **comentário eliminado:** a experiência abre normalmente, sem o comentário;
  - **mensagem:** a conversa abre (as mensagens não se eliminam nesta versão); a partilha mostra o estado do messaging-service (`DELETED`/`UNKNOWN`).
- **Dono indisponível ao abrir:** a app mostra o erro desse ecrã ("temporariamente indisponível", com "Tentar novamente"), nunca "eliminado".
- **Não há eventos de eliminação** nesta versão: uma notificação não desaparece quando o conteúdo é apagado (§19).

## 11. Erros

Problem Details com `code` estável. Nunca incluem conteúdos, tokens nem chaves.

| HTTP | `code` | Quando |
|---|---|---|
| 400 | `VALIDATION_FAILED` | parâmetros, cursores ou corpo inválidos (com `errors`) |
| 401 | `AUTHENTICATION_REQUIRED` / `INVALID_ACCESS_TOKEN` | sem token; token inválido ou expirado |
| 403 | `FORBIDDEN` | endpoint interno sem chave válida |
| 404 | `NOT_FOUND` | notificação inexistente **ou de outra pessoa** (resposta idêntica) |
| 413 | `PAYLOAD_TOO_LARGE` | lote de eventos acima de 256 KiB |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | corpo que não é JSON |
| 500 | `INTERNAL_ERROR` | erro inesperado |
| 503 | `SERVICE_UNAVAILABLE` | base de dados indisponível |

## 12. Limites e retenção

| Limite | Por omissão | Configuração |
|---|---|---|
| Retenção das notificações | 90 dias | `portofit.notifications.retention` |
| Notificações por pessoa | 1000 (as mais antigas saem primeiro) | `portofit.notifications.max-per-recipient` |
| Idade máxima de um evento aceite | 30 dias (mais antigo → `REJECTED/EXPIRED`) | `portofit.notifications.event-max-age` |
| Retenção dos eventos recebidos (deduplicação) | 35 dias (sempre maior do que a idade máxima; validado no arranque) | `portofit.notifications.event-retention` |
| Janela de deduplicação de `USER_FOLLOWED` | 24 h | `portofit.notifications.follow-dedup-window` |
| Destinatários por nova experiência | 10 000 (acima → `TRUNCATED`, §8.3) | `portofit.notifications.max-fan-out` |
| Página de seguidores na resolução | 500 | `portofit.notifications.fan-out-page` |
| Desistência da resolução | 48 h | `portofit.notifications.fan-out-max-age` |
| Eventos por lote; corpo | 100; 256 KiB | fixo (contrato) |
| `limit` da caixa | 1–50 | fixo (contrato) |
| Outbox: recuo; tentativas; idade máxima | 5 s → 10 min; 20; 24 h | `portofit.outbox.*` em cada produtor |
| Outbox: limpeza de `SENT`; `FAILED` | 3 dias; 30 dias | `portofit.outbox.*` |

A limpeza corre de hora a hora. Ao apagar notificações antigas, a contagem por ler pode descer sozinha (o mais antigo sai primeiro, lido ou não), e ficam lacunas nos `seq` (§5.1). A deduplicação não é apagada com as notificações (§6.3).

## 13. Dependências e falhas

| Situação | Efeito |
|---|---|
| notifications-service em baixo | Seguir, comentar, publicar e enviar mensagens continuam. Os eventos ficam `PENDING` nas outboxes e chegam depois, se a falha não passar das tentativas (§7). A caixa na app mostra "Notificações indisponíveis"; o WorkManager não mostra nada nesse período |
| users-service em baixo | Caixa e marcação continuam (tokens válidos). Eventos de seguidores ficam na outbox do users-service. Resoluções de novas experiências ficam `PENDING` (§8.3), nunca "sem destinatários" |
| experiences-service em baixo | Caixa continua. Abrir uma notificação de comentário ou de experiência mostra o erro desse ecrã |
| messaging-service em baixo | Caixa continua. Abrir uma notificação de mensagem mostra "Mensagens indisponíveis" |
| PostgreSQL em baixo | `503`; os produtores também param (ponto comum de falha) |

O notifications-service arranca sem os outros serviços; só depende do PostgreSQL e da chave pública.

## 14. Dados e infraestrutura

- **Responsabilidade:** só o notifications-service lê e escreve notificações; os seguidores vêm por API (§8.1). Nenhuma consulta direta a bases de outros serviços.
- **Base `notifications_db`**, com utilizador próprio `NOTIFICATIONS_DB_USER`, sem privilégios de administração. É criada por um script idempotente `backend/postgres/init/06-notifications-db.sh`, corrido à mão no volume existente, como o `05-messaging-db.sh` ([infraestrutura.md §3.1](infraestrutura.md#31-acrescentar-a-base-de-um-serviço-novo-a-um-volume-existente)). **O volume é preservado.**
- **Esquema Flyway:**

  | Tabela | Chaves e índices |
  |---|---|
  | `inbox` | chave primária `recipient_id` |
  | `notification` | chave primária `id`; único (`recipient_id`, `seq`); único (`event_id`, `recipient_id`); índice (`recipient_id`) só das não lidas |
  | `received_event` | chave primária `event_id` |
  | `fan_out_job` | chave primária `event_id` |

  Os produtores ganham uma tabela `outbox` cada (users_db, experiences_db, messaging_db), numa migração nova.
- **Contentor `notifications-service`** em **`127.0.0.1:8086`**, na rede interna com o PostgreSQL e o users-service. Monta só a chave pública, só de leitura.
- **Chaves novas no `.env`** (fora do Git e das imagens):
  - `NOTIFY_USERS_KEY`, `NOTIFY_EXPERIENCES_KEY` e `NOTIFY_MESSAGING_KEY`, cada uma só no seu produtor e no notifications-service;
  - `USERS_FOLLOWERS_KEY`, só no users-service e no notifications-service.

  O `.env.example` recebe os nomes com valores de exemplo.
- **Memória:** medida na primeira parte, ≈ 214 MiB sem carga (≈ 1,33 GiB para os sete contentores; §20). Neste computador, o serviço pode ficar parado quando não é preciso: é exatamente o caso de falha previsto (§13).

## 15. App Android (proposta)

### 15.1 Caixa de notificações

- **Acesso:** "Notificações" no menu da conta (Pesquisar, Descobrir, Estatísticas), com a contagem por ler ("99+").
  - A contagem é pedida quando um destes ecrãs volta a ficar visível; não há consulta contínua fora da caixa.
  - Sem sessão, a entrada não existe, como em "Mensagens".
- **Ecrã:**
  - lista por `seq` decrescente, com "Carregar mais" (`before`);
  - texto por tipo (§9), data, e destaque das não lidas;
  - consulta periódica de 30 s só com o ecrã RESUMED, até 60 s com erros (o `Poller` das mensagens).
- **Abrir uma notificação:** marca-a como lida (`PUT …/{id}/read`) e abre o destino:

  | Tipo | Destino |
  |---|---|
  | mensagem | a conversa |
  | comentário | a experiência |
  | seguidor | o perfil do ator |
  | nova experiência | a experiência |

  "Marcar todas como lidas" usa `PUT /notifications/read` com o `seq` mais alto carregado.
- **Falhas:** notifications-service em baixo → "Notificações indisponíveis" com "Tentar novamente"; o resto da app continua.

### 15.2 Permissão de notificações

- `POST_NOTIFICATIONS` (Android 13+) **nunca é pedida ao abrir a app.** Na caixa de notificações (ou depois do primeiro login), um cartão explica "Recebe avisos de mensagens e comentários na barra do telemóvel. Podem chegar com atraso." com "Ativar". Só esse toque abre o pedido do sistema.
- **Recusada:** o cartão passa a explicar como ativar nas definições. **A caixa dentro da app continua a funcionar.** O WorkManager só é agendado com a permissão concedida (ou em Android < 13).

### 15.3 WorkManager

- **Um trabalho periódico único** (`notifications-poll`, `ExistingPeriodicWorkPolicy.UPDATE`), de 15 em 15 min (o mínimo do Android), com a restrição "rede ligada".
- **Sem prometer execução exata:** o Android decide quando corre (Doze, *App Standby Buckets*, poupança de bateria, restrições do fabricante). Não se usam alarmes exatos nem serviços em primeiro plano.
- **Sessão:**
  - o worker usa a mesma sessão da app (o mesmo `SessionManager`, no mesmo processo), por isso as renovações do worker e dos ecrãs nunca correm em paralelo;
  - mantêm-se as regras existentes para uma renovação ambígua: sem nova tentativa automática às cegas, e com a sessão "por confirmar" até a app a resolver;
  - **se a sessão não puder ser renovada** (sem sessão, refresh recusado, ambíguo ou users-service em baixo), a execução não mostra nada e termina. Não há promessa de notificação em segundo plano nesse caso: as notificações ficam na caixa e aparecem quando a sessão voltar a ser válida.
- **Cada execução:**
  1. obtém o access token pela sessão guardada (renova com o refresh token se for preciso). Sem sessão válida, termina sem fazer nada;
  2. pede `GET /notifications?after=<último seq notificado desta conta>&limit=50`, até 3 páginas;
  3. mostra notificações do sistema só para as **não lidas** que ainda não mostrou. Mostra no máximo 5 individuais, agrupadas por canal, e acima disso um resumo "e mais N";
  4. guarda o novo cursor da conta.
- **Falhas** (rede, `5xx`, notifications-service em baixo): `Result.retry()` com o recuo do WorkManager. O cursor não avança e nada é mostrado.
- **Deduplicação local:** o cursor por conta (só o número do `seq`) e o id da notificação Android derivado do `seq`. Uma execução repetida, ou duas sobrepostas, não mostra a mesma notificação duas vezes.
- **Estado guardado:** só `{conta: último seq notificado}`, em armazenamento privado da app, excluído das cópias de segurança. Nenhum conteúdo nem token (os tokens continuam no armazenamento cifrado da sessão).

### 15.4 Canais e privacidade

- **Canais:**

  | Canal | Importância |
  |---|---|
  | "Mensagens" | predefinida |
  | "Comentários" | predefinida |
  | "Seguidores" | baixa |
  | "Novas experiências" | baixa |

  O utilizador pode desligar cada um nas definições do Android.
- **Visibilidade privada** em todas, com uma versão pública **genérica** para o ecrã bloqueado ("PortoFit: tens novidades"), sem nomes nem tipo.
- **Texto:** só o do §9. Nunca há texto de mensagens ou de comentários, mesmo desbloqueado (o servidor nem o tem).
- **Logs:** sem conteúdo, ids de conta nem tokens.

### 15.5 Notificações do sistema e leitura na caixa

São coisas diferentes:

- **Dispensar uma notificação do sistema** não marca nada como lido.
- **Ler na caixa** marca a notificação como lida no servidor e retira a notificação do sistema correspondente, se ainda estiver visível.
- **Tocar numa notificação do sistema** abre o destino e marca-a como lida, como abrir na caixa.
- **Uma notificação lida noutro aparelho** não volta a aparecer como notificação do sistema: só as não lidas são mostradas.

### 15.6 Contas, logout e destino

- **Ao terminar sessão:**
  - cancela o trabalho periódico;
  - retira todas as notificações do sistema da app;
  - apaga o cursor da conta e a caixa em memória;
  - descarta as respostas tardias (geração e conta do pedido, como nas mensagens).
- **Ao entrar com outra conta:** o mesmo, e depois volta a agendar o trabalho para a nova conta. As notificações do sistema da conta anterior nunca ficam visíveis.
- **Cada notificação do sistema leva o id da conta destinatária** no `PendingIntent`.
  - Se ao tocar a sessão for de outra conta, ou não houver sessão, a app abre só a caixa (ou o login), nunca o conteúdo.
  - Depois de entrar, não abre o destino sozinha.
- **Destino eliminado ou serviço indisponível:** o ecrã de destino mostra o seu estado (§10).

## 16. Critérios de teste

**Produtores (users-, experiences-, messaging-service), com PostgreSQL real:**

- **Mesma transação:**
  - a ação e o evento ficam juntos;
  - se a ação falhar ou for desfeita (ex.: limite, nome repetido), não fica evento;
  - uma repetição idempotente (`200`) não grava outro evento;
  - a própria ação não gera evento (comentar a própria experiência, mensagem não conta para o remetente).
- **notifications-service em baixo:** seguir, comentar, publicar e enviar mensagens respondem como sempre, com os eventos `PENDING`. Com ele de volta, chegam todos.
- **Recuo e falhas:**
  - os intervalos crescem até 10 min;
  - `REJECTED` → `FAILED` logo;
  - `403` não gasta tentativas;
  - 20 tentativas ou 24 h → `FAILED`, guardado e reposto à mão com sucesso.
- **Concorrência:** duas tarefas de envio ao mesmo tempo (`SKIP LOCKED`) não enviam o mesmo evento duas vezes no mesmo instante; mesmo que enviassem, o destino deduplica.
- **Conteúdo:** os eventos não contêm o texto de mensagens, comentários nem experiências (verificado no JSON gravado).

**notifications-service, com PostgreSQL real e um users-service falso:**

- **Autenticação e privacidade:**
  - endpoints públicos com os casos habituais de token;
  - notificações de outra pessoa ≡ inexistentes em todas as operações;
  - a caixa só mostra as do `sub`.
- **Entrega interna:**
  - sem chave, com chave errada, com a chave de outro produtor e com a `USERS_INTERNAL_KEY` → recusado;
  - tipo de outro produtor → `REJECTED`;
  - lote misto (aceite, duplicado, rejeitado) com resultados por evento.
- **Repetição de eventos:**
  - o mesmo `eventId` duas vezes, e 8 vezes em simultâneo → uma notificação;
  - o mesmo `eventId` com outro conteúdo → `REJECTED/CONFLICT`;
  - seguir, deixar e voltar a seguir em 24 h → uma notificação.
- **Cursores:**
  - `seq` únicos e crescentes com criações simultâneas;
  - `after` drena sem saltos nem repetidas;
  - `before` pagina o histórico.
- **Leitura:**
  - marcar uma e todas até um `seq` é idempotente;
  - um valor acima do fim fica no fim;
  - a contagem desce e não conta as de outras pessoas.
- **Resolução de destinatários:**
  - mais de uma página de seguidores, sem o autor;
  - falha a meio retoma no cursor sem duplicar;
  - falha temporária deixa `PENDING`, nunca `DONE` com 0;
  - `200` vazio → `DONE`;
  - `404` → `DONE` sem notificações;
  - limite → `TRUNCATED` explícito;
  - relação criada depois de `occurredAt` → sem notificação.
- **Retenção e limites:**
  - a limpeza apaga as notificações e os eventos antigos;
  - o limite por pessoa retira as mais antigas;
  - lote acima de 100 ou de 256 KiB → `400`/`413`.
- **Registos:** sem tokens, chaves nem usernames nos logs capturados.

**App (JVM):**

- **ViewModel da caixa:** visitante sem pedidos; paginação; consulta só com o ecrã ativo; abrir marca como lida e navega; conta errada não abre o destino.
- **Worker** (testado como classe, com repositório e sessão falsos):
  - só as não lidas novas e sem repetir com o mesmo cursor;
  - falha → `retry`, sem avançar o cursor;
  - sem sessão → nada;
  - limite de 5 e resumo;
  - troca de conta não usa o cursor da anterior.
- **Logout:** cancela o trabalho, retira as notificações do sistema e apaga o cursor.
- **Permissão recusada:** sem trabalho agendado, com a caixa a funcionar.
- **Conteúdo:** o texto das notificações e a versão pública genérica.

**Verificação manual (emulador ou telemóvel):**

- Permissão pedida só pelo cartão; recusa com a caixa a funcionar.
- Notificação do sistema a aparecer **sozinha**, com a app em segundo plano, numa execução escolhida pelo Android. Regista-se o atraso observado, sem prometer um valor.
  - Forçar a execução do worker (ex.: `adb shell cmd jobscheduler run`, *App Inspection*) **mostra o mecanismo, mas não prova a entrega em segundo plano** e não conta como essa verificação.
- Ecrã bloqueado com o texto genérico.
- Tocar abre o destino certo.
- Logout retira as notificações.
- Parar o notifications-service: as ações continuam; recomeçar: as notificações aparecem.
- Conta B no aparelho que tinha notificações da conta A: nenhuma visível.

## 17. Decisões

| # | Decisão | Alternativa afastada |
|---|---|---|
| 1 | Sem Firebase nesta versão (requisito **por confirmar com a docente**); WorkManager periódico, sem garantia de atraso | FCM já nesta versão (projeto Firebase, tokens de dispositivo) |
| 2 | Gostos fora desta versão | notificar gostos (ruído) |
| 3 | Transactional outbox nos três produtores, entrega em lote com resultado por evento | pedido direto na ação ("dispara e esquece") |
| 4 | Uma chave por produtor, com tipos permitidos por chave | uma chave partilhada |
| 5 | Deduplicação por `eventId` e por (`eventId`, `recipientId`); seguidores repetidos em 24 h ignorados | só por `eventId` |
| 6 | Novas experiências: um evento e resolução de destinatários no notifications-service, por páginas com cursor, com um endpoint interno de ids no users-service | um evento por seguidor na outbox do experiences-service; pedidos por destinatário; usar a lista pública paginada por número de página |
| 7 | Falha ao resolver → `PENDING`/`FAILED`, nunca "sem destinatários"; limite explícito `TRUNCATED` | concluir com 0; cortar em silêncio |
| 8 | Destinatários = seguidores desde antes da publicação que continuam a seguir quando a página é lida | fotografia dos seguidores no momento da publicação |
| 9 | Notificações sem conteúdo: tipo, ator e referências; texto construído na app; ecrã bloqueado genérico | guardar excertos de mensagens ou comentários |
| 10 | Cursor `seq` por pessoa, como nas mensagens | paginação por datas |
| 11 | Leitura na caixa separada das notificações do sistema | tratar "dispensada" como "lida" |
| 12 | Retenção de 90 dias e 1000 por pessoa, configuráveis | guardar para sempre |

## 18. Complexidade

O peso desta etapa está mais na infraestrutura de eventos do que nos ecrãs.

- **Três produtores com outbox:**
  - uma tabela e uma migração em cada base;
  - gravar o evento na mesma transação em quatro pontos (seguir, comentário, publicação, mensagem);
  - uma tarefa de envio com recuo, estados e limpeza.

  O código é praticamente igual nos três; cada um tem os seus testes.
- **notifications-service novo:** caixa com `seq`, leitura, deduplicação, entrega em lote, resolução de destinatários com cursor e retoma, retenção.
- **users-service:** um endpoint interno novo (ids de seguidores com cursor) e uma chave nova.
- **App:**
  - caixa e entrada no menu;
  - cartão de permissão e canais;
  - worker com cursor por conta;
  - `PendingIntent` com a conta destinatária;
  - limpeza no logout.
- **Infraestrutura:** contentor e base novos, quatro chaves novas, e a ordem de arranque sem dependências obrigatórias.
- **O que torna a verificação difícil:** a entrega em segundo plano depende do Android e não é reproduzível a pedido. Precisa de observação com tempo e com memória suficiente no computador.

## 19. Decisões da revisão (2026-10-22)

- **Mensagens:** uma notificação na caixa por mensagem recebida. Na app, as notificações do sistema podem ser agrupadas por conversa.
- **Sem eventos de eliminação nesta versão:** ao abrir, a app consulta o estado atual do destino (§10).
- **Seguidores:** mantém-se a regra do §8.4 e o limite configurável de 10 000 destinatários, com `TRUNCATED` registado explicitamente.
- **WorkManager:** de 15 em 15 min, sem garantia de execução exata (§15.3).
- **Preferências:** os canais do Android são as preferências iniciais; sem preferências adicionais por tipo na app.
- ***Push* com Firebase:** continua **por confirmar com a docente** ([arquitetura.md §6.2](arquitetura.md#62-pontos-em-aberto), ponto 1). Esta versão não o implementa; isso não significa que o enunciado o dispense.
- **Ajustes:**
  - deduplicação que cobre o reenvio e não deixa recriar notificações apagadas (§6.3–§6.4);
  - limites do reenvio manual (§6.4);
  - chave inválida com recuo e sem repetição intensa (§7);
  - observação da outbox sem conteúdo (§7.1);
  - paginação estável dos seguidores, sem fotografia transacional (§8.1, §8.4);
  - sessão no worker (§15.3);
  - lacunas nos cursores (§5.1).
- **Ordem de implementação:**
  - **primeira parte:** notifications-service, mais a outbox do messaging-service para novas mensagens e partilhas;
  - **segunda parte:** seguidores (users-service, com o endpoint interno), comentários e publicações (experiences-service, com a resolução de destinatários);
  - **por último:** a app Android.

## 20. Estado da implementação

### Primeira parte do backend (2026-10-22)

`backend/notifications-service` e a outbox do messaging-service. O código das duas partes segue este contrato sem alterações.

**Implementado:**

- **Serviço independente:**
  - projeto Gradle próprio;
  - base `notifications_db` com o utilizador `NOTIFICATIONS_DB_USER`, sem privilégios de administração, criada no volume existente por `06-notifications-db.sh` (corrido duas vezes, sem erros);
  - contentor em `127.0.0.1:8086`, com só a chave pública montada.
- **Caixa (§5):**
  - `GET /notifications` com `before`, `after` e `limit`, sobre um instantâneo da base;
  - contagem por ler (até 100);
  - marcação de uma e marcação até um `seq` (limitada ao `lastSeq`);
  - `seq` atribuído com a linha `inbox` bloqueada.
- **Entrega interna (§6):**
  - lotes de 1 a 100 eventos (corpo até 256 KiB), com um resultado por evento;
  - uma chave por produtor, comparada em tempo constante (uma chave vazia ou repetida não autoriza ninguém);
  - validação estrita: um campo a mais (por exemplo, um texto) é `INVALID_EVENT`;
  - deduplicação por `eventId`: o mesmo id com outro conteúdo é `CONFLICT`; também `EXPIRED`, futuro e `SELF_EVENT`.
  - **Nesta parte só se aceita `MESSAGE_RECEIVED`, e só do messaging-service.** Os outros tipos do §3 respondem `REJECTED/UNSUPPORTED_TYPE`.
- **Retenção (§12):**
  - 90 dias e 1000 por pessoa, de hora a hora;
  - deduplicação durante 35 dias (o arranque falha se não for maior do que a idade máxima);
  - apagar notificações não apaga a deduplicação.
- **Outbox no messaging-service (§7):**
  - migração `V2__outbox.sql`;
  - o evento é gravado na transação da mensagem, sem o texto e sem a experiência partilhada (só `TEXT` ou `SHARE`);
  - uma repetição idempotente ou um envio recusado não criam evento;
  - envio de 5 em 5 s (lotes até 100, `FOR UPDATE SKIP LOCKED`), recuo e `FAILED` como no §7;
  - erro de configuração com recuo próprio e sem gastar tentativas;
  - resumo de minuto a minuto e limpeza.
  - Chave `NOTIFY_MESSAGING_KEY` só no `.env` (fora do Git e das imagens).

**Testes automáticos** (PostgreSQL real, Testcontainers; corridos um serviço de cada vez, depois da última alteração):

- **notifications-service: 9 testes.**
  - Autenticação (sem token, outra chave, outra audiência, expirado).
  - Chave interna: sem chave, errada, token de utilizador em vez de chave, `WRONG_PRODUCER` e `UNSUPPORTED_TYPE`.
  - Lote misto: `ACCEPTED`, `DUPLICATE` no mesmo lote, `CONFLICT`, campo `text` a mais, `SELF_EVENT`, `EXPIRED`, futuro, tipo desconhecido; o texto não aparece em lado nenhum. Lotes de 0 e 101 eventos e corpo de 300 KB recusados.
  - Privacidade: a notificação de outra pessoa dá o mesmo `404` de uma inexistente.
  - Cursores: recentes, anteriores, novas até ao fim e lacunas depois da retenção.
  - Leitura: idempotente, limitada ao `lastSeq`, e pedidos simultâneos.
  - Concorrência: o mesmo evento 8 vezes ao mesmo tempo cria 1 notificação; 20 eventos simultâneos ficam com `seq` 1–21 sem repetições.
  - Limite por pessoa e retenção: um evento reenviado depois de a notificação ser apagada é `DUPLICATE` e não a recria. Limpeza da deduplicação aos 35 dias.
  - Configuração inválida da retenção.
  - Registos sem usernames, chaves, tokens nem texto.
- **messaging-service: 27 testes** (os 15 anteriores e 12 da outbox).
  - Evento na mesma transação, com uma linha por mensagem gravada; nenhum evento em repetições idempotentes nem em recusas; `TEXT` e `SHARE`; os campos do payload são exatamente os do §3.1.
  - notifications-service em baixo: envio `201` imediato, recuo de 5 s e 10 s, entrega única ao voltar.
  - Resposta perdida (aceite, mas respondida com `503`): o reenvio é `DUPLICATE` e o evento fica `SENT` uma vez.
  - Resposta parcial (`MISSING_RESULT`): só o evento em falta repete.
  - `REJECTED` passa a `FAILED`.
  - Chave recusada: recuo de 5 s e 10 s, 25 ciclos sem gastar tentativas, recuperação depois de corrigida; a chave não aparece nos registos.
  - Chave por configurar.
  - `MAX_ATTEMPTS` e `MAX_AGE`.
  - Lotes de 100 e 50 pela ordem de criação.
  - Dois envios simultâneos sem eventos repetidos.
  - Limpeza.
  - Resumo sem conteúdo.
- **Defeito encontrado pelos testes, antes do commit:** a consulta do resumo da outbox não indicava o tipo do parâmetro da data, e o PostgreSQL recusava-a (o resumo teria falhado de minuto a minuto). Corrigido com `CAST(:now AS timestamptz)`.

**Pedidos HTTP** (2026-10-22, no Docker Compose, com três contas temporárias apagadas no fim):

- **Entrega:**
  - mensagem `201` em 165 ms; evento `SENT` em cerca de 6 s (um ciclo do envio periódico);
  - o destinatário vê `MESSAGE_RECEIVED` com o ator, a conversa e `TEXT`, sem texto, e 1 por ler;
  - a autora e uma terceira pessoa não veem nada.
- **Privacidade e leitura:**
  - marcar a notificação de outra pessoa → `404` com o mesmo `code` e `detail` de uma inexistente (só muda o `instance`, o caminho pedido);
  - sem token → `401`;
  - marcar uma, repetir e marcar até 999 → `200`, 0 por ler.
- **Chave interna:** sem chave, com chave errada ou com o token de um utilizador → `403`, sem notificação criada.
- **notifications-service parado:**
  - duas mensagens `201` em 190 ms e 23 ms;
  - os eventos ficaram `PENDING` com 1 tentativa (`HttpConnectTimeoutException`);
  - depois de `docker compose start`, o serviço ficou saudável em 13 s e os eventos foram entregues 2 s depois;
  - 3 notificações para 3 eventos e 3 registos de deduplicação, sem repetições.
- **Chave do produtor errada** (sobreposta só num arranque do messaging-service):
  - mensagem `201`;
  - evento `PENDING`, 0 tentativas, `CONFIG_HTTP_403`;
  - três linhas de erro em cerca de 25 s (adiados 5, 10 e 20 s), sem a chave;
  - com a chave correta, o evento foi entregue 9 s depois do arranque.
- **Isolamento das bases:** `notifications_user` não entra em `messaging_db`; `messaging_user` e `users_user` não entram em `notifications_db`.
- **Registos dos dois serviços:** sem usernames, chaves, tokens nem texto.

**Consumo medido** (sete contentores, sem carga):

- **notifications-service:** ≈ 214 MiB; imagem de 499 MB; primeiro arranque em 27 s (com as migrações).
- **messaging-service:** ≈ 211 MiB.
- **PostgreSQL:** ≈ 80 MiB.
- **Total:** ≈ 1,33 GiB.
- **`notifications_db`:** ≈ 8 MB.

**Ainda não implementado (segunda parte e app):**

- outbox no users-service e no experiences-service: `USER_FOLLOWED`, com a janela de 24 h, `COMMENT_CREATED` e `EXPERIENCE_PUBLISHED`;
- resolução de seguidores: `FanOutJob` e a tabela `fan_out_job` (a migração atual ainda não a tem), consulta paginada e `USERS_FOLLOWERS_KEY`;
- app Android: caixa, contagem, WorkManager, canais e permissão.

O reenvio manual de eventos `FAILED` está documentado na infraestrutura, mas não foi exercitado nesta verificação. *Push* com Firebase Cloud Messaging continua a ser um requisito **a confirmar com a docente** ([arquitetura.md §6.2](arquitetura.md#62-pontos-em-aberto)).
