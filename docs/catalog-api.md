# catalog-service — Contrato da API

> Primeiro contrato detalhado do PortoFit. Contexto geral em [arquitetura.md](arquitetura.md).
> Estado: **proposta v1**, a validar durante a etapa 3 (implementação do catálogo).

## 1. Responsabilidade

O catalog-service é o dono de **modalidades**, **locais** e **atividades oferecidas**. Nesta fase o catálogo é **só de leitura** pela API: os dados são carregados por migrações da base de dados (dados fictícios de demonstração). Não há endpoints de criação ou edição, nem reservas, horários reserváveis ou preços.

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

### 2.3 ActivityOffering — atividade oferecida

| Campo | Tipo | Obrigatório | Regras |
|---|---|---|---|
| `id` | UUID | sim | Gerado; fixo nos dados de demonstração |
| `placeId` | UUID | sim | Local existente |
| `sportId` | string | sim | Modalidade existente |
| `name` | string | sim | 2–120 caracteres; único dentro do mesmo local |
| `description` | string | não | até 1000 caracteres |
| `level` | enum | sim | `BEGINNER`, `INTERMEDIATE`, `ADVANCED`, `ALL_LEVELS` |
| `scheduleNotes` | string | não | até 300 caracteres. **Texto informativo** (ex.: "Seg. e qua., 19h–20h"); não é um horário reservável |

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
| GET | `/offerings/{offeringId}` | Detalhe de uma atividade oferecida |
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
- Com `lat`/`lng`, só são devolvidos locais a uma distância ≤ `radiusKm` e cada resultado inclui `distanceKm`.
- A distância é em linha reta (fórmula de Haversine), em **quilómetros**, arredondada a 2 casas decimais. Não é distância a pé nem de carro.
- `sort=name`: nome do local A→Z (sem distinguir maiúsculas/minúsculas), desempate por `id`.
- `sort=distance`: mais próximo primeiro, desempate por nome. Exige coordenadas.
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

**Exemplo 1 — pesquisa por texto**

```http
GET /api/v1/places?q=boxe&page=0&size=20
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
        "parish": "Ribeira",
        "city": "Porto"
      },
      "location": { "latitude": 41.140600, "longitude": -8.613100 },
      "sports": [
        { "id": "boxe", "name": "Boxe" },
        { "id": "kickboxing", "name": "Kickboxing" }
      ],
      "rating": { "status": "AVAILABLE", "average": 4.3, "ratingsCount": 12 },
      "demo": true
    },
    {
      "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e02",
      "name": "Ginásio Exemplo Boavista (fictício)",
      "address": {
        "street": "Avenida Exemplo, 200",
        "postalCode": "4100-001",
        "parish": "Lordelo do Ouro e Massarelos",
        "city": "Porto"
      },
      "location": { "latitude": 41.157900, "longitude": -8.629100 },
      "sports": [
        { "id": "boxe", "name": "Boxe" },
        { "id": "ioga", "name": "Ioga" }
      ],
      "rating": { "status": "NO_RATINGS", "average": null, "ratingsCount": 0 },
      "demo": true
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 2,
  "totalPages": 1
}
```

**Exemplo 2 — modalidade e proximidade**

```http
GET /api/v1/places?sport=boxe&lat=41.1496&lng=-8.6109&radiusKm=3
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
        "parish": "Ribeira",
        "city": "Porto"
      },
      "location": { "latitude": 41.140600, "longitude": -8.613100 },
      "sports": [
        { "id": "boxe", "name": "Boxe" },
        { "id": "kickboxing", "name": "Kickboxing" }
      ],
      "distanceKm": 1.02,
      "rating": { "status": "AVAILABLE", "average": 4.3, "ratingsCount": 12 },
      "demo": true
    },
    {
      "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e02",
      "name": "Ginásio Exemplo Boavista (fictício)",
      "address": {
        "street": "Avenida Exemplo, 200",
        "postalCode": "4100-001",
        "parish": "Lordelo do Ouro e Massarelos",
        "city": "Porto"
      },
      "location": { "latitude": 41.157900, "longitude": -8.629100 },
      "sports": [
        { "id": "boxe", "name": "Boxe" },
        { "id": "ioga", "name": "Ioga" }
      ],
      "distanceKm": 1.79,
      "rating": { "status": "NO_RATINGS", "average": null, "ratingsCount": 0 },
      "demo": true
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 2,
  "totalPages": 1
}
```

**Exemplo 3 — experiences-service indisponível**

O mesmo pedido do exemplo 1, com o experiences-service parado. Os dados do catálogo vêm completos; só as avaliações ficam indisponíveis:

```json
{
  "items": [
    {
      "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01",
      "name": "Clube de Combate Ribeira (fictício)",
      "address": { "street": "Rua Exemplo, 10", "postalCode": "4050-001", "parish": "Ribeira", "city": "Porto" },
      "location": { "latitude": 41.140600, "longitude": -8.613100 },
      "sports": [ { "id": "boxe", "name": "Boxe" }, { "id": "kickboxing", "name": "Kickboxing" } ],
      "rating": { "status": "UNAVAILABLE", "average": null, "ratingsCount": null },
      "demo": true
    }
  ],
  "page": 0,
  "size": 20,
  "totalItems": 2,
  "totalPages": 1
}
```

(Excerto: o segundo local vem igualmente com `"status": "UNAVAILABLE"`.)

### 3.3 `GET /places/{placeId}`

Detalhe de um local, com as atividades oferecidas e a avaliação de cada uma.

**Pedido**

```http
GET /api/v1/places/3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01
```

**Resposta `200 OK`**

```json
{
  "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01",
  "name": "Clube de Combate Ribeira (fictício)",
  "description": "Local fictício criado para demonstração do PortoFit.",
  "address": {
    "street": "Rua Exemplo, 10",
    "postalCode": "4050-001",
    "parish": "Ribeira",
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
      "rating": { "status": "AVAILABLE", "average": 4.2, "ratingsCount": 9 }
    },
    {
      "id": "8a7b6c5d-4e3f-4a1b-9c8d-000000000102",
      "sport": { "id": "kickboxing", "name": "Kickboxing" },
      "name": "Kickboxing aberto",
      "description": null,
      "level": "ALL_LEVELS",
      "scheduleNotes": null,
      "rating": { "status": "AVAILABLE", "average": 4.5, "ratingsCount": 5 }
    }
  ],
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
  "sport": { "id": "boxe", "name": "Boxe" },
  "place": {
    "id": "3f1c9a2e-6b0d-4c55-9a7e-1d2b3c4d5e01",
    "name": "Clube de Combate Ribeira (fictício)",
    "demo": true
  },
  "rating": { "status": "AVAILABLE", "average": 4.2, "ratingsCount": 9 }
}
```

## 4. Erros

Os erros seguem o formato **Problem Details** (RFC 9457, `Content-Type: application/problem+json`), suportado nativamente pelo Spring Boot. Para erros de validação acrescenta-se `errors` com um elemento por parâmetro inválido. Os textos de `title`, `detail` e `message` estão em português e podem ser mostrados ao utilizador.

| Código | Quando |
|---|---|
| `400 Bad Request` | Parâmetro inválido, combinação inválida, modalidade inexistente em `sport`, UUID mal formado |
| `404 Not Found` | Local ou atividade inexistente |
| `500 Internal Server Error` | Erro inesperado (sem detalhes internos na resposta) |
| `503 Service Unavailable` | Base de dados do catálogo indisponível |

A indisponibilidade do experiences-service **não** gera erro (ver §2.4).

**`lat` sem `lng`**

```http
GET /api/v1/places?lat=41.1496
```

```json
{
  "type": "about:blank",
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
  "type": "about:blank",
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
  "type": "about:blank",
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
  "type": "about:blank",
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
  "type": "about:blank",
  "title": "Não encontrado",
  "status": 404,
  "detail": "Não existe nenhum local com este identificador.",
  "instance": "/api/v1/places/00000000-0000-0000-0000-000000000000"
}
```

## 5. Dependência do experiences-service

O catálogo obtém as avaliações com **um único pedido por resposta** (nunca um pedido por local), através de um endpoint público do experiences-service. Este endpoint pertence ao contrato do experiences-service e será detalhado nesse documento; a forma prevista é:

```http
GET /api/v1/ratings/places?ids=<placeId>,<placeId>,...&includeOfferings=true|false
```

Comportamento do catálogo:

| Situação | Resultado |
|---|---|
| Resposta dentro do prazo | `rating` com `AVAILABLE` ou `NO_RATINGS` |
| Sem resposta em ~1 s, erro de ligação ou resposta 5xx | Todos os `rating` da resposta com `UNAVAILABLE`; o pedido não é repetido |
| Local ausente na resposta do experiences-service | `NO_RATINGS` |

- O catálogo **não guarda** avaliações; não há risco de mostrar valores antigos como atuais.
- O pior caso de atraso que esta dependência acrescenta é o timeout (~1 s).
- Melhoria possível, se necessário: *cache* em memória de poucos segundos para evitar pedidos repetidos.

## 6. Dados de demonstração

- Carregados por migração ao arrancar o serviço numa base de dados vazia.
- Todos os locais têm `demo: true`, "(fictício)" no nome e uma descrição que os identifica como dados de demonstração.
- Coordenadas em zonas do Porto, para a pesquisa por proximidade ser realista; moradas genéricas ("Rua Exemplo").
- Volume previsto: cerca de 8–10 modalidades, 20–30 locais e 2–4 atividades por local.

## 7. Fora deste contrato

- Criação, edição e remoção de modalidades, locais e atividades.
- Horários estruturados, lotação, preços, reservas, pagamentos.
- Ordenação de locais por avaliação.
- Fotografias de locais.
