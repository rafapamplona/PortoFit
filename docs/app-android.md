# PortoFit — App Android

> Estado: etapa 4 — pesquisa e detalhe do catálogo ligados ao catalog-service. Sem login, publicações, mapas, GPS nem outros serviços.
> Backend: [infraestrutura.md](infraestrutura.md) · Contrato: [catalog-api.md](catalog-api.md) · Desenho: [arquitetura.md](arquitetura.md)

## 1. Percurso disponível

1. Abrir a app: lista de todos os locais (por nome), com o filtro de modalidades por cima.
2. Escrever "boxe" e tocar em **Pesquisar** (ou na tecla de pesquisa do teclado): locais com boxe.
3. Escolher uma modalidade nos filtros (ex.: Natação): a lista recomeça na primeira página com esse filtro.
4. **Carregar mais** no fim da lista, quando há mais páginas (20 locais por página).
5. Tocar num local: detalhe com descrição, morada, modalidades e atividades oferecidas.
6. Voltar (seta ou gesto do sistema): regressa à lista com a mesma pesquisa e filtro.

## 2. Executar no emulador

1. Arrancar o backend (na raiz do projeto):
   ```powershell
   docker compose up -d --wait
   curl.exe http://localhost:8081/actuator/health   # deve responder {"status":"UP",...}
   ```
2. Abrir o projeto no Android Studio e arrancar um emulador (**Device Manager**). Esperar pelo ecrã inicial do Android antes de continuar (ver etapa 0: arrancar a app com o emulador ainda a iniciar dá "Activity class does not exist").
3. Escolher a configuração **app**, variante **debug**, e carregar em **Run ▶**.

Alternativa pela linha de comandos, com o emulador já a correr:

```powershell
.\gradlew.bat installDebug
adb shell am start -n com.example.portofit/.MainActivity
```

## 3. Ligação ao backend

| Situação | URL usada pela app | Como |
|---|---|---|
| Emulador Android (por omissão, debug) | `http://10.0.2.2:8081/` | `10.0.2.2` é o computador visto a partir do emulador |
| Dispositivo físico por USB | `http://127.0.0.1:8081/` | `adb reverse tcp:8081 tcp:8081` e compilar com `-Pportofit.catalogBaseUrl=http://127.0.0.1:8081/` |
| Release | nenhuma | ainda não há backend publicado; a app mostra "O endereço do serviço não está configurado" |

- A URL vem de `BuildConfig.CATALOG_BASE_URL`, definida em `app/build.gradle.kts`. Para a mudar sem editar código: `.\gradlew.bat installDebug -Pportofit.catalogBaseUrl=http://…/`.
- O backend continua publicado só em `127.0.0.1:8081` no computador; não fica acessível na rede local.
- **HTTP sem TLS só em debug:** `app/src/debug/AndroidManifest.xml` aplica `res/xml/network_security_config.xml`, que permite tráfego em claro apenas para `10.0.2.2`, `localhost` e `127.0.0.1`. A variante release não tem esta configuração e bloqueia HTTP em claro.
- Limites de espera do cliente HTTP: ligação 5 s, leitura 10 s, pedido completo 15 s.

## 4. Estrutura (MVVM)

```
com.example.portofit/
├── PortoFitApplication.kt          cria o AppContainer
├── di/AppContainer.kt              injeção manual: OkHttp, Retrofit, repositório
├── data/
│   ├── remote/                     CatalogApi (Retrofit), DTOs (JSON do contrato), conversões DTO → modelo
│   ├── model/                      modelos da interface (PlaceSummary, PlaceDetail, Rating, …)
│   └── repository/                 CatalogRepository → DataResult (sucesso ou DataError)
└── ui/
    ├── navigation/PortoFitNavHost  pesquisa → place/{placeId}
    ├── components/                 textos de avaliação/erros, carregamento, erro com nova tentativa
    └── feature/
        ├── search/                 SearchScreen, SearchViewModel, SearchUiState
        └── place/                  PlaceDetailScreen, PlaceDetailViewModel
```

- **Screen** desenha o estado e envia eventos; **ViewModel** expõe `StateFlow<UiState>`; **Repository** chama a API e classifica os erros; **DTOs** ficam separados dos modelos da interface.
- O estado é recolhido com `collectAsStateWithLifecycle` (só enquanto o ecrã está visível).
- O texto, a pesquisa aplicada e a modalidade ficam no `SavedStateHandle`: sobrevivem à rotação e à recriação da Activity.
- Cada nova pesquisa ou mudança de filtro recomeça na página 0 e cancela o pedido anterior; um número de geração garante que respostas atrasadas de pesquisas antigas não substituem os resultados mais recentes.
- Não há dados embutidos na app: se o backend falhar, a app mostra o erro e "Tentar novamente".

## 5. Comportamento visível

| Situação | O que aparece |
|---|---|
| A carregar | indicador de progresso |
| Sem resultados | "Nenhum local encontrado." |
| Backend parado / sem rede | "Sem ligação ao servidor…" + **Tentar novamente** |
| Base de dados em baixo (HTTP 503) | "O serviço está temporariamente indisponível…" + **Tentar novamente** |
| Falha só nas modalidades | aviso discreto no lugar dos filtros + **Tentar novamente**; a pesquisa por texto continua a funcionar |
| Falha em "Carregar mais" | os locais já mostrados mantêm-se; aviso + **Tentar novamente** |
| Pesquisa com 1 carácter | aviso no campo, sem pedido ao servidor (o contrato exige 2–100) |
| `rating.status = AVAILABLE` | "★ 4,3 (12 pessoas)" |
| `NO_RATINGS` | "Sem avaliações" |
| `UNAVAILABLE` (todos, nesta fase) | "Avaliações temporariamente indisponíveis" — nunca zero estrelas |
| Locais fictícios | um único aviso por lista ("Dados de demonstração: estes locais são fictícios…") e a etiqueta "Dados de demonstração" no detalhe; os nomes já incluem "(fictício)", por isso não há etiqueta repetida em cada cartão |

## 6. Testes

```powershell
.\gradlew.bat testDebugUnitTest
```

Testes unitários (JVM, sem emulador):

- `CatalogMappersTest` — JSON real do contrato → modelos; estados de avaliação (incluindo valores incoerentes ou desconhecidos, tratados como indisponíveis); níveis; modalidades do detalhe sem repetições; campos desconhecidos ignorados.
- `SearchViewModelTest` — carregamento inicial; falha das modalidades sem bloquear a pesquisa; erro e nova tentativa; lista vazia; nova pesquisa e mudança de filtro reiniciam a paginação; pesquisa com 1 carácter; "Carregar mais" até ao fim e com erro; resposta atrasada de pesquisa antiga ignorada; página atrasada ignorada após nova pesquisa; reposição a partir do `SavedStateHandle`.
- `PlaceDetailViewModelTest` — carregamento pelo argumento de navegação; local inexistente; falha de ligação e recuperação.

## 7. Verificar falha e recuperação (manual)

Com a app aberta no emulador:

1. `docker compose stop catalog-service` → pesquisar: aparece "Sem ligação ao servidor…".
2. `docker compose start catalog-service` e esperar por `healthy` (`docker compose ps`) → **Tentar novamente**: os resultados voltam.
3. Opcional: `docker compose stop postgres` → pesquisar: "O serviço está temporariamente indisponível…"; `docker compose start postgres` → **Tentar novamente**.

`stop`/`start` não apagam dados; não usar `docker compose down -v`.

## 8. Problemas conhecidos neste ambiente

- **Build falha com `JdkImageTransform` / `jlink.exe does not exist`:** o Gradle reutilizou um daemon arrancado pela extensão Java do VS Code no seu JRE embutido (sem `jlink`). Correr `.\gradlew.bat --stop` e repetir o build no terminal (que usa o Temurin 25). Como o VS Code volta a importar o projeto ao abrir, isto pode repetir-se.
- **Memória:** o computador tem cerca de 8 GB de RAM. Docker (3,7 GB atribuídos), emulador, Android Studio, VS Code (com a extensão Java) e daemons Gradle em simultâneo esgotam a memória; já causou o encerramento da extensão Java do VS Code (`hs_err_pid*.log`). Fechar o que não estiver a ser usado antes de arrancar o emulador.
