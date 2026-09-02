NekoBox+

Разработчик: MrEternal

https://4pda.to/forum/index.php?showtopic=1121122

.github/workflows/ingest.yml — триггер на push patches.tar.gz, распаковывает в patches/, коммитит, удаляет tar.gz, зовёт build.yml через workflow_call на свежем коммите. Архив patches.tar.gz нужно брать из темы на 4pda.to по ссылке выше.

.github/workflows/build.yml — вызывается и автоматически, и вручную. Ставит JDK17 + Android SDK/NDK, гоняет два скрипта ниже, публикует Release.

scripts/prepare-workspace.sh — универсальный движок: читает Base:/Target patch commit: в каждом patches/*.patch, клонирует репозиторий на этот коммит (без полного клона — GitHub отдаёт произвольный SHA напрямую), кладёт в workspace/<name>, применяет патч. После sing-box.patch/NekoBoxForAndroid.patch дополнительно сканирует появившиеся */patches/*/README(.md) (сейчас там amneziawg-go, utls, byedpi) и так же тянет+патчит их. Версия нигде не хардкожена.

scripts/build-and-package.sh — гоняет buildScript/lib/core.docker.sh (это обязательно докер: патч тянет за собой пропатченный Go-рантайм) и ./gradlew app:assemblePlusRelease. Флейвор "plus", который добавляет этот же патч, уже сам называет вывод NekoBoxPlus-<version>-<abi>.apk — переименовывать самому не нужно.
