# Locais reais do Porto (preparação)

> **Estado:** pesquisa e preparação de dados, consultados a 2026-10-27. **Nada foi importado nem alterado** no catálogo: os locais fictícios continuam ativos.
> Dados: [`data/catalogo/locais-reais-porto.json`](../data/catalogo/locais-reais-porto.json), separados dos dados de demonstração.
> Modelo e regras do catálogo: [catalog-api.md §2](catalog-api.md#2-entidades-e-relações); estratégia de substituição: [experiences-api.md §15](experiences-api.md#15-relação-com-locais-reais-e-substituição-do-catálogo-de-demonstração).

## 1. Resumo

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
2. **`postalCode` obrigatório.**
   - Não se propõe torná-lo opcional: é confirmável.
   - Os 3 locais em falta ficam de fora da importação até o código ser confirmado na pesquisa oficial dos CTT ou junto do próprio espaço.
3. **`latitude`/`longitude` obrigatórias.** Os 3 locais sem coordenadas ficam de fora até alguém as confirmar num mapa, com a fonte registada no JSON.
4. **`parish` até 80 caracteres.**
   - O nome oficial da união de freguesias do centro ("União das Freguesias de Cedofeita, Santo Ildefonso, Sé, Miragaia, São Nicolau e Vitória") tem mais de 80.
   - Nenhum local pronto está nessa união, mas se a Constituição, a Skills ou a Academia Marcial lá ficarem, propõe-se a forma sem o prefixo ("Cedofeita, Santo Ildefonso, Sé, Miragaia, São Nicolau e Vitória", 64 caracteres) ou a abreviatura "Centro Histórico", já usada nos dados de demonstração. É uma decisão do grupo.
5. **`demo`:** os locais reais entram com `is_demo = false`. Nenhuma mudança necessária.

## 5. Proposta de importação e de retirada dos fictícios

**Importação repetível** (a implementar numa tarefa própria; nada disto existe ainda):

- **Importador:** um importador no catalog-service lê `data/catalogo/locais-reais-porto.json` (ou uma cópia em `src/main/resources/real/`) e grava numa transação, com `INSERT … ON CONFLICT (id) DO UPDATE`, como o carregador de demonstração. Repetir não cria duplicados.
- **Só locais prontos:** importa apenas locais com `"status": "ready"` e todos os campos obrigatórios. Um local pendente é ignorado e listado no registo; não há valores por omissão para preencher o que falta.
- **Validação antes de gravar:** as mesmas regras do esquema (formato do código postal, intervalos, modalidade existente, nome único dentro do local). Qualquer erro aborta a importação inteira, sem gravar metade.
- **Ids fixos do ficheiro:** nunca se reutiliza um id fictício, e um local real nunca muda de id depois de importado.
- **Execução separada do perfil `demo`:** por exemplo, um perfil `real-places`, ou um comando explícito de manutenção. Correr a importação nunca retira nem apaga nada.

**Retirada dos fictícios** (passo separado, depois de os reais estarem importados e verificados), como em [experiences-api.md §15](experiences-api.md#15-relação-com-locais-reais-e-substituição-do-catálogo-de-demonstração):

1. Relatório **antes**: experiências por local/atividade fictício (o experiences-service sabe-o pelos ids). Só informa.
2. `UPDATE place SET status = 'RETIRED', updated_at = now() WHERE is_demo` (as atividades ficam retiradas pelo estado efetivo; opcionalmente também `activity_offering.status`). **Nada é apagado**, e nenhuma experiência passa para um local real.
3. Desligar o perfil `demo` no `docker-compose.yml`. Não é preciso para manter a retirada: o script de demonstração (`ON CONFLICT DO UPDATE`) não escreve o `status`, por isso recarregá-lo não reativa os locais (verificado a 2026-10-27). Desligá-lo só evita voltar a escrever nomes e moradas fictícias.
4. Relatório **depois**: as mesmas contagens. Os locais retirados continuam a responder pelo id, e a app indica "Local de demonstração retirado".

## 6. Candidatos excluídos ou adiados

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

## 7. Campos a rever antes de importar

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
