# Android Auto 18.1.664404: UpdateUiConfigRequest (0x8009) — разбор APK

Источник: APK пользователя (base + split), jadx 1.5.1, 2026-11-09. Обфусцированные имена классов — только этой сборки.

## Видеоканал (juo, тег лога "CAR.GAL.VIDEO")
- Один класс для MAIN / CLUSTER / AUXILIARY; case 32777 (0x8009) **без проверки типа экрана**.
- Тело: yhz { 1: UiConfig (yhs) }. Без поля 1 — ошибка PROTOCOL_WRONG_MESSAGE / INVALID_UI_CONFIG
  «UpdateUiConfigRequest must specify a UiConfig» → разрыв.
- Если конфигурация видео ещё не получена, запрос откладывается до неё.
- Телефон → ГУ шлёт 0x800A с тем же yhz.

## jld.z(yhz) — применение
- Протокол < 4.3 (у нас 1.7):
  - UiConfig с полем 4 (ui_theme) → ошибка «UpdateUiConfigRequest must not specify an updated UiTheme» → разрыв.
    **Причина обрывов в шагах 77–78: мы всегда слали тему.**
  - Иначе: новое = merge(текущий UiConfig из VideoConfig поле 11, запрос). Вложенные сообщения сливаются по
    полям (слать все 4 отступа, нули явно); повторяющиеся поля дописываются (убрать на ходу нельзя).
  - Пересчёт дисплея, битрейта кодера, затем jks.c(yhs) → прямоугольник дисплея / CarDisplayUiFeatures →
    проекция. Не только для MAIN.
- Протокол ≥ 4.3: другая ветка (тема разрешена; тело декомпилировано не полностью).

## Поля UiConfig (yhs)
1 margins, 2 content_insets, 3 stable_content_insets, 4 ui_theme (enum), 5 repeated UiElement,
6 repeated yac, 7 repeated ybz, 8 yan. Отступы (ycx): 1 сверху, 2 снизу, 3 слева, 4 справа.

UiElement: 0 UNKNOWN, 1 CLOCK, 2 BATTERY_LEVEL, 3 PHONE_SIGNAL, 4 NATIVE_UI_AFFORDANCE,
5 NAVIGATION_TURN_DATA_AVAILABLE → биты CarDisplayUiFeatures 1/2/4/8/16.
Гипотеза (не проверено): 5 сообщает, что машина сама показывает повороты, и навигация прячет свою карточку.

## Навигационный канал (izq, "CAR.INST")
NavigationState 0x8006, CurrentPosition 0x8007, маршрут 0x8009 (ygh, другой канал), прогноз энергии 0x8008:
поля с пакетом/именем программы нет.

## Правильные отступы на ходу (протокол 1.7)
0x8009 на видеоканал кластера: { 1: { 2: { 1: сверху, 2: снизу, 3: слева, 4: справа } } } — без поля 4,
все четыре отступа.
