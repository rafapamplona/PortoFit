# Locais reais do Porto

> **Estado:** **primeira importação feita a 2026-10-27** (§9): 5 locais reais e 7 atividades no catálogo, fictícios retirados. **Ampliação no mesmo dia** (§10, §11): 24 locais e 45 atividades; corrida (2), escalada (0) e kickboxing (4) abaixo de 5. **Novas modalidades** (§12, 2026-10-28): 30 locais e 61 atividades em 15 modalidades. **Basquetebol, voleibol e futsal** (§13): 39 locais e 74 atividades.
> **Situação atual:** 15 modalidades, 39 locais importados com 74 atividades, e 9 entradas pendentes no ficheiro. As secções estão por ordem cronológica: **§1–§8 são a preparação (histórico)**, e cada secção seguinte atualiza as anteriores.
> **Datas de consulta:** a preparação e as secções §9–§11 são de 2026-10-27, e as §12–§13 de 2026-10-28. No ficheiro de dados, `sourcesConsultedOn` dá a data de cada fonte, que é a do commit que a acrescentou.
> Dados: [`backend/catalog-service/src/main/resources/real/locais-reais-porto.json`](../backend/catalog-service/src/main/resources/real/locais-reais-porto.json), separados dos dados de demonstração.
> Modelo e regras do catálogo: [catalog-api.md §2](catalog-api.md#2-entidades-e-relações); estratégia de substituição: [experiences-api.md §15](experiences-api.md#15-relação-com-locais-reais-e-substituição-do-catálogo-de-demonstração).

## 1. Resumo da preparação (histórico)

> Estado antes da primeira importação. A situação atual está no início deste documento.

- **11 locais reais**, todos no **município do Porto**. Cobrem as 8 modalidades do projeto, com 16 atividades.
- **6 prontos:** todos os campos obrigatórios confirmados.
  - Piscina Municipal da Constituição;
  - Piscina Municipal de Cartes;
  - Piscinas de Campanhã;
  - Clube Fluvial Portuense;
  - CrossFit Durius;
  - Estádio Universitário do Porto.
- **5 pendentes:** falta um campo obrigatório.
  - Piscina Armando Pimentel (coordenadas);
  - São Rock (código postal);
  - Skills Academy (código postal e coordenadas);
  - Academia Marcial (coordenadas);
  - Academia de Kickboxing do Boavista (código postal).
- **Nenhum pode ser importado ainda:** o esquema exige o nível de cada atividade, e nenhuma fonte o indica (§4.1).

## 2. Método e regras seguidas

- **Fontes:** só o próprio espaço (site oficial), o município ou entidades oficiais.
  - Municipais: Ágora – Cultura e Desporto do Porto, Visit Porto, Agenda Porto, Desporto365.
  - Oficiais: diretório de afiliados da CrossFit, Centro de Desporto da Universidade do Porto (CDUP).
  - Um local que só apareceu numa listagem ou num resultado de pesquisa não conta como verificado (§6).
- **Coordenadas:**
  - da fonte oficial quando ela tem mapa (CrossFit Durius, Estádio Universitário, São Rock no Desporto365);
  - senão, do objeto do **OpenStreetMap** com o mesmo nome e a mesma rua, com a ligação registada. Não é oficial, por isso fica assinalado para confirmar no mapa;
  - uma posição que o OSM só conhece ao nível da rua (sem o número) **não** foi aceite.
- **Freguesia:** derivada das coordenadas (limites do OSM), por isso nunca é mais do que "provável" sem confirmação. Fica vazia quando o local está junto a um limite e as fontes divergem.
- **Divergências:** prevalece sempre a fonte oficial (código postal, morada). A divergência fica registada em `notes`.
- **Fora desta versão:** sem horários, preços, níveis, lotação, disponibilidade, fotografias, avaliações nem experiências. As descrições são frases factuais curtas, escritas por nós.
- **Fora do município:** confirmado pelas coordenadas e pela morada. Locais em Gaia, Matosinhos, Maia, Gondomar (Rio Tinto) ou São Mamede Infesta foram excluídos (§6).

## 3. Catálogo

As fontes de cada campo estão no JSON (`fieldSources` e `sources`); aqui ficam as principais.

| # | Local | Morada | Código postal | Freguesia | Coordenadas (fonte) | Atividades (modalidade) | Site | Estado |
|---|---|---|---|---|---|---|---|---|
| 1 | Piscina Municipal da Constituição | Rua do Almirante Leote do Rêgo | 4200-260 | *por confirmar* | 41.165142, −8.613906 (OSM way 305711614) | Natação; Ioga; Pilates | [Ágora](https://www.agoraporto.pt/desporto/rmid/piscinas-municipais/piscina-municipal-da-constituicao) | pronto |
| 2 | Piscina Municipal de Cartes | Alameda de Cartes | 4300-008 | Campanhã | 41.159795, −8.573329 (OSM way 542445803) | Natação | [Ágora](https://www.agoraporto.pt/desporto/rmid/piscinas-municipais/piscina-municipal-de-cartes) | pronto |
| 3 | Piscina Municipal Eng.º Armando Pimentel | Rua de Diogo Botelho | 4150-262 | *por confirmar* | **em falta** | Natação; Pilates | [Ágora](https://www.agoraporto.pt/desporto/rmid/piscinas-municipais/piscina-municipal-eng-o-armando-pimentel) | pendente |
| 4 | Piscinas de Campanhã (FC Porto) | Rua Doutor Sousa Avides | 4300-487 | Campanhã | 41.150709, −8.589498 (OSM way 504052276) | Natação; Boxe (secção do FC Porto) | [FC Porto](https://www.fcporto.pt/pt/clube/instalacoes) · [Visit Porto](https://visitporto.travel/poi/5cd04b57f979e000010a3401) | pronto |
| 5 | Clube Fluvial Portuense — Complexo de Piscinas | Rua Aleixo Mota, s/n | 4150-044 | Lordelo do Ouro e Massarelos | 41.150512, −8.654918 (OSM way 132865132) | Natação | [fluvial.pt](https://fluvial.pt/clube/instalacoes/) | pronto |
| 6 | CrossFit Durius | Rua de Agramonte, 230 | 4150-030 | Lordelo do Ouro e Massarelos | 41.157699, −8.632250 ([crossfit.com](https://crossfit.com/gym/9054/crossfit-durius)) | CrossFit | [crossfitdurius.com](http://crossfitdurius.com/) | pronto |
| 7 | São Rock Indoor Climbing | Rua de Godim, 312 | **em falta** | Campanhã (*rever*) | 41.154387, −8.587209 ([Desporto365](https://desporto365.porto.pt/clubs/262?locale=pt)) | Escalada indoor | [saorockclimbing.com](https://saorockclimbing.com/) | pendente |
| 8 | Skills Academy | Rua do Zambeze, 282 | **em falta** | *por confirmar* | **em falta** | Boxe | [academia-skills.com](https://academia-skills.com/contactos) | pendente |
| 9 | Academia Marcial | Rua do Monte Alegre, 275 | 4250-302 | *por confirmar* | **em falta** | Kickboxing; Boxe (aulas de striking) | [academiamarcial.com](https://academiamarcial.com/) | pendente |
| 10 | Academia de Kickboxing Boavista F.C. | Rua O Primeiro de Janeiro (Estádio do Bessa Século XXI) | **em falta** (só "4100") | Ramalde | 41.162198, −8.642601 (OSM, centro do estádio; *aproximada*) | Kickboxing | [boavistakickboxing.pt](https://www.boavistakickboxing.pt/) | pendente |
| 11 | Estádio Universitário do Porto (CDUP) | Rua das Estrelas, s/n | 4150-762 | Lordelo do Ouro e Massarelos | 41.150636, −8.643849 ([CDUP](https://cdup.up.pt/en/instalacoes/estadio-universitario-do-porto/)) | Atletismo em pista (corrida) | [cdup.up.pt](https://cdup.up.pt/en/instalacoes/estadio-universitario-do-porto/) | pronto |

**Modalidades:** natação (5 locais), boxe (3), kickboxing (2), pilates (2), ioga (1), CrossFit (1), escalada (1), corrida (1).

**Revisão de consistência** (feita sobre o JSON):
- **Esquema:** comprimentos e formatos dentro das regras (nome, rua, código postal `^\d{4}-\d{3}$`, site `http(s)://`, até 6 casas decimais).
- **Ids:** todos únicos e diferentes dos fictícios.
- **Localização:** coordenadas e códigos postais (40xx–43xx) dentro do Porto; nenhum par de locais a menos de 150 m.
- **Dados de terceiros:** os códigos postais que o OSM associa às ruas divergem dos oficiais em três locais (Constituição, Cartes, CrossFit Durius). Prevaleceu sempre a fonte oficial.

## 4. Incompatibilidades com o esquema e solução mínima proposta

1. **`level` obrigatório nas atividades** (`BEGINNER`, `INTERMEDIATE`, `ADVANCED`, `ALL_LEVELS`).
   - Nenhuma fonte indica níveis, e escolher um, mesmo `ALL_LEVELS`, seria inventar.
   - **Proposta:** uma migração nova do catálogo acrescenta o valor `UNSPECIFIED` à restrição, e o contrato passa a dizer "não indicado".
   - A app já trata valores desconhecidos como `UNKNOWN` (`levelFrom`), por isso não precisa de mudar para continuar a funcionar; só um texto "Nível não indicado" seria um acrescento opcional.
   - Alternativa: tornar `level` opcional (`NULL`), o que muda o formato da resposta (mais impacto).
2. **`postalCode` obrigatório.** *(Revisto no §11: passou a opcional, só para quem não tem morada postal.)*
   - Não se propõe torná-lo opcional: é confirmável.
   - Os 3 locais em falta ficam de fora da importação até o código ser confirmado na pesquisa oficial dos CTT ou junto do próprio espaço.
3. **`latitude`/`longitude` obrigatórias.** Os 3 locais sem coordenadas ficam de fora até alguém as confirmar num mapa, com a fonte registada no JSON.
4. **`parish` até 80 caracteres.**
   - O nome oficial da união de freguesias do centro ("União das Freguesias de Cedofeita, Santo Ildefonso, Sé, Miragaia, São Nicolau e Vitória") tem mais de 80.
   - Nenhum local pronto está nessa união, mas se a Constituição, a Skills ou a Academia Marcial lá ficarem, propõe-se a forma sem o prefixo ("Cedofeita, Santo Ildefonso, Sé, Miragaia, São Nicolau e Vitória", 64 caracteres) ou a abreviatura "Centro Histórico", já usada nos dados de demonstração. É uma decisão do grupo.
5. **`demo`:** os locais reais entram com `is_demo = false`. Nenhuma mudança necessária.

## 5. Proposta de importação e de retirada dos fictícios

**Importação repetível** (a implementar numa tarefa própria; nada disto existe ainda):

- **Importador:** um importador no catalog-service lê `backend/catalog-service/src/main/resources/real/locais-reais-porto.json` (ou uma cópia em `src/main/resources/real/`) e grava numa transação, com `INSERT … ON CONFLICT (id) DO UPDATE`, como o carregador de demonstração. Repetir não cria duplicados.
- **Só locais prontos:** importa apenas locais com `"status": "ready"` e todos os campos obrigatórios. Um local pendente é ignorado e listado no registo; não há valores por omissão para preencher o que falta.
- **Validação antes de gravar:** as mesmas regras do esquema (formato do código postal, intervalos, modalidade existente, nome único dentro do local). Qualquer erro aborta a importação inteira, sem gravar metade.
- **Ids fixos do ficheiro:** nunca se reutiliza um id fictício, e um local real nunca muda de id depois de importado.
- **Execução separada do perfil `demo`:** por exemplo, um perfil `real-places`, ou um comando explícito de manutenção. Correr a importação nunca retira nem apaga nada.

**Retirada dos fictícios** (passo separado, depois de os reais estarem importados e verificados), como em [experiences-api.md §15](experiences-api.md#15-relação-com-locais-reais-e-substituição-do-catálogo-de-demonstração):

1. Relatório **antes**: experiências por local/atividade fictício (o experiences-service sabe-o pelos ids). Só informa.
2. `UPDATE place SET status = 'RETIRED', updated_at = now() WHERE is_demo` (as atividades ficam retiradas pelo estado efetivo; opcionalmente também `activity_offering.status`). **Nada é apagado**, e nenhuma experiência passa para um local real.
3. Desligar o perfil `demo` no `docker-compose.yml`. Não é preciso para manter a retirada: o script de demonstração (`ON CONFLICT DO UPDATE`) não escreve o `status`, por isso recarregá-lo não reativa os locais (verificado a 2026-10-27). Desligá-lo só evita voltar a escrever nomes e moradas fictícias.
4. Relatório **depois**: as mesmas contagens. Os locais retirados continuam a responder pelo id, e a app indica "Local de demonstração retirado".

## 6. Candidatos excluídos ou adiados (histórico)

> Situação na preparação. Alguns entraram depois: CrossFit OPO e Drop In (§10), pavilhões municipais (§10, §13) e Parque da Cidade como ponto de encontro de corrida (§11).

| Candidato | Motivo |
|---|---|
| Norte Forte – Fight Club (Massarelos) | Só listagens de terceiros; sem site oficial encontrado |
| BHOUT Club, Academia 77 | Só listagens e artigos; sem fonte oficial consultada |
| CrossFit OPO (Paranhos) | Só listagens; sem página oficial nem no diretório de afiliados consultado |
| Proa Climbing Center | Matosinhos (fora do município) |
| ZONE Bouldering, Murus Climbing | Vila Nova de Gaia (fora do município) |
| The North Wall | São Mamede Infesta, Matosinhos (fora do município) |
| CrossFit Drop In (Rio Tinto), OpenBox CrossFit (Maia) | Fora do município |
| Clube Fluvial Portuense — Posto Náutico | Vila Nova de Gaia; só o complexo de piscinas (Porto) entra |
| Parque da Cidade (corrida; Centro Municipal de Marcha e Corrida) | Sem página oficial legível com localização e morada; candidato para uma revisão futura |
| Pavilhões municipais da Ágora | As páginas não indicam modalidades do projeto; não incluídos |

O Desporto365 tem uma API interna, mas não foi usada: só páginas públicas.

## 7. Campos a rever antes de importar (histórico)

> Situação na preparação. O nível foi decidido no §9, e o Boavista resolvido no §11. As pendências atuais estão nos §11.5, §12.6 e §13.5.

**Decisões do grupo:**
- **Nível das atividades:** valor `UNSPECIFIED` ou `NULL` (§4.1). Bloqueia todos os locais.
- **Freguesia da união do centro:** forma do nome (§4.4).

**Obrigatórios em falta** (o local só entra depois):
- **Armando Pimentel:** coordenadas.
- **São Rock:** código postal (três valores diferentes em fontes de terceiros).
- **Skills Academy:** código postal e coordenadas.
- **Academia Marcial:** coordenadas.
- **Academia de Kickboxing do Boavista:** código postal (o site só diz "4100").

**A confirmar** (não bloqueiam, mas devem ser vistos):
- **Coordenadas do OSM** (Constituição, Cartes, Piscinas de Campanhã, Fluvial): confirmar no mapa que o ponto está no edifício certo.
- **Coordenada aproximada do Boavista:** é o centro do Estádio do Bessa.
- **Freguesias em falta ou junto a limites:** Constituição, Armando Pimentel, Skills, Academia Marcial (em falta); São Rock (Campanhã ou Bonfim).
- **Atividades:**
  - **Campanhã:** se o boxe tem acesso público ou é só a secção de competição.
  - **São Rock:** se é bloco, corda ou ambos (o site oficial recusa pedidos automáticos).
  - **Academia Marcial:** se o boxe e o kickboxing são aulas próprias.
  - **Estádio Universitário:** condições de acesso à pista para quem não é da U.Porto.
- **Moradas sem número de porta:** as três piscinas municipais e as Piscinas de Campanhã. O esquema aceita, mas convém confirmar.
- **Telefones:** copiados das fontes oficiais à data da consulta; são opcionais e podem ficar vazios se o grupo preferir.

## 8. Fontes consultadas (2026-10-27)

- **Ágora – Cultura e Desporto do Porto:**
  - instalações: <https://www.agoraporto.pt/desporto/rmid>
  - piscinas: [Constituição](https://www.agoraporto.pt/desporto/rmid/piscinas-municipais/piscina-municipal-da-constituicao), [Cartes](https://www.agoraporto.pt/desporto/rmid/piscinas-municipais/piscina-municipal-de-cartes), [Armando Pimentel](https://www.agoraporto.pt/desporto/rmid/piscinas-municipais/piscina-municipal-eng-o-armando-pimentel)
- **Visit Porto** (Câmara do Porto): [Piscinas de Campanhã](https://visitporto.travel/poi/5cd04b57f979e000010a3401); **Agenda Porto**: [Complexo de Piscinas de Campanhã](https://agenda-porto.pt/en/local/complexo-de-piscinas-de-campanha)
- **Clube Fluvial Portuense:** [instalações](https://fluvial.pt/clube/instalacoes/), [preçário 2025–2026](https://fluvial.pt/wp-content/uploads/2025/06/HORARIOS-PRECARIO-2025-2026.pdf) (consultado só para as atividades)
- **CrossFit Durius:** [diretório de afiliados da CrossFit](https://crossfit.com/gym/9054/crossfit-durius), [site do ginásio](http://crossfitdurius.com/)
- **São Rock:** [Desporto365](https://desporto365.porto.pt/clubs/262?locale=pt); o site oficial (saorockclimbing.com) respondeu 403
- **Skills Academy:** [site](https://academia-skills.com), [contactos](https://academia-skills.com/contactos)
- **Academia Marcial:** <https://academiamarcial.com/>
- **Academia de Kickboxing Boavista F.C.:** <https://www.boavistakickboxing.pt/>
- **CDUP:** [Estádio Universitário do Porto](https://cdup.up.pt/en/instalacoes/estadio-universitario-do-porto/)
- **OpenStreetMap / Nominatim:** coordenadas e freguesias (objetos indicados na tabela e no JSON); dados © colaboradores do OpenStreetMap, licença ODbL

## 9. Primeira importação (2026-10-27)

Decisões do grupo: `UNSPECIFIED`, apresentado como "Nível não indicado"; só locais com os campos obrigatórios verificados; os 5 pendentes ficam de fora; **atividades sem acesso do público confirmado ficam de fora**, mesmo num local importável; não é preciso cobrir já todas as modalidades.

### 9.1 Revisão dos 6 locais prontos

| Local | Acesso do público | Coordenadas | Freguesia | Resultado |
|---|---|---|---|---|
| Piscina Municipal da Constituição | Confirmado: o [regulamento 2026–2027](https://www.agoraporto.pt/files/uploads/cms/regulamento-2026-2027-RMID-CONSTITUICAO.pdf) (art.º 6.º) admite qualquer pessoa mediante inscrição prévia com documento de identificação e taxa; natação, ioga e pilates estão nas "Valências" | Objeto OSM com o nome da piscina (centro desportivo), na rua da morada | Vazia: o OSM põe a zona em Paranhos e na União de Freguesias do Centro | **Importado**: natação, ioga, pilates |
| Piscina Municipal de Cartes | Confirmado (mesmo artigo do [regulamento](https://www.agoraporto.pt/files/uploads/cms/regulamento-2026-2027-RMID-CARTES.pdf)) | Objeto OSM com o nome da piscina | Campanhã | **Importado**: natação |
| Piscinas de Campanhã | Natação confirmada: "A instalação está aberta a toda a população", pela Escola de Natação, "não sendo exclusiva a sócios" ([Visit Porto](https://visitporto.travel/pt-PT/poi/5cd04b57f979e000010a3401)). **Boxe não confirmado**: só "as secções de boxe e desporto adaptado" do clube | Objeto OSM (edifício "Piscinas de Campanhã", centro desportivo), na Rua do Doutor Sousa Avides | Campanhã | **Importado**: natação. **Boxe fora** |
| Clube Fluvial Portuense — Complexo de Piscinas | Confirmado: a escola aquática tem tabela para "sócio" e "não sócio" ([preçário 2025–2026](https://fluvial.pt/wp-content/uploads/2025/06/HORARIOS-PRECARIO-2025-2026.pdf)) | Objeto OSM do complexo de piscinas, código postal igual ao oficial | Lordelo do Ouro e Massarelos | **Importado**: natação |
| CrossFit Durius | Confirmado como ginásio comercial: o [diretório oficial](https://crossfit.com/gym/9054/crossfit-durius) indica aulas e contacto para aderir, incluindo "Dropping in" | Oficial (ligação de mapa do diretório), a ~6 m do endereço n.º 230 no OSM | Lordelo do Ouro e Massarelos | **Importado**: CrossFit |
| Estádio Universitário do Porto | **Não confirmado**: a pista aparece como "Gratuito" numa tabela "Comunidade UP"/"Externos", sem dizer a que coluna se aplica nem condições ([CDUP](https://cdup.up.pt/instalacoes/estadio-universitario-do-porto/)) | Oficial (ligação de mapa do CDUP) | Lordelo do Ouro e Massarelos | **Fora**: sem atividade confirmada |

- **"Acesso do público confirmado"** quer dizer só que a fonte diz que qualquer pessoa se pode inscrever ou aderir. O catálogo **não promete vagas, inscrições, horários nem preços** (não guarda nenhum deles).
- **Morada da Constituição corrigida.** A página da Ágora diz "Rua do Almirante Leote do Rêgo, 4200-260"; o regulamento oficial 2026–2027 diz "Rua Almirante Leote do Rego, n.º 236, 4250-322 Porto". Prevalece o regulamento: é mais específico, e o prefixo 4250 é coerente com a zona (o OSM dá 4250-161 para a rua).
- **Correspondência das freguesias** (forma curta, sem inventar):

  | Nome oficial | Forma usada |
  |---|---|
  | União das Freguesias de Lordelo do Ouro e Massarelos | Lordelo do Ouro e Massarelos |
  | Campanhã | Campanhã (freguesia própria) |
  | (zona da Constituição, junto ao limite entre Paranhos e a União de Freguesias de Cedofeita, Santo Ildefonso, Sé, Miragaia, São Nicolau e Vitória) | vazia (o campo é opcional) |

### 9.2 Importado

| Local | Atividades (todas com `level: UNSPECIFIED`) |
|---|---|
| Piscina Municipal da Constituição | Natação; Ioga; Pilates |
| Piscina Municipal de Cartes | Natação |
| Piscinas de Campanhã | Natação |
| Clube Fluvial Portuense — Complexo de Piscinas | Natação |
| CrossFit Durius | CrossFit |

**5 locais, 7 atividades.** Há natação em 4 locais, e ioga, pilates e CrossFit em 1 cada.

**Sem boxe, corrida, escalada nem kickboxing:** nenhuma destas atividades tem, nesta versão, um local com o acesso do público confirmado e todos os campos verificados. A pesquisa por boxe devolve **0 locais**, sem nada a substituí-la.

### 9.3 Como funciona

- **Ficheiro de dados:** `backend/catalog-service/src/main/resources/real/locais-reais-porto.json` (movido de `data/catalogo/`). Cada atividade tem `publicAccess` (`CONFIRMED`/`UNCONFIRMED`) e a fonte.
- **`RealPlacesImporter`** (`catalog/realdata/`):
  - lê o ficheiro e só considera locais `ready`, e deles só as atividades `CONFIRMED`; um local sem nenhuma fica de fora;
  - **valida tudo antes de gravar**: formato, comprimentos, intervalos, 6 casas decimais, modalidade existente, ids únicos e nome único no local, e nenhum id de um registo fictício ou de uma atividade de outro local. Um erro rejeita o ficheiro inteiro e nada é gravado;
  - grava numa **transação**, com `INSERT … ON CONFLICT (id) DO UPDATE` limitado a `is_demo = false`, por isso repetir não duplica;
  - **não apaga nada** e **não altera o `status`** de registos existentes: uma retirada manual mantém-se depois de nova importação;
  - corre em cada arranque com o perfil `real-places`, o do ambiente normal no `docker-compose.yml`. Uma rejeição fica no registo e o catálogo continua a responder com o que já tinha.
- **`V5__level_unspecified.sql`:** acrescenta `UNSPECIFIED` aos níveis aceites, sem alterar as migrações anteriores.
- **`V6__retire_demo_places.sql`:** passa a `RETIRED` os locais e as atividades com `is_demo`.
  - **Não apaga nada**, e nenhuma experiência passa para um local real.
  - Corre uma vez em cada base.
  - Numa base nova não muda nada, porque os fictícios só são carregados depois das migrações.
  - O script de demonstração não escreve o `status`, por isso recarregá-lo não reativa os retirados.
- **Feed** (experiences-service): `portofit.feed.include-demo-places` passa a `false` por omissão (`FEED_INCLUDE_DEMO_PLACES`). As publicações de locais fictícios saem dos dois feeds, mas continuam acessíveis pelo id, no perfil do autor e no dashboard.
- **App:** `UNSPECIFIED` já era mostrado como "Nível não indicado" (valor desconhecido → `UNKNOWN`), e passa a haver um teste que o fixa. As experiências de locais fictícios continuam com "Local fictício (dados de demonstração)", e os locais retirados com o aviso de local retirado.

### 9.4 Ambiente de demonstração separado

Com os fictícios ativos, **num volume próprio**, sem tocar no ambiente normal (usa as mesmas portas, por isso parar primeiro o normal com `docker compose stop`):

```powershell
docker compose -p portofit-demo -f docker-compose.yml -f docker-compose.demo.yml up -d --wait
```

- O `docker-compose.demo.yml` ativa o perfil `demo` no catálogo e `FEED_INCLUDE_DEMO_PLACES=true` no experiences-service.
- O nome de projeto `portofit-demo` dá volumes próprios (`portofit-demo_postgres-data`…). Precisa dos mesmos `.env` e `secrets/`, e das bases criadas pelos scripts de inicialização, que correm sozinhos num volume novo.

### 9.5 Cópia de segurança e reposição

- **Cópia:** `catalog_db` antes da importação, em `C:\Users\rafap\AndroidStudioProjects\PortoFit-backups\catalog_db-2026-10-27-antes-locais-reais.dump`.
  - Formato *custom* do `pg_dump`, **fora do repositório**.
  - SHA-256 `db19123860f1b43346afbd274ca07de13f580d41741b82491acc8a094072d188`, igual dentro e fora do contentor.
  - O `pg_restore --list` lista as 4 tabelas com dados.
- **Gerar uma cópia nova** (dentro do contentor e copiada com `docker cp`, para ficar byte a byte igual; um redirecionamento no PowerShell ou no Git Bash pode estragar o ficheiro binário):
  ```powershell
  docker compose exec -T postgres sh -c 'pg_dump -U $POSTGRES_USER -Fc -f /tmp/catalog_db.dump catalog_db'
  docker cp "$(docker compose ps -q postgres):/tmp/catalog_db.dump" ..\PortoFit-backups\catalog_db-AAAA-MM-DD.dump
  ```
- **Repor** (substitui o conteúdo atual do catálogo; parar primeiro o catálogo):
  ```powershell
  docker compose stop catalog-service
  docker cp ..\PortoFit-backups\catalog_db-2026-10-27-antes-locais-reais.dump "$(docker compose ps -q postgres):/tmp/catalog_db.dump"
  docker compose exec -T postgres sh -c 'pg_restore -U $POSTGRES_USER -d catalog_db --clean --if-exists --no-owner --role=$CATALOG_DB_USER /tmp/catalog_db.dump'
  ```
  **Reposição testada** a 2026-10-27 numa base temporária (`restore_test_tmp`, apagada a seguir): 10 locais ativos, 19 atividades, migrações até à V4.

  Depois de repor a cópia de antes, voltar a pôr `SPRING_PROFILES_ACTIVE: demo` e retirar as migrações V5/V6 do código antes de arrancar. Se não, o Flyway volta a aplicá-las e a importação volta a correr. As experiências, as contas e o resto não são afetados: estão noutras bases.

### 9.6 Verificação (2026-10-27)

- **Testes do catálogo:** 35, todos a passar. Os 31 que já existiam usam os dados fictícios numa base própria; os 4 novos (`RealPlacesImportIntegrationTest`) cobrem:
  - só aprovados e nível `UNSPECIFIED`;
  - morada do regulamento;
  - repetir sem duplicar e sem reativar um local retirado à mão;
  - um valor inválido rejeita tudo; um id fictício é recusado;
  - a migração V6 retira sem apagar, e recarregar a demo não reativa;
  - pesquisa só com reais; natação 4, boxe 0; `NO_RATINGS`; proximidade; fictício pelo id como `RETIRED`.
- **Outros testes:** experiences-service (feed 5, experiências 17, estatísticas 2); app (`CatalogMappersTest` 6, `PlaceDetailViewModelTest` 10).
- **No ambiente normal, por HTTP:**
  - **Migrações e importação:** V5 e V6 aplicadas; importação de 5 locais e 7 atividades, com 6 de fora. Depois de reiniciar (nova importação): as mesmas contagens.
  - **Fictícios:** 10 locais e 19 atividades, todos `RETIRED`, nenhum apagado.
  - **Pesquisa:**
    - mostra só os 5 reais, sem fictícios, todos com avaliação `NO_RATINGS`;
    - natação 4, ioga 1, pilates 1, CrossFit 1; boxe, corrida, escalada e kickboxing 0;
    - proximidade a partir dos Aliados (41.1496, −8.6109; 5 km): Constituição 1,75 km, Campanhã 1,8 km, CrossFit Durius 2,0 km, Cartes 3,34 km e Fluvial 3,69 km.
  - **Detalhe:**
    - real: morada do regulamento, atividades `UNSPECIFIED` e `ACTIVE`, sem avaliações;
    - fictício pelo id: `200`, `demo: true`, `RETIRED`.
  - **Experiências antigas** (as 3 ativas, todas de locais fictícios): `200` pelo id, com `place.demo: true`; aparecem no perfil do autor; o feed popular não as inclui.
  - **Dados preservados:** contas, experiências (4, 3 ativas), comentário, lista e item, seguidores e os volumes. *(Corrigido no §11.1: dizia também "mensagens" e "as 9 fotografias do volume". Nessa altura havia 0 mensagens, 0 fotografias na base e 0 ficheiros no volume; o "9" eram as 9 entradas da pasta.)*
- **Emulador:** não aberto (0,75 GB livres). A apresentação na app ("Nível não indicado", "Sem avaliações", local fictício retirado) fica coberta pelos testes JVM e pelos textos que já existiam, mas falta a confirmação visual.

### 9.7 Pendências (histórico)

> Situação a seguir à primeira importação. Parte foi resolvida nos §10–§13.

- **Os 5 locais pendentes do §7**, com código postal ou coordenadas em falta.
- **Acesso do público por confirmar:**
  - **Estádio Universitário:** a pista.
  - **Piscinas de Campanhã:** o boxe da secção do clube.
- **Freguesia da Constituição.**
- **Coordenadas a confirmar num mapa:** as quatro piscinas importadas vieram do OSM.
- **Verificação visual na app**, quando houver memória para o emulador.

## 10. Ampliação: 5 locais por modalidade (2026-10-27)

Objetivo: pelo menos 5 locais reais ativos em cada uma das 8 modalidades, contando os já importados, só no município do Porto e com as mesmas regras (§2, §9). Fontes consultadas e dados revistos a 2026-10-27. Sem modalidades novas, horários, preços, níveis, fotografias nem avaliações.

### 10.1 Resultado por modalidade

| Modalidade | Locais ativos | Meta | Locais |
|---|---|---|---|
| Natação | 5 | cumprida | Constituição, Cartes, Campanhã, Fluvial, **Piscina da FADEUP** |
| Ioga | 12 | cumprida | Constituição, **Pavilhões Fontes Pereira de Melo e Lagarteiro**, **8 Fitness UP**, **Fox Gym** |
| Pilates | 11 | cumprida | Constituição, **Pavilhão Fontes Pereira de Melo**, **8 Fitness UP**, **Fox Gym** |
| CrossFit | 5 | cumprida | Durius, **OPO**, **Drop In**, **Foz**, **Nortada** |
| Boxe | 6 | cumprida | **Fitness UP Bessa, Constituição, Alegria, Praça Galiza e São João**, **Fox Gym** |
| Corrida | **1** | **não cumprida** | **Pista de Atletismo do Parque Desportivo de Ramalde/INATEL** |
| Escalada | **0** | **não cumprida** | — |
| Kickboxing | **3** | **não cumprida** | **Fitness UP Foz, São João e Ramalde Z.Ind.** |

Os 8 Fitness UP com ioga e pilates são Antas, Foz, Bessa, Constituição, Alegria, Praça Galiza, São João e Ramalde Z.Ind.

**22 locais e 43 atividades** (eram 5 e 7). Ficam de fora 8 locais: os 5 pendentes do §7, os pavilhões do Viso e Pêro Vaz de Caminha e o Estádio Universitário. Todas as atividades têm `level: UNSPECIFIED`.

### 10.2 Locais novos e fontes

| Local | Morada | Atividades | Acesso do público | Coordenadas |
|---|---|---|---|---|
| CrossFit OPO | Rua Silva Porto, 630, 4250-471 (Paranhos) | CrossFit | [Diretório de afiliados](https://www.crossfit.com/gym/7027/crossfit-opo): aulas, "Dropping in" | Do diretório; a ~100 m da rua, porque o OSM não tem o n.º 630 |
| CrossFit Drop In | Rua da Ranha, 320, 4350-273 (Campanhã) | CrossFit | [Diretório](https://www.crossfit.com/gym/16130/crossfit-drop-in) | Do diretório; a inversa dá a Rua da Ranha, no concelho do Porto |
| CrossFit Foz | Rua Aleixo da Mota (Edifício do Clube Fluvial Portuense), s/n, 4150-044 | CrossFit | [Diretório](https://www.crossfit.com/gym/14773/crossfit-foz) | Do diretório |
| Nortada CrossFit | Rua António Silva Marinho, 53, 4100-064 (Ramalde) | CrossFit | [Diretório](https://www.crossfit.com/gym/21500/nortada-crossfit) | Do diretório; mesma rua na inversa |
| Piscina da Faculdade de Desporto da U.Porto | Rua Dr. Plácido da Costa, 91, 4200-450 (Paranhos) | Natação | [CDUP](https://cdup.up.pt/portfolio/natacao/): categoria "Comunidade U.Porto e Externos (público)" | **Aproximadas:** centro do campus no [OSM](https://www.openstreetmap.org/way/36979229) |
| Pavilhão Municipal Fontes Pereira de Melo | Rua do Professor Damião Peres, 44, 4100-198 (Ramalde) | Ioga, Pilates | Programa municipal "Dias com Energia" ([Ágora](https://www.agoraporto.pt/desporto/programas-de-atividade-fisica)): gratuito, "abertas a pessoas de qualquer idade", com inscrição no Portal de Desporto | **Aproximadas:** a [escola](https://www.openstreetmap.org/relation/5610792) onde fica o pavilhão |
| Pavilhão Municipal do Lagarteiro | Avenida de Francisco Esteves, 4300-005 (Campanhã) | Ioga | "Dias com Energia" | [Objeto OSM](https://www.openstreetmap.org/way/381422373) com o nome do pavilhão |
| Pista de Atletismo do Parque Desportivo de Ramalde/INATEL | Rua do Dr. Aarão Lacerda, 4100-015 (Ramalde) | Corrida (pista, utilização individual) | [Preçário oficial 2026–2027](https://www.agoraporto.pt/files/uploads/cms/Parque-Desp-Ramalde-Horario-Precos-2026-2027_v2.pdf) (16.10.2026): pista com "Utilização Individual", de segunda a domingo | Da [ligação de mapa da Ágora](https://www.agoraporto.pt/desporto/rmid/grandes-campos-municipais/parque-desportivo-de-ramalde); a ~65 m do centro da pista no OSM |
| Fitness UP Porto - Antas | Rua Professor Bento de Jesus Caraça, 314, 4200-132 (Paranhos) | Pilates, Yoga | [Adesão online](https://www.fitnessup.pt/adesao-online/plano&source=menu) e treino experimental; modalidades no [mapa de aulas](https://www.fitnessup.pt/mapa-de-aulas/antas) | [Objeto OSM](https://www.openstreetmap.org/node/12474468236) com o mesmo nome, número e código postal |
| Fitness UP Porto - Foz | Rua Marta Mesquita da Câmara, 114, 4150-485 | Pilates, Yoga, Kickboxing | Idem ([aulas](https://www.fitnessup.pt/mapa-de-aulas/porto-foz)) | Marcador do mapa oficial, que coincide com o objeto do OSM |
| Fitness UP Porto - Bessa | Rua O Primeiro de Janeiro, 314, 4100-365 (Ramalde) | Boxe, Pilates, Yoga | Idem ([aulas](https://www.fitnessup.pt/mapa-de-aulas/porto-bessa)) | Marcador do mapa oficial |
| Fitness UP Porto - Constituição | Rua do Seixal, 130, 4000-521 (Bonfim) | Boxe, Pilates, Yoga | Idem ([aulas](https://www.fitnessup.pt/mapa-de-aulas/porto-constituicao)) | Marcador oficial; o OSM tem um centro desportivo no mesmo n.º |
| Fitness UP Porto - Alegria | Rua da Alegria, 819, 4000-038 (Bonfim) | Boxe, Pilates, Yoga | Idem ([aulas](https://www.fitnessup.pt/mapa-de-aulas/porto-alegria)) | Marcador oficial; a inversa dá o n.º 847 |
| Fitness UP Porto - Praça Galiza | Praça da Galiza, 96, 4150-344 | Boxe, Pilates, Yoga | Idem ([aulas](https://www.fitnessup.pt/mapa-de-aulas/praca-galiza)) | Marcador oficial; mesmo código postal na inversa |
| Fitness UP Porto - São João | Estrada da Circunvalação, 7316, 4200-287 (Paranhos) | Boxe, Kickboxing, Pilates, Yoga | Idem ([aulas](https://www.fitnessup.pt/mapa-de-aulas/sao-joao)) | Marcador oficial; a inversa dá a Rua da Arroteia, junto à Circunvalação |
| Fitness UP Porto - Ramalde Z.Ind. | Rua Manuel Pinto de Azevedo, 2, 4100-320 (Ramalde) | Kickboxing, Pilates, Yoga | Idem ([aulas](https://www.fitnessup.pt/mapa-de-aulas/porto-zona-industrial)) | Marcador oficial; mesma rua e código postal |
| Fox Gym | Travessa de Santo Isidro, 122, 4000-099 (Bonfim) | Boxe, Pilates, Yoga | [Adesão pelo site](https://foxgym.pt/); [mapa de aulas](https://foxgym.pt/mapa-de-aulas/), sem data | [Objeto OSM](https://www.openstreetmap.org/node/11059019047) com o mesmo nome, número e código postal |

**Notas:**
- **Fitness UP:** as moradas, os telefones e o marcador do mapa vêm da página de cada ginásio; as modalidades vêm do mapa de aulas lido a 2026-10-27. O clube avisa que pode mudar atividades e horários, por isso convém rever estas atividades se a importação for refeita mais tarde. O boxe e o kickboxing são aulas de grupo do ginásio, não secções de competição.
- **Dias com Energia:** as aulas são aos sábados, nos pavilhões, de outubro a abril. De maio a setembro passam para parques e jardins, que não foram importados por não terem morada de instalação.
- **Mesmo edifício, estabelecimentos distintos:** a CrossFit Foz fica no edifício do Clube Fluvial Portuense, a 113 m do ponto da piscina. O Fitness UP Constituição não é a Piscina Municipal da Constituição. Nenhum foi fundido.
- **Freguesias:** vêm da geocodificação inversa, na forma curta do §9.1. Também "Aldoar, Foz do Douro e Nevogilde" e "Bonfim".
- **Academia Marcial:** o acesso do público fica confirmado, porque o site oferece "1 aula grátis para poderes experimentar a modalidade antes do ato de inscrição". O boxe e o kickboxing são dados na mesma aula de "Striking (MMA, Kickboxing e Boxe)". Continua **pendente pelas coordenadas**.
- **Mudanças face ao §6:** o CrossFit OPO e o CrossFit Drop In entram agora com o diretório oficial. A morada do Drop In fica no Porto (Campanhã), não em Rio Tinto. Os pavilhões municipais entram pelo programa "Dias com Energia".

### 10.3 Metas não cumpridas (histórico)

> Situação no fim desta ampliação. Revista no §11: o Boavista entrou, a corrida passou a 2 e o kickboxing a 4.

- **Corrida (1):**
  - O **Centro Municipal de Marcha e Corrida**, no Parque da Cidade, tem treinos orientados (terças e quintas às 19 h, sábados às 10 h), mas a [página da Ágora](https://www.agoraporto.pt/desporto/programas-de-atividade-fisica) não diz quem pode participar, como se faz a inscrição nem o custo. Só remete para a Federação Portuguesa de Atletismo, cuja última informação legível sobre a inscrição no programa nacional é de 2024. Também não tem morada de instalação: o ponto de encontro é "junto ao viaduto do Edifício Transparente".
  - Os treinos da **Porto Runners** são pontos de encontro no Parque da Cidade, sem morada nem data da informação.
  - O **atletismo do CDUP** (pista da FADEUP) não diz se aceita externos.
  - A pista do **Estádio Universitário** continua não confirmada (§9.1).
  - A Ágora não indica outra pista com utilização pelo público.
  - Nenhum parque foi apresentado como local de corrida.
- **Escalada (0):** o único ginásio de escalada encontrado no concelho é o **São Rock**, que continua pendente. O site oficial recusa pedidos automáticos (403), as fontes de terceiros dão três códigos postais diferentes e o acesso do público não está confirmado. Os outros ginásios de escalada da zona ficam em Matosinhos, Gaia ou Maia (§6).
- **Kickboxing (3):**
  - O **Boavista** continua sem código postal completo (o site só diz "4100").
  - A **Academia Marcial** tem acesso confirmado, mas não tem coordenadas: o OSM só conhece a rua.
  - O **Fitness UP Monte dos Burgos** tem kickboxing, mas fica em São Mamede de Infesta, Matosinhos (4465-021).
  - O **Invicta Martial Arts** não refere kickboxing.

### 10.4 Também excluídos ou pendentes nesta revisão

| Candidato | Motivo |
|---|---|
| Pavilhão Municipal do Viso (ioga) | Pendente: o único objeto próximo no OSM, "Gimnodesportivo do Viso", fica noutra rua e tem outro código postal; não se confirma que seja o mesmo |
| Pavilhão Municipal Pêro Vaz de Caminha (ioga, pilates) | Pendente: nem o pavilhão nem a rua estão no OSM, e a página oficial não tem mapa |
| Fitness UP Monte dos Burgos | São Mamede de Infesta, Matosinhos |
| Fitness UP Bolhão (ioga, pilates) | Não preciso para as metas; não verificado |
| Programa "No Porto a Vida é Longa" | Só para maiores de 60 anos residentes no Porto; não é acesso do público em geral |
| BHOUT Club Porto-Bessa | O site não indica morada nem modalidades |
| Climb UP | O site não indica a localização; não se confirma que seja no Porto |

### 10.5 Importação e verificação

- **Cópia de segurança** antes de importar: `C:\Users\rafap\AndroidStudioProjects\PortoFit-backups\catalog_db-2026-10-27-antes-ampliacao.dump`, fora do repositório, feita pelo método do §9.5.
  - SHA-256 `68cec4b11e77f8e8c8f378523a6e8a356a51d9c209cee11fce73f70ada9b5e1e`, igual dentro e fora do contentor.
  - O `pg_restore --list` lista as 4 tabelas com dados.
- **Importação:** a imagem do catálogo foi reconstruída com o ficheiro novo, e o arranque importou **22 locais e 43 atividades, com 8 de fora**.
  - **Ids novos fixos**, guardados no ficheiro.
  - Os 5 locais anteriores mantêm os ids e os dados.
- **Verificação por HTTP:**
  - **Repetição:** depois de reiniciar (nova importação), as mesmas contagens. Ficam 32 locais na base (22 reais ativos e 10 fictícios, todos `RETIRED`) e 62 atividades, sem nomes de local repetidos.
  - **Contagens por modalidade** (`/api/v1/places?sport=…`): as do §10.1.
  - **Detalhe:** os 22 locais, com morada, código postal, freguesia e atividades `UNSPECIFIED`, e sem fictícios na pesquisa.
  - **Coordenadas:** todas dentro do concelho, confirmadas pela geocodificação inversa. Há só 3 pares a menos de 200 m, todos estabelecimentos distintos com moradas próprias: Fluvial e CrossFit Foz (113 m), Fitness UP Alegria e Fox Gym (113 m), Fitness UP Bessa e Pavilhão Fontes Pereira de Melo (148 m).
  - **Proximidade:** boxe a 3 km dos Aliados devolve 4 locais, com distância.
- **Experiências antigas:** continuam a responder `200` pelo id, com o local fictício. O feed popular não mostra locais fictícios.
- **Outras bases:** a importação só escreve no `catalog_db`. Contagens atuais: 2 contas, 1 seguidor, 4 experiências, 1 comentário, 1 lista com 1 item, 0 mensagens e 0 registos de media. O volume das fotografias tem 9 pastas e **0 ficheiros**. A frase "as 9 fotografias do volume" do §9.6 não corresponde a isto; o mais provável é que contasse as 9 pastas, mas não ficou confirmado.
- **Testes:** `RealPlacesImportIntegrationTest`, com as contagens novas (22/43/8 e os totais por modalidade): 4 testes, todos a passar. A app e os outros serviços não mudaram, por isso não voltaram a correr. Emulador não aberto.

### 10.6 Pendências (histórico)

> Situação no fim desta ampliação. O Pavilhão Pêro Vaz de Caminha foi resolvido no §13.

- **Corrida, escalada e kickboxing abaixo de 5** (§10.3).
- **Pavilhões do Viso e Pêro Vaz de Caminha:** coordenadas.
- **Academia Marcial:** coordenadas.
- **Coordenadas aproximadas** (FADEUP e Fontes Pereira de Melo) e as do OSM: confirmar num mapa.
- **Aulas dos ginásios:** os mapas de aulas mudam. Rever as atividades dos Fitness UP e do Fox Gym antes de uma nova importação.

## 11. Revisão das metas em falta (2026-10-27)

Revisão de corrida, escalada e kickboxing com critérios menos restritivos.
- Preço e inscrição online não são obrigatórios.
- Valem pontos de encontro de treino com evidência atual de funcionamento e de participação pública.
- As coordenadas podem ser aproximadas, com a precisão explícita, e o OSM não é a única fonte aceite.
- Nunca se inventam códigos postais.

Sempre só no município do Porto, sem duplicar estabelecimentos.

### 11.1 Discrepância das fotografias e das mensagens (só leitura)

- **Ambiente:** há um único projeto Compose (`portofit`, `docker-compose.yml`), com 7 serviços. Há dois volumes: `portofit_postgres-data`, montado no PostgreSQL, e `portofit_experiences-media`, montado em `/data/media` no experiences-service. Não existe hoje nenhum volume nem projeto `portofit-demo`, por isso os dados não estão noutro ambiente desta máquina.
- **Estado atual:**

  | O quê | Volume (ficheiros) | Base (referências) |
  |---|---|---|
  | Fotografias | 0 ficheiros, em 9 entradas de primeiro nível (8 pastas de prefixo e `tmp`, criadas a 2026-10-19) | 0 linhas em `media` |
  | Mensagens | — | 0 em `message`, 0 em `conversation` |

- **Registos históricos** (do registo das sessões de trabalho, e não da documentação):
  - **2026-10-19 14:24 e 15:16:** depois da verificação das fotografias, a limpeza das contas temporárias deixou "ficheiros no volume: 0". A limpeza automática do serviço também registou `rowsRemoved=2`.
  - **2026-10-20 a 2026-10-22:** as verificações das mensagens usaram contas temporárias (`verifmsg…`). A limpeza, limitada a essas contas, registou sempre "restantes (conversas/mensagens): 0/0".
  - **2026-10-27, antes da primeira importação:** a linha de base registou `media` 0, `message` 0, 1 comentário, 1 seguidor e `ls /data/media | wc -l` = **9**.
- **Conclusão:**
  - **Não houve eliminação:** não há fotografias nem mensagens permanentes desde 2026-10-19 e 2026-10-20. As que existiram eram dados temporários de verificação, removidos de propósito nessas datas.
  - **O "9" do §9.6 contava as entradas da pasta**, não fotografias, e a frase listava "mensagens" sem as haver. Foi um erro de redação da documentação, corrigido no §9.6.
  - **A importação não está envolvida:** só escreve no `catalog_db`.
  - Nada foi reposto nem alterado nesta investigação.

### 11.2 Alteração mínima: código postal opcional

- **Porquê:** o Centro Municipal de Marcha e Corrida é válido, mas é um ponto de encontro e não tem morada postal. O código postal do Edifício Transparente (4100-429) é do edifício, não do ponto de encontro, por isso não foi usado.
- **Alteração:**
  - `V7__place_postal_code_optional.sql` só tira o `NOT NULL`. A restrição de formato continua a valer quando há valor.
  - No catálogo, `Address.postalCode` e o importador aceitam `null` (um valor mal formatado continua a rejeitar o ficheiro).
  - Na app, o DTO aceita o campo nulo ou ausente, e o detalhe mostra só a cidade.
  - O contrato está em [catalog-api.md](catalog-api.md) (§2.2 e ajuste 12).
- **Os locais que já tinham código postal não mudam.** Só o ponto de encontro fica sem ele.

### 11.3 Resultado

| Modalidade | Antes | Agora | Novos |
|---|---|---|---|
| Corrida | 1 | **2** | **Centro Municipal de Marcha e Corrida — Parque da Cidade** |
| Escalada | 0 | **0** | — |
| Kickboxing | 3 | **4** | **Academia de Kickboxing Boavista F.C.** |

As outras modalidades não mudam: natação 5, ioga 12, pilates 11, CrossFit 5, boxe 6. No total ficam **24 locais e 45 atividades**, com 8 de fora.

- **Centro Municipal de Marcha e Corrida:**
  - Entra como **"ponto de encontro de treino"**, não como instalação: o nome, a descrição e a rua dizem-no.
  - **Evidência atual:** a [Ágora](https://www.agoraporto.pt/desporto/programas-de-atividade-fisica) diz que a cidade "proporciona, neste momento, um centro municipal de marcha e corrida" e que "é possível realizar caminhadas ou corrida em grupo no Parque da Cidade", às terças e quintas às 19 h e aos sábados às 10 h. A [Porto Runners](https://www.portorunners.net/divulgacao/treinos) descreve o mesmo ponto de encontro e treina lá com o centro.
  - **Coordenadas aproximadas (~100 m):** as do Edifício Transparente. Batem com a morada oficial do edifício ([Visit Porto](https://visitporto.travel/poi/5cd04b56f979e000012c5df8): Via do Castelo do Queijo, 395) e com o [edifício no OSM](https://www.openstreetmap.org/way/108391028).
  - **Inscrição e custo:** a página oficial não os indica (remete para a Federação Portuguesa de Atletismo), e o catálogo não os guarda.
- **Academia de Kickboxing Boavista F.C.:**
  - **Acesso do público:** o site oferece uma "aula experimental grátis" e tem aulas coletivas de manutenção e Kick Kids ([aulas](https://www.boavistakickboxing.pt/aulas/)). A aula de competição é só para atletas do clube e não foi incluída.
  - **Código postal e coordenadas:** os do estádio onde a academia funciona, tirados dos [contactos do Boavista FC](https://www.boavistafc.pt/contactos) (4100-365; GPS 41°09'45.0"N 8°38'35.0"W). Ficam a ~45 m do centro do estádio no OSM e estão assinalados como aproximados.
  - **Não é o Fitness UP Bessa** (a 125 m, na mesma rua): são estabelecimentos diferentes.

### 11.4 Porque é que não se chega a 5

- **Corrida (2):**
  - **Treinos de fim de semana da Porto Runners:** entraram no ficheiro como **pendentes**. São "abertos a todos aqueles que queiram participar", mas a "entrada sul (Av. da Boavista)" corresponde a duas entradas do parque no OSM, a ~640 m uma da outra. A página de treinos também não tem data; o clube tem atividade publicada em 2026-07-18.
  - **Os treinos da Porto Runners à semana são os do Centro Municipal** e não se duplicam.
  - **Atletismo do CDUP e pista do Estádio Universitário:** continuam sem confirmar se aceitam externos.
  - **Corridas e eventos pontuais** (Corrida do Dragão, Corrida IPO, Can Run Club, ESN Run Club): não são treinos regulares, ou já passaram, ou são só para estudantes.
- **Escalada (0):**
  - **São Rock:** continua pendente (§11.5).
  - **Escalada do CDUP:** é uma equipa de competição universitária, com "treinos para o ano letivo 2026/2027 ainda por agendar".
  - **Porto Bridge Climb:** é uma subida guiada por escadas ao arco da Ponte da Arrábida, com arnês, e não escalada. Não entra.
  - Os outros ginásios de escalada ficam fora do município (§6).
- **Kickboxing (4):**
  - **Academia Marcial:** continua pendente pelas coordenadas. O OSM só tem o n.º 261 da rua, com outro código postal, e não se sabe o sentido da numeração; uma coordenada a partir daí seria um palpite.
  - **NFK Training Center (Rua de Aleixo da Mota, 306):** só aparece no Urban Sports Club, sem site oficial encontrado.
  - **Solinca Dragão:** só tem Body Combat.
  - **Fitness UP Monte dos Burgos:** fica em Matosinhos.

### 11.5 A confirmar manualmente no navegador

| Local | Campo | Onde confirmar | Valor a pôr no JSON |
|---|---|---|---|
| São Rock Indoor Climbing | Acesso do público e tipo de escalada | <https://saorockclimbing.com/>: entrada avulsa, *day pass* ou aulas para o público; bloco, corda ou ambos | `offerings[0].publicAccess: "CONFIRMED"`, `publicAccessSource` com a página e a frase; nome da atividade, se mudar |
| São Rock Indoor Climbing | Código postal | Rodapé ou contactos do site, ou a pesquisa dos [CTT](https://www.ctt.pt/feapl_2/app/open/postalCodeSearch/postalCodeSearch.jspx) para "Rua de Godim" (n.º 312) | `address.postalCode`, ou `null` se nenhuma fonte oficial o indicar |
| São Rock Indoor Climbing | Estado | Depois das linhas acima | `status: "ready"` (as coordenadas e a morada já vêm do [Desporto365](https://desporto365.porto.pt/clubs/262?locale=pt)) |
| Academia Marcial | Coordenadas | Num mapa, o n.º 275 da Rua do Monte Alegre, 4250-302 | `location`, a fonte em `fieldSources.location` e `status: "ready"` |
| Porto Runners (fim de semana) | Entrada exata | Com o clube (Facebook ou contactos em <https://www.portorunners.net/>): a "Entrada das Colunas" (41.165687, −8.672167) ou a entrada sem nome (41.166512, −8.67958) | `location` e `status: "ready"` |
| NFK Training Center | Fonte oficial | Site ou página própria do ginásio (Rua de Aleixo da Mota, 306): kickboxing e acesso do público | Local novo, se houver fonte oficial |

Depois de editar o ficheiro, basta reiniciar o catálogo com a imagem reconstruída (`docker compose up -d --build --wait catalog-service`). A importação é repetível: atualiza pelos ids e não duplica.

### 11.6 Cópia, importação e verificação

- **Cópia antes desta revisão:** `C:\Users\rafap\AndroidStudioProjects\PortoFit-backups\catalog_db-2026-10-27-antes-revisao-metas.dump`, com SHA-256 `5ac45772f8371f5e216949dc206015bbcc866c6f558496e3a174f1a02b647b1a` igual dentro e fora do contentor, e 4 tabelas com dados.
- **Arranque:** V7 aplicada e importação de **24 locais e 45 atividades, com 8 de fora**. Depois de reiniciar (nova importação), as mesmas contagens: 34 locais na base, 64 atividades, 0 fictícios ativos e 0 nomes repetidos.
- **API:**
  - contagens por modalidade iguais às do §11.3;
  - o detalhe do ponto de encontro devolve `postalCode: null`;
  - a corrida a 1 km do Castelo do Queijo devolve o ponto de encontro a 0,28 km;
  - as coordenadas estão todas no concelho.
- **Testes:** catálogo, 35 de 35, incluindo a migração V7 e o ponto de encontro sem código postal. Na app, `CatalogMappersTest` 7, com um teste novo sem código postal, e `PlaceDetailViewModelTest` 10. Emulador não aberto, por isso a apresentação "só a cidade" no detalhe não foi vista no ecrã.

## 12. Novas modalidades (2026-10-28)

O objetivo era ter até 5 locais reais ativos para cada nova modalidade, só no município do Porto, com as mesmas regras (§2, §9–§11). Fontes consultadas e dados revistos a 2026-10-28. Cada atividade diz o tipo de acesso: aluguer de campo, aula, academia ou treino livre de sócios. Equipas de competição não contam.

### 12.1 Modalidades e compatibilidade

- **`V8__more_sports.sql`:** acrescenta 7 modalidades, com slugs estáveis e `ON CONFLICT (id) DO NOTHING`.

  | Slug | Nome |
  |---|---|
  | `futebol` | Futebol |
  | `futsal` | Futsal |
  | `basquetebol` | Basquetebol |
  | `voleibol` | Voleibol |
  | `tenis` | Ténis |
  | `padel` | Padel |
  | `musculacao` | Musculação |

  Os 8 slugs anteriores não mudam, por isso os interesses e as publicações existentes continuam válidos.
- **Sem listas fixas:**
  - o users-service valida os interesses contra `GET /sports`;
  - a app lê as modalidades da API (filtros, interesses e nomes);
  - o experiences-service guarda a modalidade da atividade.

  Por isso **não houve alterações na app nem noutros serviços**.
- **Pesquisa:** o texto é normalizado (sem acentos), por isso "tenis" e "ténis" dão o mesmo resultado. "Futebol" e "futsal" são categorias distintas: "futsal" encontra só o campo de futsal.

### 12.2 Resultado

| Modalidade | Locais | Meta (até 5) | Locais e acesso |
|---|---|---|---|
| Futebol | **4** | não atingida | Parque Desportivo de Ramalde/INATEL, Campo Municipal do Viso, Estádio Universitário do Porto, Oporto Indoor Games: todos **aluguer de campo** |
| Futsal | **1** | não atingida | Oporto Indoor Games (**aluguer** do campo) |
| Basquetebol | **0** | não atingida | — |
| Voleibol | **0** | não atingida | — |
| Ténis | **3** | não atingida | Estádio Universitário (**aluguer** de courts); Clube de Ténis do Porto (**aulas**, com preço para não sócios); Quinta de Monserrate — Parque da Cidade (**aluguer** de campos) |
| Padel | **3** | não atingida | Clube de Ténis do Porto e Quinta de Monserrate (**aluguer** de campos); Padel Athletic Club (**aluguer** e aula experimental) |
| Musculação | **5** | atingida | Fitness UP Antas, Bessa, Constituição e São João (**treino livre** de sócios na zona de musculação); Fox Gym (**aula** "Funcional de Musculação") |

**Total do catálogo:** 30 locais e 61 atividades (eram 24 e 45), com 9 de fora.

- **Locais novos:** Campo Municipal do Viso, Clube de Ténis do Porto, Quinta de Monserrate — Parque da Cidade, Padel Athletic Club e Oporto Indoor Games.
- **Locais existentes com atividades novas, sem nova entrada:**
  - **Parque Desportivo de Ramalde/INATEL:** futebol. O nome passou de "Pista de Atletismo do Parque…" para o parque, com o mesmo id.
  - **Estádio Universitário do Porto:** futebol e ténis. Estava no ficheiro sem atividade confirmada e passa a ser importado; a pista continua de fora.
  - **4 Fitness UP e o Fox Gym:** musculação.
- **Pendentes no ficheiro:**
  - **Campo Municipal dos Choupos** (futebol de 7, aluguer confirmado): falta a posição, porque a página oficial não tem mapa e o OSM não tem o campo.
  - **Your Top-Padel Industrial** (padel): falta a posição e o código postal.

### 12.3 Fontes (consultadas a 2026-10-28)

| Local | Fonte do acesso | Coordenadas |
|---|---|---|
| Ramalde, Viso, Choupos | Preçários municipais 2026–2027 da Ágora (16.10.2026): "Utilização" por hora para "Privados ou clubes de fora da cidade do Porto, mediante marcação prévia" ([Ramalde](https://www.agoraporto.pt/files/uploads/cms/Parque-Desp-Ramalde-Horario-Precos-2026-2027_v2.pdf), [Viso](https://www.agoraporto.pt/files/uploads/cms/Campo-do-Viso-Horario-Precos-2026-2027_v2.pdf), [Choupos](https://www.agoraporto.pt/files/uploads/cms/Campo-dos-Choupos-Horario-Precos-2026-2027_v2.pdf)) | Ramalde: ligação de mapa da Ágora; Viso: [objeto OSM "Campo Sintético do Viso"](https://www.openstreetmap.org/way/334849388) na Rua Direita do Viso |
| Estádio Universitário | [Tabela de preços do CDUP](https://cdup.up.pt/instalacoes/estadio-universitario-do-porto/): colunas "Comunidade UP" e "Externos" para os campos de futebol 5, 7 e 11 e os courts de ténis; reserva online | Ligação de mapa do CDUP (já existente) |
| Clube de Ténis do Porto | Preçários com coluna "N/ Sócio" ([adultos](https://www.ctporto.com/p369-precario-adultos-pt), [escola](https://www.ctporto.com/p172-precario-escola-pt)) e preço "Reserva" no [padel](https://www.ctporto.com/p159-precario-reservas-padel-clube-de-tenis-do-porto-pt) | "Coordenadas GPS" oficiais na [página de contactos](https://www.ctporto.com/p123-contactos-pt) |
| Quinta de Monserrate — Parque da Cidade | ["Book your court!"](https://quintademonserrate.com/en/parque-da-cidade/) e academias; morada nos [contactos](https://quintademonserrate.com/en/contacts/) | [Objeto OSM](https://www.openstreetmap.org/way/755451318) com o n.º 15687 da Estrada Interior da Circunvalação, junto aos campos de ténis; lado do Porto (Nevogilde) |
| Padel Athletic Club | ["Reservar campo" e "Aula experimental"](http://padelathleticclub.pt/) | [Objeto OSM](https://www.openstreetmap.org/node/14045851701) com o mesmo nome e número |
| Oporto Indoor Games | ["Aluguer de Campos de Futebol de Cinco e Futebol de Sete"](https://www.oportoindoorgames.pt/aluguer-de-campos) e campo de futsal; [reservas](https://www.oportoindoorgames.pt/reservas-contactos) | [Edifício OSM](https://www.openstreetmap.org/way/897251551) com o endereço 1063-1095 da mesma rua |
| Fitness UP (musculação) | Página da rede ["No ginásio"](https://www.fitnessup.pt/treinar-ginasio): "Zona de musculação", "mais de 300 postos de treino". A descrição é da rede, não de cada clube | As do §10 |
| Fox Gym (musculação) | [Mapa de aulas](https://foxgym.pt/mapa-de-aulas/): "Funcional de Musculação" | As do §10 |

Sem preços, horários, níveis (`UNSPECIFIED`), disponibilidade, fotografias nem avaliações.

### 12.4 Porque é que a meta não foi atingida

- **Basquetebol, voleibol e futsal:**
  - **Pavilhões municipais da Ágora (11):** os preçários confirmam o aluguer a privados, mas nem as páginas nem os PDF dizem que modalidades cada pavilhão permite. Atribuir-lhes estas modalidades seria inventar.
  - **Pavilhão Luís Falcão (CDUP):** tem o mesmo problema; só menciona badminton.
  - **Equipas universitárias do CDUP** (basquetebol 3x3, voleibol de praia, futebol 7, ténis, padel): são de competição e não contam.
  - **Escolas e captações de clubes:** as encontradas eram de Matosinhos, ou captações de formação competitiva (FC Porto voleibol), por isso não entram.
  - **Porto CCD:** não indica modalidades nem acesso para quem não é sócio.
  - **Arena PlaySports (Prelada) e Footspace:** só têm fichas em plataformas de reserva (Playtomic, Urban Sports Club), sem site oficial encontrado.
- **Futebol:**
  - Os campos municipais do **Parque da Cidade**, do **Outeiro** (cedido ao Sporting Clube da Cruz) e o **Prof. Joaquim Novais** não têm preçário nem regras de acesso publicadas.
  - O **Campo dos Choupos** está pendente pelas coordenadas.
  - O **Dragon Force** não tem página oficial com escolas e moradas.
- **Ténis:**
  - **Monte Aventino** (gerido pela Federação Portuguesa de Ténis): as fontes só dizem que "aceita reservas", sem dizer de quem.
  - **Boa Hora Ténis & Padel:** fica em Lisboa.
- **Padel:**
  - **Your Top-Padel:** pendente pelas coordenadas.
  - **Norte Padel:** só tem ficha na Playtomic.
  - **Complexo do Fluvial:** o site do clube só tem modalidades aquáticas (o padel junto ao Fluvial é outro negócio, sem site oficial encontrado).
- **Musculação:**
  - **Ginásio de Ramalde:** o regulamento municipal reserva-o a clubes e atletas.
  - **Sala de musculação do Estádio Universitário:** remete para os "preços UPFit", sem condições para externos.

### 12.5 Importação e verificação

- **Cópia antes de alterar:** `C:\Users\rafap\AndroidStudioProjects\PortoFit-backups\catalog_db-2026-10-28-antes-novas-modalidades.dump`, com SHA-256 `816fb5a4c37607c10cbc1eecb2621d5b5d1c1b9f811c2b258818d82234e1f4e2` igual dentro e fora do contentor, e 4 tabelas com dados.
- **Arranque:** V8 aplicada e importação de **30 locais e 61 atividades, com 9 de fora**. Depois de reiniciar (nova importação), as mesmas contagens: 40 locais na base (30 reais e 10 fictícios `RETIRED`), 80 atividades e 15 modalidades, sem nomes repetidos.
- **API:**
  - contagens por modalidade iguais às do §12.2; as anteriores não mudaram (§11.3);
  - detalhes com o tipo de acesso no nome e na descrição da atividade, e `UNSPECIFIED`;
  - coordenadas todas no concelho;
  - um par novo a menos de 200 m: Oporto Indoor Games e Padel Athletic Club, a 67 m, estabelecimentos diferentes com números de porta diferentes (1063 e 1010);
  - ténis a 5 km dos Aliados: Clube de Ténis do Porto a 1,4 km e Estádio Universitário a 2,76 km.
- **Compatibilidade, com uma conta temporária, removida no fim:**
  - **Interesses:** "tenis" e "padel" aceites (`200`); "tennis" recusado (`400 UNKNOWN_INTERESTS`).
  - **Publicação:** publicar em "Aulas de ténis" do Clube de Ténis do Porto dá `201`, com a modalidade `tenis`/"Ténis".
  - **Filtros:** aparece em `?sportId=tenis` e no feed popular filtrado por ténis; a avaliação do local passou a 4,0 (1). A experiência foi eliminada a seguir.
  - **Dados dos utilizadores:** todas as tabelas das outras bases e o volume das fotografias ficaram com as mesmas contagens que antes.
- **Testes do catálogo:** 35, todos a passar.
  - `CatalogApiIntegrationTest`: 15 modalidades, com "Basquetebol" primeiro, "Ténis" e "Musculação".
  - `RealPlacesImportIntegrationTest`: os totais novos, as contagens das 15 modalidades, o estádio só com futebol e ténis, "tenis" e "ténis" iguais, e "futsal" diferente de "futebol".
- **App:** sem alterações, por isso sem novo build.

### 12.6 Pendências

- **Por coordenadas:** Campo dos Choupos e Your Top-Padel.
- **Modalidades sem locais:** basquetebol e voleibol. Precisam de uma fonte oficial que associe uma modalidade a um pavilhão com acesso do público.
- **Fitness UP:** a musculação vem da descrição da rede; confirmar por clube, se for possível.

## 13. Basquetebol, voleibol e futsal: pesquisa alargada (2026-10-28)

O objetivo era ter até 5 locais verificáveis de basquetebol, voleibol e futsal no município do Porto. Fontes consultadas a 2026-10-28.

**Fontes aceites nesta revisão**, além do site próprio e do preçário:
- páginas oficiais dos espaços em plataformas de reserva (Playtomic);
- diretórios e calendários federativos (Associação de Voleibol do Porto, Federação Portuguesa de Basquetebol);
- notícias municipais oficiais.

**A modalidade nunca foi atribuída a um pavilhão por parecer adequada.** Nos pavilhões municipais, o aluguer vem do preçário da Ágora, e a modalidade de uma fonte federativa que a liga explicitamente a esse pavilhão.

A "Funcional de Musculação" do Fox Gym não foi alterada.

### 13.1 Resultado

| Modalidade | Locais | Meta (até 5) | Locais e acesso |
|---|---|---|---|
| Basquetebol | **5** | atingida | **Prática livre:** Parque do Covelo, Jardim Paulo Vallada e Bairro do Viso. **Aluguer:** Pavilhão Municipal do Lagarteiro e Pavilhão Municipal Manoel de Oliveira |
| Voleibol | **3** | não atingida | **Aluguer:** Pavilhão A do Estádio Universitário (preço para externos), Pavilhão Municipal Irene Lisboa e Pavilhão Municipal Pêro Vaz de Caminha |
| Futsal | **4** | não atingida | **Aluguer:** Oporto Indoor Games (§12), Arena PlaySports Porto (Prelada), Footspace e Porto CCD |

**Restrições de participação:**
- **Prática livre:** sem aulas, reservas nem horário.
- **Aluguer:** é aluguer do espaço, não uma aula nem uma equipa aberta.
- **Porto CCD:** a página da plataforma não indica se a reserva exige ser sócio (fica anotado).

**Total do catálogo:** 39 locais e 74 atividades (eram 30 e 61), com 9 de fora.

**Atividades acrescentadas a locais existentes, sem nova entrada:**
- **Pavilhão do Lagarteiro:** basquetebol.
- **Estádio Universitário:** voleibol.
- **Pavilhão Pêro Vaz de Caminha:** passou de pendente a importado, com as coordenadas do mapa da AVP. Trouxe também o ioga e o pilates do "Dias com Energia", que já estavam confirmados: **ioga passa a 13 e pilates a 12**.

**Pendente no ficheiro:** a **União Académica António Aroso — escola de basquetebol**.
- O site tem "Inscrições abertas para a época 2026/2027" e "Basquetebol a partir dos 5 anos", sem informação para adultos.
- Falta saber onde o clube treina:
  - a FPB situa um campus de 2017 na EB 2,3 Leonardo Coimbra;
  - os perfis de estatísticas de terceiros indicam a Manoel de Oliveira;
  - nenhuma destas fontes confirma o local atual.

### 13.2 Fontes e coordenadas (consultadas a 2026-10-28)

| Local | Modalidade e acesso | Coordenadas |
|---|---|---|
| Parque do Covelo, Jardim Paulo Vallada, Bairro do Viso | [GO Porto, 23/02/2022](https://www.goporto.pt/noticias/municipio-promove-desporto-ao-ar-livre-para-todos): lista municipal das 15 tabelas de basquetebol requalificadas, "de acesso fácil e gratuito" | Campo de basquetebol dentro de cada espaço da lista: [Covelo](https://www.openstreetmap.org/node/13741182024), [Paulo Vallada](https://www.openstreetmap.org/way/482327698), [Bairro do Viso](https://www.openstreetmap.org/way/456415508) (Rua de Bordeaux); a geocodificação inversa confirma o espaço de cada um |
| Pavilhão do Lagarteiro, Pavilhão Manoel de Oliveira | Calendário oficial da FPB, época 2026/2027: jogos em novembro de 2026 no "Gim. do Lagarteiro" e na "Escola EB23 Manoel de Oliveira" ([FC Porto](https://www.fpb.pt/calendario/clube_120/), [Vasco da Gama](https://www.fpb.pt/calendario/clube_176/)). Aluguer: [preçário do Lagarteiro](https://www.agoraporto.pt/files/uploads/cms/Pav-Lagarteiro-Horario-Precos-2026-2027_v2.pdf) e [preçário da Manoel de Oliveira](https://www.agoraporto.pt/files/uploads/cms/Pav-Manoel-Oliv-Horario-Precos-2026-2027_v2.pdf) | Lagarteiro: as do §10. Manoel de Oliveira: [pavilhão](https://www.openstreetmap.org/way/160503724) dentro da escola com o n.º 134 e o código postal da morada oficial |
| Pavilhão Irene Lisboa, Pavilhão Pêro Vaz de Caminha, Pavilhão A do Estádio Universitário | Diretório de clubes da AVP: o Boavista FC joga [no Irene Lisboa](https://www.avporto.pt/boavistafc/), o Colégio Universal [no Pêro Vaz de Caminha](https://www.avporto.pt/gcuniversal/), a CDUP/Volleystars [no Pavilhão A](https://www.avporto.pt/cdup/). Aluguer: preçários da Ágora ([Irene Lisboa](https://www.agoraporto.pt/files/uploads/cms/Pav-Irene-Lisboa-Horario-Precos-2026-2027_v2.pdf), [Pêro Vaz de Caminha](https://www.agoraporto.pt/files/uploads/cms/Pav-Pero-Vaz-Caminha-Horario-Precos-2026-2027_v2.pdf)) e [tabela do CDUP](https://cdup.up.pt/instalacoes/estadio-universitario-do-porto/) (Pavilhão A, "Externos") | Ligações de mapa da AVP; caem nas escolas Irene Lisboa (Rua de Cervantes, 532) e Pêro Vaz de Caminha (a ~70 m da Rua Irmã Maria Droste). São aproximadas, a escola onde fica o pavilhão |
| Arena PlaySports Porto (Prelada) | [Página do espaço no Playtomic](https://playtomic.com/pt/clubs/arena-playsports-porto-prelada-novo-relvado): "um pavilhão para a prática de futsal"; reservas por email e telefone | Coordenadas da página; caem na EB Maria Lamas (Travessa da Prelada) |
| Footspace | [Página do espaço no Playtomic](https://playtomic.com/pt/clubs/footspace): futsal, campos cobertos (sintético e taco) | Coordenadas da página |
| Porto CCD | [Página do espaço no Playtomic](https://playtomic.com/pt/clubs/ccdporto): "um pavilhão para a prática de futsal" | Coordenadas da página; caem na Rua de Alves Redol, 292 |

Sem preços, horários, níveis (`UNSPECIFIED`), disponibilidade, fotografias nem avaliações.

### 13.3 Porque é que voleibol e futsal ficaram abaixo de 5

- **Voleibol:**
  - **Outros pavilhões indicados pela AVP:** a Escola Francisco Torrinha (FC Porto, CD Foz) e os colégios Júlio Dinis e Rosário não são municipais nem têm acesso do público publicado.
  - **Clubes:** o site da AEGO Sport está offline. A Volleystars não tem inscrições de praticantes. As captações do FC Porto terminaram em junho e são seleção de atletas, não escola aberta.
  - **Campos de praia do Parque da Cidade:** a única informação atual é o Estádio de Praia sazonal (junho a setembro), sem condições de uso fora dos eventos. A página da Ágora já não existe.
- **Futsal:**
  - **Pavilhões municipais:** não foi encontrada uma fonte federativa ou municipal que ligue o futsal a um pavilhão específico.
  - **Pavilhão Luís Falcão (CDUP):** só menciona badminton.
  - Os locais de futsal da Arena PlaySports e do Porto CCD também têm futebol; essas atividades não foram acrescentadas, porque esta revisão é só destas três modalidades.
- **Basquetebol, candidatos não usados:**
  - **Parque da Cidade:** tem dois campos possíveis em entradas diferentes.
  - **Largo de Miragaia e Rua Coronel Hélder Ribeiro:** sem posição do campo.
  - **Escola EB 2.3 Viso:** é o pavilhão do Viso, sem coordenadas confirmadas.
  - **Académico FC:** sem inscrições nem local de treino publicados.

### 13.4 Importação e verificação

- **Cópia antes de alterar:** `C:\Users\rafap\AndroidStudioProjects\PortoFit-backups\catalog_db-2026-10-28-antes-basquetebol-voleibol-futsal.dump`, com SHA-256 `859c8bcb319877931ffd47f11529194add46101f62d69e83bcc95436da49390b` igual dentro e fora do contentor, e 4 tabelas com dados.
- **Importação:** 39 locais e 74 atividades, com 9 de fora. Depois de reiniciar, as mesmas contagens: 49 locais na base (39 reais e 10 fictícios `RETIRED`) e 93 atividades, sem nomes repetidos.
- **API:**
  - basquetebol 5, voleibol 3, futsal 4, ioga 13, pilates 12; as outras modalidades sem alteração;
  - "futsal" encontra os 4 locais de futsal, e "futebol" continua com os seus 4;
  - coordenadas todas no concelho;
  - um par novo a menos de 200 m: Clube de Ténis do Porto e Pavilhão Irene Lisboa, a 194 m, instalações diferentes.
- **Outras bases:** só o `catalog_db` mudou (mais 9 locais e 13 atividades). Contas, publicações, listas, mensagens, notificações e o volume ficaram iguais.
- **Testes:** `RealPlacesImportIntegrationTest` com os totais e as contagens novas, e o estádio com futebol, ténis e voleibol: 4, a passar.

### 13.5 Pendências

- **Voleibol (3) e futsal (4) abaixo de 5.**
- **União Académica António Aroso:** falta o local de treino.
- **Tabelas de basquetebol:** a fonte é de 2022; confirmar no local o estado atual.
- **Porto CCD:** confirmar se a reserva do pavilhão exige ser sócio.
