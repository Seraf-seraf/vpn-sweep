# vpn-sweep

Go CLI для поиска проверяемого клиентского сочетания по импортированным VPN/proxy-конфигурациям.
Первый полный успех по явной политике останавливает поиск, сохраняется локально и завершает ядро.

```sh
make check GO=/absolute/path/to/go
./build/vpn-sweep version
./build/vpn-sweep inspect --input - --format json < examples/input.json
./build/vpn-sweep space
```

- [ТЗ и 21 этап](docs/PLAN.md)
- [Запуск, бюджеты, policy и ограничения](docs/usage.md)
- [Совместимость и источники](docs/compatibility.md)
- [RunStore, fault tests и решения](docs/decisions.md)
- [Результаты и оставшаяся приёмка](docs/EXECUTION.md)
- [Зависимости и лицензии](docs/dependencies.md)

Рабочий режим core требует внешнего Xray с commit ac5d8a8 и замороженным хешем бинарника.
Режим happ заблокирован до доказанного mapping конкретной версии. QUIC, media и системный
Android VPN/TUN не заявлены. Android ARM64 cross-build не заменяет проверку на устройстве.

Публичные примеры синтетические и используют .invalid. Реальные конфиги, подписки, state и
результаты храните вне checkout, в приватных каталогах. Secret scan — дополнительная
эвристическая проверка, не гарантия распознавания любых секретов. MIT.
