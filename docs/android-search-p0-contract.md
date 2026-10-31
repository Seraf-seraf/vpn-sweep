# Android SEARCH: контракт P0

Статус: выбранный координатором контракт для последующей реализации B. Это не
утверждение, что SEARCH, Android lifecycle, реальный `protect` или VPN уже
работают. Принятые Compose-дизайн, CLI-контракты и алгоритм `search.Engine`
сохраняются. До реализации B координатор вносит выбор в исходную спецификацию и
tracker; этот файл не управляет tracker или lease.

## Гранулярность и бюджеты

- `Engine.Run` выбирает candidate/attempt; `Engine.attempt` последовательно
  вызывает `Open` для каждого round и `Close` перед следующим round. Пропущенный
  или неподдерживаемый candidate может не вызвать `Open`. CLI создаёт отдельный
  Xray process в каждом `Runner.Start` и ожидает его завершения при `Close`.
- Android создаёт один одноразовый Android-managed worker на каждый фактический
  `Open`/round, а не один на candidate и не повторно используемый worker на всю
  сессию. Worker встроенно валидирует конфигурацию; CLI subprocess `-test` и
  внешний Xray executable не переносятся. Каждый worker создаёт не более одного
  embedded Xray instance. `Open` возвращает handle только после готовности,
  эквивалентной успешной аутентифицированной SOCKS negotiation в CLI; один
  успешный parse или `core.Start` сам по себе readiness не подтверждает.
- Сохраняются действующие лимиты `Engine`: `Attempts` считает candidate attempts,
  не rounds; нулевой лимит сохраняет существующую семантику отсутствия cap.
  Session и candidate deadlines, payload/journal budgets и интервал остаются
  контрактами текущего runtime. Произвольный cap на 500 попыток не вводится.
- Текущие CLI defaults: session 7200 s, payload и journal по 256 MiB,
  attempts 0, candidate deadline 600 s, startup 15 s, cleanup 5 s и интервал
  между candidates 2000 ms. Значения и допустимые границы берутся из текущего
  runtime/manifest, а не переопределяются Android-адаптером.
- Bind, старт, встроенная валидация, смерть и cleanup учитываются в действующих
  session/attempt/startup/cleanup budgets и отмене. CLI ожидает graceful stop и
  затем отдельно ограниченное ожидание после принудительного завершения; нельзя
  молча удлинять лимиты, исключать overhead или считать timeout доказанной смертью.
  Реальная стоимость запуска, памяти и энергии пока не измерена.

## Владение и запуск

Один session coordinator владеет `Engine.Run`, state и выбранным снимком source
и selection. Все реальные сетевые producers конкретного round — embedded Xray и
адаптеры `Check`, `UDP`, `Media` — принадлежат worker этого round; coordinator
вызывает их через ограниченный IPC, не создавая для SEARCH собственные сокеты.
Это сохраняет существующую последовательность `Engine`, не переписывая её.

Worker — bound Android service в app-private `android:process=":search"`,
`exported=false`, без `isolatedProcess=true`. Он принимает одну комбинацию
session, attempt, round и worker generation и не обслуживает новую generation.
Следующий `Open` допустим только после cleanup и подтверждённой смерти именно
предыдущего worker. Ошибки запуска или проверки возможностей worker — не
подтверждение неисправности сервера.

Все SEARCH и VPN entrypoints используют один межпроцессный `flock(LOCK_EX)` на
стабильном app-private inode и внутрипроцессную сериализацию владельцев. SEARCH
не стартует при активном VPN или неизвестном владении; VPN не стартует во время
SEARCH. Отказ захвата lock, неизвестный владелец или неподдерживаемый primitive —
fail closed. Lock-файл не удаляется и не пересоздаётся; его нельзя подменять
другим inode. Все участники используют совместимый `flock`, не смешивая его с
`FileChannel`/`fcntl` как с якобы эквивалентным механизмом.

Coordinator приобретает exclusive lock на независимом открытии до SEARCH
effects и передаёт worker управляемый duplicate **того же open file description**.
Каждый holder сохраняет свой FD; явный `LOCK_UN` запрещён, пока может жить хоть
один worker или его duplicate. При смерти coordinator duplicate worker продолжает
удерживать kernel lease. Следующий независимый SEARCH/VPN открывает inode заново
и приобретает lock; повторный `flock` на переданном FD сам по себе не доказывает
отсутствие других holders. Между rounds coordinator может сохранять свой lease,
но допускает новый worker только после death/cleanup предыдущего и проверки
generation и lease. Android Binder FD-transfer и filesystem semantics требуют
отдельной platform-проверки; host fake и host `flock` не доказывают их.

Создание worker или bind ещё не доказывают доставку duplicate. Если coordinator
погиб до передачи FD, его lock может освободиться; поздний worker без полученного
lease и живого Binder coordinator не загружает gomobile и не начинает сеть. Если
duplicate уже доставлен, worker держит его до смерти. Неизвестная доставка —
отозванная generation и ожидание death/lease, а не предположение о владении.

До загрузки gomobile или начала сети worker получает binding, подтверждает
принадлежность lease и generation и регистрирует death recipient Binder
coordinator/client. Coordinator регистрирует death recipient именно worker
Binder до передачи команды старта. Ошибка `linkToDeath`, уже мёртвый Binder,
отсутствующий bind или неопределённое состояние — запрет старта. Android worker
не наследует CLI `Pdeathsig`: при смерти coordinator он отменяет работу и
завершает себя, удерживая свой lease FD до смерти.

`onServiceDisconnected` не снимает binding и допускает `onServiceConnected`.
При disconnect, cancel, неизвестной доставке или завершении coordinator
запрещает dispatch старой generation, явно снимает binding и ожидает death
конкретного remote Binder/process. `onDestroy`, `stop`, `unbind`, ответ cleanup
или timeout по отдельности не являются доказательством смерти. Пока death,
cleanup и lease не подтверждены, новый worker/VPN остаётся закрытым; при
неопределённости — quarantine, без auto-rebind старой generation.

Профили/данные подписки размером до 8 MiB передаются через bounded app-private
FD/stream, а не как большой Binder parcel; обе стороны закрывают свои копии.
`TransactionTooLargeException` может означать неизвестную доставку: Open не
повторяется вслепую, generation отзывается и выполняется тот же fail-closed
cleanup/death protocol. Содержимое профилей, конфигурации и секреты не логируются.

## Additive SEARCH guard и network policy

Существующий `mobile.Start` требует реальный TUN FD и устанавливает VPN guard;
он не является инициализатором SEARCH. B добавляет отдельный `InitializeSearch`
без TUN и без вызова VPN `Start`. По умолчанию mode и resolver запрещают
операции; после admission устанавливается ровно одна immutable SEARCH policy и
её `AndroidNetwork` callback/resolver для данного worker. VPN guard и его
fail-closed поведение не ослабляются.

До первого обращения к gomobile-классу каждый SEARCH/VPN entrypoint в процессе,
который загружает библиотеку, обязан уже получить exclusive lease и обеспечить
пустое состояние `xray.browser.dialer` и `XRAY_BROWSER_DIALER`. В worker до
загрузки также обязательны binding и обе death registrations. Например,
существующие VPN `Mobile.outboundDigest` и `Mobile.validateConfig` выполняются
раньше `Builder.establish`, поэтому lease нужен и перед ними. Cleanup не
владельца не должен вызывать `Mobile.stop` или загрузить gomobile до admission.
Для lock до gomobile допустим минимальный отдельный native `flock` helper;
gojni helper, который сам загружает Xray до захвата, этой границы не обеспечивает.
Инициализация pinned Xray может запустить browser-dialer
listener при регистрации env handler до `core.New`; поздняя установка guard не
является защитой. Bootstrap не должен неявно загрузить Go через приложение или
provider до этих проверок. Если обеспечить порядок нельзя, worker не стартует.

Immutable policy привязана к session/worker generation и наблюдаемым identity и
version выбранной сети. `AndroidNetwork` — неизменяемая callback/resolver
граница, созданная в worker; она проверяет эти значения на каждом connection
callback; неизвестная сеть/версия, появление VPN, смена сети, потеря owner или
ошибка callback отзывают policy, отменяют round и блокируют новые операции.
Cleanup закрывает уже допущенные ресурсы. Resolver использует ту же policy и
проверку для каждого запроса; system/default и Xray DNS не получают отдельный
незащищённый путь.

Guard охватывает Xray dialer и listener, DNS и все прямые socket paths
`Check`/`UDP`/`Media`. Роль контроллера задаётся точкой регистрации, а не только
`network,address`: Xray UDP dialer может использовать локальный wildcard при
создании outbound packet socket. Нельзя предполагать, что обычный Go dialer
probe автоматически проходит через Xray controllers. Поскольку pinned Xray
игнорирует их ошибки, denial должен сохранить fail-closed эффект для FD; ошибка
подмены/закрытия FD завершает worker, а не разрешает трафик.

SEARCH следует системному default route и не вызывает VPN `protect` или binding
к прежней physical network для скрытого обхода пользовательской VPN. Android
network callbacks асинхронны: P0 не утверждает атомарность внешней смены VPN с
каждым пакетом. Реальные identity/version callback, отмена, cleanup и отсутствие
silent bypass остаются обязательной отдельной Android/manual приёмкой.

## Результат, отмена и ошибки

- Source/selection фиксируются на session generation; их изменение во время
  SEARCH блокируется до явной отмены. Rotation не создаёт второй Engine. Уход
  foreground owner останавливает/приостанавливает сессию; background FGS не
  обещается. После смерти процесса состояние interrupted, без autoconnect.
- Поздние events/results старой generation игнорируются. Успех: COMPLETE и hashes
  → cleanup → проверка generation → атомарная публикация private результата и
  согласованной metadata. Один AtomicFile не является multi-file transaction.
  Previous-good сохраняется; Power disabled при несовпадающей revision.
- Cancel и commit сериализуются одной точкой принятия решения: cancel до commit
  сохраняет previous-good, завершённый commit возвращает success, а не ложный
  cancelled. Неизвестный итог восстанавливается по commit record и hashes.
- Если `Open` частично стартовал worker, сам `Open` завершает его или удерживает
  quarantine: Engine не вызывает `Close` для ошибочного `Open`. Поскольку
  обычная ошибка `Open` записывается как failed candidate, BUSY, потеря owner,
  неизвестная доставка/death и неполный cleanup синхронно отменяют session
  context с различимой причиной; они не выдаются за отказ сервера.

## Границы P0 и последующих проверок

P0 проверяет контракты, compile/link и synthetic owner/fake ports. Компиляция,
fake callback и host lock не доказывают реальный Binder lifecycle, защиту
Android-сокетов, пользовательскую VPN или click wiring приложения. Manual
проверка устройства и live runtime выполняется пользователем отдельно, не как
предусловие реализации B. Host Compose spike ограничен synthetic кнопкой и не
подменяет проверку screen wiring. Следующий этап начинается только после
приёмки соответствующих gates координатором.

При будущей интеграции C постоянные UI-test dependencies и test manifest
принадлежат только QA/test variant. Test Activity и test classpath не должны
попадать в обычные debug/release APK; merged manifests и classpaths проверяются
в integration. Temporary host spike не меняет эти варианты.
