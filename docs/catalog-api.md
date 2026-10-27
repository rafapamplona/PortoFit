# catalog-service — Contrato da API

> Primeiro contrato detalhado do PortoFit. Contexto geral em [arquitetura.md](arquitetura.md).
> Estado: **v1 implementada no backend**: `GET /sports`, `GET /places` e `GET /places/{placeId}` (etapa 3); `GET /offerings/{offeringId}`, estado `ACTIVE`/`RETIRED` e avaliações reais pedidas ao experiences-service (etapa 6, [experiences-api.md §6](experiences-api.md#6-validação-da-atividade-no-catálogo)). Ajustes feitos durante a implementação em [§8](#8-registo-de-ajustes).

## 1. Responsabilidade

O catalog-service é o dono de **modalidades**, **locais** e **atividades oferecidas**. Nesta fase o catálogo é **só de leitura** pela API. O esquema e as modalidades (dados de referência) são criados por migrações Flyway; os locais e atividades de demonstração, **fictícios**, só são carregados com o perfil `demo` (ver §6). Não há endpoints de criação ou edição, nem reservas, horários reserváveis ou preços.

Todos os endpoints deste documento são **públicos** (não exigem sessão).

- Base URL em desenvolvimento: `http://localhost:8081/api/v1`
- A partir do emulador Android: `http://10.0.2.2:8081/api/v1`
- Formato: JSON em UTF-8. Datas em ISO 8601 UTC. Nomes de campos em inglês (camelCase); textos em português.

## 2. Entidades e relações

```
Sport (modalidade) 1 ──── N ActivityOffering (atividade oferecida) N ──── 1 Place (local)
```

- Um **local** oferece uma ou mais atividades.
- Uma **atividade oferecida** pertence a um local e a uma modalidade. O mesmo local pode ter várias atividades da mesma modalidade (ex.: "Boxe iniciação" e "Boxe competição").
- Uma **experiência** (no experiences-service) refere-se a uma atividade oferecida pelo seu `id`.

### 2.1 Sport — modalidade

| Campo | Tipo | Obrigatório | Regras |
|---|---|---|---|
| `id` | string | sim | *Slug* estável e legível, padrão `^[a-z0-9]+(-[a-z0-9]+)*$`, 2–40 caracteres. Ex.: `boxe`, `artes-marciais` |
| `name` | string | sim | 2–60 caracteres, único (sem distinguir maiúsculas/minúsculas). Ex.: "Boxe" |
| `description` | string | não | até 300 caracteres |

O id é um *slug* (e não um UUID) porque é usado como filtro em URLs (`sport=boxe`) e guardado como interesse no users-service; tem de ser estável e compreensível.

### 2.2 Place — local

| Campo | Tipo | Obrigatório | Regras |
|---|---|---|---|
| `id` | UUID | sim | Gerado; fixo nos dados de demonstração para serem reprodutíveis |
| `name` | string | sim | 2–120 caracteres |
| `description` | string | não | até 1000 caracteres |
| `address.street` | string | sim | até 200 caracteres |
| `address.postalCode` | string | sim | formato português `^\d{4}-\d{3}$` |
| `address.parish` | string | não | freguesia, até 80 caracteres |
| `address.city` | string | sim | até 80 caracteres (nesta fase sempre "Porto") |
| `location.latitude` | número | sim | −90 a 90, até 6 casas decimais |
| `location.longitude` | número | sim | −180 a 180, até 6 casas decimais |
| `contacts.phone` | string | não | até 20 caracteres |
| `contacts.website` | string | não | URL `http(s)://`, até 200 caracteres |
| `demo` | booleano | sim | `true` em todos os dados fictícios |
| `status` | enum | sim | `ACTIVE` (por omissão) ou `RETIRED`. Um local retirado não aparece na pesquisa pública e deixa de aceitar publicações, mas continua a existir e a responder pelo id em `GET /places/{id}`, com o estado (ver [experiences-api.md §15](experiences-api.md#15-relação-com-locais-reais-e-substituição-do-catálogo-de-demonstração)). Não há endpoints para retirar: é uma operação de quem gere os dados |

### 2.3 ActivityOffering — atividade oferecida

| Campo | Tipo | Obrigatório | Regras |
|---|---|---|---|
| `id` | UUID | sim | Gerado; fixo nos dados de demonstração |
| `placeId` | UUID | sim | Local existente |
| `sportId` | string | sim | Modalidade existente |
| `name` | string | sim | 2–120 caracteres; único dentro do mesmo local |
| `description` | string | não | até 1000 caracteres |
| `level` | enum | sim | `BEGINNER`, `INTERMEDIATE`, `ADVANCED`, `ALL_LEVELS`, `UNSPECIFIED` ("Nível não indicado": a fonte não indica níveis; usado nos locais reais) |
| `scheduleNotes` | string | não | até 300 caracteres. **Texto informativo** (ex.: "Seg. e qua., 19h–20h"); não é um horário reservável |
| `status` | enum | sim | `ACTIVE` (por omissão) ou `RETIRED`, como no local. Nas respostas é sempre o estado **efetivo**: `RETIRED` se a atividade ou o seu local estiverem retirados. Atividades retiradas não contam para a pesquisa pública |

### 2.4 Rating — avaliação agregada (complemento)

As avaliações **não pertencem ao catálogo**: são calculadas pelo experiences-service (regras em [arquitetura.md §3.7](arquitetura.md#37-avaliação-geral-sem-distorção)). O catálogo pede-as e junta-as à resposta. Como o experiences-service pode estar indisponível, o objeto `rating` tem sempre um **estado explícito**:

| `status` | Significado | `average` | `ratingsCount` | Como a app mostra |
|---|---|---|---|---|
| `AVAILABLE` | Há avaliações | número 1,0–5,0 (1 casa decimal) | inteiro ≥ 1 (pessoas distintas) | "★ 4,3 (12)" |
| `NO_RATINGS` | Ainda ninguém avaliou | `null` | `0` | "Sem avaliações" |
| `UNAVAILABLE` | Não foi possível obter as avaliações agora | `null` | `null` | "Avaliação indisponível" |

`UNAVAILABLE` **nunca** é apresentado como zero estrelas nem como "sem avaliações". Quando acontece, a resposta continua a ser `200 OK` com todos os dados do catálogo.

## 3. Endpoints

| Método | Caminho | Descrição |
|---|---|---|
| GET | `/sports` | Listar modalidades |
| GET | `/places` | Pesquisar locais (texto, modalidade, proximidade), paginado |
| GET | `/places/{placeId}` | Detalhe de um local com atividades oferecidas |
| GET | `/offerings/{offeringId}` | Detalhe de uma atividade oferecida (também usado pelo experiences-service para validar publicações) |
| GET | `/actuator/health` | Estado do serviço (fora do prefixo `/api/v1`) |

### 3.1 `GET /sports`

Lista todas as modalidades, ordenadas por nome. Não é paginado (a lista é pequena).

**Pedido**

```http
GET /api/v1/sports
```

**Resposta `200 OK`**

```json
{
  "items": [
    { "id": "boxe", "name": "Boxe", "description": "Desporto de combate com os punhos." },
    { "id": "corrida", "name": "Corrida", "description": null },
    { "id": "ioga", "name": "Ioga", "description": null },
    { "id": "natacao", "name": "Natação", "description": null }
  ]
}
```

### 3.2 `GET /places`

Pesquisa locais. Todos os parâmetros são opcionais; sem parâmetros devolve todos os locais por ordem alfabética.

**Parâmetros**

| Parâmetro | Tipo | Por omissão | Validação | Descrição |
|---|---|---|---|---|
| `q` | string | — | após remover espaços nas pontas: 2–100 caracteres | Texto pesquisado |
| `sport` | string | — | id de modalidade existente | Só locais com pelo menos uma atividade desta modalidade |
| `lat` | número | — | −90 a 90; obrigatório se existir `lng` | Latitude do utilizador |
| `lng` | número | — | −180 a 180; obrigatório se existir `lat` | Longitude do utilizador |
| `radiusKm` | número | `5` | 0,1 a 50; só permitido com `lat`/`lng` | Raio de pesquisa em **quilómetros** |
| `sort` | enum | `distance` se houver coordenadas, senão `name` | `name`, `distance` | Ordenação |
| `page` | inteiro | `0` | ≥ 0 | Página (começa em 0) |
| `size` | inteiro | `20` | 1 a 50 | Elementos por página |

**Regras de pesquisa**

- `q` encontra locais cujo **nome do local**, **nome de uma modalidade** oferecida ou **nome de uma atividade oferecida** contenha o texto, **sem distinguir maiúsculas de minúsculas nem acentos**. Ex.: `q=boxe`, `q=BOXE` e `q=Boxe` dão o mesmo resultado; `q=natacao` encontra "Natação".
- Este é o comportamento pretendido; a forma de o implementar é escolhida na implementação do serviço (ver [arquitetura.md §3.2](arquitetura.md#32-responsabilidades)).
- `q` e `sport` combinam-se com **E** (ex.: `q=boavista&sport=boxe` → locais de boxe com "boavista" no nome).
- **Só dados ativos:** a pesquisa devolve apenas locais `ACTIVE`; o filtro `sport`, a pesquisa pelo nome da modalidade ou da atividade e a lista `sports` de cada resultado só consideram atividades `ACTIVE`. Um local retirado continua acessível em `GET /places/{id}` (§3.3).
- Com `lat`/`lng`, só são devolvidos locais a uma distância ≤ `radiusKm` e cada resultado inclui `distanceKm`. A app (etapa 14) envia as coordenadas com 6 casas decimais, um raio de 1, 3, 5 ou 10 km e `sort=distance`, só depois de o utilizador o pedir ([app-android.md §21](app-android.md#21-pesquisa-por-proximidade-etapa-14)).
- A distância é em linha reta (fórmula de Haversine), em **quilómetros**, arredondada a 2 casas decimais. Não é distância a pé nem de carro.
- Os caracteres `%` e `_` em `q` são procurados literalmente (não funcionam como curingas).
- Parâmetros enviados vazios (ex.: `q=`, `sport=`) são tratados como ausentes.
- `sort=name`: nome do local A→Z, sem distinguir maiúsculas/minúsculas nem acentos; desempate pelo `id`.
- `sort=distance`: mais próximo primeiro; desempate pelo nome e, por fim, pelo `id`. Exige coordenadas.
- As duas ordenações são totais (terminam no `id`), por isso a paginação é estável: percorrer as páginas devolve cada local exatamente uma vez.
- **Ordenar por avaliação não é suportado nesta versão**: as avaliações pertencem ao experiences-service e podem estar indisponíveis, o que tornaria a ordem instável. Pode ser acrescentado mais tarde com uma cópia local das avaliações.

**Paginação** — todas as listas paginadas usam o mesmo envelope:

| Campo | Descrição |
|---|---|
| `items` | Elementos da página |
| `page` | Página atual (começa em 0) |
| `size` | Tamanho pedido |
| `totalItems` | Total de resultados |
| `totalPages` | Total de páginas |

Uma página para além do fim devolve `items: []` (não é erro).

**Exemplo 1 — pesquisa por texto** (resposta real com os dados de demonstração; `size=2` para encurtar)

```http
GET /api/v1/places?q=boxe&size=2
```

```json
{
  "items": [
    {
      "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e06",
      "name": "Box Funcional Exemplo Bonfim (fictício)",
      "address": {
        "street": "Rua Exemplo, 77",
        "postalCode": "4000-001",
        "parish": "Bonfim",
        "city": "Porto"
      },
      "location": { "latitude": 41.1478, "longitude": -8.5987 },
      "sports": [
        { "id": "boxe", "name": "Boxe" },
        { "id": "crossfit", "name": "CrossFit" }
      ],
      "rating": { "status": "UNAVAILABLE", "average": null, "ratingsCount": null },
      "demo": true
    },
    {
      "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01",
      "name": "Clube de Combate Ribeira (fictício)",
      "address": {
        "street": "Rua Exemplo, 10",
        "postalCode": "4050-001",
        "parish": "Centro Histórico",
        "city": "Porto"
      },
      "location": { "latitude": 41.1406, "longitude": -8.6131 },
      "sports": [
        { "id": "boxe", "name": "Boxe" },
        { "id": "kickboxing", "name": "Kickboxing" }
      ],
      "rating": { "status": "UNAVAILABLE", "average": null, "ratingsCount": null },
      "demo": true
    }
  ],
  "page": 0,
  "size": 2,
  "totalItems": 4,
  "totalPages": 2
}
```

Os 4 resultados são os locais com a modalidade Boxe; por ordem de nome, os dois seguintes estão na página 1. Nesta versão todas as avaliações vêm `UNAVAILABLE` (ver §5).

**Exemplo 2 — modalidade e proximidade** (resposta real; `size=2`)

```http
GET /api/v1/places?sport=boxe&lat=41.1496&lng=-8.6109&radiusKm=3&size=2
```

```json
{
  "items": [
    {
      "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01",
      "name": "Clube de Combate Ribeira (fictício)",
      "address": {
        "street": "Rua Exemplo, 10",
        "postalCode": "4050-001",
        "parish": "Centro Histórico",
        "city": "Porto"
      },
      "location": { "latitude": 41.1406, "longitude": -8.6131 },
      "sports": [
        { "id": "boxe", "name": "Boxe" },
        { "id": "kickboxing", "name": "Kickboxing" }
      ],
      "distanceKm": 1.02,
      "rating": { "status": "UNAVAILABLE", "average": null, "ratingsCount": null },
      "demo": true
    },
    {
      "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e06",
      "name": "Box Funcional Exemplo Bonfim (fictício)",
      "address": {
        "street": "Rua Exemplo, 77",
        "postalCode": "4000-001",
        "parish": "Bonfim",
        "city": "Porto"
      },
      "location": { "latitude": 41.1478, "longitude": -8.5987 },
      "sports": [
        { "id": "boxe", "name": "Boxe" },
        { "id": "crossfit", "name": "CrossFit" }
      ],
      "distanceKm": 1.04,
      "rating": { "status": "UNAVAILABLE", "average": null, "ratingsCount": null },
      "demo": true
    }
  ],
  "page": 0,
  "size": 2,
  "totalItems": 4,
  "totalPages": 2
}
```

**Exemplo 3 — avaliações quando o experiences-service existir**

Os exemplos 1 e 2 mostram o comportamento atual e também o que acontece se o experiences-service falhar: os dados do catálogo vêm completos e só as avaliações ficam `UNAVAILABLE`. Quando as avaliações estiverem disponíveis, o mesmo campo passa a ter, por exemplo:

```json
"rating": { "status": "AVAILABLE", "average": 4.3, "ratingsCount": 12 }
```

ou, para um local ainda sem avaliações:

```json
"rating": { "status": "NO_RATINGS", "average": null, "ratingsCount": 0 }
```

### 3.3 `GET /places/{placeId}`

Detalhe de um local, com as atividades oferecidas e a avaliação de cada uma. Responde também para locais **retirados** (referências históricas de experiências antigas): `status` do local e de cada atividade indica se estão disponíveis. A lista `offerings` inclui as atividades retiradas, com `"status": "RETIRED"`; a app não as apresenta como disponíveis e não permite publicar nelas.

**Pedido**

```http
GET /api/v1/places/3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01
```

**Resposta `200 OK`** — com avaliações como ficarão quando o experiences-service existir. Nesta versão os três `rating` vêm `{ "status": "UNAVAILABLE", "average": null, "ratingsCount": null }`; o resto da resposta é igual à real.

```json
{
  "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01",
  "name": "Clube de Combate Ribeira (fictício)",
  "description": "Local fictício criado para demonstração do PortoFit. Não corresponde a nenhum estabelecimento real.",
  "address": {
    "street": "Rua Exemplo, 10",
    "postalCode": "4050-001",
    "parish": "Centro Histórico",
    "city": "Porto"
  },
  "location": { "latitude": 41.140600, "longitude": -8.613100 },
  "contacts": { "phone": null, "website": null },
  "rating": { "status": "AVAILABLE", "average": 4.3, "ratingsCount": 12 },
  "offerings": [
    {
      "id": "8a7b6c5d-4e3f-4a1b-9c8d-000000000101",
      "sport": { "id": "boxe", "name": "Boxe" },
      "name": "Boxe iniciação",
      "description": "Técnica base, condição física e sparring leve.",
      "level": "BEGINNER",
      "scheduleNotes": "Seg. e qua., 19h–20h (informativo)",
      "status": "ACTIVE",
      "rating": { "status": "AVAILABLE", "average": 4.2, "ratingsCount": 9 }
    },
    {
      "id": "8a7b6c5d-4e3f-4a1b-9c8d-000000000102",
      "sport": { "id": "kickboxing", "name": "Kickboxing" },
      "name": "Kickboxing aberto",
      "description": null,
      "level": "ALL_LEVELS",
      "scheduleNotes": null,
      "status": "ACTIVE",
      "rating": { "status": "AVAILABLE", "average": 4.5, "ratingsCount": 5 }
    }
  ],
  "status": "ACTIVE",
  "demo": true
}
```

O `rating` do local e o de cada atividade seguem as regras de [arquitetura.md §3.7](arquitetura.md#37-avaliação-geral-sem-distorção): cada pessoa conta uma vez por atividade e uma vez por local. Por isso a avaliação do local não é a média das atividades, e o `ratingsCount` do local (12) pode ser menor do que a soma das atividades (9 + 5 = 14) — neste exemplo, duas pessoas avaliaram as duas atividades. Se o experiences-service falhar, **todos** os `rating` vêm `UNAVAILABLE` e o resto da resposta é igual.

As experiências publicadas sobre o local **não** fazem parte desta resposta: a app pede-as ao experiences-service (`GET /experiences?placeId=…`, com as ordenações por data, gostos, interações e estrelas de [arquitetura.md §3.6](arquitetura.md#36-métricas-e-ordenações)). Se este falhar, o ecrã do local mostra na mesma as atividades.

### 3.4 `GET /offerings/{offeringId}`

Detalhe de uma atividade oferecida, com resumo do local. Usado pela app (ecrã de publicação) e pelo experiences-service para **validar a atividade e copiar o contexto** (`placeId`, `sportId`, nomes) ao publicar uma experiência.

**Pedido**

```http
GET /api/v1/offerings/8a7b6c5d-4e3f-4a1b-9c8d-000000000101
```

**Resposta `200 OK`**

```json
{
  "id": "8a7b6c5d-4e3f-4a1b-9c8d-000000000101",
  "name": "Boxe iniciação",
  "description": "Técnica base, condição física e sparring leve.",
  "level": "BEGINNER",
  "scheduleNotes": "Seg. e qua., 19h–20h (informativo)",
  "status": "ACTIVE",
  "sport": { "id": "boxe", "name": "Boxe" },
  "place": {
    "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01",
    "name": "Clube de Combate Ribeira (fictício)",
    "demo": true
  },
  "rating": { "status": "AVAILABLE", "average": 4.2, "ratingsCount": 9 }
}
```

**Parâmetro `includeRating`** (`true` por omissão, ou `false`):

- `true` (a app): acrescenta `rating`, obtido como no detalhe do local (§5), com os mesmos três estados.
- `false` (o experiences-service, ao validar uma publicação): o campo `rating` é **omitido** e o catálogo **não faz nenhum pedido** ao experiences-service. Assim, validar uma publicação nunca leva o catálogo a chamar de volta o serviço que a está a processar ([experiences-api.md §6.1](experiences-api.md#61-endpoint-do-catálogo)).

Erros: `404` se a atividade não existir (o experiences-service converte-o em `UNKNOWN_OFFERING`); `400` se o id não for um UUID ou `includeRating` não for `true`/`false`. A resposta inclui sempre `place.id`, que o experiences-service compara com o local indicado na publicação, e `status`, com que recusa publicações em atividades retiradas.

## 4. Erros

Os erros seguem o formato **Problem Details** (RFC 9457, `Content-Type: application/problem+json`), suportado nativamente pelo Spring Boot. Para erros de validação acrescenta-se `errors` com um elemento por parâmetro inválido. Os textos de `title`, `detail` e `message` estão em português e podem ser mostrados ao utilizador.

| Código | Quando |
|---|---|
| `400 Bad Request` | Parâmetro inválido, combinação inválida, modalidade inexistente em `sport`, UUID mal formado |
| `404 Not Found` | Local ou atividade inexistente |
| `405 Method Not Allowed` | Método diferente de GET |
| `500 Internal Server Error` | Erro inesperado (sem detalhes internos na resposta) |
| `503 Service Unavailable` | Base de dados do catálogo indisponível (resposta em ~5 s, o limite de espera por uma ligação) |

O campo `type` é omitido: segundo o RFC 9457, um `type` ausente equivale a `"about:blank"`. A ordem dos campos no JSON não é significativa.

A indisponibilidade do experiences-service **não** gera erro (ver §2.4).

**`lat` sem `lng`**

```http
GET /api/v1/places?lat=41.1496
```

```json
{
  "title": "Pedido inválido",
  "status": 400,
  "detail": "Um ou mais parâmetros são inválidos.",
  "instance": "/api/v1/places",
  "errors": [
    { "field": "lng", "message": "Indica a latitude e a longitude em conjunto." }
  ]
}
```

**Vários parâmetros inválidos**

```http
GET /api/v1/places?q=b&radiusKm=80&size=500
```

```json
{
  "title": "Pedido inválido",
  "status": 400,
  "detail": "Um ou mais parâmetros são inválidos.",
  "instance": "/api/v1/places",
  "errors": [
    { "field": "q", "message": "A pesquisa deve ter entre 2 e 100 caracteres." },
    { "field": "radiusKm", "message": "O raio só pode ser usado com coordenadas." },
    { "field": "radiusKm", "message": "O raio deve estar entre 0,1 e 50 km." },
    { "field": "size", "message": "O tamanho da página deve estar entre 1 e 50." }
  ]
}
```

**Ordenação por distância sem coordenadas**

```http
GET /api/v1/places?sort=distance
```

```json
{
  "title": "Pedido inválido",
  "status": 400,
  "detail": "Um ou mais parâmetros são inválidos.",
  "instance": "/api/v1/places",
  "errors": [
    { "field": "sort", "message": "A ordenação por distância exige latitude e longitude." }
  ]
}
```

**Modalidade inexistente**

```http
GET /api/v1/places?sport=quidditch
```

```json
{
  "title": "Pedido inválido",
  "status": 400,
  "detail": "Um ou mais parâmetros são inválidos.",
  "instance": "/api/v1/places",
  "errors": [
    { "field": "sport", "message": "Modalidade desconhecida: quidditch." }
  ]
}
```

**Local inexistente**

```http
GET /api/v1/places/00000000-0000-0000-0000-000000000000
```

```json
{
  "title": "Não encontrado",
  "status": 404,
  "detail": "Não existe nenhum local com este identificador.",
  "instance": "/api/v1/places/00000000-0000-0000-0000-000000000000"
}
```

## 5. Dependência do experiences-service

**Estado atual (etapa 6):** implementado como descrito abaixo (componente `ExperiencesRatingProvider`, configurado por `EXPERIENCES_BASE_URL`; sem esse valor não faz pedidos e devolve `UNAVAILABLE`). Até à etapa 5 todas as avaliações vinham `UNAVAILABLE`.

O catálogo obtém as avaliações com **um único pedido por resposta** (nunca um pedido por local), através de um endpoint público do experiences-service, definido em [experiences-api.md §4.6](experiences-api.md#46-get-ratingsplaces--avaliações-agregadas-em-lote):

```http
GET /api/v1/ratings/places?ids=<placeId>,<placeId>,...&includeOfferings=true|false
```

- Até 50 ids por pedido (o tamanho máximo de página). A pesquisa usa `includeOfferings=false`; o detalhe do local e o de uma atividade usam `includeOfferings=true`.
- O experiences-service só devolve `AVAILABLE` ou `NO_RATINGS`; `UNAVAILABLE` é decidido aqui, quando o pedido falha.

Comportamento do catálogo:

| Situação | Resultado |
|---|---|
| Resposta dentro do prazo | `rating` com `AVAILABLE` ou `NO_RATINGS` |
| Sem resposta em ~1 s, erro de ligação, resposta 4xx/5xx ou resposta inválida (ex.: média fora de 1–5) | Todos os `rating` da resposta com `UNAVAILABLE`; o pedido não é repetido |
| Local ausente na resposta do experiences-service | `NO_RATINGS` |

- O catálogo **não guarda** avaliações; não há risco de mostrar valores antigos como atuais.
- O pior caso de atraso que esta dependência acrescenta é o timeout (~1 s).
- Melhoria possível, se necessário: *cache* em memória de poucos segundos para evitar pedidos repetidos.

## 6. Dados de demonstração

- **Modalidades** (8: Boxe, Corrida, CrossFit, Escalada, Ioga, Kickboxing, Natação, Pilates): dados de referência reais, criados pela migração `V3__reference_sports.sql` em qualquer ambiente.
- **Locais e atividades fictícios** (10 locais, 19 atividades): ficheiro `src/main/resources/demo/catalog-demo-data.sql`, carregado **só com o perfil Spring `demo`**, hoje usado só no ambiente de demonstração separado (`docker-compose.demo.yml`). Não fazem parte das migrações normais.
- **Desde 2026-10-27, o ambiente normal usa locais reais** (perfil `real-places`, [locais-reais.md §9](locais-reais.md#9-primeira-importação-2026-10-27)): 5 locais e 7 atividades. A migração `V6__retire_demo_places.sql` passou os fictícios a `RETIRED`, sem os apagar.
- O carregamento corre em cada arranque, depois das migrações, numa transação; usa ids fixos e `INSERT ... ON CONFLICT DO UPDATE`, pelo que reiniciar não cria duplicados e alterações ao ficheiro são aplicadas.
- Desativar o perfil não apaga dados já carregados; podem ser identificados por `is_demo = true`. **Só podem ser removidos enquanto não houver experiências sobre eles**: as experiências referem-se aos locais e atividades pelo id, sem chaves estrangeiras entre serviços. A estratégia para substituir os dados fictícios por locais reais (retirar em vez de apagar, ids novos, sem passar experiências automaticamente) está em [experiences-api.md §15](experiences-api.md#15-relação-com-locais-reais-e-substituição-do-catálogo-de-demonstração).
- Todos os locais têm `demo: true`, "(fictício)" no nome e uma descrição que os identifica como dados de demonstração.
- Coordenadas em zonas do Porto, para a pesquisa por proximidade ser realista; moradas genéricas ("Rua Exemplo").
- Os nomes dos locais incluem "Exemplo" e "(fictício)"; as freguesias e coordenadas são reais apenas como zona aproximada.

## 7. Fora deste contrato

- Criação, edição e remoção de modalidades, locais e atividades.
- Horários estruturados, lotação, preços, reservas, pagamentos.
- Ordenação de locais por avaliação.
- Fotografias de locais.

## 8. Registo de ajustes

Ajustes feitos na implementação da etapa 3 (backend do catálogo), por ordem de importância.

| # | Ajuste | Motivo |
|---|---|---|
| 1 | Os dados fictícios deixaram de vir de migrações e passaram a um perfil `demo` explícito (§6). As modalidades ficam numa migração normal. | Separar dados de demonstração das migrações usadas em qualquer ambiente; as modalidades são dados de referência reais, de que dependem os interesses no users-service. |
| 2 | Todas as avaliações vêm `UNAVAILABLE`, sem chamadas a outros serviços (§5). | O experiences-service ainda não existe; não se inventam avaliações nem se mostram zeros. |
| 3 | Os exemplos 1 e 2 passaram a ser respostas reais; o exemplo 3 e o detalhe mostram o formato futuro das avaliações. | Os exemplos anteriores não correspondiam aos dados de demonstração (freguesia, totais, ordem). |
| 4 | `GET /offerings/{offeringId}` fica pendente. | Fora do âmbito desta etapa; será necessário quando o experiences-service validar publicações. |
| 5 | Parâmetros vazios são ignorados; `%` e `_` em `q` são literais; ordenação por nome também ignora acentos; desempates explícitos até ao `id`. | Comportamento previsível para a app e paginação estável. |
| 6 | Erros sem o campo `type` (equivale a `about:blank`); acrescentado o código 405. | Comportamento por omissão do Spring, equivalente pelo RFC 9457. |
| 7 | O nome da modalidade é único sem distinguir maiúsculas/minúsculas **nem acentos**. | Coerente com a regra de pesquisa. |
| 8 | A modalidade mantém-se "Ioga" (id `ioga`), como no contrato. Pesquisar "yoga" não encontra resultados. | Grafia em português europeu; sinónimos na pesquisa ficam como melhoria possível. |
| 9 | Etapa 6: `GET /offerings/{id}` com `includeRating` e `status`; migração `V4__catalog_status.sql` com `status` em locais e atividades (todos `ACTIVE`); o detalhe do local pede local e atividades num só pedido (antes eram duas chamadas ao fornecedor de avaliações). | Validação de publicações sem chamada de volta; retirar em vez de apagar ([experiences-api.md §15](experiences-api.md#15-relação-com-locais-reais-e-substituição-do-catálogo-de-demonstração)). |
| 10 | Etapa 6 (integração na app): a pesquisa pública só mostra locais `ACTIVE` e só considera atividades `ACTIVE`; `GET /places/{id}` acrescenta `status` ao local e a cada atividade (efetivo) e continua a responder para locais retirados. | Não apresentar dados retirados como disponíveis, mantendo legível o conteúdo histórico. |
| 11 | Locais reais (2026-10-27): `level` aceita `UNSPECIFIED` (migração `V5__level_unspecified.sql`); importação repetível dos locais reais aprovados (perfil `real-places`); fictícios retirados (`V6__retire_demo_places.sql`), nunca apagados. | Não inventar níveis; substituir os dados fictícios sem quebrar as referências das experiências ([locais-reais.md §9](locais-reais.md#9-primeira-importação-2026-10-27)). |

**Implementação da pesquisa sem maiúsculas/acentos:** função SQL `portofit_normalize(text)` (minúsculas + substituição dos caracteres acentuados), aplicada ao texto pesquisado e aos nomes. Usa só funções nativas do PostgreSQL, sem extensões nem privilégios adicionais, e é `IMMUTABLE`, por isso também serve nos índices únicos.
