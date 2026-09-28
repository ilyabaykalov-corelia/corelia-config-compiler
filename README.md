# corelia-config-compiler — legacy / deprecated

Этот Maven-модуль сохранён в reactor для совместимости и истории, но не содержит актуальной реализации и не должен использоваться в новой разработке.

Текущая компиляция external configuration выполняется `ru.corelia.platformv.PlatformVConfigurationCompiler` из `corelia-platform-v`; используйте `../corelia.sh build --config-dir …` или низкоуровневый `../scripts/compile-config.sh`. Удаление модуля не входит в задачу: его наличие зафиксировано как технический долг до отдельного решения о совместимости Maven reactor.
