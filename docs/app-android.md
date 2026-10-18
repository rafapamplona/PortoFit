# PortoFit — App Android

> Estado: etapa 4 — pesquisa e detalhe do catálogo (públicos); etapa 5 (app) — registo, login, sessão e logout no users-service. Sem perfis, interesses, seguidores, publicações, mapas nem GPS.
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
| **Na interface (emulador)** | — | **pendente**: não foi possível arrancar o emulador (≈1,3 GB de RAM livre) |

**Por confirmar no emulador** (com `docker compose up -d --wait`):

1. Sem sessão: a pesquisa e o detalhe funcionam; a barra mostra **Entrar**.
2. **Criar conta** com dados inválidos (ex.: username `A`, palavra-passe curta): erros por campo, sem pedido ao servidor.
3. Criar conta válida: volta à pesquisa com `@username` na barra.
4. Terminar sessão (menu `@username`): volta a **Entrar**.
5. **Entrar** com palavra-passe errada: "Email ou palavra-passe incorretos." (o pedido demora ~2 s).
6. Entrar com os dados certos: `@username` na barra.
7. Fechar a app (deslizar nas recentes) e abri-la: `@username` aparece e a sessão é confirmada sem pedir login.
8. `docker compose stop users-service`, fechar e abrir a app: aparece o aviso "Não foi possível confirmar a tua sessão…" com **Tentar novamente**, `@username` mantém-se e a pesquisa funciona; `docker compose start users-service` e **Tentar novamente** → o aviso desaparece.
9. Com o users-service parado, **Terminar sessão**: a sessão local termina e aparece o aviso de que o servidor não foi avisado; voltar a arrancar o serviço no fim.
