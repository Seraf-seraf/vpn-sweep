# vpn-sweep

CLI на Go для поиска работоспособного сочетания клиентских параметров по импортированным VPN/proxy-конфигурациям. Серверы и реквизиты задаются входными конфигурациями: инструмент не сканирует неизвестные серверы и не подбирает учётные данные.

Проверки выполняются через локальный SOCKS внешнего Xray без direct fallback. Первый полный успех по выбранной policy останавливает поиск, сохраняет результат локально и завершает тестовый туннель. Результат подтверждает только проверенные ресурсы и возможности в текущих условиях сети; универсальный обход и постоянная работоспособность не обещаются.

## Возможности и статус

- Импорт поддерживаемых JSON-конфигураций, массивов, URI/base64-списков и HTTPS-подписок с явным распознаванием формата. Неизвестные поля, цепочки и сочетания отклоняются; произвольный Happ JSON не гарантированно совместим.
- Ленивый детерминированный поиск взаимодействий параметров, дедупликация и раздельные идентификаторы серии, кандидата и попытки. Адреса, порты, авторизация и серверный transport/security-контракт сохраняются.
- Управляемые бюджеты времени, попыток, полезной нагрузки и журнала; сохранение прогресса и resume.
- Содержательные HTTPS-проверки с TLS verification, проверкой ответа и повторением после перезапуска ядра. Ping, открытый порт, запуск Xray и произвольный HTTP200 не считаются полным успехом.
- Отдельные результаты передачи видео и YouTube media delivery. Они не подтверждают воспроизведение, декодирование, слышимый звук, DRM или доступ к аккаунту.

Рабочий режим `--mode core` требует внешнего Xray с commit `ac5d8a8` (полный SHA: `ac5d8a83ba798c390cd89f235c3d4c35c77c1586`). Runner проверяет commit в выводе версии, фиксирует SHA-256 бинарника и проверяет конфигурацию перед запуском. Одного номера версии Xray недостаточно.

`--mode happ` пока заблокирован: mapping настроек конкретной версии Happ не подтверждён. Обычный CLI SOCKS не перенаправляет весь Android-трафик; системный VPN/TUN, MTU и per-app VPN не проверяются. QUIC/HTTP3 unsupported; TCP-успех не засчитывается как QUIC. Android ARM64 cross-build выполнен, приёмка на устройстве остаётся unverified.

## Сборка и первый запуск без сети

Требуются Git, Go **1.24+** и Make. Проверенный компилятор — Go 1.27.2. Сторонних Go modules нет; внешнее ядро Xray в сборку не входит.

```sh
git clone https://github.com/Seraf-seraf/vpn-sweep.git
cd vpn-sweep
make build
./build/vpn-sweep version
./build/vpn-sweep help
./build/vpn-sweep inspect --input - --format json < examples/input.json
./build/vpn-sweep space
```

Эти команды не запускают VPN и не проверяют интернет. `examples/input.json` — синтетическая конфигурация с адресами `.invalid`. CLI использует команду `help`, а не флаг `--help`.

Для целевого Android ARM64 artifact:

```sh
make android
```

Результат — `build/vpn-sweep-android-arm64`. Для Android используйте ARM64 artifact, а не Linux-бинарник. Установка Termux, приватный импорт и последовательность run/resume описаны в [инструкции Termux](docs/google-youtube-termux.md).

## Выбор policy

Для `run` обязателен явный `--policy`. Команда `policy --policy private-file` проверяет структуру offline и не подтверждает доступность ресурсов.

| Профиль | Критерий и границы |
|---|---|
| `web-basic-v1` | Два раунда, не менее трёх операторов страниц и двух операторов downloads с независимыми hashes, каждый download ≥1 MiB. Downloads выполняются в первом раунде, страницы повторяются после restart. Ресурсы задаются пользователем. |
| `custom-v1` | Явная пользовательская policy с более узким scope; требуется `--allow-custom-policy`. |
| `google-youtube-web-v1` | Повторные содержательные проверки HTML Google и YouTube после restart. Оба ресурса принадлежат Google. Preset live-unverified; для run/resume нужен `--allow-unverified-web-profile`. Успех означает доступ к страницам, не видео или общему интернету. |
| `representative-video-v1` | Независимые capabilities `video_transfer` и `youtube_delivery`; обязательные задаются в `video.required`. Нужен `--allow-video-profile`. Пример policy unverified, готового подтверждённого публичного media preset нет. |

Video profile использует ограниченные samples и два раунда с новым Xray. Ориентиры примера — 10 Mbps, три samples за раунд, max read gap 3 секунды; параметры конфигурируются. Проверяются TLS, status/Range/размер и поддерживаемые MP4 headers; для generic reference обязателен независимый SHA-256. Это измерение доставки данных, не гарантия 1080p playback.

Optional YouTube adapter требует отдельного изолированного Python с `yt-dlp==2026.8.19`, `yt-dlp-ejs==0.8.0` и Node ≥24. Resolver и media-запросы идут через тот же SOCKS; signed URLs обновляются для каждого раунда/resume и не сохраняются. Cookies, аккаунты, token-provider plugins и автоматическая загрузка компонентов не используются. Реальные YouTube extraction/delivery и работа зависимостей в Termux пока unverified.

Точные поля reference, ограничения clear MP4/YouTube форматов, зависимости, opt-in flags и команды: [video profile](docs/video-profile.md). Google Storage или другой generic sample не засчитывается как YouTube delivery.

## Приватные данные, запуск и resume

Настоящие VPN-конфиги, ссылки подписки, policy, state и результаты храните **вне checkout** и общего Android `/sdcard`: файлы с правами `0600`, приватные каталоги `0700`. Не помещайте реквизиты в argv, историю команд или issues. `.gitignore` не удаляет уже отслеживаемые секреты; secret scan — дополнительная эвристика, не гарантия.

Перед сетевым запуском нужны совместимый input, проверенная policy, внешний Xray и нужная сеть без другого VPN поверх тестов. Happ, захватывающий Termux, может создать вложенный туннель. `--attest-no-other-vpn` — явное подтверждение пользователя, а не автоматическое доказательство отсутствия другого VPN. Системные VPN-настройки инструмент не меняет.

Остановка — Ctrl-C. Resume использует сохранённые входы и условия серии; изменённые core/network/policy или frozen resolver dependencies требуют новой серии. Operational budgets задаются на каждый запуск. Полное описание flags, exit codes и форматов: [usage](docs/usage.md).

При успехе приватный каталог результата содержит:

- `working.json` — готовый конфиг с необходимыми реквизитами;
- `verification.json` — policy и условия проверки;
- `report.json` — результаты, scope и ограничения;
- `COMPLETE.json` — завершающий маркер целостности экспорта.

`status` показывает состояние серии, `report` — журнал попыток. `capability_blocked` сохраняет кандидата для retry; это не доказательство неисправности сервера. Исчерпание бюджета означает pause. Исчерпание пространства означает «рабочее соединение не найдено в исследованном пространстве».

## Локальные проверки

Для полного набора проверок нужны Go, Python 3 и Node ≥24; actual resolver tests с optional dependencies включаются через `VPN_SWEEP_TEST_PYTHON` и `VPN_SWEEP_TEST_JS` на существующий изолированный комплект. Они используют закрытый loopback SOCKS, не реальные YouTube/VPN.

В Linux-среде разработки:

```sh
umask 077
make check
make build android
```

`make check` выполняет проверку форматирования, scanner/resolver tests, secret scan, vet, race suite и обе сборки. Тестовые fixtures требуют `umask 077`. Если среда помечает `/tmp` как Git checkout, для тестов нужен доступный приватный temp root вне checkout, например `TMPDIR=/var/tmp` на Linux. Это не путь для Termux.

[Локальные gates и известные ограничения](docs/ci-contract.md), [зависимости и лицензии](docs/dependencies.md).

## Документация

- [ТЗ, план и границы этапов](docs/PLAN.md)
- [CLI, бюджеты, policy и recovery](docs/usage.md)
- [Google/YouTube web-only preset и Termux](docs/google-youtube-termux.md)
- [Video transfer и optional YouTube adapter](docs/video-profile.md)
- [Совместимость, version gates и источники](docs/compatibility.md)
- [Журнал, fault tests и инженерные решения](docs/decisions.md)
- [Выполненные проверки и оставшаяся приёмка](docs/EXECUTION.md)

Лицензия проекта — [MIT](LICENSE). Лицензии внешнего Xray, optional resolver/runtime и выбранных media samples учитываются отдельно.
