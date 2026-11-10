# Вид приборки, кнопка View, плашка навигации и отступы AA

Наблюдения, выводы и план по задаче: включать/выключать карточку навигационной программы («плашку»)
и менять отступы в зависимости от вида приборки (AID) и навигационной программы.
Обновлено 2026-11-09.

## 1. Что известно на сегодня

### 1.1 Заезд 2026-11-08 (режим декодирования 1280×720, свежий AAtoKombi.jar)
- **Декодер ГУ не учитывает обрезку в SPS.** probe: decode_mode=1, sps_cropped=2, full_frame=0; лог
  DisplayManager: окно 1010×376; на фото весь кадр 1280×720 сжат в 1010×376 (карточка Google видна
  целиком, текст сплющен, нечитаем). Файл aa_cluster_config всегда содержит исходный SPS телефона
  (флаг обрезки 0) — по нему проверить запись обрезки нельзя; счётчик в probe говорит, что она была.
- **Смена вида приборки не меняет картинку AA**: во всех видах приборка показывает окно карты целиком,
  тот же размер и положение. Влиять можно только на содержимое кадра.
- **Строк `cluster view:` в логе нет** (jar свежий): приборка не прислала ни Map_Presentation (fct 54),
  ни MapViewAndOrientation SetGet. На MHI2 то же самое: приборка сообщает своё состояние только на Audi,
  поэтому zakklmv/MHI2-Carplay-Maps считает нажатия View.
- **Кнопки руля** во всех логах: 40 (крутилка), 41 (BACK / смена виджетов), 44 (нажатие правого колеса),
  46, 47. View не приходит ни на один экземпляр DSIKeyPanel; экземпляры 1–7 на STD2 не существуют
  («The DSI service instance is not available»), слушатели к ним не подключаются.

### 1.2 Android Auto 18.1.664404 (APK пользователя, jadx 1.5.1)
Подробно — docs/GEARHEAD_18_UI_CONFIG.md. Коротко:
- 0x8009 (UpdateUiConfigRequest) обрабатывается одним классом видеоканала для MAIN/CLUSTER/AUXILIARY,
  **проверки типа экрана нет**.
- Тело: `{1: UiConfig}`; без поля 1 — ошибка протокола INVALID_UI_CONFIG и разрыв.
- При версии протокола < 4.3 (у нас 1.7) UiConfig **с полем 4 (тема) — ошибка протокола**
  «UpdateUiConfigRequest must not specify an updated UiTheme» и разрыв. Это причина обрывов в шаге 77–78:
  мы всегда слали тему.
- Без темы новое значение накладывается на текущее (из VideoConfig поле 11) обычным protobuf merge:
  вложенные сообщения — по полям (отступы слать все четыре, нули явно), повторяющиеся поля дописываются
  (убрать элемент на ходу нельзя). Затем телефон пересчитывает дисплей и сообщает приложениям.
- UiConfig: 1 margins, 2 content_insets, 3 stable_content_insets, 4 ui_theme, 5 repeated UiElement,
  6–8 прочее. Отступы: 1 сверху, 2 снизу, 3 слева, 4 справа.
- UiElement: 1 CLOCK, 2 BATTERY_LEVEL, 3 PHONE_SIGNAL, 4 NATIVE_UI_AFFORDANCE,
  **5 NAVIGATION_TURN_DATA_AVAILABLE** («машина сама показывает подсказки поворотов»), уходит в
  CarDisplayUiFeatures (бит 16). Гипотеза, не проверено: навигация может прятать свою карточку.
- В навигационном канале (NavigationState, CurrentPosition, маршрут, прогноз энергии) **имени/пакета
  программы нет** — программу придётся распознавать по данным или выбирать вручную.

### 1.3 navsd FunctionList ГУ (MIBHMI.jar, mqbab2)
- Сток объявляет `fctMap_PresentationAvailable = false` (fct 54). Приборка (ASG) запрашивает только
  функции, объявленные в FunctionList FSG, — поэтому Map_Presentation не приходит никогда.
- `fctMapViewAndOrientationAvailable = true`, но SetGet от приборки тоже не приходил.
- Стадия MapPresentation в фабрике NavSDBindingFactoryAll создаётся, сток на SetGet ничего не отвечает.
- FSG_Setup: только `activationDeactivationOfTheVideoNavigationSupported`. ASG_Capabilities от приборки:
  `__5DSymbolPresentation` + 4 резервных бита.

### 1.4 Кнопки руля на STD2: служба tsd.mibstd2.keypanel на процессоре J5
- Бинарник `/net/j5/tsd/bin/keypanel/tsd.mibstd2.keypanel` (в дампе `net/j5/...`), запускается сервисом
  `J5e.Keypanel` (net/j5/tsd/etc/system/main.conf). Принимает от IOC сообщения MFL (MQB «Tastencode»),
  переводит через таблицу в коды DSI и отдаёт в DSIKeyPanel.
- Строки: `setMfwMqbEvent(): Tastencode 1 and 2 detected`, `mapMfwMqbEvent(): keyCode/eventCode`,
  **`MFW_MQB: mapMfwMqbEvent(): code not found in mapping table, original code: N`**,
  `sendMfwEventToDSI(): wheeelll keycode`.
- **Таблица MQB → DSI** (исправлено 2026-11-10 по дизассемблеру mapMfwMqbEvent, 0x10c2d8: таблица по
  адресу GOT 0x147da4 + 0xfffef70c = **0x1374b0** (смещение в файле 0x374b0), 29 записей по 8 байт
  {u8 код MQB, u8 флаг, 2 байта 0, u32 код DSI}; поиск по коду MQB, не найден — строка «code not found»
  (уровень Error); DSI 34 — «нет кнопки»):

  | MQB | DSI | | MQB | DSI | | MQB | DSI |
  |---|---|---|---|---|---|---|---|
  | 0x00 | 34 | | 0x11 | 43 | | 0x1B | 52 |
  | 0x01 | 35 | | 0x12 (флаг 1) | 44 | | 0x1C | 53 |
  | 0x02 | 36 | | 0x13 | 44 | | 0x1D | 54 |
  | 0x03 | 37 | | 0x14 | 45 | | 0x1E | 55 |
  | 0x04 | 38 | | 0x15 | 46 | | 0x1F | 56 |
  | 0x05 | 39 | | 0x16 | 47 | | 0x20 | 57 |
  | 0x06 (флаг 1) | 40 | | 0x17 | 48 | | 0x21 | 58 |
  | 0x08 | 41 | | 0x18 | 49 | | 0x22 | 59 |
  | 0x10 | 42 | | 0x19 | 50 | | 0xF0 | 60 |
  | | | | 0x1A | 51 | | 0xF1 | 60 |

  Флаг 1 — колёсико (0x06 → DSI 40 крутилка, 0x12 → DSI 44). Прежняя таблица в этом файле (0x138494)
  была ошибочной — это другая структура.
- **Код MQB кнопки View — 0x23 (35)** (slog J5 2026-11-10: 5 нажатий View → «code not found in mapping
  table, original code: 35», по одной строке на нажатие). Ещё не найден в таблице код **7** (3 раза,
  какая кнопка — не установлено). Обоих кодов в таблице STD2 нет, поэтому до ГУ они не доходят.
- Логи J5 идут в slog процессора J5 (logging.ini J5: `DefaultLogger = slog Info`), а не в slog i.MX6.
  Читать: с i.MX6 через qnet (имя узла J5 — `J5.net.intra`, `on -f j5` не работает: «Invalid node»).

## 2. Выводы
1. Обрезку надо делать на выходе: параметры выхода mm-renderer `video_src_x/y/w/h`
   (mmr-mmf-routing → screen_writer VideoWriterSrcX/Y/W/H → SCREEN_PROPERTY_SOURCE_POSITION/SIZE).
   Если они меняются на ходу — это и есть сдвиг видимой части кадра без перезапуска видео.
2. Вид приборки — два пути: (A) объявить Map_Presentation в FunctionList и посмотреть, начнёт ли
   приборка MQB присылать состояние; (B) достать кнопку View из keypanel J5.
3. Отступы на ходу возможны: 0x8009 `{1:{2:{1,2,3,4}}}` без темы.
4. Программа навигации ГУ не сообщается — распознавание по данным или ручной выбор профиля.

## 3. Сделано в сборке 2026-11-09 (dist-cluster)
- **libkb_dm.so (dec_player.c):** перед входом задаётся обрезка источника `video_src_*` = область обрезки
  из общей памяти (crop_x, crop_y, видимый размер), если видимый размер меньше кодированного. Лог:
  `dec: source crop x/y/w/h` или `dec: source crop rejected, error N`.
  - `/tsd/var/aatokombi/no-src-crop` — не задавать (как раньше, сжатие всего кадра).
  - `/tsd/var/aatokombi/decode-src` «x y w h» — своя область источника; файл перечитывается раз в
    секунду во время показа, при изменении содержимого применяется сразу (проверка «на лету»).
- **AAtoKombi.jar:** тень navsd `FunctionList` объявляет Map_Presentation (fct 54) доступной
  (`/tsd/var/aatokombi/no-map-presentation` — как в стоке; в лог: `navsd FunctionList: Map_Presentation
  announced/off`). `MapPresentation` хранит ASG_HMI_State от приборки и отвечает Status на init/Get/SetGet.
  Строки `cluster view:` в логе и `/dev/shmem/aa_cluster_view` — если приборка начнёт присылать.

## 4. Проверки в машине
1. Картинка: читается ли текст, нет ли сжатия. Если в логе `source crop rejected` или картинка та же —
   прислать aatokombi-dm.log.
2. Опыт «на лету»: во время показа карты
   `echo "0 0 1010 376" > /tsd/var/aatokombi/decode-src` (левый верхний угол кадра),
   потом `echo "270 344 1010 376" > /tsd/var/aatokombi/decode-src` (правый нижний),
   вернуть `echo "134 172 1010 376" > /tsd/var/aatokombi/decode-src`. Сдвигается ли картинка сразу.
3. Вид приборки: переключить виды кнопкой View, открыть меню слева/справа. Нужны строки
   `navsd FunctionList` и `cluster view:` в aatokombi.log, файл /dev/shmem/aa_cluster_view.
   Если что-то на приборке стало хуже (навигационные пункты, меню) — `touch /tsd/var/aatokombi/no-map-presentation`
   и перезагрузка ГУ.
4. Кнопка View: нажать View 5 раз с паузой, затем выполнить на ГУ
   `on -f j5 sloginfo > /tsd/var/aatokombi/j5slog.txt` и прислать файл. Ищем строки
   «code not found in mapping table, original code: N» — N и есть код MQB кнопки View.

## 5. Дальнейший план
- **View:** если код найден — вариант 1: добавить его в таблицу keypanel (правка бинарника J5: подпись
  .tsdsignature, проверка при запуске не изучена — риск), вариант 2: перехватить сообщение раньше (IOC → J5).
  Если строки «code not found» нет даже при нажатиях — поднять уровень логирования keypanel в
  /net/j5/tsd/etc/system/logging/logging.ini (`tsd.mibstd2.keypanel.HIDAdapter = slog Debug`, имя логгера
  не проверено) или признать, что IOC/шлюз не пересылает View на ГУ.
- **Отступы на ходу:** вернуть 0x8009 в правильном формате (без темы, все четыре отступа), команда
  `insets сверху снизу слева справа` через файл команд jar → libgal.
- **Опыт с UiElement 5** (NAVIGATION_TURN_DATA_AVAILABLE) при подключении — прячет ли Google/Яндекс/2ГИС
  свою карточку.
- **Профили:** вид приборки (если появится) + программа навигации (распознавание по данным или ручной
  выбор) → область источника (decode-src) и отступы (0x8009).
- **Запасной путь обрезки:** если `video_src_*` не работает — DisplayManager задаёт окну
  SOURCE_POSITION/SIZE.
