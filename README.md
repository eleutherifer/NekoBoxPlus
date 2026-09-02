NekoBox+

Разработчик: MrEternal

https://4pda.to/forum/index.php?showtopic=1121122

.github/workflows/ingest.yml — триггер на push patches.tar.gz, распаковывает архив в patches/, гоняет prepare-workspace.sh, затем удаляет и patches/ и patches.tar.gz с диска, архив коммитится при загрузке, а этим же прогоном его удаление уходит в тот же коммит, что добавляет workspace/. В репозитории остаётся только workspace/.
Архив patches.tar.gz нужно брать из темы на 4pda.to по ссылке выше.

.github/workflows/build.yml — чекаут → тулчейн → build-and-package.sh → релиз

scripts/prepare-workspace.sh — универсальный движок: читает Base:/Target patch commit: в каждом patches/*.patch, клонирует репозиторий на этот коммит (без полного клона — GitHub отдаёт произвольный SHA напрямую), кладёт в workspace/<name>, применяет патч. После sing-box.patch/NekoBoxForAndroid.patch дополнительно сканирует появившиеся */patches/*/README(.md) (сейчас там amneziawg-go, utls, byedpi) и так же тянет+патчит их. Версия нигде не хардкожена.

scripts/build-and-package.sh — гоняет buildScript/lib/core.docker.sh (это обязательно докер: патч тянет за собой пропатченный Go-рантайм) и ./gradlew app:assemblePlusRelease. Флейвор "plus", который добавляет этот же патч, уже сам называет вывод NekoBoxPlus-<version>-<abi>.apk — переименовывать самому не нужно.
