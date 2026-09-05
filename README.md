# NekoBox+

[Тема на 4pda.to](https://4pda.to/forum/index.php?showtopic=1121122)

![иконка](https://4pda.to/s/Zy0hQiz0uez16wFVR8mVRz13wFaLl2Ls0oiPLV.webp)

Разработчик NekoBox+ выкладывает `.apk`-файлы и `patches.tar.gz` в упомянутой теме на `4pda.to` и не планирует переносить разработку на GitHub.  

**Требуется Android**: 6.0+  
**Русский интерфейс**: Да  
**Разработчик**: MrEternal  
**Имя пакета**: com.nb4a.plus  

## Краткое описание:
Форк клиента NekoBox by Starifly с дополнительными функциями и возможностями

## Описание:
• Защита от утечек IP  
• HWID  
• XHTTP  
• AmneziaWG 3.1  
• NaiveProxy  
• MasterDnsVPN  
• ByeDPI outbound  
• TrustTunnel  
• MASQUE  
• Mieru  
• Tailscale  
• Adblock  

## Подробное описание
<details>
  <summary>Поддержка протоколов и транспортов</summary>
  • <b>XHTTP</b> — значительно расширенная совместимость (на основе sing-box-extended и Xray-core): поддержка uplinkDataPlacement, sessionPlacement, xPadding* параметров, uplinkDataKey, seqPlacement и многих других. Параметры доступны в GUI. Много оптимизаций и исправлений утечек памяти/соединений.<br />
  • <b>AmneziaWG</b> 3.1** — полноценная поддержка (файлы .conf, self-hosted vpn:// ссылки). Улучшения импорта/экспорта, PersistentKeepAlive, Reserved, MTU=1280 по умолчанию, возможность цепочек (например, VLESS → AmneziaWG).<br />
  • <b>NaiveProxy</b> — нативная поддержка (плагин больше не нужен).<br />
  • <b>MasterDnsVPN</b> — полноценная поддержка с отдельными профилями, логами и индикаторами.<br />
  • <b>ByeDPI outbound</b> — встроенный, работает в цепочках и как front proxy.<br />
  • <b>TrustTunnel</b> — поддержка с Client Random Prefix, libcronet, uTLS, QUIC.<br />
  • <b>MASQUE</b> — поддержка.<br />
  • <b>Mieru</b> — поддержка без плагина.<br />
  • <b>Tailscale</b> — базовая поддержка.<br />
</details>
<details>
  <summary>Защита от утечек IP и безопасность</summary>
  • Убран mixed outbound в режиме VPN.<br />
  • Локальный SOCKS/HTTP прокси с аутентификацией (логин/пароль).<br />
  • По умолчанию включены и автоматически настраиваются базовые правила маршрутизации по стране пользователя.<br />
  • Автоматический выбор приложений для проксирования по стране.<br />
  Изменённая логика обработки трафика с неопределённым package name (больше не раскрывает реальный IP).<br />
  • Конфигурируемые белые списки DNS для TUN-трафика.<br />
  • Предупреждения о возможных утечках.<br />
  • Скрытие Clash API (по умолчанию включено) — панель sing-box доступна только внутри приложения.<br />
  • NekoBox всегда маршрутизируется через прокси.<br />
</details>
<details>
  <summary></summary>
</details>
<details>
  <summary></summary>
</details>
<details>
  <summary></summary>
</details>
<details>
  <summary></summary>
</details>
<details>
  <summary></summary>
</details>
<details>
  <summary></summary>
</details>
<details>
  <summary></summary>
</details>
<details>
  <summary></summary>
</details>


# Скрипты в этом репозитории

`.github/workflows/ingest.yml` — триггер на `push patches.tar.gz`, распаковывает архив в `patches/`, гоняет `prepare-workspace.sh`, затем удаляет и `patches/` и `patches.tar.gz` с диска, архив коммитится при загрузке, а этим же прогоном его удаление уходит в тот же коммит, что добавляет `workspace/`. В репозитории остаётся только `workspace/`.  
Архив `patches.tar.gz` нужно брать из темы на `4pda.to` по ссылке выше.

`.github/workflows/build.yml` — чекаут → тулчейн → `build-and-package.sh` → релиз

`scripts/prepare-workspace.sh` — универсальный движок: читает `Base:/Target patch commit:` в каждом  `patches/*.patch`, клонирует репозиторий на этот коммит (без полного клона — GitHub отдаёт произвольный SHA напрямую), кладёт в `workspace/<name>`, применяет патч. После `sing-box.patch/NekoBoxForAndroid.patch` дополнительно сканирует появившиеся `*/patches/*/README(.md)` (сейчас там `amneziawg-go`, `utls`, `byedpi`) и так же тянет+патчит их. Версия нигде не хардкожена.

`scripts/build-and-package.sh` — гоняет `buildScript/lib/core.docker.sh` (это обязательно докер: патч тянет за собой пропатченный Go-рантайм) и `./gradlew app:assemblePlusRelease`. Флейвор "plus", который добавляет этот же патч, уже сам называет вывод `NekoBoxPlus-<version>-<abi>.apk` — переименовывать самому не нужно.
