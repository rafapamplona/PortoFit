# PortoFit — App Android

> Estado: etapa 4 — pesquisa e detalhe do catálogo (públicos); etapa 5 (app) — registo, login, sessão, logout, "O meu perfil", pesquisa de pessoas, perfis públicos e seguir; etapas 6–9 — experiências (E1, §14), fotografias (E2, §15), comentários, gostos e visualizações (E3, §16), Descobrir e Estatísticas (E4, §17); etapa 11 — listas pessoais (§18); etapa 12 — mensagens e partilha (§19). Sem mapas nem GPS.
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

- **Build falha com `JdkImageTransform` / `jlink.exe does not exist`:** o Gradle reutilizou um daemon arrancado pela extensão Java do VS Code no seu JRE embutido (sem `jlink`). O VS Code não pode ser cliente Gradle deste projeto: servidor Java da Red Hat em modo `LightWeight` e extensão "Gradle for Java" desativada no espaço de trabalho (ver [infraestrutura.md §9](infraestrutura.md#9-jdk-do-gradle)). Se voltar a acontecer, ver nessa secção como identificar quem arrancou o daemon e correr `.\gradlew.bat --stop`.
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

**Verificação na interface (2026-10-19, pelo utilizador, no emulador):**

| Percurso | Resultado |
|---|---|
| Captura pela câmara e pré-visualização | fotografia tirada e mostrada no formulário |
| Rotação com a fotografia escolhida | rascunho e fotografia mantidos |
| Publicar e consultar | experiência com fotografia no local e no detalhe |
| Substituir pela galeria | nova fotografia mostrada |
| Remover a fotografia | removida, texto mantido |

**Pendente:** orientação de uma fotografia tirada numa câmara física (só verificável num telemóvel).

## 16. Comentários, gostos e visualizações (etapa 8, E3)

Contrato: [experiences-api.md §13](experiences-api.md#13-etapa-e3-comentários-gostos-e-visualizações). Sem feeds, dashboard nem notificações (E4 e seguintes).

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| `ExperiencesApi` | `data/remote/` | `GET …/comments`, `POST …/comments` (com `Idempotency-Key`), `PATCH`/`DELETE /comments/{id}`, `PUT`/`DELETE …/like`, `POST …/views` (token **ou** `X-Viewer-Key`); detalhe com token opcional |
| `ExperiencesRepository` | `data/repository/` | `CommentOutcome` (publicado, por confirmar, recusado, já publicado, publicado e eliminado), `LikeResult`/`LikeError` (com `UNCONFIRMED` para resultados desconhecidos), `registerView` com uma só tentativa; `Experience` com `likesCount`, `commentsCount` e `likedByMe: Boolean?` |
| `ViewerKeys` / `PreferencesViewerKeys` | `data/repository/ViewerKeys.kt` | Chave aleatória da instalação (UUID) para visitantes, em `viewer_key.xml`, excluída das cópias de segurança e das transferências entre aparelhos |
| `CommentsController` | `ui/feature/experience/` | Lista paginada, publicar com chave por tentativa, editar e eliminar os próprios; estado independente do conteúdo da experiência |
| `ExperienceDetailViewModel` | `ui/feature/experience/` | Gosto (`LikeButton`: entrar, próprio, por confirmar, gostar, retirar), reconciliação, visualização única, avisos de contagens |
| `LikeSection`, `CommentsSection` | `ui/feature/experience/ExperienceInteractions.kt` | Contagens, botão de gosto, lista, formulário, edição e confirmação de eliminação |
| `ExperienceListLoader`, `ExperienceSortSelector`, `InteractionCounts` | `ui/feature/experience/ExperienceList.kt` | Quatro ordenações; "N gostos · N comentários" nos cartões; contagens atualizadas no item |

### Regras

- **Comentários:** lista paginada (20 por página, mais antigos primeiro), com carregar, vazio, erro e "Tentar novamente" próprios. Uma falha nunca esconde o texto nem a fotografia da experiência.
  - **Publicar exige sessão.** Sem sessão: "Entra na tua conta para comentar. O texto fica guardado." e **Entrar**. O login regressa ao detalhe e nada é enviado sozinho; o texto continua no campo.
  - **Validação** como no servidor: 1 a 1000 caracteres (pontos de código), só espaços recusado, caracteres de controlo recusados, contador visível.
  - **Idempotência:** a chave é gerada no primeiro envio e guardada com o texto no `SavedStateHandle` **antes** do pedido.
    - Resultado desconhecido (rede, `5xx`, resposta ilegível): texto e chave bloqueados; "Tentar novamente" repete o mesmo pedido. Há também "Descartar esta tentativa", com confirmação.
    - Sobrevive à rotação e à recriação do processo. Chaves com mais de 6 dias não são reutilizadas.
    - O `POST` nunca é repetido automaticamente.
    - Recusa definitiva (`400`, `429` com os minutos de espera, sessão terminada): a chave é descartada e o texto mantém-se para corrigir. `409 DIFFERENT_CONTENT` e `COMMENT_DELETED` são explicados.
  - **Editar e eliminar:** só aparecem nos comentários cujo `authorId` é o da sessão (o servidor decide sempre). Eliminar pede confirmação.
    - Um `PATCH` falhado mantém o texto editado e o erro; o `PATCH` é idempotente, por isso repetir é seguro.
    - Um comentário que já não existe (`404`) sai da lista.
  - **Depois de um sucesso:** a lista é pedida de novo e o detalhe também, para as contagens confirmadas.
- **Gostos:**
  - **Contagem e estado:** os do último pedido confirmado pelo servidor. Durante um pedido o botão fica desativado (sem envios duplicados). Depois de `204`, o detalhe é lido de novo para a contagem exata.
  - **Visitante:** "Entrar para gostar". Ao regressar do login, o detalhe é lido de novo (agora com `likedByMe`), mas o gosto **não** é enviado.
  - **Autor:** não há botão ("É a tua experiência: os gostos são de outras pessoas.").
  - **`likedByMe` ausente com sessão** (ex.: sessão por confirmar): estado "por confirmar", com "Atualizar"; nunca é convertido em `false`.
  - **Resultado desconhecido:** o estado mostrado continua o último confirmado, com "Não foi possível confirmar o gosto" e **Atualizar**, que reconcilia com o servidor. Não há repetição automática.
- **Listas** (local, o meu perfil, perfil público):
  - cada cartão mostra as estrelas (do local/atividade) e, à parte, "N gostos · N comentários";
  - quatro ordenações: **Mais recentes**, **Melhor avaliação do local**, **Mais gostadas** e **Mais interações (gostos e comentários)**. Mudar de ordenação recomeça na primeira página; a escolha fica no `SavedStateHandle`;
  - **ao voltar do detalhe**, as contagens confirmadas atualizam o item já carregado, sem recarregar a lista nem perder a pesquisa, os filtros ou as páginas. A ordem só muda no próximo carregamento.
- **Visualizações:**
  - registadas ao abrir o detalhe **com sucesso**, em segundo plano;
  - no máximo uma por abertura: a marca fica no `SavedStateHandle` antes do pedido, por isso recomposições, rotação, recriação, "Tentar novamente" e leituras de atualização não repetem;
  - o autor não envia (espera-se que a sessão esteja carregada para o saber);
  - visitantes enviam a chave aleatória da instalação. Não são enviados identificadores de hardware, o email nem nada que identifique o aparelho, e o servidor não usa o IP. Com sessão vai só o token;
  - uma falha é ignorada e não impede ler a experiência; o total de visualizações não é mostrado (fica para o dashboard, E4).
- **Experiência eliminada** (o servidor responde `404` ao gostar, comentar, listar comentários ou ler de novo): o detalhe passa a "Esta experiência já não existe.", as ações desaparecem e as listas são avisadas.
- **Cliente HTTP:** as regras do `HttpClients` mantêm-se. As novas tentativas de ligação do OkHttp são seguras: os gostos são idempotentes, os comentários levam chave e o servidor elimina as visualizações repetidas. A app não repete pedidos por iniciativa própria.

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest`: **196 testes, 0 falhas** (eram 168), executados depois da última alteração ao código. `assembleDebug` e `lintDebug` concluídos (0 erros).

Avisos novos do lint: "autor" assinalado como erro ortográfico (o dicionário é inglês) e o contador de caracteres do comentário sugerido como plural, como os já existentes.

Neste computador, o Gradle passou a escolher para o daemon um JRE 25 sem `jlink`, instalado pela extensão Java do VS Code. Por isso, os comandos desta etapa indicaram o JDK em cada execução, sem alterar configurações. Este contorno foi depois substituído por uma correção persistente ([infraestrutura.md §9](infraestrutura.md#9-jdk-do-gradle)):

```powershell
.\gradlew.bat "-Porg.gradle.java.installations.auto-detect=false" "-Porg.gradle.java.installations.paths=C:/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot" :app:testDebugUnitTest
```

- **`ExperienceInteractionsTest` (19, novo):**
  - **gostos:**
    - visitante → login sem pedido;
    - `likedByMe` ausente com sessão → por confirmar, sem envio, e "Atualizar" resolve;
    - dois toques → um pedido, com o último estado confirmado durante o pedido e a contagem exata depois, avisada às listas;
    - falha ambígua mantém o estado e reconcilia sem repetir;
    - falha definitiva (`429`) mantém o estado; retirar o gosto confirmado;
    - o autor não pode gostar;
    - regresso do login sem gosto nem comentário automáticos (texto mantido);
    - experiência eliminada durante a visita;
  - **visualizações:**
    - uma por abertura, sem repetir em recomposições, atualizações, "Tentar novamente", gosto e recriação com o mesmo estado;
    - nova abertura regista;
    - o autor não regista;
    - falha da leitura não regista e o sucesso seguinte sim;
    - sessão a carregar espera para saber se é o autor;
  - **comentários:**
    - falha da lista sem afetar o conteúdo, paginação sem repetições, lista vazia;
    - sessão e validação (só espaços, 1001 caracteres, 1000 emojis aceites);
    - falha ambígua: texto e chave preservados e bloqueados, também depois da recriação; a repetição usa a mesma chave; o sucesso limpa o texto e atualiza a lista e as contagens;
    - recusa definitiva com chave nova na tentativa seguinte, e descartar;
    - tentativa com mais de 6 dias não repetida;
    - só o autor do comentário edita e elimina, com confirmação, e a contagem atualizada;
    - edição falhada mantém o texto;
    - experiência eliminada ao comentar mantém o texto.
- **`ExperienceListLoaderTest` (2, novo):**
  - as quatro ordenações enviadas com os nomes do contrato, e mudar recomeça na primeira página;
  - contagens do detalhe atualizam só o item certo, sem novos pedidos nem perder páginas.
- **`NetworkExperiencesRepositoryTest` (+7):**
  - detalhe com token ou como visitante (`likedByMe` ausente → `null`), `401` renovado uma vez, servidor sem os campos novos → contagens a 0;
  - comentários públicos convertidos;
  - publicar com token e chave, `401` renovado com a mesma chave, repetição `200`;
  - classificação de rede, `5xx`, `400`, `404`, `429` com `Retry-After`, `DIFFERENT_CONTENT` com `commentId` e `COMMENT_DELETED`;
  - editar e eliminar comentários;
  - gostos (`204`, `CANNOT_LIKE_OWN`, rede e `5xx` como desconhecidos, `404`, `429`, sem sessão sem pedido);
  - visualização com a chave da instalação ou com o token, num único pedido, mesmo com falha.

Não têm testes JVM: os ecrãs Compose, as `SharedPreferences` reais da chave e a navegação para o login e de volta.

### Verificação (2026-10-19, pelo assistente)

**Emulador não usado:** havia ~0,8 GB de memória livre e nenhum aparelho ligado; o `Pixel_9` precisa de 4 GB. Verificaram-se por HTTP, nos contentores, **os pedidos que a app faz** (mesmos caminhos, cabeçalhos, corpos e tamanhos de página), com duas contas temporárias (apagadas no fim com os seus dados; `pamplona`, os dados existentes e os volumes preservados). 11 verificações, 0 falhas:

| Verificação | Resultado |
|---|---|
| Publicar | `201` com `likesCount: 0` e sem `likedByMe` |
| Detalhe com sessão de outra pessoa / sem sessão | `likedByMe: false` / campo ausente (→ "Gostar" / "Entrar para gostar") |
| Visualização como visitante (`X-Viewer-Key`) e com sessão (token) | `204`, `204` |
| Comentar e repetir a mesma tentativa | `201`, depois `200` com o mesmo id e `Idempotent-Replayed` |
| Lista de comentários (`page=0&size=20`) | o comentário, com o autor |
| Editar o próprio comentário | `200` com `editedAt` |
| Gostar e ler de novo | `204`; `likedByMe: true`, 1 gosto, 1 comentário |
| O autor tenta gostar | `400 CANNOT_LIKE_OWN` |
| Publicações do autor com `recent`, `stars`, `likes`, `interactions` | `200` nas quatro, contagens nos itens |
| Retirar o gosto e eliminar o comentário | `204`, `204`; 0/0, `likedByMe: false` |
| Experiência eliminada: detalhe, comentários, gosto | `404` ×3 |

**Verificação na interface (2026-10-19, pelo utilizador):**

| Percurso | Resultado |
|---|---|
| Criar, editar e eliminar comentários | feito, com as contagens atualizadas |
| Própria experiência | sem botão para gostar |
| Gostar e retirar o gosto numa experiência de outra conta | feito |
| Ordenações nas listas | as quatro presentes |

**Verificação na interface (2026-10-19, pelo assistente, no emulador já aberto pelo utilizador, com a sessão da `pamplona`):** sem arrancar o emulador nem reinstalar a app, sem limpar dados. Foi usado `adb` (toques e leitura do ecrã) e `docker compose stop`/`start experiences-service`. Com ~0,4 GB livres, só foi usado o necessário. A memória dos outros serviços não foi alterada.

| Passo | Resultado |
|---|---|
| Escrever um comentário; parar o experiences-service; **Comentar** | "Não foi possível confirmar se o comentário foi publicado…"; texto mantido no campo e bloqueado |
| Voltar a ligar o serviço; **Tentar novamente** | publicado uma vez (mesma chave; um só registo na base de dados); "1 comentário" e "0 gostos · 1 comentário" |
| Editar esse comentário; parar o serviço; **Guardar** | "Sem ligação ao serviço de experiências. O texto mantém-se; tenta novamente."; texto editado mantido, com **Guardar** e **Cancelar** |
| Voltar a ligar; **Guardar** | guardado, marcado "editado" |
| **Eliminar** o comentário | pede confirmação ("Eliminar este comentário?"); depois "0 gostos · 0 comentários" |
| Serviço parado: pesquisa de locais | 10 locais; "Avaliações temporariamente indisponíveis" (não 0 estrelas) |
| Serviço parado: detalhe do local | atividades e "Partilhar experiência" disponíveis; só a secção de experiências mostra o erro, com **Tentar novamente** (seletor de ordenação visível) |
| Serviço parado: o meu perfil | descrição, interesses e dados privados (users-service) disponíveis; só "Publicações" mostra o erro |
| Voltar a ligar; **Tentar novamente** no perfil; reabrir o local | publicações e experiências de volta; local "★ 4,0 (1 pessoa)". A primeira pesquisa logo a seguir ao arranque ainda mostrou avaliações indisponíveis (arranque a frio, prazo de 1 s já documentado); a seguinte já as mostrou |

O comentário temporário (criado pelo assistente na experiência da `pamplona`) foi eliminado pela interface e, depois, a sua marca e o registo da chave foram removidos da base de dados. Os dados ficaram como estavam antes da verificação: a marca de comentário anterior, as publicações e as contas mantêm-se. O experiences-service ficou saudável no fim.

**Login a partir de uma interação:** não foi feito pelo assistente, porque exigia terminar a sessão da `pamplona`. Foi verificado depois pelo utilizador (2026-10-19, no emulador):

| Percurso | Resultado |
|---|---|
| Sair da sessão, tocar em "Entrar para gostar", entrar e regressar | volta à mesma experiência; o gosto **não** é executado automaticamente |
| Novo toque em "Gostar" | o gosto é aceite |

Com isto, não há verificações pendentes da E3.

## 17. Descobrir e Estatísticas (etapa 9, E4)

Contrato: [experiences-api.md §14](experiences-api.md#14-etapa-e4-feeds-e-dashboard). Sem listas de favoritos, mensagens nem notificações.

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| Barra inferior | `ui/navigation/PortoFitNavHost.kt` | Pesquisar (inicial), Descobrir e Estatísticas. Visível só nesses três ecrãs; detalhes, perfis, login e publicação abrem por cima, como antes. Mudar de destino usa `saveState`/`restoreState`: cada destino guarda a pilha, os ViewModels e a posição |
| `DiscoverRepository` | `data/repository/DiscoverRepository.kt` | `GET /feed/popular` (token opcional), `GET /feed`, `GET /me/stats`, `GET /me/stats/experiences`. Leituras com sessão devolvem `SessionResult` (sucesso, falha, sessão terminada, sessão por confirmar), com renovação única depois de um `401`. Um `mode` desconhecido é recusado, nunca tratado como pessoal |
| `DiscoverViewModel` / `DiscoverScreen` | `ui/feature/discover/` | Separadores Para ti / Populares, filtro por modalidade, paginação, "Atualizar", faixas por `mode`/`fallbackReason`, motivo de cada item |
| `StatsViewModel` / `StatsScreen` | `ui/feature/stats/` | Período, totais, histograma, lista por publicação; secções independentes |
| Ícones | `res/drawable/ic_search.xml`, `ic_explore.xml`, `ic_bar_chart.xml` | Vetores Material Symbols, como o `ic_arrow_back` (sem biblioteca nova) |

### Regras

- **Navegação:**
  - Pesquisar continua a ser o início; o menu da conta está na barra superior dos três destinos;
  - alternar entre destinos preserva pesquisas, filtros, separadores, listas e posição;
  - o login aberto em Descobrir ou Estatísticas regressa a esse destino, sem executar nada.
- **Descobrir:**
  - visitantes veem só **Populares** e um convite para entrar; com sessão há **Para ti** (por omissão) e **Populares**, e o separador fica guardado;
  - cartões de experiência existentes, com abertura do detalhe e do perfil do autor; no feed pessoal, cada cartão diz porquê ("Segues @ana", "Interesse: Boxe");
  - filtro por modalidade e "Atualizar"; se as modalidades falharem, aparece um aviso e o feed continua sem filtro;
  - faixas conforme o servidor:
    - `PERSONAL` → "Para ti: de quem segues e dos teus interesses";
    - `NO_PREFERENCES` → explicação de que não é personalizado, com **Escolher interesses** (O meu perfil) e **Procurar pessoas** (Pesquisar);
    - `NO_MATCHES` → explicação (com ou sem filtro);
    - `POPULAR_FALLBACK` → "Não foi possível personalizar agora: a mostrar publicações populares, não personalizadas", com **Tentar novamente**.

    Um resultado popular nunca aparece como "Para ti";
  - **mudança de modo entre páginas:** a página nova não é juntada; aparece "A lista mudou entretanto…" com **Recomeçar**;
  - **sessão:** um `401` renova a sessão uma vez (gestão existente). Sessão terminada → "Entra novamente"; sessão por confirmar → aviso temporário com **Tentar novamente**;
  - **modo de demonstração:** "Dados de demonstração: algumas experiências são sobre locais fictícios" quando há itens de locais `demo`;
  - contagens confirmadas no detalhe atualizam o item ao voltar.
- **Estatísticas:**
  - **sem sessão:** convite e **Entrar**; depois do login regressa a Estatísticas;
  - **período:** **Sempre / 7 dias / 30 dias**, com a nota "Os dias contam em UTC". Cada secção mostra o intervalo devolvido pelo servidor (`from`/`to`, em UTC), nunca recalculado no telemóvel;
  - **"Recebido pelas tuas publicações":** gostos, "Comentários (incluindo as tuas respostas)", interações e visualizações, com a nota "não são pessoas únicas"; publicações criadas no período;
  - **"Estrelas que deste aos locais/atividades":** histograma de 5 a 1 com barras, separado e com a nota "não são notas recebidas"; cada linha é lida como "4 estrelas de 5: 3 publicações";
  - **por publicação:** lista paginada, ordenações (recentes, interações, visualizações) e abertura do detalhe;
  - **falhas:** totais e lista falham de forma independente e nunca aparecem como zeros. "Sem atividade neste período" só com dados confirmados. Uma página seguinte com outro intervalo (ex.: mudou o dia UTC) não é juntada ("Recomeçar");
  - **privacidade entre contas:** ao terminar sessão ou trocar de conta, os dados são apagados logo; respostas tardias da conta anterior (ou de outro período/ordenação) são ignoradas pela geração e pela conta do pedido.

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest`: **216 testes, 0 falhas** (eram 196), executados de novo, sem cache, depois da última alteração ao código. `assembleDebug` e `lintDebug` concluídos (0 erros; o aviso novo de plural foi corrigido).

- **`DiscoverViewModelTest` (10, novo):**
  - visitante só com Populares;
  - sessão a carregar sem pedidos;
  - Para ti por omissão e separador guardado;
  - fallback e motivos no estado, sem motivos de personalização;
  - paginação no mesmo modo e mudança de modo sem juntar, até recomeçar;
  - resposta antiga ignorada ao mudar o filtro;
  - modalidades em falha sem bloquear o feed;
  - sessão terminada, por confirmar e rede com nova tentativa;
  - troca de conta, ignorando a resposta da anterior, e terminar sessão volta a Populares;
  - contagens do detalhe.
- **`StatsViewModelTest` (7, novo):**
  - visitante sem pedidos;
  - totais e lista do mesmo período com o intervalo do servidor, e período e ordenação guardados;
  - resposta antiga ignorada ao mudar o período;
  - falhas independentes e nunca zeros (erro, sessão terminada, por confirmar);
  - terminar sessão apaga logo e uma resposta tardia não repõe;
  - troca de conta sem dados da anterior;
  - paginação e intervalo diferente numa página seguinte.
- **`NetworkExperiencesRepositoryTest` (+3):**
  - popular público e com token, pessoal só com sessão e `401` renovado;
  - modos e motivos (motivo desconhecido → indisponível; modo desconhecido → erro);
  - estatísticas com o intervalo do servidor e o histograma completo; renovação recusada → sessão terminada.

### Verificação na interface (2026-10-19, pelo assistente)

No emulador já aberto pelo utilizador, com a sessão da `pamplona`. A app foi atualizada com `adb install -r`, que mantém os dados; não foram criados dados novos. Havia ~0,2 GB livres no computador.

| Passo | Resultado |
|---|---|
| Abrir a app | Pesquisar com a barra inferior Pesquisar / Descobrir / Estatísticas |
| Filtro Boxe em Pesquisar → Descobrir → Pesquisar | o filtro e os "4 locais encontrados" mantêm-se |
| Descobrir, Para ti (a `pamplona` não segue ninguém nem tem interesses) | explicação "não é um feed personalizado", **Escolher interesses**, **Procurar pessoas**, nota dos Populares e aviso de demonstração |
| Populares + filtro Boxe | "Ainda não há experiências desta modalidade"; separador e filtro mantidos depois de ir a Pesquisar e voltar |
| Abrir a experiência e voltar | detalhe e regresso a Descobrir |
| users-service parado → Para ti | "Não foi possível personalizar agora: a mostrar publicações populares, não personalizadas", com **Tentar novamente** |
| Estatísticas com o users-service parado | totais, "Desde sempre até … (UTC)", visualizações com a nota, histograma separado |
| 7 dias | "De 13/10/2026 a 19/10/2026 21:54 (UTC)"; lista com "0 gostos · 0 comentários · 0 visualizações"; abrir o detalhe e voltar |
| users-service de volta → **Tentar novamente** | Para ti volta à explicação "sem preferências" |
| Leitor de ecrã do histograma | "1 estrelas" encontrado e corrigido; depois "1 estrela de 5: 0 publicações", "4 estrelas de 5: 1 publicação" |

**Verificação na interface (2026-10-20, pelo utilizador):**

| Percurso | Resultado |
|---|---|
| Publicação de uma segunda conta, depois de acrescentar Boxe aos interesses | aparece em "Para ti" com a indicação correta ("Interesse: Boxe") |
| Login iniciado em Estatísticas | regressa a Estatísticas |
| Terminar sessão e entrar noutra conta | as estatísticas privadas desaparecem; a outra conta vê só os seus dados |

A segunda conta e a sua publicação foram criadas pelo utilizador para testes manuais e são mantidas. Com isto, não há verificações pendentes da E4.

## 18. Listas pessoais (etapa 11)

Contrato: [collections-api.md](collections-api.md). Sem "vezes guardada" no dashboard, mensagens nem notificações.

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| `COLLECTIONS_BASE_URL` | `app/build.gradle.kts` | Só em debug: `http://10.0.2.2:8084/` (`-Pportofit.collectionsBaseUrl=…` para mudar); em release vazio → "não disponível nesta versão". A configuração de rede de debug já permitia `10.0.2.2` |
| `CollectionsApi`, `CollectionsRepository` | `data/remote/`, `data/repository/` | Todos os pedidos com o access token, com renovação única depois de um `401` (na criação, com a mesma chave). `CreateCollectionOutcome` (criada, por confirmar, recusada, já criada com outro nome, criada e eliminada); `CollectionError` com `UNCONFIRMED` para resultados desconhecidos e `EXPERIENCES_UNAVAILABLE` (nada guardado) à parte |
| `HttpClients.collections` | `di/HttpClients.kt` | Pool própria de ligações curtas; novas tentativas de ligação seguras (escritas idempotentes ou com chave) |
| `CollectionChanges` | `data/repository/CollectionsRepository.kt` | Aviso de lista alterada: os outros ecrãs pedem só essa lista (`GET /collections/{id}`) e atualizam o nome e a contagem sem recarregar as páginas |
| `CollectionListLoader`, `CollectionCreator` | `ui/feature/collections/CollectionsShared.kt` | Listas paginadas com gerações; criação com chave por tentativa no `SavedStateHandle` |
| `CollectionsViewModel`, `CollectionDetailViewModel`, `SaveToCollectionViewModel` | `ui/feature/collections/CollectionsViewModels.kt` | As minhas listas; itens de uma lista; seletor "Guardar numa lista" |
| Ecrãs | `ui/feature/collections/CollectionsScreens.kt` | Listas, detalhe e seletor, com diálogos de criar, mudar o nome e eliminar |

### Regras

- **Acesso:**
  - "As minhas listas" no menu da conta (Pesquisar, Descobrir, Estatísticas);
  - "Guardar numa lista" no detalhe de cada experiência;
  - sem sessão, os dois ecrãs mostram um convite para entrar, e o login regressa ao mesmo ecrã.
- **Listas:** paginadas (20, mais recentes primeiro), com "Carregar mais".
  - **Criar:** nome de 1 a 60 caracteres, numa só linha, validado como no servidor, com contador.
  - **Mudar o nome:** o nome escrito mantém-se numa falha ("Já tens uma lista com este nome", "não foi possível confirmar… repetir é seguro").
  - **Eliminar:** pede confirmação ("As experiências guardadas não são eliminadas"). Uma falha temporária mantém a lista; uma lista que já não existe sai da página.
- **Criar com idempotência:**
  - a chave é gerada no primeiro envio e guardada com o nome no `SavedStateHandle` antes do pedido (sobrevive à rotação e à recriação);
  - resultado desconhecido → nome e chave bloqueados, "Tentar novamente" com a mesma chave, e "Descartar tentativa";
  - recusa definitiva → chave descartada e nome mantido;
  - nunca há repetição automática nem a mesma chave com outro nome; chaves com mais de 6 dias não são reutilizadas.
- **Guardar numa lista:**
  - o seletor mostra as listas paginadas e permite criar uma nova, que aparece no topo; guardar exige sempre um toque, também depois de entrar ou de criar;
  - cada lista tem o seu estado: "Guardar" → "A guardar…" (sem envios duplicados) → "✓ Guardada";
  - falhas: "não foi possível confirmar… repetir é seguro" (resultado desconhecido, com "Tentar novamente"); "nada foi guardado" (serviço das experiências indisponível); "a experiência já não existe" (desativa o seletor).
- **Detalhe de uma lista:**
  - **disponíveis:** os cartões existentes, que abrem a experiência e o autor;
  - **eliminadas:** "Esta experiência foi eliminada.", sem conteúdo antigo;
  - **desconhecidas:** "Experiência temporariamente indisponível.", com **Tentar novamente**;
  - todas têm "Remover da lista", sem envios duplicados; um resultado desconhecido mantém o item, com a mensagem;
  - com o serviço das experiências em falha aparece um aviso geral, e nenhum item é marcado como eliminado nem a lista é apagada;
  - abrir um item e voltar mantém a página, a posição e o estado; as contagens das listas são atualizadas depois de guardar, remover, mudar o nome ou eliminar.
- **Privacidade entre contas:** ao terminar sessão ou trocar de conta, os três ecrãs apagam logo as listas, os itens, os estados de guardar e a tentativa de criação da conta anterior. Respostas tardias são ignoradas (geração e conta do pedido).

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest`: **234 testes, 0 falhas** (eram 216), executados depois da última alteração ao código. `assembleDebug` e `lintDebug` concluídos (0 erros; aviso novo só no contador de caracteres do nome, como os já existentes).

- **`CollectionsViewModelsTest` (14, novo):**
  - **As minhas listas:**
    - visitante sem pedidos; paginação e erro com nova tentativa;
    - validação do nome; recusa com o nome mantido e chave nova na tentativa seguinte;
    - criação ambígua preservada e bloqueada, também depois da recriação, sem repetição automática; repetição com a mesma chave;
    - descartar a tentativa; tentativa com mais de 6 dias;
    - mudar o nome: sucesso, nome repetido, resultado desconhecido;
    - eliminar com confirmação e falha temporária que mantém a lista;
    - troca de conta e terminar sessão, com resposta tardia ignorada;
    - contagens atualizadas por aviso sem recarregar.
  - **Detalhe:**
    - itens disponíveis, eliminados e desconhecidos (com o aviso), todos removíveis;
    - remover sem duplicados e resultado desconhecido que mantém o item;
    - paginação, sessão terminada com resposta tardia, lista inexistente.
  - **Seletor:**
    - nada guardado depois de entrar;
    - guardar sem duplicados e confirmado;
    - falhas distintas; lista nova sem guardar sozinha; experiência eliminada; troca de conta.
- **`NetworkCollectionsRepositoryTest` (4, novo):**
  - sem sessão nada é pedido;
  - criar com token e chave, `401` renovado com a mesma chave, repetição `200` e classificação dos resultados (rede e `5xx` → por confirmar, `NAME_TAKEN`, limite, `DIFFERENT_CONTENT` com id, `COLLECTION_DELETED`, validação);
  - escritas: `UNCONFIRMED` vs. `EXPERIENCES_UNAVAILABLE` vs. `EXPERIENCE_GONE` vs. `COLLECTION_GONE`, limite;
  - itens: disponível com o formato `Experience`, eliminado sem conteúdo; desconhecido, conteúdo ilegível ou estado novo → `UNKNOWN`, nunca eliminado; `experiencesStatus`.

### Verificação (2026-10-20, pelo assistente)

**Emulador não usado:** não estava aberto e havia ~0,4 GB de memória livre. Verificaram-se por HTTP **os pedidos que a app faz** (mesmos caminhos, cabeçalhos, chave e tamanhos de página), com duas contas temporárias, apagadas no fim com as suas experiências e listas. A `pamplona`, a segunda conta do utilizador, as suas publicações e os volumes foram preservados. 10 verificações, 0 falhas:

| Verificação | Resultado |
|---|---|
| Lista inicial (`page=0&size=20`) | `200`, vazia |
| Criar e repetir a mesma tentativa | `201`, depois `200` com a mesma lista e `Idempotent-Replayed` |
| Mudar o nome | `200` |
| Guardar duas e repetir uma; `GET` da lista (aviso de alteração) | `204` ×3; contagem 2 |
| Itens depois de a autora eliminar uma | eliminada `DELETED` sem conteúdo; a outra `AVAILABLE` no formato `Experience` |
| experiences-service parado | listas `200`; itens `UNKNOWN` e `experiencesStatus: UNAVAILABLE`; remover a eliminada `204` |
| Serviço de volta | item `AVAILABLE`; o removido já não está |
| Outra conta | `404` |
| Eliminar a lista | `204`; a experiência continua (`200`) |

A primeira execução parou ao publicar a experiência de teste (catálogo em arranque a frio, `HttpTimeoutException`, já documentado); as contas criadas foram apagadas e a verificação repetida com várias tentativas de publicação.

**Verificação na interface (2026-10-20, pelo utilizador):**

| Percurso | Resultado |
|---|---|
| Criar e mudar o nome de uma lista | feito |
| Guardar uma experiência, abrir o item e voltar | feito |
| Remover o item e eliminar a lista | feito; a experiência continua a existir |
| Login a partir de "Guardar numa lista" | regressa ao seletor sem guardar; guarda só depois de um novo toque |
| experiences-service parado | a lista continua acessível e o item aparece como temporariamente indisponível (não eliminado) |
| Reiniciar o serviço e tocar em "Tentar novamente" | o conteúdo do item volta |
| Login iniciado em "As minhas listas" | regressa diretamente às listas |

Antes disso, o assistente tentou duas vezes fazer estas últimas verificações (2026-10-20), sem sucesso por falta de memória no computador: o emulador ficou sem resposta ("System UI isn't responding"). Nada foi gravado e os serviços continuaram saudáveis.

Com isto, não há verificações pendentes da etapa 11.

## 19. Mensagens e partilha (etapa 12)

Contrato: [messaging-api.md](messaging-api.md). Sem notificações, grupos, anexos, edição nem eliminação de mensagens.

### Componentes

| Peça | Ficheiro | Função |
|---|---|---|
| `MESSAGING_BASE_URL` | `app/build.gradle.kts` | Só em debug: `http://10.0.2.2:8085/` (`-Pportofit.messagingBaseUrl=…` para mudar). Em release fica vazio, e a app mostra "não disponível nesta versão". A configuração de rede de debug já permitia `10.0.2.2` |
| `MessagingApi`, `MessagingRepository` | `data/remote/`, `data/repository/` | Todos os pedidos levam o access token, com renovação única depois de um `401` (no envio, com a mesma chave). Resultados do envio: `Sent`, `Unconfirmed` (rede, `5xx`, `2xx` ilegível), `Rejected` (com `Retry-After` no `429`; `503 EXPERIENCES_UNAVAILABLE` é definitivo), `AlreadySent` (`DIFFERENT_CONTENT`). Abrir distingue `USERS_UNAVAILABLE`, `USER_NOT_FOUND` e `CANNOT_MESSAGE_SELF` |
| `HttpClients.messaging` | `di/HttpClients.kt` | Pool própria de ligações curtas; as novas tentativas de ligação são seguras (leituras e `PUT` idempotentes, abrir devolve sempre a mesma conversa, envio com chave) |
| `Poller` | `ui/feature/messaging/Poller.kt` | Um único ciclo por ecrã: consulta logo ao voltar ao ecrã e depois de N em N s, com recuo até 60 s nos erros. Nunca há duas consultas em simultâneo: um pedido feito durante uma consulta fica para logo a seguir, uma vez |
| `InboxViewModel`, `ConversationViewModel`, `ShareExperienceViewModel` | `ui/feature/messaging/` | Caixa, conversa e seletor de partilha |
| Ecrãs | `ui/feature/messaging/MessagingScreens.kt` | Caixa, conversa (bolhas, composição, partilhas) e "Partilhar experiência" |

### Percursos

- **Caixa:** "Mensagens" no menu da conta (Pesquisar, Descobrir, Estatísticas).
  - Mostra o participante, o início da última mensagem ("Tu: …", "Partilhou uma experiência"), a hora e as mensagens por ler (até "99+").
  - Paginada pelo cursor do servidor, com "Carregar mais"; sem emails nem ids no ecrã.
- **Iniciar conversa:** "Enviar mensagem" no perfil de outra pessoa (nunca no próprio).
  - Com sessão, abre a conversa: o servidor cria-a ou devolve a existente.
  - Sem sessão, vai para o login e regressa ao perfil; é preciso um novo toque.
  - Se a conversa for aberta sem sessão e se entrar depois, o ecrã pede "Iniciar conversa": nada é criado sozinho.
- **Conversa:**
  - **histórico:** as últimas 30 mensagens e "Carregar mensagens anteriores";
  - **bolhas:** as enviadas à direita e as recebidas à esquerda, com cores diferentes, o remetente ("Tu" ou o username), a hora e o texto. A descrição para leitores de ecrã junta remetente, hora e texto;
  - **composição:** contador de 1000 caracteres.
- **Partilhar:** "Partilhar" no detalhe da experiência, ao lado de "Guardar numa lista".
  - Abre as conversas recentes e "Procurar pessoa" (pesquisa existente do users-service, a partir de 2 caracteres, sem o próprio).
  - Depois de escolher o destinatário, mostra a pré-visualização, um texto opcional e o botão **Enviar**. Só esse toque envia.
  - Depois de enviada, oferece "Abrir conversa".
- **Login com regresso:** caixa, conversa e partilha mostram o convite para entrar.
  - O login regressa ao mesmo ecrã, sem criar conversas nem enviar nada.
  - O rascunho e o destinatário escolhido mantêm-se.

### Regras

- **Consulta periódica:** só com o ecrã **RESUMED** (`LifecycleResumeEffect`): 10 s na conversa e 30 s na caixa, até 60 s com erros. Ao sair do ecrã, ir para segundo plano ou desligar o ecrã, o ciclo para; ao voltar, consulta logo. Não há WorkManager nem serviços em segundo plano. O atraso das mensagens não tem prazo garantido (contrato §12).
- **Cursores:**
  - a consulta pede `after=<cursor>&limit=50` e avança o cursor **só até à última mensagem recebida**, nunca até `lastSeq`;
  - repete enquanto `hasMoreAfter` (até 10 páginas por ciclo; o resto segue no ciclo seguinte, do mesmo cursor);
  - as mensagens juntam-se por `seq`, sem repetidos e por ordem;
  - uma mensagem enviada entra na lista, mas não mexe no cursor: uma mensagem da outra pessoa com `seq` menor, gravada pouco antes, chega na consulta seguinte.
- **Leitura:**
  - a lista reporta o `seq` da última mensagem mostrada (pelo menos metade dentro do ecrã);
  - com a conversa ativa, o ViewModel marca como lida a recebida mais recente **até essa mensagem**;
  - quem está a ler o histórico antigo não marca as novas, e as próprias mensagens nunca contam;
  - um só pedido de cada vez; o valor do servidor nunca faz recuar o cursor de leitura.
- **Mensagens novas sem deslocar a leitura:**
  - quem está no fim da conversa (ou acabou de enviar) segue para a nova mensagem;
  - quem está a ler mais acima fica onde está, e aparece um botão "N mensagens novas" que leva ao fim;
  - o histórico mais antigo entra por cima sem mudar a posição (chaves estáveis por `seq`).
- **Envio idempotente:**
  - uma `Idempotency-Key` por tentativa lógica, guardada com o texto no `SavedStateHandle` antes do pedido;
  - resultado desconhecido → texto bloqueado, "Tentar novamente" (mesma chave e mesmo texto) e "Descartar" (com aviso de que pode já ter sido enviada). **Nunca há repetição automática do `POST`**;
  - a tentativa fica confirmada sozinha quando uma consulta devolve uma mensagem minha com o mesmo `clientKey`;
  - recusa definitiva → chave descartada, texto mantido; o `429` mostra os minutos de espera.
- **Partilhas:**
  - disponíveis → cartão que abre a experiência;
  - eliminadas → "Esta experiência foi eliminada.", sem conteúdo antigo;
  - temporariamente indisponíveis → "Atualizar", que pede só `GET …/shares` sem mexer nas mensagens nem no cursor. Também se atualizam sozinhas, no máximo de 60 em 60 s, com a conversa visível;
  - uma falha do serviço das experiências nunca impede receber mensagens de texto.
- **users-service em falha:** abrir uma conversa nova mostra "Não é possível iniciar a conversa agora", com "Tentar novamente". Na partilha para uma pessoa sem conversa, o texto e o destinatário mantêm-se. As conversas existentes continuam a funcionar.
- **Privacidade entre contas:**
  - ao terminar sessão ou trocar de conta, os três ecrãs param a consulta e apagam logo as mensagens, a caixa e os destinatários;
  - o rascunho e a tentativa da conta anterior são removidos do `SavedStateHandle`;
  - as respostas tardias são ignoradas (geração e conta do pedido).
- **Rascunhos e dados privados:**
  - os rascunhos ficam só no `SavedStateHandle`, com chaves por conta e por conversa ou partilha. É o mínimo necessário para a recriação, sem ficheiros, preferências nem cópias de segurança;
  - a app não regista mensagens, tokens nem emails.

### Testes automáticos (JVM)

`.\gradlew.bat :app:testDebugUnitTest`: **264 testes, 0 falhas** (eram 234), executados depois da última alteração. `assembleDebug` e `lintDebug` concluídos: 0 erros e nenhum aviso novo além do dicionário inglês ("momento").

- **`ConversationViewModelTest` (13).** Usa um messaging-service simulado que responde a `before`/`after` como o real, com tempo virtual:
  - **sessão:** visitante sem pedidos;
  - **histórico:** últimas 30 e anteriores, por `seq` crescente e sem repetidos;
  - **consulta incremental:**
    - 117 mensagens novas drenadas com `after` 3 → 53 → 103;
    - o envio não avança o cursor: a mensagem 121 chega depois da minha 122;
    - mais de 10 páginas continuam no ciclo seguinte;
  - **consulta periódica:** só com o ecrã ativo; sem pedidos sobrepostos com uma consulta lenta e um envio pelo meio; parada em pausa; recuo 10 → 20 → 40 → 60 → 60 s e regresso aos 10 s;
  - **leitura:** só as recebidas mostradas e só com a conversa ativa; nunca recua;
  - **envio:**
    - chave guardada antes do pedido; sem repetição automática; tentativa mantida na recriação;
    - "Tentar novamente" com a mesma chave; confirmação pelo `clientKey` sem novo envio;
    - recusa com o texto mantido e chave nova; validação sem pedidos; descartar a tentativa;
  - **contas:** terminar sessão apaga tudo, para a consulta e ignora a resposta tardia; a conta seguinte não herda o rascunho;
  - **abrir a partir do perfil:** com o toque explícito; users-service em falha e nova tentativa; depois de entrar, é preciso um novo toque;
  - **conversa inexistente** → `Gone`;
  - **partilhas:** indisponíveis não bloqueiam mensagens nem o cursor; "Atualizar" só com `GET …/shares`; atualização automática de 60 em 60 s; eliminada sem conteúdo.
- **`InboxViewModelTest` (4):**
  - visitante sem pedidos;
  - cursor sem repetir uma conversa que subiu;
  - consulta de 30 s só com o ecrã ativo, junta pelo id e reordena, com recuo de 60 s e parada em pausa;
  - terminar sessão apaga tudo e ignora a resposta tardia.
- **`ShareExperienceViewModelTest` (6):**
  - visitante sem pedidos;
  - texto opcional e envio só com "Enviar", sem repetir depois de enviada;
  - pesquisa a partir de 2 caracteres e sem o próprio;
  - users-service em falha mantém o destinatário e o texto;
  - resultado desconhecido com a mesma chave, também depois da recriação; recusa temporária com o texto mantido e chave nova;
  - experiência eliminada não é partilhada;
  - trocar de conta apaga o estado guardado.
- **`NetworkMessagingRepositoryTest` (4):**
  - sem sessão, nada é pedido;
  - envio com token e chave, e `401` renovado com a mesma chave;
  - classificação dos 11 resultados do envio e dos erros de abrir;
  - um texto vazio não vai no corpo;
  - partilhas `AVAILABLE`/`DELETED`/`UNKNOWN`: conteúdo ilegível ou estado novo → `UNKNOWN`, nunca eliminada; `experiencesStatus`; `404`, rede e leitura.
- **`MessagingFormatTest` (3):** hora de hoje e data de outros dias, no fuso do aparelho; "99+"; regras do texto iguais às do servidor.

**Prova dos testes de cursor:** com o defeito introduzido de propósito (avançar o cursor até `lastSeq`), os dois testes de drenagem falham. O código foi reposto antes dos testes finais.

### Verificação (2026-10-21, pelo assistente)

**Emulador não usado:** o computador tinha cerca de 0,05 GB de memória livre, com o emulador, o Android Studio e o VS Code abertos. Da última vez, com cerca de 0,1 GB, o emulador deixou de responder.

Verificaram-se por HTTP **os pedidos que a app faz** (mesmos caminhos, tamanhos de página, chaves e cursores), com duas contas temporárias, apagadas no fim com a conversa, as mensagens e a experiência. A `pamplona`, a segunda conta, as suas publicações, a lista e os volumes foram preservados: antes e depois, 2 contas, 3 experiências ativas, 1 lista com 1 item e 0 conversas. 11 verificações, 0 falhas:

| Verificação | Resultado |
|---|---|
| Abrir a partir do perfil | `201` com o username |
| Conversa vazia (`limit=30`) | `items: []`, `lastSeq: 0` |
| Enviar e repetir a mesma tentativa | `201`, depois `200` com a mesma mensagem, o `clientKey` e `Idempotent-Replayed` |
| Caixa do destinatário (`limit=20`) | participante, pré-visualização, 1 por ler; nenhum email na resposta |
| Consulta incremental (`after=0&limit=50`) | a mensagem, sem o `clientKey` de quem a enviou |
| Marcar como lida | 0 por ler na caixa |
| Partilhar sem texto | `201` `AVAILABLE` no formato `Experience` |
| Drenar mensagens novas de 2 em 2 | `seq` 2–7 sem saltos |
| A autora elimina a experiência (`GET …/shares`) | `DELETED` sem conteúdo |
| Histórico (`before=4`) | 1, 2, 3 por ordem |

Na primeira execução, abrir a conversa deu `503 USERS_UNAVAILABLE`: com o computador quase sem memória, o users-service passou do prazo de 1 s. Nada foi criado, como o contrato prevê. As contas foram apagadas e a verificação repetida, com abertura à primeira (`201`).

**Primeira tentativa do utilizador (2026-10-22):** "Enviar mensagem" no perfil de outra conta mostrou "Não é possível iniciar a conversa agora". O messaging-service não obteve resposta do users-service dentro de 1 s; o prazo passou a 2 s ([messaging-api.md §19](messaging-api.md#19-estado-da-implementação), diagnóstico). Não foi um teste bem-sucedido; a verificação na interface foi feita depois (abaixo).

### Verificação na interface (2026-10-22, pelo assistente)

Feita no emulador, conduzido por ADB/uiautomator, com capturas de ecrã. Usaram-se quatro contas temporárias (`verifuimsg*`), apagadas no fim com as suas conversas, mensagens e experiências.

- **Sessão do utilizador:** a sessão da `pamplona` na app foi terminada para usar as contas temporárias. A app ficou sem sessão; é preciso entrar de novo. Os dados da app não foram limpos.
- **Medições:** o messaging-service teve, só durante a verificação, um registo de acessos temporário (método, caminho, estado; sem cabeçalhos) e registos `DEBUG`. Ficou depois com a configuração normal.
- **O "outro lado":** as mensagens da outra pessoa foram enviadas por HTTP, enquanto a conta na app era observada no ecrã. A direção inversa (A → B) também foi feita na app, depois de trocar de conta.
- **Memória:** o computador tinha cerca de 0,25 GB livres. O emulador (já aberto) respondeu, embora devagar.

| # | Verificação | Observado no ecrã |
|---|---|---|
| 1 | Abrir conversa pelo perfil | Visitante: "Enviar mensagem" leva ao login; ao entrar, regressa ao perfil sem pedidos ao messaging-service e com 0 conversas. Com sessão abre a conversa. Primeira abertura com o serviço a frio: ver os defeitos abaixo; "Tentar novamente" abriu a mesma conversa (`200`), sem duplicar |
| 2 | Enviar e receber nos dois sentidos | B enviou pela app (1 `POST`, `201`); a resposta de A apareceu sozinha. Depois A, na app, respondeu a B. Servidor e ecrã com as mesmas mensagens, sem repetidas. Enviadas à direita (cor primária, "Tu"), recebidas à esquerda |
| 3 | Caixa e por ler | "3 mensagens por ler" → abrir (`PUT …/read`) → voltar: sem contagem. Para A, depois de trocar de conta: "7 mensagens por ler" |
| 4 | Histórico e mensagens novas a ler mais acima | Ao abrir: as últimas 30 mensagens, a última à vista. "Carregar mensagens anteriores" trouxe as 15 mais antigas, por ordem. A ler mais acima, chegaram 2 (e depois 1) mensagens: a lista não se moveu e apareceu "2 mensagens novas" / "1 mensagem nova". Nenhum `PUT read` enquanto se lia mais acima; tocar na indicação leva ao fim e marca |
| 5 | Partilhar pelo detalhe e abrir pela conversa | Detalhe → "Partilhar" → pré-visualização e conversa recente (a conversa sem mensagens não aparece) → texto opcional → "Enviar" → "Partilhada com @…" → "Abrir conversa" → cartão (atividade, local, estrelas, texto, autor) → o toque abre a experiência |
| 6 | Partilha eliminada vs. indisponível | experiences-service parado: "Experiência temporariamente indisponível." com "Atualizar", e o texto da mensagem visível; uma mensagem de texto foi enviada (`201`). De volta: "Atualizar" pediu só `GET …/shares?seqs=48` e o cartão voltou. A autora eliminou a experiência: "Esta experiência foi eliminada.", sem conteúdo antigo, com o texto mantido |
| 7 | Login a partir de "Enviar mensagem" e "Partilhar" | Ambos regressam ao contexto (perfil; seletor de partilha) sem conversa criada nem `POST` de mensagens. **"Mensagens" não foi verificado no ecrã:** sem sessão não há entrada para a caixa (o menu da conta só a mostra com sessão). O convite só aparece se a sessão terminar com o ecrã aberto, o que está coberto pelos testes JVM |
| 8 | users-service parado | Conversa nova: "Não é possível iniciar a conversa agora. Nada foi criado; tenta novamente daqui a pouco."; de volta, "Tentar novamente" abriu-a (`201`). Com ele parado, a caixa e a conversa existente funcionaram e uma mensagem foi enviada (`201`) |
| 9 | messaging-service parado | "Enviar": "Não foi possível confirmar se a mensagem foi enviada. Repetir é seguro", com o rascunho mantido e bloqueado; 25 s sem repetição automática. De volta, "Tentar novamente": 1 `POST` (`201`) e 1 mensagem no servidor |
| 10 | Terminar sessão e trocar de conta | 40 s depois de B sair, 0 pedidos de mensagens. A, depois de entrar, vê só as suas conversas; o rascunho que B deixara por enviar não aparece nem foi enviado |

**Pedidos concorrentes (os dois do mesmo segundo nos registos de 2026-10-22):**

- **Duplo toque** em "Enviar mensagem" (dois toques no mesmo comando ADB): 1 `POST /conversations` e um só ecrã ("Voltar" regressou ao perfil).
- **Duas rotações** com a conversa aberta a partir do perfil: 1 abertura e 1 carga inicial. Depois houve só a consulta imediata ao voltar a ficar ativo e as periódicas, nunca sobrepostas.
- **Explicação mais provável (não reproduzida exatamente):** a cena original não vem de toques nem de recriação. Com o messaging-service acabado de arrancar, o primeiro pedido autenticado ficou cerca de 10 s parado antes da validação do token (medido com registos `DEBUG`: 15,2 s e 15,5 s no total), e a app desiste ao fim de 10 s. Um "Tentar novamente" durante esse bloqueio deixa dois pedidos à espera do mesmo arranque, que terminam juntos.
- **Nenhum defeito de concorrência encontrado,** por isso não houve correção.

**Defeitos encontrados e corrigidos** (código; confirmado de novo no ecrã depois de cada correção):

1. **"Nada foi criado" falso:** depois de um timeout ao abrir, a app dizia "Mensagens indisponíveis de momento. Nada foi criado", mas o servidor tinha criado a conversa (`201` depois de 15 s). Agora diz "Não foi possível confirmar a abertura da conversa (sem resposta do servidor). Tentar novamente é seguro: não cria conversas repetidas."
2. **Última mensagem tapada ao abrir:** a barra de escrita só aparecia quando as mensagens carregavam, depois de a lista descer ao fim, e tapava as últimas. Agora a barra existe desde que a conversa é conhecida ("Enviar" ativo só depois de carregar).
3. **Teclado aberto tapava as mensagens enviadas e recebidas:** a lista desce agora até ao fim real (último item e depois deslocamento consumido só até ao fim). Também acompanha cada passo da animação do teclado, se a pessoa estava no fim.
   - **Consequência observada antes da correção:** a mesma frase foi enviada duas vezes pelo guião de verificação, porque a primeira ficou escondida. Foram dois envios distintos, com chaves diferentes; a app não duplicou nada.
4. **"O teu texto mantém-se":** aparece agora só onde há texto escrito. Desapareceu da abertura a partir do perfil e da partilha sem texto (`messagingErrorMessage(error, hasDraft)`).

**Observações sem correção:**

- **Código frio no messaging-service:** depois de cada reinício, os primeiros pedidos de cada tipo foram lentos neste computador. A primeira validação de token levou cerca de 10 s; a primeira consulta às experiências passou dos 2 s e deu `503`, com o texto mantido e o envio seguinte com sucesso. A quente, o mesmo caminho levou 30–44 ms. Em todos os casos, "Tentar novamente" resolveu sem duplicados.
- **Escrita por ADB:** algumas letras ou espaços duplicados ("Ve  esta aula", "CCom") vêm do teclado virtual com `adb input text`, não da app.

**Testes automáticos depois das correções:** 265 testes JVM, 0 falhas (+1: escolha da mensagem consoante haja rascunho). `assembleDebug` e `lintDebug` sem erros.

**Pendente:** o convite para entrar no ecrã "Mensagens" quando a sessão termina com ele aberto (só testes JVM).
