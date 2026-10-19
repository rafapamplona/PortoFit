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

## 13. Seguir utilizadores e listas de seguidores/seguidos (etapa 5, continuação)

Contrato: [users-api.md](users-api.md) §3.8–3.10. Sem feed nem notificações (etapas seguintes).

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| `PeopleApi` | `data/remote/PeopleApi.kt` | perfil público com token opcional; `PUT`/`DELETE /users/{userId}/follow`; `GET …/followers` e `…/following` |
| `NetworkPeopleRepository` | `data/repository/PeopleRepository.kt` | com sessão, pede o perfil com o access token (para `followedByMe`); `setFollowing` com renovação única após `401`; erros como `FollowError` |
| `PublicProfileViewModel` | `ui/feature/people/PeopleViewModels.kt` | contagens, relação e estado do botão (`FollowButton`), ação em curso/falhada/por confirmar (`FollowAction`) |
| `FollowListViewModel` | idem | lista paginada de seguidores ou seguidos (20 por página) |
| Ecrãs | `ui/feature/people/PeopleScreens.kt` | botão e contagens no perfil; `FollowListScreen` (`user/{userId}/list/{FOLLOWERS|FOLLOWING}`) |

### Regras

- **Relação só a confirmada pelo servidor.** "Seguir" / "Deixar de seguir" depende de `followedByMe` devolvido pelo servidor. Se, com sessão, a resposta não trouxer a relação, não há botão: aparece "Não foi possível confirmar se segues esta pessoa." + **Tentar novamente**.
- **Depois de seguir/deixar de seguir com sucesso** (o servidor responde `204` sem corpo), o perfil é pedido de novo para confirmar a relação e as contagens. Se esse pedido falhar, mantém-se o último estado confirmado com "O pedido foi aceite, mas não foi possível confirmar o resultado…" + **Atualizar**. O novo estado nunca é inventado.
- **Falha** (rede, servidor, sessão): o botão e as contagens ficam como estavam, com mensagem, e um novo toque repete a ação (`PUT`/`DELETE` são idempotentes).
- **Sem pedidos duplicados:** enquanto a ação está em curso o botão fica desativado e o ViewModel ignora novos toques.
- **Visitante:** vê as contagens e "Seguir" com "Entra na tua conta para seguir esta pessoa.". O toque abre o login sem pedir nada ao servidor. Depois de entrar (ou de criar conta a partir do login), a app regressa ao perfil, que é recarregado com a sessão; seguir exige um **novo toque**.
- **Próprio perfil:** sem botão de seguir (continua "Editar o meu perfil").
- **Estado atualizado ao regressar:** ao voltar a um perfil (de outro perfil, de uma lista ou do login) a relação e as contagens são pedidas de novo sem esconder o conteúdo. Uma falha nessa atualização mantém o perfil. Respostas de pedidos antigos são ignoradas.
- **Listas:** tocar nas contagens abre "Seguidores" ou "A seguir" (públicas), com "Carregar mais", lista vazia, erro e **Tentar novamente**. Abrir um perfil a partir da lista e voltar mantém a lista (estado no ViewModel da entrada de navegação).
- **Login regressa a quem o abriu:** `onDone` do login e do registo faz `popBackStack()` (o registo substitui o login na pilha). Assim, a partir da pesquisa volta-se à pesquisa e a partir de um perfil volta-se ao perfil.

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest`: **100 testes, 0 falhas**, executados depois da última alteração ao código. Novos:

- `PeopleViewModelsTest` (+13):
  - visitante com contagens, cujo toque não faz pedidos;
  - seguir e deixar de seguir conforme a relação confirmada (contagens atualizadas);
  - toques repetidos durante o pedido resultam num só pedido;
  - falha que mantém o estado e permite repetir;
  - ação aceite sem confirmação, seguida de "Atualizar";
  - sem botão no próprio perfil nem com a relação por confirmar;
  - recarga depois de entrar e de terminar sessão, sem seguir automaticamente;
  - atualização ao regressar ao ecrã (uma falha mantém o perfil);
  - resposta antiga do perfil ignorada;
  - listas: paginação até ao fim, pedido certo para seguidos, erro e nova tentativa, falha ao carregar mais que mantém a lista, resposta antiga ignorada.

  O teste de privacidade passou a admitir as contagens e `followedByMe`, e continua a excluir o email.
- `NetworkPeopleRepositoryTest` (7):
  - visitante sem cabeçalho `Authorization`;
  - com sessão envia o token, e um `401` renova uma vez e repete;
  - seguir sem sessão não faz pedidos;
  - `PUT`/`DELETE` com token;
  - `401` ao seguir renova e repete;
  - erros classificados (`CANNOT_FOLLOW_SELF`, `404`, `503`, rede);
  - listas públicas convertidas em resumos.

`assembleDebug` e `lintDebug` concluídos, sem avisos novos (os existentes são versões mais recentes e cores não usadas, não alterados).

### Verificação no emulador (2026-10-19, pelo assistente)

Duas contas temporárias criadas por HTTP (A e B, palavras-passe aleatórias não mostradas). B tem descrição e interesses `boxe`, `corrida`. A conta `pamplona` não foi usada nem alterada. No fim, as duas contas foram apagadas (as relações apagam-se em cascata) e só `pamplona` ficou. O volume foi preservado e os três serviços ficaram saudáveis.

| Verificação | Resultado |
|---|---|
| Visitante abre o perfil de B | "0 seguidores", "0 a seguir", **Seguir** + "Entra na tua conta para seguir esta pessoa." |
| Visitante toca **Seguir** | abre "Entrar"; nenhum pedido de seguir |
| Entrar como A | regressa ao perfil de B: **Seguir**, 0 seguidores (sem ação automática), sem o aviso de visitante |
| Toque em **Seguir** | "1 seguidor", **Deixar de seguir**; no servidor, seguidores de B = [A] |
| "1 seguidor" → lista → A → Voltar → Voltar | lista "Seguidores" com A; perfil de A com "Editar o meu perfil" e sem botão de seguir; voltar mantém a lista e depois o perfil de B |
| B → seguidores → A → "1 a seguir" → B (2.ª vez) → **Deixar de seguir** → voltar 4× | o perfil original de B atualizou ao regressar: **Seguir**, 0 seguidores |
| Lista de seguidores vazia | "Ainda ninguém segue esta pessoa." |
| "Editar o meu perfil" (com sessão) | abre "O meu perfil" (descrição e interesses) |
| `catalog-service` parado, perfil de B | "Boxe", "Corrida" + nota de nomes alternativos; **Seguir** funciona ("1 seguidor") |
| `users-service` parado, **Deixar de seguir** | "Sem ligação ao serviço de contas. Nada foi alterado; tenta novamente."; mantém "1 seguidor" e **Deixar de seguir** |
| Serviço reiniciado, novo toque | **Seguir**, 0 seguidores (a sessão sobreviveu ao reinício) |

Não verificado na interface (coberto pelos testes automáticos): duplo toque rápido, resultado aceite mas não confirmado, relação por confirmar e "Carregar mais" nas listas (eram precisas mais de 20 contas).

## 14. Experiências (etapa 6, E1, sem fotografias)

Contrato: [experiences-api.md](experiences-api.md) (§4 endpoints, §5 idempotência, §7 avaliações). Sem fotografias, comentários, gostos, feed nem dashboard.

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| `ExperiencesApi` + DTOs | `data/remote/ExperiencesApi.kt` | listar (por local ou autor, `recent`/`stars`), detalhe, publicar com `Idempotency-Key`, editar, eliminar |
| `NetworkExperiencesRepository` | `data/repository/ExperiencesRepository.kt` | leituras públicas (`DataResult`); escritas com o access token e uma renovação após `401` (na publicação, com a mesma chave); classificação dos resultados em `PublishOutcome` e `MutationResult` |
| `ExperienceChanges` | idem | avisos de publicação/edição/eliminação entre ecrãs (o local e os perfis na pilha atualizam-se) |
| `ExperienceRules` | `data/model/ExperienceModels.kt` | as validações do contrato: texto normalizado de 1 a 2000 pontos de código, sem só espaços nem caracteres de controlo; estrelas de 1 a 5 |
| `ExperienceListLoader` + componentes | `ui/feature/experience/ExperienceList.kt` | lista paginada (10 por página, "Carregar mais", respostas antigas ignoradas, sem repetições pelo id), cartão, estrelas, ordenação |
| `PublishExperienceViewModel` | `ui/feature/experience/PublishExperienceViewModel.kt` | formulário e tentativa lógica (chave, conteúdo enviado, data) |
| `ExperienceDetailViewModel` | `ui/feature/experience/ExperienceDetailViewModel.kt` | detalhe, estado atual da atividade, editar texto/estrelas e eliminar com confirmação (só o autor) |
| Ecrãs | `ExperienceScreens.kt`, `place/PlaceDetailScreen.kt`, `profile/ProfileScreen.kt`, `people/PeopleScreens.kt` | publicar (`place/{id}/publish/{offeringId}`), detalhe (`experience/{id}`), secção "Experiências" no local, "Publicações" nos perfis |

`EXPERIENCES_BASE_URL` só em debug: `http://10.0.2.2:8083/`, que a configuração de rede de debug já permite. Em release fica vazio e a secção mostra "não disponível nesta versão". O cliente HTTP tem uma pool própria de ligações curtas (5 s), como o do users-service, e mantém as novas tentativas automáticas do OkHttp: as leituras, o PATCH e o DELETE são idempotentes, e a publicação leva sempre a chave.

### Regras

- **Local:** experiências paginadas com estrelas (★★★★☆, com descrição para leitores de ecrã), data, `@autor` (abre o perfil) e atividade; o toque abre o detalhe. A ordenação é "Mais recentes" ou "Melhor avaliação do local", reposta ao recriar. Não há contadores de gostos nem comentários.
- **Estados do catálogo:**
  - local fictício: indicação "Dados de demonstração";
  - local retirado: aviso de que as experiências antigas continuam legíveis mas não é possível publicar;
  - atividade retirada: indicação "Atividade retirada do catálogo" e sem botão de publicar;
  - no detalhe da experiência, se o catálogo indicar que a atividade foi retirada, aparece um aviso de registo histórico (uma falha do catálogo não afeta o detalhe).
- **Publicar:** "Partilhar experiência" só nas atividades ativas.
  - Sem sessão, abre o login e regressa ao local, **sem publicar**; com sessão, abre o formulário.
  - Se a sessão terminar no formulário, "Entrar" regressa ao formulário com o texto e as estrelas.
  - Envios repetidos durante o pedido são ignorados e o botão fica desativado.
  - Idempotência, conflitos e prazo de 6 dias como em [experiences-api.md §5](experiences-api.md#5-publicação-sem-duplicados-idempotência): resultado desconhecido → conteúdo bloqueado e "Tentar novamente" com a mesma chave; alterar exige "Descartar esta tentativa" com confirmação.
- **Detalhe:** "Editar" e "Eliminar" só com sessão do autor; o servidor decide sempre (`403` para outra pessoa).
  - A edição altera só texto e estrelas, envia só o que mudou e fica no `SavedStateHandle`; a atividade não é editável (com a explicação).
  - Eliminar pede confirmação e, depois, regressa ao ecrã anterior.
- **Atualização após alterações:** publicar, editar ou eliminar avisa o `ExperienceChanges`.
  - O detalhe do local pede de novo a lista e o local (avaliações). Se o catálogo falhar nessa atualização, mantém o local anterior com o aviso "Não foi possível atualizar a avaliação…"; a publicação, já confirmada, não é posta em causa.
  - Os perfis do autor recarregam as "Publicações".
- **Perfis:** "Publicações" no próprio perfil (pelo `userId` da sessão) e no público (pelo `userId` do perfil), em vez da indicação de funcionalidade indisponível.
- **Falhas isoladas:** se o experiences-service falhar, só as secções de experiências mostram o erro com "Tentar novamente"; o catálogo e o perfil continuam visíveis.

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest`: **140 testes, 0 falhas** (eram 100), executados depois da última alteração. `assembleDebug` e `lintDebug` concluídos. Avisos novos do lint: "autor" assinalado como erro ortográfico (o dicionário é inglês) e o contador de caracteres sugerido como plural, como o já existente da descrição.

- `NetworkExperiencesRepositoryTest` (7):
  - listas públicas e conversão (data com microssegundos, local fictício);
  - data inválida → erro inesperado;
  - publicar sem sessão não faz pedidos;
  - token e chave enviados, e um `401` renova e repete com a mesma chave;
  - `200` com `Idempotent-Replayed`;
  - resultados desconhecidos (rede, `503`, `500`, `2xx` ilegível) e recusas definitivas (`CATALOG_UNAVAILABLE`, validação, atividade inexistente, de outro local ou retirada, `429` com `Retry-After`, `409` dos dois tipos);
  - editar e eliminar com token e erros (`403`, `404`, rede, `503`).
- `PublishExperienceViewModelTest` (12):
  - validações do contrato (vazio, só espaços, 2000 emojis aceites, 2001, controlo, estrelas);
  - publicação com texto normalizado e aviso às listas;
  - envios repetidos ignorados;
  - resultado desconhecido: conteúdo bloqueado e repetição com a mesma chave e conteúdo;
  - tentativa por confirmar restaurada depois da recriação do processo, também a meio do pedido;
  - descartar → conteúdo editável e chave nova;
  - recusa definitiva descarta a chave;
  - chave com mais de 6 dias não reutilizada;
  - conflitos `409`;
  - sem sessão não envia e, depois de entrar, só com novo toque;
  - sessão terminada mantém o formulário.
- `ExperienceDetailViewModelTest` (10):
  - detalhe público com o estado da atividade; falha do catálogo sem efeito;
  - experiência inexistente;
  - autorização da interface (outra pessoa, sem sessão, autor com sessão por confirmar);
  - editar envia só o que mudou e avisa as listas; sem alterações não há pedido; texto inválido não é enviado;
  - edição restaurada depois da recriação;
  - falha mantém a edição e não duplica pedidos;
  - eliminar com confirmação e aviso; eliminação recusada.
- `PlaceDetailViewModelTest` (+7):
  - paginação e ordenações;
  - ordenação reposta ao recriar;
  - falha só na secção de experiências;
  - resposta antiga ignorada;
  - alteração neste local atualiza a lista e as avaliações (e noutro local não);
  - falha da atualização mantém o local com o aviso;
  - publicar só em atividades ativas.
- `ProfileViewModelTest` (+2), `PeopleViewModelsTest` (+1): publicações pelo `userId`, falha isolada do perfil e atualização após alterações do autor.
- `CatalogMappersTest` (+1): estado do local e das atividades (valor desconhecido → `RETIRED`; sem campo → `ACTIVE`).

### Verificação (2026-10-19, pelo assistente)

**Emulador não usado:** havia cerca de 0,56 GB de memória livre, com os quatro contentores em execução. Na verificação anterior, o emulador deixou menos de 0,45 GB e o VS Code já deixou de responder antes por falta de memória. A interface fica por verificar no emulador.

O fluxo foi verificado com os **mesmos pedidos que a app faz**, nos contentores, com duas contas temporárias (palavras-passe aleatórias, não mostradas; apagadas no fim, com as suas experiências; `pamplona` intacta):

| Verificação | Resultado |
|---|---|
| A publica 4★ na Ribeira; repetição com a mesma chave e conteúdo | `201`; `200`, `Idempotent-Replayed: true`, mesmo id |
| Mesma chave com outro conteúdo (a app impede-o) | `409 DIFFERENT_CONTENT` |
| Lista do local (`recent`) e do perfil de A | 1 experiência em cada; catálogo: local 4,0 (1) |
| B publica 2★; lista por estrelas | A (4), B (2); local 3,0 (2) |
| B edita a de A / A edita para 5★ | `403` / `200` com `editedAt`; local 3,5 (2) |
| A elimina; repetição da chave dessa publicação | `204`, local 2,0 (1); `409 EXPERIENCE_DELETED` |
| Kickboxing retirado (temporariamente, reposto no fim) | detalhe do local com a atividade `RETIRED`; não aparece em `sport=kickboxing`; publicar → `400 OFFERING_RETIRED` |
| experiences-service parado | pesquisa e detalhe do catálogo `200` (~0,5 s) com avaliações `UNAVAILABLE`; perfil público `200`; experiências sem ligação (a app mostra o erro só nessa secção) |
| Reinício | avaliações de volta (2,0 (1)); quatro serviços saudáveis |

**Verificação na interface (2026-10-19, pelo utilizador, no emulador):**

| Percurso | Resultado |
|---|---|
| Publicar uma experiência com 4 estrelas | publicada |
| Consultá-la no local | visível na secção "Experiências" |
| Editar para 3 estrelas | avaliação do local atualizada |
| Consultar a publicação no próprio perfil | visível em "Publicações" |
| Eliminar | desaparece das listas; a avaliação volta a "Sem avaliações" |

Não verificado na interface (coberto pelos testes automáticos e pelos pedidos HTTP acima): resultado de publicação desconhecido e repetição, regresso do login sem publicar, estados retirados e secção de experiências com o serviço parado.

## 15. Fotografias (etapa 7, E2)

Contrato: [experiences-api.md §12](experiences-api.md#12-etapa-e2-fotografias). Fotografia opcional, no máximo uma por experiência. Sem comentários, gostos nem feeds.

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| `PhotoDrafts` / `AndroidPhotoDrafts` | `data/photo/` | Prepara a fotografia, fora da thread principal: lê as dimensões antes de descodificar e descodifica já reduzida (`ImageDecoder.setTargetSize` no Android 9+; `BitmapFactory` com `inSampleSize` no 8). Aplica a orientação EXIF e grava um JPEG (qualidade 85, lado maior até 1600 px, cada lado ≥ 200 px) em `cache/photo-drafts/`. Cria o ficheiro de destino da câmara e a URI do FileProvider |
| `PhotoSizing` | `data/photo/PhotoDrafts.kt` | Regras puras: tamanho final segundo os limites do servidor, subamostragem e nomes de ficheiros válidos |
| `PhotoDraftController` | `ui/feature/experience/` | Estado da fotografia do rascunho (nenhuma, a preparar, pronta, em falta) partilhado pela publicação e pela edição; captura em curso; limpeza dos ficheiros |
| `ExperiencesRepository` | `data/repository/` | `uploadPhoto` (multipart, campo `file`, com token e renovação após `401`); `mediaId` na publicação; `editBody` com `PhotoChange` (Keep, Remove, Replace) |
| `PhotoDraftSection`, `ExperiencePhotoView` | `ui/feature/experience/ExperiencePhotos.kt` | Photo Picker e `TakePicture`, pré-visualização, remover; fotografia nas listas e no detalhe (Coil, com espaço reservado e erro isolado) |
| `PhotoUrls` | `ui/components/PhotoUrls.kt` | Resolve `/api/v1/media/{id}` com o URL configurado (`EXPERIENCES_BASE_URL`), fornecido por `CompositionLocal` na `MainActivity` |

**Dependência nova:** Coil 3.3.0 (`coil-compose`, `coil-network-okhttp`), com o cliente OkHttp da app; não altera versões existentes (OkHttp continua 4.12.0, Kotlin 2.2.10). A câmara usa a aplicação do sistema e não acrescenta CameraX nem a permissão `CAMERA`.

### Regras

- **Escolher:** Photo Picker (`PickVisualMedia`, só imagens), sem acesso amplo à galeria. A imagem é logo preparada para a pasta privada da app, por isso a autorização temporária da URI escolhida deixa de ser precisa. São aceites os formatos que o aparelho descodifica (JPEG, PNG, WebP, HEIF…); o servidor recebe sempre JPEG.
- **Tirar:** "Tirar fotografia" no formulário abre a aplicação de câmara do sistema (`TakePicture`) com uma URI `content://` do FileProvider. Só a pasta `cache/photo-drafts/` é partilhada, com permissão temporária. A fotografia é em resolução completa (não a miniatura) e é preparada como as da galeria, e a captura original é apagada.
  - Cancelada → "Nenhuma fotografia foi tirada."
  - Sem câmara → aviso, com a galeria e a publicação sem fotografia disponíveis.
  - Em nenhum dos casos se perdem o texto, as estrelas ou a fotografia anterior.
- **SavedStateHandle:** só nomes de ficheiros gerados pela app (o preparado, o de captura em curso), o `mediaId` já enviado e o rascunho a que pertence; nunca bitmaps, ficheiros nem URIs de terceiros.
  - Uma captura em curso sobrevive à recriação do processo enquanto a câmara está aberta.
  - Se o ficheiro preparado desaparecer (ex.: cache limpa), aparece "A fotografia escolhida já não está disponível…": o texto mantém-se e é possível escolher de novo ou remover.
- **Publicar:** primeiro "A enviar a fotografia…" (`POST /media`), e só depois de sucesso o `mediaId` entra na publicação ("A publicar…"). Envios repetidos são ignorados e o conteúdo fica bloqueado durante o envio.
  - **Falha do envio da fotografia:** "A fotografia não foi enviada e a experiência não foi publicada", com "Publicar sem fotografia"; nada é criado e o rascunho mantém-se.
  - **Falha ao criar a experiência:** são as mensagens da E1.
  - **Fotografia já enviada:** reaproveitada nas novas tentativas depois de uma recusa definitiva. É enviada de novo só se o servidor responder `MEDIA_NOT_USABLE`, se a fotografia mudar, ou depois de descartar uma tentativa.
- **Resultado ambíguo:** a tentativa guarda a chave, o conteúdo **e o `mediaId`**. "Tentar novamente" repete exatamente o mesmo pedido, sem novo envio da fotografia, e a fotografia dessa tentativa não pode ser mudada nem removida. Para mudar, segue-se o descarte com confirmação já definido.
- **Editar:** "Escolher da galeria", "Tirar fotografia", "Remover fotografia"/"Descartar a nova" e "Manter a atual". O PATCH segue o contrato:
  - campo `mediaId` ausente: mantém (nunca se envia `null` por omissão);
  - `null`: remove;
  - novo id: substitui, depois de enviada a fotografia nova.

  A falha do envio da fotografia nova não faz PATCH e mantém a edição. Um PATCH falhado reaproveita o `mediaId` já enviado (salvo `MEDIA_NOT_USABLE`).
- **Mostrar:** as fotografias aparecem nas listas (corte 16:9) e no detalhe (proporção real), com espaço reservado enquanto carregam e "Fotografia indisponível" se falharem. O texto aparece sempre. O URL completo resolve-se com o endereço configurado; os modelos só têm o caminho relativo, e só caminhos `/api/v1/media/{uuid}` do mesmo serviço são aceites.
- **Limpeza:** só ficheiros da app em `cache/photo-drafts/` (nunca fotografias da galeria):
  - ao publicar ou guardar;
  - ao sair do ecrã;
  - ao trocar ou remover a fotografia;
  - ao arrancar a app, os esquecidos há mais de 3 dias.

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest`: **168 testes, 0 falhas** (eram 140), executados de novo, sem cache, depois da última alteração. `assembleDebug` e `lintDebug` concluídos.

Avisos novos do lint: `ExifInterface` da plataforma em vez do da AndroidX. Só é usado no Android 8, onde é suficiente, e trocá-lo acrescentaria uma dependência.

- **`PublishExperienceViewModelTest` (+11):**
  - fotografia enviada antes de publicar e `mediaId` só depois do sucesso, com os ficheiros apagados no fim;
  - falha do envio sem publicar nem perder o rascunho, e "Publicar sem fotografia";
  - envios repetidos ignorados durante o envio, com a fotografia bloqueada;
  - resultado ambíguo: mesma chave, conteúdo e `mediaId`, sem novo envio, também depois da recriação do processo;
  - reaproveitamento depois de uma recusa definitiva, e novo envio depois de `MEDIA_NOT_USABLE` ou de descartar;
  - fotografia em falta depois da recriação (aviso, texto mantido, nova escolha) e antes de enviar;
  - captura que sobrevive à recriação, cancelamento e câmara indisponível;
  - falha a preparar que mantém a anterior;
  - trocar de fotografia apaga a anterior e obriga a novo envio;
  - sair do ecrã apaga os ficheiros temporários.
- **`ExperienceDetailViewModelTest` (+5):**
  - substituir: envio e depois PATCH com o novo id;
  - remover → `null`, manter → campo ausente, sem alterações → sem pedido;
  - falha do envio sem PATCH;
  - PATCH falhado reaproveita o `mediaId` (salvo `MEDIA_NOT_USABLE`);
  - fotografia nova restaurada depois da recriação, e cancelar apaga o ficheiro.
- **`NetworkExperiencesRepositoryTest` (+6):**
  - corpo do PATCH nos três casos;
  - PATCH com `MEDIA_NOT_USABLE`;
  - `mediaId` na publicação (e ausente sem fotografia);
  - fotografia convertida com o caminho relativo;
  - multipart com o campo `file`, token, `401` renovado e erros (`400`, `413`, `429` com `Retry-After`, `503`, rede);
  - envio sem sessão não faz pedidos.
- **`PhotoSizingTest` (4) e `PhotoUrlsTest` (2):** tamanho final, mínimos e panorâmicas, subamostragem, nomes aceites; resolução com o URL configurado e recusa de endereços absolutos, outros caminhos e falta de configuração.

Não têm testes JVM (dependem do Android): a descodificação real (`ImageDecoder`/`BitmapFactory`), a orientação no aparelho, o FileProvider e os ecrãs.

### Verificação (2026-10-19, pelo assistente)

**Emulador não usado:** havia ~0,5 GB de memória livre e o `Pixel_9` precisa de 4 GB (diagnóstico do ambiente). Verificaram-se por HTTP, nos contentores, **os pedidos que a app faz**, com contas temporárias (apagadas no fim; `pamplona`, o registo de experiência já existente e os volumes preservados):

| Verificação | Resultado |
|---|---|
| Envio multipart com nome `foto.jpg` e tipo `image/jpeg` (conteúdo PNG) | `201`; o servidor decide pelo conteúdo |
| Publicar com `mediaId` | `201`, `photo.url` relativo |
| URL resolvido com o endereço configurado + caminho | `200`, JPEG |
| Envio de um JPEG preparado | `201` |
| PATCH `{"stars":3}` (sem `mediaId`) | fotografia mantida |
| PATCH `{"mediaId":"<novo>"}` | substituída; URL antigo `404` |
| PATCH `{"mediaId":null}` | removida; texto intacto; URL `404` |
| Eliminar | `204` |

As duas primeiras execuções receberam `503 CATALOG_UNAVAILABLE` (`HttpTimeoutException`): o catálogo estava parado há 2 h e, com ~0,2 GB livres, a sua memória tinha ido para o disco. É o arranque a frio já documentado. A terceira, com o catálogo a responder em ~15 ms, passou. As fotografias pendentes dessas tentativas foram marcadas como eliminadas e a limpeza automática do serviço apagou os ficheiros e as linhas (volume vazio no fim).

**Pendente:** verificar no emulador ou num aparelho:
- galeria;
- captura pela câmara, cancelamento e rotação com uma fotografia escolhida;
- publicar, consultar, substituir e remover;
- falha de envio na interface;
- orientação de uma fotografia real tirada ao alto.

A câmara do emulador é simulada (cena virtual ou webcam), o que chega para testar o percurso. A orientação EXIF real e a câmara de um fabricante concreto só se verificam num telemóvel.
