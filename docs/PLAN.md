# План реализации vpn-sweep

Согласован 2026-10-18. Tracker: `2c3c68d5-f288-4c9f-b7d5-24f5a99f2669`.
UI Goal не включён. Разрешены локальная реализация и синтетические проверки.
Запрещены commit/push/PR, публикация, реальные VPN credentials/live VPN tests и изменения системной сети.

## Согласованное ТЗ: 16 разделов

1. **Продукт.** Go CLI исследует клиентские сочетания для импортированных исправных серверов. Не сканирует неизвестные серверы, не подбирает credentials. Первый полный успех прекращает поиск; туннель завершается. Нет гарантии обхода или существования решения.
2. **Доказательства.** Xray target `ac5d8a83ba798c390cd89f235c3d4c35c77c1586`, не tag/latest. Android ARM64/Termux без root — основная платформа. Cloud linux/amd64 и cross-build не являются device-приёмкой. Скриншоты не задают field bounds.
3. **CLI.** inspect, space, run, resume, status/report, export. Один enum mode=happ/core. Явные budgets, policy, seed и terminal statuses; partial никогда не success.
4. **Режимы.** Серверные адреса/порты/auth/keys/protocol/transport/security/SNI/Host/path/serviceName/flow неизменяемы. Happ требует доказанного mapping конкретной версии; JSON passthrough не равен применению UI settings. Core не требует root/TUN.
5. **Импорт.** Полный JSON, массивы, URI, HTTPS subscription snapshots. Формат распознаётся явно, unknown не конвертируется. Произвольный код/hooks не исполняются, исходная routing/direct оболочка не используется незаметно. Unknown chain semantics отклоняется.
6. **Архитектура.** Модульный CLI, внешнее ядро, отдельные importer/capability/space/search/core/probe/policy/state/secret/export. Генератор без сети. Policy — единственный владелец success. Context/deadlines проходят границы.
7. **Параметры.** Типы, единицы, domains, active_if, requires/conflicts, версии и mapping. min<=max. Неактивные поля канонизируются. Fragment — внешний TCP; noises — применимый UDP socket. MTU не проверяется SOCKS. Advanced fragmentation, Happ array/RandRange/IP preference без mapping исключены.
8. **Поиск.** Ленивый конечный полный обход и широкий seeded поиск взаимодействий с глобальным исследованием. Нет миллионов JSON в памяти, скрытого лимита 30 или бесконечной оптимизации после success. Candidate/series/attempt/probe IDs раздельны; новая сеть/ядро допускает повтор.
9. **Бюджеты.** Defaults-предложения: один кандидат/запрос одновременно, no attempt cap, session 2h, payload 256MiB, startup 15s, HTTPS 45s, download 90s, confirm 10min. Все limits конфигурируемы; исчерпание бюджета — pause, не исчерпание пространства.
10. **Проверки.** TLS trust/hostname, semantic content, redirects, bounded data transfer, fresh reconnection. Default policy: 3 независимых оператора x 2 rounds после restart, 2 проверенных объекта по 1MiB. Google/YouTube — примеры. Media page и media payload — отдельные capabilities. Не использовать аккаунты/обход защиты сайта.
11. **Путь.** Ни direct fallback, ни environment proxy в probes. Redirects остаются в туннеле. DNS endpoint и сайта разделены. UDP/QUIC проверяются отдельно; TCP fallback не QUIC success.
12. **Среда/ошибки.** Nested VPN конфликт/unknown/user attestation явны. IP-check не доказывает отсутствие VPN. Не менять Android VPN автоматически. Различать process/config/network/endpoint/tunnel/TLS/site/content/transfer/partial/inconclusive; 403/429/CAPTCHA не успех и не доказательство поломки сервера.
13. **State/lifecycle.** Durable progress, raw structured probe results, no secret logs, lock, interrupted recovery и version checks. Stop/kill/wait только собственных процессов. SIGKILL не гарантирует userspace cleanup. Success требует сохранения и cleanup.
14. **Секреты/Git.** Private dirs 0700/files 0600 вне checkout. Atomic writes, symlink checks. Нет telemetry. .gitattributes LF/text/binary; .gitignore secrets/subscriptions/state/results/logs/build, сохраняя clean fixtures. Проверять tracked/staged content: ignore не удаляет tracked secrets.
15. **Этапы.** Ниже каждый этап имеет самостоятельную цель, разрешённые действия, исключения и бинарный stop criterion. Состояние выполнения в EXECUTION.md и tracker.
16. **Открытые gates.** Happ build, Android/Termux версии, device и live acceptance остаются blocked/unverified до доказательств. Не блокируют независимую локальную разработку. Минимальная версия Go определяется API/dependencies, не компилятором Xray.

## 21 этап

| № | Цель | Разрешённые действия | Не входит | Бинарный stop criterion |
|---|---|---|---|---|
|1|Спецификация совместимости|spec/compatibility, pinned sources|runtime/network|Каждая возможность classified, unknown не promised|
|2|Правила файлов|attributes/ignore, tracked/staged scan|staging/commit, массовая нормализация|LF/binary/ignore/fixtures checks проходят|
|3|Техническая основа Android|toolchain/dependency spikes, target build|VPN/live/device claims|Target компилируется; device gap явно записан|
|4|Каркас/схемы|CLI, model, errors|network|Help/version/invalid args и contracts проходят|
|5|Локальный импорт|JSON/URI parsers, clean tests|subscriptions/core|Contract preservation и reject tests проходят|
|6|Подписки|HTTPS fetch, local TLS tests|настоящие подписки|limits/redirect/auth/format/redaction tests проходят|
|7|Capabilities/render|catalog, renderer, offline validation|network, guessed Happ|Version/constraint/render tests проходят; real core gate явно указан|
|8|Генератор|lazy exhaustive/broad, IDs/checkpoints|network|Small-grid completeness, deterministic resume, no duplicate disabled fields|
|9|State/recovery|durability, lock, fault tests|VPN|Interrupted recovery и no lost candidate tests проходят|
|10|Core lifecycle|fake core/loopback, start/stop/wait|system VPN|Lifecycle negatives и no foreign kills проходят|
|11|HTTPS/TCP probes|local TLS/proxy, validators|public live tests|No fallback/cert/stub/checksum negatives проходят|
|12|UDP/QUIC|local transport adapters/classification|TUN, universal support|Поддержанное доказано; остальное explicit unsupported|
|13|Success policy|pure evaluator/sequence tests|network search|Partial/missing/restart/required transport negatives проходят|
|14|Search coordinator|integration, budgets, fair scheduling|live|First full success stops; pause/resume/exhaustion distinctions pass|
|15|Secure export|private config/report, atomic/recovery|publication|Permissions/redaction/symlink/save failure tests pass|
|16|Happ adapter|version-specific mapping research/tests|guesses, SOCKS-as-TUN|Known build mapping proved; иначе BLOCKED|
|17|Android device|target artifact + device lifecycle|live internet claims|Device matrix passes; без устройства BLOCKED|
|18|Engineering gates|scripts/Makefile/CI declarations|deploy/publish/live secrets|Available test/build/scan gates pass; hosted CI not invented|
|19|Control live acceptance|separately authorized test server|restricted network|Live policy positive/negative proven; сейчас BLOCKED|
|20|Restricted acceptance|separately authorized needed network|server changes, extra optimization|Full success/accurate negative proven; сейчас BLOCKED|
|21|Local package|artifact/checksum/licenses/docs|commit/push/release|Local package verified, outstanding gates explicit|

Полная приёмка продукта требует этапов 16, 17, 19, 20. Их отсутствие нельзя компенсировать desktop tests.
