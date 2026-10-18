# PortoFit — App Android

> Estado: etapa 4 — pesquisa e detalhe do catálogo (públicos); etapa 5 (app) — registo, login, sessão, logout e "O meu perfil" (descrição e interesses) no users-service; pesquisa de pessoas e perfis públicos (sem sessão). Sem seguidores, publicações, mapas nem GPS.
> Backend: [infraestrutura.md](infraestrutura.md) · Contratos: [catalog-api.md](catalog-api.md), [users-api.md](users-api.md) · Desenho: [arquitetura.md](arquitetura.md)

## 1. Percurso disponível

1. Abrir a app: lista de todos os locais (por nome), com o filtro de modalidades por cima.
2. Escrever "boxe" e tocar em **Pesquisar** (ou na tecla de pesquisa do teclado): locais com boxe.
3. Escolher uma modalidade nos filtros (ex.: Natação): a lista recomeça na primeira página com esse filtro.
4. **Carregar mais** no fim da lista, quando há mais páginas (20 locais por página).
5. Tocar num local: detalhe com descrição, morada, modalidades e atividades oferecidas.
6. Voltar (seta ou gesto do sistema): regressa à lista com a mesma pesquisa e filtro.

Conta (opcional; a pesquisa e o detalhe funcionam sempre sem sessão):

7. **Entrar** (barra superior da pesquisa) → email e palavra-passe; ou **Criar conta** → nome de utilizador, email e palavra-passe.
8. Com sessão, a barra mostra `@username`; tocar nele abre o menu com **Terminar sessão**.
9. Ao reabrir a app, a sessão é recuperada sozinha (ver §10).

## 2. Executar no emulador

1. Arrancar o backend (na raiz do projeto):
   ```powershell
   docker compose up -d --wait
   curl.exe http://localhost:8081/actuator/health   # catálogo: {"status":"UP",...}
   curl.exe http://localhost:8082/actuator/health   # contas: {"status":"UP",...}
   ```
2. Abrir o projeto no Android Studio e arrancar um emulador (**Device Manager**). Esperar pelo ecrã inicial do Android antes de continuar (ver etapa 0: arrancar a app com o emulador ainda a iniciar dá "Activity class does not exist").
3. Escolher a configuração **app**, variante **debug**, e carregar em **Run ▶**.
4. **Na primeira execução (Android 17+)**, o sistema pergunta se o PortoFit pode encontrar e ligar-se a dispositivos próximos: escolher **Permitir** e depois tocar em **Tentar novamente** (os pedidos feitos antes da resposta já falharam). Ver §3.

Alternativa pela linha de comandos, com o emulador já a correr:

```powershell
.\gradlew.bat installDebug
adb shell am start -n com.example.portofit/.MainActivity
```

## 3. Ligação ao backend

| Situação | URL usada pela app | Como |
|---|---|---|
| Emulador Android (por omissão, debug) | catálogo `http://10.0.2.2:8081/`; contas `http://10.0.2.2:8082/` | `10.0.2.2` é o computador visto a partir do emulador |
| Dispositivo físico por USB | `http://127.0.0.1:8081/` e `:8082/` | `adb reverse tcp:8081 tcp:8081`, `adb reverse tcp:8082 tcp:8082` e compilar com `-Pportofit.catalogBaseUrl=http://127.0.0.1:8081/ -Pportofit.usersBaseUrl=http://127.0.0.1:8082/` |
| Release | nenhuma | ainda não há backend publicado; a app mostra "O endereço do serviço não está configurado" e "As contas não estão disponíveis nesta versão da app" |

- As URLs vêm de `BuildConfig.CATALOG_BASE_URL` e `BuildConfig.USERS_BASE_URL`, definidas em `app/build.gradle.kts` só para debug. Para as mudar sem editar código: `-Pportofit.catalogBaseUrl=…` / `-Pportofit.usersBaseUrl=…`.
- Os serviços continuam publicados só em `127.0.0.1:8081` e `127.0.0.1:8082` no computador; não ficam acessíveis na rede local.
- **HTTP sem TLS só em debug:** `app/src/debug/AndroidManifest.xml` aplica `res/xml/network_security_config.xml`, que permite tráfego em claro apenas para `10.0.2.2`, `localhost` e `127.0.0.1`. A variante release não tem esta configuração e bloqueia HTTP em claro.
- Limites de espera: catálogo — ligação 5 s, leitura 10 s, pedido 15 s; contas — leitura 20 s e pedido 25 s (o login inclui o BCrypt do servidor, ~2 s neste PC) e **sem novas tentativas automáticas** (`retryOnConnectionFailure = false`).

### Acesso à rede local (Android 17, `targetSdk` 37)

Com `targetSdk` 37, o Android 17 aplica a **proteção de rede local**: o tráfego de uma app para endereços da rede local — incluindo `10.0.2.2`, que é um endereço privado — exige a permissão de execução `ACCESS_LOCAL_NETWORK` (grupo "Dispositivos próximos"). Sem ela, as ligações TCP **não dão erro imediato: ficam sem resposta até ao timeout**, e a app mostra "Sem ligação ao servidor".

- A permissão está declarada **só em `app/src/debug/AndroidManifest.xml`**, e a `MainActivity` pede-a **só em builds de debug** em Android 17+. A release não a declara nem pede: o backend publicado estará na Internet, que não precisa desta permissão.
- Se a permissão for recusada: **Definições → Apps → PortoFit → Autorizações → Dispositivos próximos → Permitir**, ou `adb shell pm grant com.example.portofit android.permission.ACCESS_LOCAL_NETWORK`.
- Desinstalar a app apaga a autorização; reinstalar com `adb install -r` ou pelo Android Studio mantém-na.
- Diagnóstico: `adb shell run-as com.example.portofit sh -c "printf 'GET /api/v1/sports HTTP/1.0\r\n\r\n' | nc -w 10 10.0.2.2 8081 | head -1"` faz o pedido **com o utilizador da app**; sem a permissão dá `nc: Timeout`, com ela `HTTP/1.1 200`. O mesmo pedido feito só com `adb shell` (sem `run-as`) funciona sempre, porque a shell não está sujeita a esta regra.

Referência: [Local network permission — Android Developers](https://developer.android.com/privacy-and-security/local-network-permission).

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

## 8. Verificação da etapa 4 (2026-10-16)

Emulador Pixel 9 (`sdk_gphone16k_x86_64`, Android 17 / API 37), backend em Docker Compose com os dados de demonstração.

**Confirmado visualmente pelo utilizador**

| Verificação | Resultado |
|---|---|
| Pesquisa "boxe" | 4 locais |
| "natacao" e "natação" | os mesmos 2 locais |
| Caracteres `ç` e `ã` no campo | aceites ao colar; escrevê-los com o teclado do emulador é difícil (limitação do emulador, não da app) |

**Verificado pelo assistente na app em execução** (comandos `adb` e leitura da hierarquia do ecrã com `uiautomator`; não são capturas de imagem)

| Verificação | Resultado |
|---|---|
| Filtro por modalidade | "boxe" → 4 locais; "boxe" + CrossFit → 2 (Bonfim, Antas), chip CrossFit selecionado |
| Abrir detalhe e voltar | detalhe do Bonfim com morada, freguesia, modalidades e atividades; ao voltar mantêm-se "boxe", CrossFit e os 2 locais |
| Rotação | horizontal (`ROTATION_90`) e de novo vertical: mantêm-se "boxe", CrossFit e "2 locais encontrados"; definições de rotação do emulador repostas no fim |
| Falha do catalog-service | `docker compose stop catalog-service` + nova pesquisa (filtro "Todas") → "Sem ligação ao servidor…" + **Tentar novamente**; pesquisa e filtro mantidos |
| Recuperação | `docker compose start catalog-service` (healthy) + **Tentar novamente** → "4 locais encontrados"; volume `portofit_postgres-data` preservado; backend saudável no fim |
| Testes unitários | 19 testes (conversões, ViewModels) — `.\gradlew.bat testDebugUnitTest` |
| Lint (`:app:lintDebug`) | 0 erros; 15 avisos: versões mais recentes disponíveis (7), recursos não usados do template (7), `android:label` redundante na activity (1). Nenhum na `MainActivity` |

**Pendente**

- Indicador vermelho "1 problema" na `MainActivity` no Android Studio: o código compila (Gradle) e o lint não aponta nada nesse ficheiro, logo não é um erro de compilação nem de lint. O registo do Studio mostra que a última sincronização Gradle do projeto foi a 2026-10-15, antes das alterações desta etapa (dependências, `buildConfig`, plugin de serialização). Falta ler a mensagem concreta no Studio; se for análise do editor, **File → Sync Project with Gradle Files** deve resolvê-la.
- Pormenor de dados: o horário de demonstração já contém "(informativo)" e o ecrã acrescenta "Horário (informativo):", repetindo a palavra.

## 9. Problemas conhecidos neste ambiente

- **"Sem ligação ao servidor" no emulador, com o backend `UP` no PC:** falta a autorização de rede local (ver §3). Confirmar com o comando `run-as … nc` dessa secção.

- **Build falha com `JdkImageTransform` / `jlink.exe does not exist`:** o Gradle reutilizou um daemon arrancado pela extensão Java do VS Code no seu JRE embutido (sem `jlink`). Correr `.\gradlew.bat --stop` e repetir o build no terminal (que usa o Temurin 25). Como o VS Code volta a importar o projeto ao abrir, isto pode repetir-se.
- **Memória:** o computador tem cerca de 8 GB de RAM. Docker (3,7 GB atribuídos), emulador, Android Studio, VS Code (com a extensão Java) e daemons Gradle em simultâneo esgotam a memória; já causou o encerramento da extensão Java do VS Code (`hs_err_pid*.log`). Fechar o que não estiver a ser usado antes de arrancar o emulador.

## 10. Conta e sessão (etapa 5)

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| `UsersApi` + DTOs | `data/remote/UsersApi.kt` | `POST api/v1/auth/register`, `/login`, `/refresh`, `/logout` (docs/users-api.md §3.1–3.4) |
| `RetrofitAuthGateway` | `data/auth/AuthGateway.kt` | chama a API e classifica falhas: recusa do servidor (`code`, campos, `Retry-After`), 5xx, pedido **não enviado** (sem rede, ligação recusada) ou **ambíguo** (timeout, ligação cortada depois do envio) |
| `FileSessionStore` + `KeystoreSecretCipher` | `data/auth/SessionStore.kt` | refresh token, userId e username num ficheiro privado (`files/session/session.bin`), cifrado com AES-256-GCM e chave do Android Keystore; escrita atómica |
| `SessionManager` | `data/auth/SessionManager.kt` | estado da sessão (`StateFlow`), login, registo, renovação partilhada, logout |
| `LoginViewModel`, `RegisterViewModel`, `AccountViewModel` | `ui/feature/auth/AuthViewModels.kt` | formulários e estado da conta para a barra superior |
| Ecrãs | `ui/feature/auth/AuthScreens.kt`, `AccountUi.kt` | Entrar, Criar conta, ação "Entrar"/"@username" e avisos de sessão na pesquisa |

### Regras

- **Access token só em memória.** O **refresh token** fica cifrado com uma chave do Android Keystore (não exportável) num ficheiro privado da app. A pasta `files/session/` está excluída das cópias de segurança e da transferência entre aparelhos (`res/xml/backup_rules.xml` e `data_extraction_rules.xml`). Decisão desta etapa: um ficheiro cifrado em vez do DataStore proposto no contrato — o mesmo resultado sem acrescentar dependências.
- **Ao reabrir a app**, a sessão guardada é lida e confirmada com uma renovação; o username aparece logo e o catálogo não espera pelo users-service.
- **Uma só renovação:** a renovação está protegida por exclusão mútua; pedidos que chegam durante uma renovação esperam e usam o novo access token.
- **Ordem segura:** o novo refresh token é guardado **antes** de a sessão ficar ativa. Se o logout ou um novo login acontecer durante uma renovação, o resultado tardio é descartado e o token tardio é revogado no servidor.
- **Resultados de uma renovação** (docs/users-api.md §8.3):

| Resultado | O que a app faz |
|---|---|
| Sucesso | guarda o novo refresh token e fica ativa |
| `401` (sessão inválida, expirada, revogada ou reutilizada) | apaga a sessão local e mostra "A tua sessão terminou. Entra novamente…" |
| Pedido não enviado, `429` ou `5xx` | **mantém a sessão** (nada é apagado) e mostra o aviso com **Tentar novamente**; a pesquisa continua a funcionar |
| Ambíguo (timeout ou ligação cortada depois do envio) | mantém a sessão, **não repete sozinha**; só "Tentar novamente" (ação do utilizador) volta a tentar e, se o servidor já tiver consumido o token, pede novo login |

- **Logout** apaga logo a sessão local (memória e ficheiro) e avisa o servidor em segundo plano. Se o servidor não responder, mostra: "Terminaste sessão neste dispositivo, mas não foi possível avisar o servidor. A sessão no servidor deixa de valer quando expirar…" (limite previsto em docs/users-api.md §6.5).
- **Formulários:** validação local igual ao contrato (username 3–30 `a-z0-9._`, reservados; email; palavra-passe 10–64 caracteres e ≤ 72 bytes, diferente do username e do email); palavra-passe oculta por omissão ("Mostrar"/"Ocultar"); campos e botão desativados durante o envio (um segundo envio é ignorado); a palavra-passe nunca vai para o `SavedStateHandle`; teclado de email e de palavra-passe, "Seguinte"/"Concluir", e o formulário desloca-se com o teclado aberto (`imePadding` + `verticalScroll`).
- **Sem logs de segredos:** não há registo de pedidos HTTP; os `toString()` de DTOs e da sessão guardada omitem palavras-passe e tokens.

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest` — 47 testes, dos quais 27 desta etapa:

- `SessionManagerTest` (15): login guarda a sessão; login recusado não guarda nada; conflitos de registo por campo; `429` com espera; reabrir recupera a sessão e guarda o novo token; sem sessão não contacta o servidor; sessão ilegível; **5 pedidos simultâneos → 1 renovação**; access token expirado é renovado; `401` apaga a sessão; falha temporária mantém a sessão e permite nova tentativa; **falha ambígua não é repetida automaticamente** (só com "Tentar novamente"); logout com e sem servidor; renovação que termina depois do logout não ressuscita a sessão.
- `SessionStorageAndGatewayTest` (6): guardar/ler, ficheiro sem o token em claro, ficheiro ilegível apagado, limpar, `toString()` sem token, classificação das falhas de rede.
- `AuthViewModelsTest` (6): validação antes de enviar, envios repetidos ignorados, erros distintos (credenciais, rede, limite com minutos), email preservado e palavra-passe não guardada na recriação, validação do registo como o contrato, conflitos do servidor nos campos.

A cifra real (Android Keystore) só existe num aparelho; nos testes JVM é substituída por uma cifra falsa reversível.

### Verificação desta etapa (2026-10-18)

| Verificação | Como | Resultado |
|---|---|---|
| Testes unitários | `testDebugUnitTest` | 47/47 |
| Build | `assembleDebug` | passou |
| Variantes | manifestos e `BuildConfig` gerados | debug: `USERS_BASE_URL = http://10.0.2.2:8082/`, configuração de rede local; release: sem URL, sem HTTP em claro, sem `ACCESS_LOCAL_NETWORK` |
| Serviços só no computador | portas à escuta | 8081 e 8082 só em `127.0.0.1`; recusadas por outro endereço do PC; PostgreSQL sem porta publicada |
| Na interface (emulador) | ver abaixo | confirmado pelo utilizador e pelo assistente |

**Confirmado manualmente pelo utilizador no emulador** (Pixel 9, Android 17)

| Verificação | Resultado |
|---|---|
| Catálogo sem sessão | pesquisa e detalhe acessíveis |
| Conta autenticada | `@pamplona` na barra superior |
| Fechar a app nas recentes e reabrir | sessão recuperada sem pedir login |
| Terminar sessão | funciona |
| Login com palavra-passe errada / correta | mostra o erro / entra |

**Falha e recuperação do users-service — verificado pelo assistente** (2026-10-18, `adb` + leitura da hierarquia do ecrã com `uiautomator`; o ficheiro de sessão foi observado só pelo tamanho e data, com `run-as … ls -l files/session/`, nunca pelo conteúdo)

| Passo | Resultado |
|---|---|
| Ponto de partida | 1 conta em `users_db`; sessão `@pamplona`; `session.bin` com 162 bytes (21:01) |
| `docker compose stop users-service` | catálogo funcional: `GET /places?q=boxe` 200 no PC; na app, "boxe" → 4 locais, `@pamplona` mantém-se |
| Fechar à força e reabrir a app com o serviço parado | aviso "Não foi possível confirmar a tua sessão (sem ligação ao servidor)…" com **Tentar novamente**; `@pamplona` mantém-se; catálogo com 10 locais; **`session.bin` inalterado (162 bytes, 21:01)** — a falha temporária não apagou o refresh token |
| `docker compose start users-service` (healthy) + **Tentar novamente** | aviso desaparece; `@pamplona` mantém-se; `session.bin` regravado às 21:05 (novo refresh token da rotação) |
| Serviço parado de novo + `@pamplona` → **Terminar sessão** | barra passa a **Entrar**; aviso "Terminaste sessão neste dispositivo, mas não foi possível avisar o servidor…"; **`session.bin` apagado**; catálogo funcional |
| Final | `users-service` arrancado; os três serviços healthy; health 8081/8082 → 200; continua 1 conta em `users_db` |

Consequência esperada do último passo (docs/users-api.md §6.5): o refresh token dessa sessão continua válido no servidor até expirar, porque o logout não chegou ao serviço. Para voltar a usar a conta na app é preciso entrar de novo.

## 11. O meu perfil (etapa 5, continuação)

Aberto pelo menu `@username` → **O meu perfil** (só com sessão). Mostra o username, a descrição, os interesses, o email numa secção "Dados privados" (só o próprio o vê) e uma secção "Publicações" que diz explicitamente que ainda não estão disponíveis — não é mostrada uma lista vazia como se tivesse sido consultada.

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| `ProfileApi` + DTOs | `data/remote/ProfileApi.kt` | `GET /api/v1/me`, `PATCH /api/v1/me` (`{"description": …}`, `null` apaga), `PUT /api/v1/me/interests` |
| `NetworkProfileRepository` | `data/repository/ProfileRepository.kt` | pedidos com o access token do `SessionManager`; perante `401`, renovação partilhada e **uma** repetição; classifica `CATALOG_UNAVAILABLE`, `UNKNOWN_INTERESTS`, sessão terminada/por confirmar, rede e servidor |
| `ProfileViewModel` | `ui/feature/profile/ProfileViewModel.kt` | perfil do servidor e edições locais separados; gravações independentes |
| `ProfileScreen` | `ui/feature/profile/ProfileScreen.kt` | secções, botões "Guardar descrição" e "Guardar interesses", confirmação ao sair |

### Regras

- **Descrição e interesses gravam-se em separado**, cada um com o seu estado (a guardar, guardado, erro); uma falha num não bloqueia o outro. Botões desativados enquanto a gravação decorre (sem envios duplicados).
- **Edições locais nunca são sobrescritas** por respostas de carregamento: o perfil do servidor e as edições ficam em campos diferentes; cada carregamento tem um número de geração (respostas antigas são ignoradas) e uma gravação bem sucedida invalida carregamentos em curso. Uma resposta de gravação só limpa a edição se o utilizador não a tiver alterado entretanto.
- **Rotação e recriação:** as edições por guardar ficam no `SavedStateHandle`. Ao sair com alterações por guardar (seta ou gesto do sistema) aparece "Descartar alterações?" com **Descartar** / **Continuar a editar**.
- **Interesses:** chips com as modalidades do catálogo (`GET /api/v1/sports`, ids como `boxe`), no máximo 10, pela ordem escolhida. Os interesses guardados aparecem **sempre**, mesmo sem catálogo; sem nome do catálogo, o nome é feito a partir do id (`artes-marciais` → "Artes marciais") com uma nota a explicá-lo.
- **Catálogo indisponível:** aviso com **Tentar novamente**; só aparecem os interesses existentes, que podem ser removidos (e voltar a ser escolhidos), mas não se podem acrescentar novos. Nada é apagado por falha de carregamento. Se o users-service recusar modalidades novas com `CATALOG_UNAVAILABLE`, a edição mantém-se e o erro é explicado.
- **Descrição:** 0–300 caracteres (contador), espaços nas pontas removidos ao guardar, vazio → `null`.
- **Texto ampliado e teclado:** conteúdo em coluna com deslocamento e `imePadding`; chips em `FlowRow`, que passam à linha seguinte.

### Correção encontrada na verificação: ligações HTTP paradas

No emulador, o primeiro pedido ao perfil depois de alguns segundos sem atividade falhava com "Sem ligação…" e o seguinte funcionava. Causa: o servidor (Tomcat) fecha ligações paradas ao fim de 10 s, o OkHttp mantinha-as na pool durante minutos e, como o cliente do users-service tinha `retryOnConnectionFailure = false`, um pedido numa ligação já fechada falhava sem nova tentativa. A mesma causa podia fazer uma renovação de sessão parecer **ambígua** e deixar a sessão "por confirmar" sem motivo.

Correção em `di/AppContainer.kt` (sem alterar o backend):

- a pool de ligações do users-service descarta ligações paradas ao fim de **5 s** (antes dos 10 s do servidor);
- as chamadas de **sessão** (registo, login, refresh, logout) usam um cliente sem novas tentativas automáticas, como exige o contrato; as do **perfil** (`GET`, `PUT` e `PATCH` da descrição, idempotentes) usam o comportamento normal do OkHttp.

Confirmado no emulador: três aberturas do perfil, duas delas depois de 15 s sem pedidos, carregaram todas (antes falhava a primeira depois de uma pausa).

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest` — 64 testes, dos quais 17 desta funcionalidade (executados antes da correção do cliente HTTP, que só alterou `AppContainer.kt`, sem testes JVM; a app foi recompilada com `assembleDebug` depois da correção):

- `ProfileViewModelTest` (14): carregamento e nomes alternativos; erro e nova tentativa; resposta de carregamento não substitui edições; resposta antiga ignorada; guardar descrição (aparar, confirmar, envios repetidos ignorados); descrição vazia → `null` e texto demasiado longo não enviado; edição feita durante a gravação não se perde; falha da descrição não afeta os interesses; selecionar/remover/guardar pela ordem; limite de 10; catálogo indisponível mostra os guardados e só permite remover; `CATALOG_UNAVAILABLE` do servidor mantém a edição; confirmação ao sair e descartar; edições sobrevivem à recriação.
- `NetworkProfileRepositoryTest` (3): sem sessão não faz pedidos; `401` → uma renovação e uma repetição com o novo token; classificação de erros (`CATALOG_UNAVAILABLE`, `UNKNOWN_INTERESTS`, 5xx, rede).

### Verificação no emulador (2026-10-18, pelo assistente)

Feita com uma **conta temporária de verificação** criada pela própria app (a conta `pamplona` não foi usada nem alterada), apagada no fim; com `adb` e leitura da hierarquia do ecrã (`uiautomator`).

| Verificação | Resultado |
|---|---|
| Abrir "O meu perfil" pelo menu `@username` | username, descrição, interesses, email em "Dados privados", "Publicações: ainda não disponíveis" |
| Editar e guardar a descrição | "Descrição guardada." |
| Escolher Boxe e Ioga e guardar | "Interesses guardados.", "2 de 10" |
| Sair e reabrir o perfil; fechar e reabrir a app | descrição e 2 interesses mantidos (vindos do servidor); sair sem alterações não pede confirmação |
| Rotação com edição por guardar | texto mantido em horizontal e de novo em vertical |
| Voltar com edição por guardar | "Descartar alterações?"; **Continuar a editar** mantém o texto; **Descartar** sai |
| `catalog-service` parado | aviso de modalidades indisponíveis e nota dos nomes alternativos; interesses guardados mantidos ("Boxe" a partir do id); remover Ioga e guardar → "Interesses guardados."; guardar a descrição → "Descrição guardada." |
| `users-service` parado | guardar → "Sem ligação ao serviço de contas…" e a edição mantém-se; abrir o perfil → erro com **Tentar novamente**; a pesquisa continua (10 locais); depois de reiniciar, guardar e **Tentar novamente** funcionam |
| Final | serviços reiniciados e saudáveis; sessão da conta de verificação terminada e conta apagada; resta só `pamplona` |

Nota: o teclado do emulador duplica por vezes o primeiro carácter escrito por `adb shell input text` (ex.: `vverif_…`, `!!`); é uma limitação do método de teste, não da app.

### Revisão do cliente HTTP (2026-10-18)

A correção da pool de ligações foi revista e passou a estar em `di/HttpClients.kt`, com testes próprios:

| Cliente | Usado por | Novas tentativas automáticas do OkHttp | Porquê |
|---|---|---|---|
| `catalog()` | catálogo (só `GET`) | ligadas | leituras |
| `users(...)` | perfil (`GET /me`, `PATCH /me`, `PUT /me/interests`) e pessoas (`GET /users…`) | ligadas | leituras, e escritas idempotentes: repetir o mesmo `PATCH` da descrição ou o mesmo `PUT` (substitui a lista) deixa o mesmo resultado |
| `session(...)` | registo, login, refresh, logout (`POST`) | **desligadas** (inclui a repetição automática de respostas `408`) | um refresh repetido às cegas pode reenviar um token já consumido; o registo não é idempotente |

`users` e `session` partilham a pool de ligações que descarta ligações paradas ao fim de 5 s (o servidor fecha-as aos 10 s). `HttpClientsTest` verifica estas regras. **Os testes Android voltaram a ser executados depois desta alteração**: 68/68 (antes das funcionalidades da secção seguinte) e 80/80 no fim desta etapa.

## 12. Pesquisa de pessoas e perfis públicos (etapa 5, continuação)

Sem sessão. A pesquisa tem uma escolha **Locais | Pessoas** (botões segmentados). Cada modo tem o seu próprio texto, filtros e resultados: alternar não apaga nem repete nada, e o modo escolhido sobrevive à recriação.

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| `PeopleApi` + DTOs | `data/remote/PeopleApi.kt` | `GET /api/v1/users?q=&page=&size=` e `GET /api/v1/users/{userId}` (sem token) |
| `NetworkPeopleRepository` | `data/repository/PeopleRepository.kt` | resultados como `DataResult` (via `apiCall`, partilhado com o catálogo) |
| `PeopleSearchViewModel` | `ui/feature/people/PeopleViewModels.kt` | texto, pesquisa submetida, resultados e paginação próprios |
| `PublicProfileViewModel` | idem | perfil público + nomes das modalidades + regra de "Editar o meu perfil" |
| Ecrãs | `ui/feature/people/PeopleScreens.kt`, `ui/feature/search/SearchScreen.kt` | itens do modo Pessoas na lista de pesquisa; ecrã do perfil público (`user/{userId}`) |

### Regras

- **Pesquisa de pessoas:** submissão explícita (botão ou tecla), 2–50 caracteres (minúsculas, espaços removidos), sem pedido antes da primeira pesquisa (o contrato exige texto); paginação "Carregar mais" (20 por página); respostas de pesquisas antigas ignoradas; estados de carregamento, vazio ("Nenhuma pessoa encontrada."), erro com **Tentar novamente**.
- **Perfil público:** username, descrição (ou "Sem descrição."), interesses (ou "Sem interesses indicados.") e "Publicações: ainda não disponíveis". **Nunca** email nem outros dados privados — o modelo `PublicProfile` nem tem esses campos, mesmo quando é o próprio utilizador.
- **"Editar o meu perfil"** só aparece com sessão **ativa** do próprio utilizador (não aparece sem sessão, com outro utilizador ou com a sessão por confirmar) e leva a "O meu perfil".
- **Interesses legíveis sem catálogo:** nomes do catálogo quando disponível; caso contrário, nome feito a partir do id (`artes-marciais` → "Artes marciais") com uma nota.
- **Voltar** do perfil público regressa à lista de pessoas com a pesquisa e os resultados intactos (o estado fica no ViewModel do ecrã de pesquisa).
- **Falha do users-service** só afeta o modo Pessoas ("Sem ligação ao serviço de contas. A pesquisa de locais continua disponível."); a pesquisa de locais não depende dele.

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest` — **80 testes, 0 falhas**, executados no código final desta etapa. Novos:

- `HttpClientsTest` (4): sessão sem novas tentativas; catálogo e users-service com; pool partilhada entre sessão e perfil, separada do catálogo; tempos de espera.
- `PeopleViewModelsTest` (11): sem pedido antes de pesquisar; validação 2–50 e normalização; "Carregar mais" até ao fim; resposta antiga ignorada; erro, nova tentativa e lista vazia; pesquisa reposta ao recriar; perfil público com nomes do catálogo e alternativos; interesses legíveis com o catálogo indisponível; "Editar o meu perfil" só com sessão ativa do próprio; pessoa inexistente e nova tentativa; **privacidade** (JSON com `email` não chega ao modelo; `PublicProfile` só tem `userId`, `username`, `description`, `interests`).
- `SearchViewModelTest` (+1): alternar para Pessoas e voltar mantém a pesquisa de locais, sem repetir pedidos; modo reposto ao recriar.

### Verificação no emulador (2026-10-18, pelo assistente, sem sessão)

| Verificação | Resultado |
|---|---|
| Barra superior com **Locais / Pessoas** | presente |
| Locais: "boxe" | 4 locais |
| Pessoas: indicação inicial; "pamp" | "Escreve parte de um nome de utilizador…"; "1 pessoa encontrada", `@pamplona` |
| Alternar Locais ↔ Pessoas (duas vezes) | cada modo mantém o seu texto e resultados ("boxe"/4 locais; "pamp"/`@pamplona`) |
| Abrir `@pamplona` | "Sem descrição.", "Sem interesses indicados.", "Publicações… ainda não estão disponíveis"; sem email, sem "Dados privados", sem "Editar o meu perfil" |
| Voltar | lista de pessoas com "pamp" e o resultado |
| `users-service` parado | Pessoas: "Sem ligação ao serviço de contas. A pesquisa de locais continua disponível." + **Tentar novamente**; Locais: nova pesquisa (filtro Boxe) → 4 locais |
| Reiniciar o serviço + **Tentar novamente** | `@pamplona` de novo; três serviços saudáveis no fim |

Não verificado na interface (coberto pelos testes automáticos): "Editar o meu perfil" no próprio perfil com sessão, e interesses com nomes alternativos num perfil público (a conta `pamplona` não tem interesses e não foi alterada; não foi criada conta temporária nesta etapa).
