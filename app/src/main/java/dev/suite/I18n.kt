package dev.suite

import android.content.Context
import java.util.Locale

/**
 * In-app localization for English, Russian, and Portuguese.
 * Keeps English strings as canonical keys across existing UI code so switches are instant on recreate().
 */
object I18n {
    const val LANG_SYSTEM = "system"
    const val LANG_EN = "en"
    const val LANG_RU = "ru"
    const val LANG_PT = "pt"

    private const val PREF_UI = "ui"
    private const val KEY_LANG = "app_lang"

    @Volatile
    private var activeLang: String = LANG_EN

    fun init(ctx: Context): String {
        val s = saved(ctx)
        activeLang = resolve(s)
        return activeLang
    }

    fun saved(ctx: Context): String =
        ctx.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE).getString(KEY_LANG, LANG_SYSTEM) ?: LANG_SYSTEM

    fun setLang(ctx: Context, code: String) {
        val norm = when (code) {
            LANG_EN, LANG_RU, LANG_PT -> code
            else -> LANG_SYSTEM
        }
        ctx.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE).edit().putString(KEY_LANG, norm).commit()
        activeLang = resolve(norm)
    }

    fun current(): String = activeLang

    private fun resolve(savedCode: String): String = when (savedCode) {
        LANG_EN, LANG_RU, LANG_PT -> savedCode
        else -> when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
            "ru" -> LANG_RU
            "pt" -> LANG_PT
            else -> LANG_EN
        }
    }

    fun nativeName(code: String): String = when (code) {
        LANG_EN -> "English"
        LANG_RU -> "Русский"
        LANG_PT -> "Português"
        else -> t("System")
    }

    fun displaySummary(ctx: Context): String {
        val s = saved(ctx)
        val cur = init(ctx)
        return if (s == LANG_SYSTEM) "${t("System")} (${nativeName(cur)})" else nativeName(cur)
    }

    fun summaryKeywords(): Pair<String, String> = when (activeLang) {
        LANG_RU -> "Когда" to "выполнить"
        LANG_PT -> "Quando" to "fazer"
        else -> "When" to "do"
    }

    fun t(en: String): String {
        val l = activeLang
        if (l == LANG_EN || en.isBlank()) return en
        val map = if (l == LANG_RU) ruExact else ptExact
        map[en]?.let { return it }
        return translateDynamic(en, l)
    }

    // ------------------------------------------------------------------ exact maps

    private val ruExact: HashMap<String, String> = hashMapOf(
        // Tabs & common chrome
        "Shortcuts" to "Быстрые",
        "Automation" to "Автоматизация",
        "Gestures" to "Жесты",
        "Gaming" to "Игры",
        "Audio" to "Аудио",
        "Settings" to "Настройки",
        "All off" to "Всё выключено",
        "Off" to "Выкл",
        "On" to "Вкл",
        "Running" to "Работает",
        "Not running" to "Не запущено",
        "All running" to "Всё работает",
        "Enable" to "Включить",
        "Disable" to "Выключить",
        "Active" to "Активно",
        "active" to "активно",
        "SUMMARY" to "СВОДКА",
        "BETA" to "БЕТА",

        // Theme & Language cards
        "Theme" to "Тема",
        "Light" to "Светлая",
        "System" to "Системная",
        "Dark" to "Тёмная",
        "Language" to "Язык",
        "Request a language" to "Запросить перевод",
        "Want SolCube in your language? Open an issue on GitHub or send me a DM on Reddit." to
            "Нужен SolCube на вашем языке? Создайте обращение на GitHub или напишите мне в личку на Reddit.",
        "Open an issue on GitHub" to "Открыть issue на GitHub",
        "DM on Reddit (u/HerrKaizer__)" to "Написать на Reddit (u/HerrKaizer__)",

        // Power, Diagnostics & Settings actions
        "vivo background power settings" to "Фоновое питание vivo",
        "Allow SolCube to use power in the background, or vivo freezes it." to
            "Разрешите SolCube работу в фоне, иначе vivo заморозит приложение.",
        "Diagnostics" to "Диагностика",
        "App & permissions" to "Приложение и права",
        "Shell services" to "Shell-сервисы",
        "Live values" to "Текущие значения",
        "Recent events" to "Недавние события",
        "Copy report" to "Скопировать отчёт",
        "Diagnostics copied" to "Отчёт скопирован",
        "Check for updates" to "Проверить обновления",
        "Restart SolCube" to "Перезапустить SolCube",
        "Restarting…" to "Перезапуск…",
        "Restarting SolCube (back in ~2 s)…" to "Перезапуск SolCube (~2 с)…",
        "Restarted (Shizuku wasn't running)" to "Перезапущено (Shizuku не работал)",
        "Can't find a feature?" to "Не можете найти функцию?",
        "Can't find something?" to "Чего-то не хватает?",
        "Missing a feature, or something doesn't work on your phone? Send the creator a DM on Reddit with your idea." to
            "Не хватает функции или что-то не работает на вашем телефоне? Напишите автору в личку на Reddit.",
        "Message on Reddit" to "Написать на Reddit",
        "Open GitHub" to "Открыть GitHub",
        "Later" to "Позже",
        "No release notes." to "Нет описания изменений.",
        "You're on the latest version" to "Установлена последняя версия",
        "Couldn't check for updates" to "Не удалось проверить обновления",

        // Shizuku & overlay banners
        "Shizuku isn't running — it stops on every reboot. Start it again in the Shizuku app (Wireless debugging). Circle to Search, Frame interpolation and Super resolution need it; SolCube brings them back by itself once it's running." to
            "Shizuku не запущен — он останавливается после каждой перезагрузки. Запустите его в приложении Shizuku (отладка по Wi-Fi). Он нужен для Circle to Search, интерполяции кадров и суперразрешения; SolCube сам вернёт их в работу.",
        "Open Shizuku" to "Открыть Shizuku",
        "Allow SolCube to use Shizuku so it can start and stop the shell-side parts." to
            "Разрешите SolCube доступ к Shizuku для управления системными сервисами.",
        "Allow" to "Разрешить",
        "Shizuku isn't installed" to "Shizuku не установлен",
        "Waiting for Shizuku" to "Ожидание Shizuku",
        "Shizuku isn't ready" to "Shizuku не готов",
        "Needs Shizuku" to "Нужен Shizuku",
        "Allow “Display over other apps” for the ripple and the temperature pill." to
            "Разрешите показ поверх других окон для анимации и индикатора температуры.",
        "Needs “Display over other apps”" to "Нужно разрешение «Поверх других окон»",

        // Shortcuts: Circle to Search, Double press, Power hold
        "Circle to Search" to "Circle to Search",
        "Two-finger hold" to "Удержание двумя пальцами",
        "Power-button hold" to "Удержание кнопки питания",
        "Navigation bar hold" to "Удержание панели навигации",
        "Double power press" to "Двойное нажатие питания",
        "Double press" to "Двойное нажатие",
        "Power hold" to "Удержание питания",
        "Hold two fingers still on the screen" to "Удерживайте два пальца неподвижно на экране",
        "Hold the power button (screen on)" to "Удерживайте кнопку питания (экран включён)",
        "Hold one finger still on the navigation bar at the bottom" to
            "Удерживайте палец на нижней панели навигации",
        "Pick a trigger below" to "Выберите триггер ниже",
        "Starting the listener…" to "Запуск слушателя…",
        "Triggers Google's Circle to Search instantly" to "Мгновенно открывает Circle to Search",
        "Listener running, ripple overlay starting…" to "Слушатель работает, запуск эффекта…",
        "Ripple, then Google's Circle to Search" to "Эффект, затем Circle to Search",
        "Instant trigger (no animation)" to "Мгновенный запуск (без анимации)",
        "Skips the ripple overlay and opens Circle to Search right away." to
            "Пропускает эффект и сразу открывает Circle to Search.",
        "Default assistant settings" to "Настройки ассистента",
        "Make SolCube the handler" to "Назначить SolCube обработчиком",
        "Set up double press" to "Настроить двойное нажатие",
        "SolCube handles it" to "Управляется SolCube",
        "vivo's double press is off" to "Двойное нажатие vivo выключено",
        "Not handled by SolCube" to "Не назначено для SolCube",
        "Its switch in vivo Wallet (“Double press the Power button to quickly swipe cards”) is off. Set up turns it on." to
            "Переключатель в vivo Wallet выключен. «Настроить» включит его автоматически.",
        "Android asks which app to use each time." to "Android спрашивает, какое приложение открыть.",
        "Does" to "Действие",
        "Short vibration on double press" to "Короткая вибрация при двойном нажатии",
        "Android's own setting (default 300 ms). Takes effect after a restart. A single press locks the screen that much later." to
            "Системная настройка Android (по умолчанию 300 мс). Вступает в силу после перезагрузки.",
        "Add app" to "Добавить приложение",
        "Tap an app to use it; hold it to remove it." to "Нажмите, чтобы выбрать; удерживайте, чтобы удалить.",
        "Or" to "Или",
        "Other" to "Другое",
        "Pick action" to "Выбрать действие",
        "Same hold as Circle to Search's power-button hold: turning this on turns that off, and the other way round." to
            "То же удержание, что и в Circle to Search: включение одного отключает другое.",
        "Ring › Vibrate › Silent" to "Звук › Вибро › Без звука",
        "Silent on/off" to "Без звука вкл/выкл",
        "Vibrate on/off" to "Вибрация вкл/выкл",
        "Couldn't set it (is Shizuku running?)" to "Не удалось применить (Shizuku запущен?)",
        "Couldn't save it (is Shizuku running?)" to "Не удалось сохранить (Shizuku запущен?)",

        // Gestures
        "Screen gestures" to "Жесты на экране",
        "+ Add gesture" to "+ Добавить жест",
        "Test pad" to "Тест",
        "Gesture settings" to "Настройки жестов",
        "Sensitivity" to "Чувствительность",
        "Strict" to "Строгая",
        "Normal" to "Обычная",
        "Loose" to "Мягкая",
        "Only clear, careful drawings count. Fewest mistakes." to
            "Засчитываются только чёткие фигуры. Минимум ложных срабатываний.",
        "A balance of both." to "Баланс между точностью и удобством.",
        "Sloppier drawings count too, but more chance of a wrong or accidental match." to
            "Распознаёт и небрежные жесты, но выше шанс ложного срабатывания.",
        "Vibrate when recognised" to "Вибрация при распознавании",
        "Paused in apps" to "Пауза в приложениях",
        "Gestures won't trigger while these apps are open." to
            "Жесты отключены, пока открыты выбранные приложения.",
        "Choose" to "Выбрать",
        "Active in all apps (never on lock screen)" to
            "Работают во всех приложениях (кроме экрана блокировки)",
        "No gestures assigned yet. Tap + Add gesture to pick a shape and choose what it does." to
            "Жесты пока не назначены. Нажмите «+ Добавить жест», чтобы выбрать фигуру и действие.",
        "No gestures assigned" to "Жесты не назначены",
        "No gesture has an action" to "Ни одному жесту не назначено действие",
        "Paused while testing" to "На паузе во время теста",
        "Starting…" to "Запуск…",
        "Add gesture" to "Добавить жест",
        "Draw a custom shape" to "Нарисовать свою фигуру",
        "Two fingers" to "Два пальца",
        "One finger" to "Один палец",
        "two fingers" to "два пальца",
        "one finger" to "один палец",
        "Draw anywhere with two fingers — swipes, shapes & digits." to
            "Рисуйте двумя пальцами где угодно — свайпы, фигуры и цифры.",
        "Closed shapes only (circle, square, triangle, 8) so normal scrolling never triggers them." to
            "Только замкнутые фигуры (круг, квадрат, треугольник, 8), чтобы не мешать прокрутке.",
        "Swipes" to "Свайпы",
        "Shapes" to "Фигуры",
        "Digits" to "Цифры",
        "Custom" to "Свои",
        "Swipe up" to "Свайп вверх",
        "Swipe down" to "Свайп вниз",
        "Swipe left" to "Свайп влево",
        "Swipe right" to "Свайп вправо",
        "Circle" to "Круг",
        "Square" to "Квадрат",
        "Triangle" to "Треугольник",
        "Cross (X)" to "Крест (X)",
        "Check mark ✓" to "Галочка ✓",
        "Arrow up ^" to "Стрелка вверх ^",
        "Arrow down v" to "Стрелка вниз v",
        "Arrow left <" to "Стрелка влево <",
        "Arrow right >" to "Стрелка вправо >",
        "(deleted gesture)" to "(удалённый жест)",
        "Try a gesture" to "Проверка жеста",
        "Draw with one or two fingers — live gestures are paused while this pad is open." to
            "Рисуйте одним или двумя пальцами — фоновые жесты на паузе, пока открыто это окно.",
        "Draw a gesture" to "Нарисуйте жест",
        "Assign action" to "Назначить действие",
        "Not a closed shape" to "Не замкнутая фигура",
        "With one finger only closed shapes count (circle, square, triangle, 8), so scrolling never sets one off" to
            "Одним пальцем работают только замкнутые фигуры (круг, квадрат, треугольник, 8), чтобы обычная прокрутка не вызывала их",
        "A straight line" to "Прямая линия",
        "Only up / down / left / right swipes count (two fingers); other lines are taken as scrolling" to
            "Учитываются только свайпы вверх / вниз / влево / вправо двумя пальцами; остальные линии считаются прокруткой",
        "Screen gestures turned on" to "Жесты включены",
        "Custom shape saved" to "Своя фигура сохранена",
        "Edit" to "Изменить",
        "Teach my way of drawing it" to "Обучить моему стилю рисования",
        "Add more drawings of it" to "Добавить ещё образцы",
        "Delete" to "Удалить",
        "Your saved drawings will be kept." to "Ваши сохранённые образцы останутся.",
        "No actions yet" to "Действия не заданы",
        "Record a custom shape" to "Запись своей фигуры",
        "Experimental: custom gestures haven't been tested much. They may not be recognised reliably, and some two-finger actions in apps (scrolling, moving a map) may set one off by mistake. Shapes with a few clear turns work best." to
            "Экспериментально: пользовательские жесты могут распознаваться менее точно. Лучше всего работают фигуры с несколькими чёткими углами.",
        "Name, e.g. Heart" to "Название, напр. Сердце",
        "Start over" to "Заново",
        "Save" to "Сохранить",
        "Saved" to "Сохранено",
        "Give it a name" to "Введите название",
        "A bit bigger, please — draw that one again." to "Слишком мелко — нарисуйте крупнее.",
        "Straight lines are taken as scrolling. Draw a shape with a turn in it." to
            "Прямая линия считается прокруткой. Нарисуйте фигуру с изгибом или углом.",
        "Got it. Again." to "Принято. Ещё раз.",
        "Saved, but it can't be used with one finger: it isn't a closed shape" to
            "Сохранено, но нельзя использовать одним пальцем: фигура не замкнута",
        "A cross is two quick diagonal strokes, which is exactly what two flicks look like while scrolling a feed or moving a map. With one finger it would go off by itself." to
            "Крест состоит из двух диагональных штрихов — это похоже на обычные взмахи при прокрутке, поэтому одним пальцем он срабатывал бы случайно.",
        "This is two straight lines with one corner. Ordinary drags, swipes between photos and the back gesture draw that shape all the time, so with one finger it would go off by itself." to
            "Эта фигура состоит из двух линий с одним углом и слишком похожа на обычные свайпы одним пальцем.",
        "Part of this digit is an open line, and a scroll that curls at the end can look just like it. With one finger it would go off by itself." to
            "Эта цифра не замкнута, поэтому изогнутая прокрутка одним пальцем могла бы вызывать её случайно.",
        "A one-finger swipe is how every app scrolls." to
            "Свайп одним пальцем используется для прокрутки во всех приложениях.",
        "This digit is an open line that doesn't end where it starts. A curved scroll or swipe in an app can draw the same shape, so with one finger it would go off by itself." to
            "Эта цифра не замыкается в начале, поэтому обычные свайпы могли бы срабатывать по ошибке.",
        "With one finger only closed shapes are allowed (one stroke that ends where it started), because ordinary scrolls and swipes never do that. This custom gesture isn't drawn that way." to
            "Одним пальцем разрешены только замкнутые фигуры (один штрих, заканчивающийся там же, где начался). Эта фигура нарисована иначе.",

        // Routines & Profiles UI
        "Routines" to "Сценарии",
        "Profiles" to "Профили",
        "New routine" to "Новый сценарий",
        "Edit routine" to "Изменить сценарий",
        "New gesture" to "Новый жест",
        "Edit gesture" to "Изменить жест",
        "New profile" to "Новый профиль",
        "Edit profile" to "Изменить профиль",
        "Delete routine" to "Удалить сценарий",
        "Delete profile" to "Удалить профиль",
        "Name" to "Название",
        "When" to "Когда",
        "When opening" to "При открытии",
        "Only if" to "Только если",
        "Between" to "В интервале",
        "Do, in this order" to "Выполнить по порядку",
        "Add condition" to "Добавить условие",
        "Add action" to "Добавить действие",
        "Add network" to "Добавить сеть",
        "Add Bluetooth device" to "Добавить Bluetooth-устройство",
        "Pick apps" to "Выбрать приложения",
        "Pick app" to "Выбрать приложение",
        "Pick trigger" to "Выбрать триггер",
        "Pick routine" to "Выбрать сценарий",
        "Pick a time" to "Выбрать время",
        "Pick time" to "Выбрать время",
        "Pick a gesture  ▾" to "Выбрать жест  ▾",
        "Set" to "Задать",
        "Done" to "Готово",
        "Cancel" to "Отмена",
        "Close" to "Закрыть",
        "Remove" to "Удалить",
        "Run now" to "Запустить",
        "Search apps" to "Поиск приложений",
        "Clear all" to "Сбросить",
        "None selected" to "Ничего не выбрано",
        "Loading apps…" to "Загрузка приложений…",
        "No routines yet. Tap Add routine below to create one." to
            "Сценариев пока нет. Нажмите «Новый сценарий» ниже.",
        "Tap Add routine to create one (gestures live in the Gestures tab)" to
            "Создайте сценарий (жесты находятся во вкладке «Жесты»)",
        "All routines are off" to "Все сценарии выключены",
        "All profiles are off" to "Все профили выключены",
        "Tap a routine to edit it" to "Нажмите на сценарий для настройки",
        "Tap a profile to edit it" to "Нажмите на профиль для настройки",
        "Change settings or launch an app while another is open" to
            "Автонастройки при открытии выбранных приложений",
        "Can't listen for events, checking every 30 s instead" to
            "Слушатель событий недоступен, проверка каждые 30 с",
        "Gestures are off. Turn them on in the Gestures card." to
            "Жесты выключены. Включите их в разделе «Жесты».",
        "One finger, draw it big, anywhere. Not on the lock screen." to
            "Одним пальцем, рисуйте крупно где угодно (кроме экрана блокировки).",
        "Two fingers a little apart, draw it big, anywhere. Not on the lock screen." to
            "Двумя пальцами чуть врозь, рисуйте крупно где угодно (кроме экрана блокировки).",
        "Shares this hold duration with Shortcuts. When this routine's schedule and conditions match, it runs instead of the Shortcuts card." to
            "Длительность общая с вкладкой «Быстрые». Если условия совпадают, выполняется этот сценарий.",
        "Double power press isn't handled by SolCube yet." to
            "Двойное нажатие питания ещё не передано SolCube.",
        "Runs instead of the Double power press card when this routine's schedule and conditions match." to
            "При совпадении условий выполняется вместо карточки в «Быстрых».",
        "Any game vivo Game Cube runs (the ones it lists in Game space)" to
            "Любая игра из Game Space (vivo Game Cube)",
        "Any Wi-Fi network" to "Любая сеть Wi-Fi",
        "Any of these networks" to "Любая из этих сетей",
        "Leave empty for any Wi-Fi network" to "Оставьте пустым для любой сети Wi-Fi",
        "None of these networks nearby" to "Ни одной из этих сетей поблизости",
        "When any of these opens" to "При открытии любого из них",
        "When leaving any of these" to "При выходе из любого из них",
        "Keep Wi-Fi on before it runs" to "Держать Wi-Fi включённым до запуска",
        "If Wi-Fi is off in this window, turn it on and buzz. Stops once the routine has run that day. Uses the days below." to
            "Если Wi-Fi выключен в это время, включит его и подаст сигнал. Останавливается после запуска в этот день.",
        "to" to "до",
        "At most once a day" to "Не чаще одного раза в день",
        "Only after another routine ran (within 24 h)" to
            "Только после другого сценария (за 24 ч)",
        "Only after" to "Только после",
        "Stop for the day once this routine ran today" to
            "Остановить на сегодня, если этот сценарий уже сработал",
        "Stop once this ran" to "Остановить после",
        "Only between two routines (after the first ran, until the second runs)" to
            "Между двумя сценариями (после первого и до второго)",
        "After…" to "После…",
        "Before…" to "До…",
        "After" to "После",
        "Before" to "До",
        "No" to "Нет",
        "All conditions must match" to "Все условия должны совпадать",
        "Any condition can match" to "Достаточно одного условия",
        "Only if not already done" to "Только если ещё не применено",
        "Skips actions that are already in place (app already running, setting already set, music already playing). If nothing is left to do, the profile stays silent: no pill." to
            "Пропускает уже выполненные действия. Если делать нечего, индикатор не появится.",
        "When leaving" to "При выходе",
        "Back to before when leaving" to "Возвращать как было при выходе",
        "NFC and settings changed by this profile return to what they were before (NFC also turns off after 3 min locked; skipped if already on). One-shot and background apps stay as-is." to
            "Изменённые этим профилем настройки (включая NFC) вернутся в прежнее состояние при выходе.",
        "Status pill" to "Островок статуса",
        "Show actions instead of name" to "Показывать действия вместо названия",
        "Off: shows the profile name (e.g. “Wallet” / “Wallet off”). On: shows what changed (e.g. “NFC on” / “NFC off”)." to
            "Выкл: показывает имя профиля. Вкл: показывает изменения (напр. «NFC вкл» / «NFC выкл»).",
        "Saved networks" to "Сохранённые сети",
        "Add Wi-Fi network" to "Добавить сеть Wi-Fi",
        "Network name (SSID)" to "Имя сети (SSID)",
        "Bluetooth device" to "Bluetooth-устройство",
        "Bluetooth device name" to "Имя устройства Bluetooth",
        "Exact device name" to "Точное имя устройства",
        "Type device name…" to "Ввести имя устройства…",
        "Run routine" to "Запустить сценарий",
        "No other routines" to "Других сценариев нет",
        "Routines engine isn't running" to "Движок сценариев не запущен",
        "Charger type" to "Тип зарядки",
        "Any" to "Любой",
        "All" to "Все",
        "On" to "В сети",
        "Not on" to "Не в сети",
        "In" to "В",
        "Not in" to "Не в",
        "Connected" to "Подключено",
        "Not" to "Не",
        "Leave empty for any Bluetooth device" to "Оставьте пустым для любого устройства",
        "Launch in background" to "Запустить в фоне",
        "Close these apps" to "Закрыть эти приложения",
        "Keep these apps open" to "Оставить эти приложения открытыми",
        "Revive these apps" to "Восстановить эти приложения",
        "Current app" to "Текущее приложение",
        "Apps" to "Приложения",
        "Status pill text" to "Текст индикатора",
        "Message to show" to "Текст сообщения",
        "Shell command" to "Команда shell",
        "e.g. input keyevent 3" to "напр. input keyevent 3",
        "Battery level" to "Уровень заряда",
        "Battery temperature" to "Температура батареи",
        "Charging" to "Заряжается",
        "Not charging" to "Не заряжается",
        "Disconnected" to "Отключено",
        "Unlocked" to "Разблокирован",
        "Locked" to "Заблокирован",
        "Screen on" to "Экран включён",
        "Screen off" to "Экран выключен",
        "Playing" to "Воспроизведение",
        "Not playing" to "Пауза",
        "In call" to "В звонке",
        "Not in call" to "Не в звонке",
        "Vibrate" to "Вибрация",
        "Silent" to "Без звука",
        "Normal" to "Обычный",
        "AC" to "Сеть",
        "Wireless" to "Беспр.",
        "Toggle" to "Переключить",
        "Back to before" to "Как было до",
        "Except…" to "Кроме…",
        "Set text" to "Текст",
        "Set URL" to "Ссылка",
        "Set cmd" to "Команда",
        "nothing yet (add an action below)" to "ничего не выбрано (добавьте действие ниже)",
        "“Back to before” puts back what that setting was before a routine last changed it." to
            "«Как было до» возвращает значение, которое было до запуска сценария.",
        "Mon" to "Пн", "Tue" to "Вт", "Wed" to "Ср", "Thu" to "Чт", "Fri" to "Пт", "Sat" to "Сб", "Sun" to "Вс",
        "Mon–Fri" to "Пн–Пт",
        "Weekends" to "Выходные",

        // Trigger groups & triggers
        "Wi-Fi & Network" to "Wi-Fi и сеть",
        "Wi-Fi connects" to "Подключение к Wi-Fi",
        "Wi-Fi disconnects" to "Отключение от Wi-Fi",
        "Wi-Fi out of range" to "Wi-Fi вне зоны",
        "Wi-Fi is off" to "Wi-Fi выключен",
        "Airplane mode on" to "Режим полёта вкл.",
        "Airplane mode off" to "Режим полёта выкл.",
        "Wi-Fi turned on" to "Wi-Fi включился",
        "Wi-Fi turned off" to "Wi-Fi выключился",
        "Mobile data on" to "Мобильный интернет вкл.",
        "Mobile data off" to "Мобильный интернет выкл.",
        "Bluetooth & Audio" to "Bluetooth и аудио",
        "Bluetooth connects" to "Bluetooth подключается",
        "Bluetooth disconnects" to "Bluetooth отключается",
        "Bluetooth turned on" to "Bluetooth включился",
        "Bluetooth turned off" to "Bluetooth выключился",
        "Headphones connected" to "Наушники подключены",
        "Headphones disconnected" to "Наушники отключены",
        "Media starts playing" to "Медиа начало играть",
        "Media stops playing" to "Медиа остановлено",
        "Call starts or rings" to "Начало или вызов звонка",
        "Call ends" to "Конец звонка",
        "Battery & Power" to "Батарея и питание",
        "Charger connected" to "Зарядка подключена",
        "Charger disconnected" to "Зарядка отключена",
        "Battery drops below" to "Заряд ниже порога",
        "Battery rises above" to "Заряд выше порога",
        "Battery saver on" to "Экономия заряда вкл.",
        "Battery saver off" to "Экономия заряда выкл.",
        "Boost mode on" to "Режим Boost вкл.",
        "Boost mode off" to "Режим Boost выкл.",
        "Battery temperature high" to "Высокая температура батареи",
        "Battery cooled down" to "Батарея остыла",
        "Device & Screen" to "Устройство и экран",
        "Phone unlocked" to "Телефон разблокирован",
        "Phone locked" to "Телефон заблокирован",
        "Screen turns on" to "Экран включился",
        "Screen turns off" to "Экран выключился",
        "Screen off for a while" to "Экран долго выключен",
        "Turned to landscape" to "Горизонтальная ориентация",
        "Turned to portrait" to "Вертикальная ориентация",
        "Do Not Disturb on" to "Не беспокоить вкл.",
        "Do Not Disturb off" to "Не беспокоить выкл.",
        "Sound mode: ring" to "Звук: звонок",
        "Sound mode: vibrate" to "Звук: вибрация",
        "Sound mode: silent" to "Звук: без звука",
        "Dark mode on" to "Тёмная тема вкл.",
        "Dark mode off" to "Тёмная тема выкл.",
        "Apps & Games" to "Приложения и игры",
        "App opened" to "Приложение открыто",
        "App closed" to "Приложение закрыто",
        "Any game opened" to "Запущена любая игра",
        "Game closed" to "Игра закрыта",
        "Schedule" to "Расписание",
        "At a time" to "По времени",
        "One-finger gesture" to "Жест одним пальцем",
        "Two-finger gesture" to "Жест двумя пальцами",

        // Condition groups & names
        "Connectivity" to "Связь",
        "Wi-Fi state" to "Состояние Wi-Fi",
        "Wi-Fi network" to "Сеть Wi-Fi",
        "Bluetooth state" to "Состояние Bluetooth",
        "Headphones / audio" to "Наушники / аудио",
        "Mobile data" to "Мобильный интернет",
        "Airplane mode" to "Режим полёта",
        "Device & State" to "Состояние устройства",
        "Screen / lock" to "Экран / блокировка",
        "Do Not Disturb" to "Не беспокоить",
        "Sound mode" to "Режим звука",
        "Dark mode" to "Тёмная тема",
        "Orientation" to "Ориентация",
        "Media playback" to "Воспроизведение медиа",
        "Phone call" to "Телефонный звонок",

        // Action groups & names
        "Wi-Fi" to "Wi-Fi",
        "Bluetooth" to "Bluetooth",
        "NFC" to "NFC",
        "Location" to "Геолокация",
        "Auto-sync" to "Автосинхронизация",
        "Sound & Media" to "Звук и медиа",
        "Media volume" to "Громкость медиа",
        "Ring volume" to "Громкость звонка",
        "Notification volume" to "Громкость уведомлений",
        "Alarm volume" to "Громкость будильника",
        "Display & Power" to "Экран и питание",
        "Screen brightness" to "Яркость экрана",
        "Auto-brightness" to "Автояркость",
        "Screen rotation" to "Автоповорот экрана",
        "Screen timeout" to "Таймаут экрана",
        "Keep screen on" to "Не выключать экран",
        "Extra dim" to "Экстра-затемнение",
        "Grayscale" to "Оттенки серого",
        "Battery saver" to "Экономия заряда",
        "Boost mode" to "Режим Boost",
        "Lock screen" to "Заблокировать экран",
        "Wake screen" to "Разбудить экран",
        "Apps & Navigation" to "Приложения и навигация",
        "Open app" to "Открыть приложение",
        "Close specific apps" to "Закрыть выбранные",
        "Close all apps" to "Закрыть все приложения",
        "Revive apps" to "Перезапустить приложения",
        "Go to Home screen" to "На главный экран",
        "Open link / URL" to "Открыть ссылку / URL",
        "Open link" to "Открыть ссылку",
        "SolCube" to "SolCube",
        "Temperature pill" to "Индикатор температуры",
        "FPS counter" to "Счётчик FPS",
        "Frame interpolation" to "Интерполяция кадров",
        "Super resolution" to "Суперразрешение",
        "Play together" to "Играть вместе",
        "Stream to laptop" to "Трансляция на ноутбук",
        "Gemini CTS" to "Gemini CTS",
        "Gemini Live" to "Gemini Live",
        "CTS: two-finger hold" to "CTS: удержание двумя пальцами",
        "CTS: power-button hold" to "CTS: кнопка питания",
        "CTS: nav bar hold" to "CTS: панель навигации",
        "Power button hold" to "Удержание кнопки питания",
        "Utilities" to "Инструменты",
        "Show status pill" to "Показать островок",
        "Vibrate (done)" to "Вибрация (готово)",
        "Take screenshot" to "Сделать скриншот",
        "Run another routine" to "Запустить сценарий",
        "Wait" to "Пауза",
        "Run shell command" to "Команда shell",
        "16:10 screen" to "Экран 16:10",
        "Portrait lock" to "Портретный",
        "Auto-rotate" to "Автоповорот",
        "Landscape" to "Альбомный",
        "Auto" to "Авто",
        "Play / Pause" to "Пуск / Пауза",
        "Play" to "Пуск",
        "Pause" to "Пауза",
        "Next" to "Следующий",
        "Previous" to "Предыдущий",
        "Stop" to "Стоп",
        "Keep screen on" to "Экран всегда вкл.",
        "Normal screen timeout" to "Обычный таймаут экрана",
        "Screen timeout back to before" to "Таймаут экрана как до",

        // Gaming & Audio
        "Temperature overlay" to "Индикатор температуры",
        "Temp pill" to "Температура",
        "Super res" to "Суперразр.",
        "Stream" to "Стрим",
        "Fine volume" to "Точная громкость",
        "Knob" to "Ручка",
        "Slider" to "Ползунок",
        "Media volume in 1 % steps. The volume buttons still jump 10 % at a time." to
            "Громкость медиа с шагом 1 %. Кнопки громкости по-прежнему меняют на 10 %.",
        "Keeps vivo frame interpolation on in listed games" to
            "Удерживает интерполяцию кадров vivo в играх из списка",
        "No listed game running" to "Игры из списка не запущены",
        "vivo super resolution, kept on while enabled" to
            "Суперразрешение vivo, поддерживается пока включено",
        "Apps keep playing when another starts sound. Calls still pause them." to
            "Приложения не ставят звук друг друга на паузу. Звонки по-прежнему их приостанавливают.",
        "No app has asked for audio yet" to "Пока ни одно приложение не запрашивало аудио",
        "Frames per second on screen, in its own pill: left of the temperature pill in landscape, above it in portrait." to
            "Счётчик FPS на экране: слева от температуры в ландшафте и сверху в портрете.",
        "Games" to "Игры",
        "Tap the ratio to change it. Only whole multiples work." to
            "Нажмите на множитель для смены. Работают только целые кратности.",
        "package name, e.g. com.game.app" to "имя пакета, напр. com.game.app",
        "Add" to "Доб.",
        "Enter a package name" to "Введите имя пакета",
        "Already in the list" to "Уже в списке",
        "No app with that package is installed" to "Приложение с таким пакетом не установлено",
        "Heat pause (battery)" to "Пауза по перегреву (батарея)",
        "Gray numbers" to "Серые цифры",
        "Show CPU & GPU load" to "Показывать загрузку CPU и GPU",
        "Load as bars (upright in portrait)" to "Загрузка в виде полос",
        "Blur background" to "Размывать фон",
        "Also a Quick Settings tile" to "Доступно как плитка в шторке",
        "Add tile" to "Добавить плитку",
        "Streaming" to "Трансляция",
        "Laptop shape on" to "Режим 16:10 вкл",
        "Laptop not reachable — screen resets in 15 s" to
            "Ноутбук не найден — экран вернётся через 15 с",
        "Phone screen matches the laptop. Start the stream when the game is open." to
            "Экран подогнан под ноутбук. Нажмите «Начать стрим», когда игра открыта.",
        "Plug in the cable; the laptop helper must be running." to
            "Подключите кабель; на ноутбуке должен быть запущен помощник.",
        "Normal screen" to "Обычный экран",
        "Stop stream" to "Остановить стрим",
        "Start stream" to "Начать стрим",
        "The phone stays the touch controller, dimmed. Exit from the round button on the phone, or Ctrl+Alt+X on the laptop. If the cable is pulled, the screen goes back to normal by itself." to
            "Телефон остаётся затемнённым тачпадом. Выход — круглая кнопка на экране или Ctrl+Alt+X на ноутбуке.",
        "Apps on the bubble" to "Приложения на кнопке",
        "Launch buttons in the bubble's panel, plus Back and Home." to
            "Быстрый запуск приложений с плавающей панели, плюс Назад и Домой.",
        "Laptop helper not found — is the cable in and the helper running?" to
            "Помощник на ноутбуке не найден — проверьте кабель и программу.",
    )

    private val ptExact: HashMap<String, String> = hashMapOf(
        // Tabs & common chrome
        "Shortcuts" to "Atalhos",
        "Automation" to "Automação",
        "Gestures" to "Gestos",
        "Gaming" to "Jogos",
        "Audio" to "Áudio",
        "Settings" to "Ajustes",
        "All off" to "Tudo desligado",
        "Off" to "Desl.",
        "On" to "Lig.",
        "Running" to "Em execução",
        "Not running" to "Parado",
        "All running" to "Tudo ativo",
        "Enable" to "Ativar",
        "Disable" to "Desativar",
        "Active" to "Ativo",
        "active" to "ativo",
        "SUMMARY" to "RESUMO",
        "BETA" to "BETA",

        // Theme & Language cards
        "Theme" to "Tema",
        "Light" to "Claro",
        "System" to "Sistema",
        "Dark" to "Escuro",
        "Language" to "Idioma",
        "Request a language" to "Solicitar idioma",
        "Want SolCube in your language? Open an issue on GitHub or send me a DM on Reddit." to
            "Quer o SolCube no seu idioma? Abra uma issue no GitHub ou me mande uma DM no Reddit.",
        "Open an issue on GitHub" to "Abrir issue no GitHub",
        "DM on Reddit (u/HerrKaizer__)" to "DM no Reddit (u/HerrKaizer__)",

        // Power, Diagnostics & Settings actions
        "vivo background power settings" to "Energia em segundo plano (vivo)",
        "Allow SolCube to use power in the background, or vivo freezes it." to
            "Permita uso de bateria em segundo plano, ou a vivo congelará o SolCube.",
        "Diagnostics" to "Diagnóstico",
        "App & permissions" to "App e permissões",
        "Shell services" to "Serviços shell",
        "Live values" to "Valores ao vivo",
        "Recent events" to "Eventos recentes",
        "Copy report" to "Copiar relatório",
        "Diagnostics copied" to "Relatório copiado",
        "Check for updates" to "Buscar atualizações",
        "Restart SolCube" to "Reiniciar SolCube",
        "Restarting…" to "Reiniciando…",
        "Restarting SolCube (back in ~2 s)…" to "Reiniciando SolCube (~2 s)…",
        "Restarted (Shizuku wasn't running)" to "Reiniciado (Shizuku estava parado)",
        "Can't find a feature?" to "Não achou um recurso?",
        "Can't find something?" to "Falta alguma coisa?",
        "Missing a feature, or something doesn't work on your phone? Send the creator a DM on Reddit with your idea." to
            "Sentiu falta de um recurso ou algo não funciona no seu celular? Mande uma DM para o criador no Reddit.",
        "Message on Reddit" to "Mensagem no Reddit",
        "Open GitHub" to "Abrir GitHub",
        "Later" to "Depois",
        "No release notes." to "Sem notas da versão.",
        "You're on the latest version" to "Você já está na versão mais recente",
        "Couldn't check for updates" to "Não foi possível verificar atualizações",

        // Shizuku & overlay banners
        "Shizuku isn't running — it stops on every reboot. Start it again in the Shizuku app (Wireless debugging). Circle to Search, Frame interpolation and Super resolution need it; SolCube brings them back by itself once it's running." to
            "O Shizuku está parado — ele fecha ao reiniciar o celular. Inicie-o no app Shizuku (Depuração sem fio). Circle to Search, Interpolação e Super resolução dependem dele.",
        "Open Shizuku" to "Abrir Shizuku",
        "Allow SolCube to use Shizuku so it can start and stop the shell-side parts." to
            "Permita o uso do Shizuku para o SolCube controlar os serviços em segundo plano.",
        "Allow" to "Permitir",
        "Shizuku isn't installed" to "Shizuku não está instalado",
        "Waiting for Shizuku" to "Aguardando Shizuku",
        "Shizuku isn't ready" to "Shizuku não está pronto",
        "Needs Shizuku" to "Requer Shizuku",
        "Allow “Display over other apps” for the ripple and the temperature pill." to
            "Permita “Sobrepor a outros apps” para o efeito visual e a pílula de temperatura.",
        "Needs “Display over other apps”" to "Requer “Sobrepor a outros apps”",

        // Shortcuts: Circle to Search, Double press, Power hold
        "Circle to Search" to "Circle to Search",
        "Two-finger hold" to "Segurar dois dedos",
        "Power-button hold" to "Segurar botão ligar",
        "Navigation bar hold" to "Segurar barra de navegação",
        "Double power press" to "Toque duplo no botão ligar",
        "Double press" to "Toque duplo",
        "Power hold" to "Segurar ligar",
        "Hold two fingers still on the screen" to "Segure dois dedos parados na tela",
        "Hold the power button (screen on)" to "Segure o botão ligar (tela acesa)",
        "Hold one finger still on the navigation bar at the bottom" to
            "Segure um dedo parado na barra de navegação embaixo",
        "Pick a trigger below" to "Escolha um gatilho abaixo",
        "Starting the listener…" to "Iniciando leitor…",
        "Triggers Google's Circle to Search instantly" to "Abre o Circle to Search instantaneamente",
        "Listener running, ripple overlay starting…" to "Leitor ativo, iniciando animação…",
        "Ripple, then Google's Circle to Search" to "Animação e depois Circle to Search",
        "Instant trigger (no animation)" to "Disparo imediato (sem animação)",
        "Skips the ripple overlay and opens Circle to Search right away." to
            "Pula a animação e abre o Circle to Search na hora.",
        "Default assistant settings" to "Assistente padrão",
        "Make SolCube the handler" to "Tornar SolCube o padrão",
        "Set up double press" to "Configurar toque duplo",
        "SolCube handles it" to "Controlado pelo SolCube",
        "vivo's double press is off" to "Toque duplo da vivo desligado",
        "Not handled by SolCube" to "Não controlado pelo SolCube",
        "Its switch in vivo Wallet (“Double press the Power button to quickly swipe cards”) is off. Set up turns it on." to
            "A opção na vivo Wallet está desligada. Configurar ativa automaticamente.",
        "Android asks which app to use each time." to "O Android pergunta qual app usar toda vez.",
        "Does" to "Ação",
        "Short vibration on double press" to "Vibração curta no toque duplo",
        "Android's own setting (default 300 ms). Takes effect after a restart. A single press locks the screen that much later." to
            "Configuração do Android (padrão 300 ms). Requer reinício.",
        "Add app" to "Adicionar app",
        "Tap an app to use it; hold it to remove it." to "Toque para usar; segure para remover.",
        "Or" to "Ou",
        "Other" to "Outro",
        "Pick action" to "Escolher ação",
        "Same hold as Circle to Search's power-button hold: turning this on turns that off, and the other way round." to
            "Mesmo botão do Circle to Search: ativar um desativa o outro.",
        "Ring › Vibrate › Silent" to "Som › Vibrar › Silencioso",
        "Silent on/off" to "Silencioso lig./desl.",
        "Vibrate on/off" to "Vibrar lig./desl.",
        "Couldn't set it (is Shizuku running?)" to "Não foi possível aplicar (Shizuku ativo?)",
        "Couldn't save it (is Shizuku running?)" to "Não foi possível salvar (Shizuku ativo?)",

        // Gestures
        "Screen gestures" to "Gestos na tela",
        "+ Add gesture" to "+ Adicionar gesto",
        "Test pad" to "Testar",
        "Gesture settings" to "Ajustes de gestos",
        "Sensitivity" to "Sensibilidade",
        "Strict" to "Rigorosa",
        "Normal" to "Normal",
        "Loose" to "Flexível",
        "Only clear, careful drawings count. Fewest mistakes." to
            "Aceita apenas desenhos bem definidos. Menos toques acidentais.",
        "A balance of both." to "Equilíbrio entre precisão e facilidade.",
        "Sloppier drawings count too, but more chance of a wrong or accidental match." to
            "Aceita traços rápidos, mas aumenta a chance de disparo acidental.",
        "Vibrate when recognised" to "Vibrar ao reconhecer",
        "Paused in apps" to "Pausado em apps",
        "Gestures won't trigger while these apps are open." to
            "Os gestos não disparam enquanto estes apps estiverem abertos.",
        "Choose" to "Escolher",
        "Active in all apps (never on lock screen)" to
            "Ativo em todos os apps (nunca na tela de bloqueio)",
        "No gestures assigned yet. Tap + Add gesture to pick a shape and choose what it does." to
            "Nenhum gesto configurado. Toque em + Adicionar gesto para escolher uma forma.",
        "No gestures assigned" to "Sem gestos configurados",
        "No gesture has an action" to "Nenhum gesto tem ação",
        "Paused while testing" to "Pausado durante o teste",
        "Starting…" to "Iniciando…",
        "Add gesture" to "Adicionar gesto",
        "Draw a custom shape" to "Desenhar forma personalizada",
        "Two fingers" to "Dois dedos",
        "One finger" to "Um dedo",
        "two fingers" to "dois dedos",
        "one finger" to "um dedo",
        "Draw anywhere with two fingers — swipes, shapes & digits." to
            "Desenhe em qualquer lugar com dois dedos — deslizes, formas e dígitos.",
        "Closed shapes only (circle, square, triangle, 8) so normal scrolling never triggers them." to
            "Apenas formas fechadas (círculo, quadrado, triângulo, 8) para não interferir na rolagem.",
        "Swipes" to "Deslizes",
        "Shapes" to "Formas",
        "Digits" to "Dígitos",
        "Custom" to "Personalizados",
        "Swipe up" to "Deslizar para cima",
        "Swipe down" to "Deslizar para baixo",
        "Swipe left" to "Deslizar para esquerda",
        "Swipe right" to "Deslizar para direita",
        "Circle" to "Círculo",
        "Square" to "Quadrado",
        "Triangle" to "Triângulo",
        "Cross (X)" to "Cruz (X)",
        "Check mark ✓" to "Visto ✓",
        "Arrow up ^" to "Seta cima ^",
        "Arrow down v" to "Seta baixo v",
        "Arrow left <" to "Seta esquerda <",
        "Arrow right >" to "Seta direita >",
        "(deleted gesture)" to "(gesto excluído)",
        "Try a gesture" to "Testar gesto",
        "Draw with one or two fingers — live gestures are paused while this pad is open." to
            "Desenhe com um ou dois dedos — gestos em segundo plano pausam aqui.",
        "Draw a gesture" to "Desenhe um gesto",
        "Assign action" to "Atribuir ação",
        "Not a closed shape" to "Não é uma forma fechada",
        "With one finger only closed shapes count (circle, square, triangle, 8), so scrolling never sets one off" to
            "Com um dedo só contam formas fechadas (círculo, quadrado, triângulo, 8), para a rolagem nunca disparar",
        "A straight line" to "Linha reta",
        "Only up / down / left / right swipes count (two fingers); other lines are taken as scrolling" to
            "Apenas deslizes cima/baixo/esquerda/direita (dois dedos) contam; outras linhas viram rolagem",
        "Screen gestures turned on" to "Gestos na tela ativados",
        "Custom shape saved" to "Forma personalizada salva",
        "Edit" to "Editar",
        "Teach my way of drawing it" to "Ensinar meu jeito de desenhar",
        "Add more drawings of it" to "Adicionar mais amostras",
        "Delete" to "Excluir",
        "Your saved drawings will be kept." to "Seus desenhos salvos serão mantidos.",
        "No actions yet" to "Sem ações ainda",
        "Record a custom shape" to "Gravar forma personalizada",
        "Experimental: custom gestures haven't been tested much. They may not be recognised reliably, and some two-finger actions in apps (scrolling, moving a map) may set one off by mistake. Shapes with a few clear turns work best." to
            "Experimental: gestos personalizados podem falhar ocasionalmente. Formas com cantos bem definidos funcionam melhor.",
        "Name, e.g. Heart" to "Nome, ex.: Coração",
        "Start over" to "Recomeçar",
        "Save" to "Salvar",
        "Saved" to "Salvo",
        "Give it a name" to "Dê um nome",
        "A bit bigger, please — draw that one again." to "Um pouco maior, por favor — desenhe de novo.",
        "Straight lines are taken as scrolling. Draw a shape with a turn in it." to
            "Linhas retas viram rolagem. Desenhe uma forma com curva ou canto.",
        "Got it. Again." to "Certo. Mais uma vez.",
        "Saved, but it can't be used with one finger: it isn't a closed shape" to
            "Salvo, mas não pode usar com um dedo: não é uma forma fechada",
        "A cross is two quick diagonal strokes, which is exactly what two flicks look like while scrolling a feed or moving a map. With one finger it would go off by itself." to
            "Uma cruz tem dois traços diagonais parecidos com toques rápidos ao rolar a tela; com um dedo dispararia sozinha.",
        "This is two straight lines with one corner. Ordinary drags, swipes between photos and the back gesture draw that shape all the time, so with one finger it would go off by itself." to
            "São duas retas com um canto, muito parecidas com gestos comuns de voltar ou passar fotos com um dedo.",
        "Part of this digit is an open line, and a scroll that curls at the end can look just like it. With one finger it would go off by itself." to
            "Parte deste dígito é aberta e parece uma rolagem curva; com um dedo dispararia por engano.",
        "A one-finger swipe is how every app scrolls." to
            "Um deslize com um dedo é como todos os apps rolam a tela.",
        "This digit is an open line that doesn't end where it starts. A curved scroll or swipe in an app can draw the same shape, so with one finger it would go off by itself." to
            "Este dígito não termina onde começou, podendo ser confundido com rolagem normal.",
        "With one finger only closed shapes are allowed (one stroke that ends where it started), because ordinary scrolls and swipes never do that. This custom gesture isn't drawn that way." to
            "Com um dedo só formas fechadas são permitidas (um traço que termina onde começou). Esta forma não foi desenhada assim.",

        // Routines & Profiles UI
        "Routines" to "Rotinas",
        "Profiles" to "Perfis",
        "New routine" to "Nova rotina",
        "Edit routine" to "Editar rotina",
        "New gesture" to "Novo gesto",
        "Edit gesture" to "Editar gesto",
        "New profile" to "Novo perfil",
        "Edit profile" to "Editar perfil",
        "Delete routine" to "Excluir rotina",
        "Delete profile" to "Excluir perfil",
        "Name" to "Nome",
        "When" to "Quando",
        "When opening" to "Ao abrir",
        "Only if" to "Somente se",
        "Between" to "Entre",
        "Do, in this order" to "Fazer, nesta ordem",
        "Add condition" to "Adicionar condição",
        "Add action" to "Adicionar ação",
        "Add network" to "Adicionar rede",
        "Add Bluetooth device" to "Adicionar dispositivo Bluetooth",
        "Pick apps" to "Escolher apps",
        "Pick app" to "Escolher app",
        "Pick trigger" to "Escolher gatilho",
        "Pick routine" to "Escolher rotina",
        "Pick a time" to "Escolher hora",
        "Pick time" to "Escolher hora",
        "Pick a gesture  ▾" to "Escolher gesto  ▾",
        "Set" to "Definir",
        "Done" to "Concluído",
        "Cancel" to "Cancelar",
        "Close" to "Fechar",
        "Remove" to "Remover",
        "Run now" to "Executar",
        "Search apps" to "Buscar apps",
        "Clear all" to "Limpar tudo",
        "None selected" to "Nenhum selecionado",
        "Loading apps…" to "Carregando apps…",
        "No routines yet. Tap Add routine below to create one." to
            "Nenhuma rotina ainda. Toque em Nova rotina abaixo para criar uma.",
        "Tap Add routine to create one (gestures live in the Gestures tab)" to
            "Crie uma rotina (gestos ficam na aba Gestos)",
        "All routines are off" to "Todas as rotinas desligadas",
        "All profiles are off" to "Todos os perfis desligados",
        "Tap a routine to edit it" to "Toque em uma rotina para editar",
        "Tap a profile to edit it" to "Toque em um perfil para editar",
        "Change settings or launch an app while another is open" to
            "Altera ajustes ou abre apps automaticamente",
        "Can't listen for events, checking every 30 s instead" to
            "Sem leitura de eventos, verificando a cada 30 s",
        "Gestures are off. Turn them on in the Gestures card." to
            "Os gestos estão desligados. Ative-os na aba Gestos.",
        "One finger, draw it big, anywhere. Not on the lock screen." to
            "Um dedo, desenhe grande em qualquer lugar (exceto tela de bloqueio).",
        "Two fingers a little apart, draw it big, anywhere. Not on the lock screen." to
            "Dois dedos um pouco afastados, desenhe grande (exceto tela de bloqueio).",
        "Shares this hold duration with Shortcuts. When this routine's schedule and conditions match, it runs instead of the Shortcuts card." to
            "Compartilha esta duração com Atalhos. Se as condições baterem, roda no lugar do atalho.",
        "Double power press isn't handled by SolCube yet." to
            "O toque duplo no botão ligar ainda não é controlado pelo SolCube.",
        "Runs instead of the Double power press card when this routine's schedule and conditions match." to
            "Roda no lugar do card em Atalhos quando o horário e as condições coincidirem.",
        "Any game vivo Game Cube runs (the ones it lists in Game space)" to
            "Qualquer jogo listado no Game Space (vivo Game Cube)",
        "Any Wi-Fi network" to "Qualquer rede Wi-Fi",
        "Any of these networks" to "Qualquer uma destas redes",
        "Leave empty for any Wi-Fi network" to "Deixe vazio para qualquer rede Wi-Fi",
        "None of these networks nearby" to "Nenhuma destas redes por perto",
        "When any of these opens" to "Quando qualquer um destes abrir",
        "When leaving any of these" to "Ao sair de qualquer um destes",
        "Keep Wi-Fi on before it runs" to "Manter Wi-Fi ligado antes de rodar",
        "If Wi-Fi is off in this window, turn it on and buzz. Stops once the routine has run that day. Uses the days below." to
            "Se o Wi-Fi estiver desligado neste horário, liga e vibra. Para após rodar no dia.",
        "to" to "até",
        "At most once a day" to "No máximo uma vez ao dia",
        "Only after another routine ran (within 24 h)" to
            "Apenas após outra rotina (em 24 h)",
        "Only after" to "Somente depois",
        "Stop for the day once this routine ran today" to
            "Parar por hoje quando esta rotina já tiver rodado",
        "Stop once this ran" to "Parar após rodar",
        "Only between two routines (after the first ran, until the second runs)" to
            "Apenas entre duas rotinas (após a primeira, até a segunda)",
        "After…" to "Depois…",
        "Before…" to "Antes…",
        "After" to "Depois",
        "Before" to "Antes",
        "No" to "Não",
        "All conditions must match" to "Todas as condições devem bater",
        "Any condition can match" to "Qualquer condição serve",
        "Only if not already done" to "Somente se ainda não estiver assim",
        "Skips actions that are already in place (app already running, setting already set, music already playing). If nothing is left to do, the profile stays silent: no pill." to
            "Pula ações já aplicadas. Se não houver nada a fazer, não mostra aviso.",
        "When leaving" to "Ao sair",
        "Back to before when leaving" to "Restaurar estado anterior ao sair",
        "NFC and settings changed by this profile return to what they were before (NFC also turns off after 3 min locked; skipped if already on). One-shot and background apps stay as-is." to
            "Ajustes e NFC alterados pelo perfil voltam como estavam antes ao sair.",
        "Status pill" to "Pílula de status",
        "Show actions instead of name" to "Mostrar ações em vez do nome",
        "Off: shows the profile name (e.g. “Wallet” / “Wallet off”). On: shows what changed (e.g. “NFC on” / “NFC off”)." to
            "Desligado: mostra o nome do perfil. Ligado: mostra o que mudou (ex.: “NFC lig.”).",
        "Saved networks" to "Redes salvas",
        "Add Wi-Fi network" to "Adicionar rede Wi-Fi",
        "Network name (SSID)" to "Nome da rede (SSID)",
        "Bluetooth device" to "Dispositivo Bluetooth",
        "Bluetooth device name" to "Nome do dispositivo Bluetooth",
        "Exact device name" to "Nome exato do dispositivo",
        "Type device name…" to "Digitar nome do dispositivo…",
        "Run routine" to "Executar rotina",
        "No other routines" to "Sem outras rotinas",
        "Routines engine isn't running" to "O motor de rotinas não está ativo",
        "Charger type" to "Tipo de carregador",
        "Any" to "Qualquer",
        "All" to "Todas",
        "On" to "Na rede",
        "Not on" to "Fora da rede",
        "In" to "Em",
        "Not in" to "Fora de",
        "Connected" to "Conectado",
        "Not" to "Não",
        "Leave empty for any Bluetooth device" to "Deixe vazio para qualquer Bluetooth",
        "Launch in background" to "Abrir em segundo plano",
        "Close these apps" to "Fechar estes apps",
        "Keep these apps open" to "Manter estes apps abertos",
        "Revive these apps" to "Reabrir estes apps",
        "Current app" to "App atual",
        "Apps" to "Apps",
        "Status pill text" to "Texto da pílula de status",
        "Message to show" to "Mensagem a exibir",
        "Shell command" to "Comando shell",
        "e.g. input keyevent 3" to "ex.: input keyevent 3",
        "Battery level" to "Nível de bateria",
        "Battery temperature" to "Temperatura da bateria",
        "Charging" to "Carregando",
        "Not charging" to "Não carregando",
        "Disconnected" to "Desconectado",
        "Unlocked" to "Desbloqueado",
        "Locked" to "Bloqueado",
        "Screen on" to "Tela ligada",
        "Screen off" to "Tela desligada",
        "Playing" to "Tocando",
        "Not playing" to "Pausado",
        "In call" to "Em chamada",
        "Not in call" to "Fora de chamada",
        "Vibrate" to "Vibrar",
        "Silent" to "Silencioso",
        "Normal" to "Normal",
        "AC" to "CA",
        "Wireless" to "Sem fio",
        "Toggle" to "Alternar",
        "Back to before" to "Como estava antes",
        "Except…" to "Exceto…",
        "Set text" to "Definir texto",
        "Set URL" to "Definir URL",
        "Set cmd" to "Definir cmd",
        "nothing yet (add an action below)" to "nada ainda (adicione uma ação abaixo)",
        "“Back to before” puts back what that setting was before a routine last changed it." to
            "“Como estava antes” restaura o valor anterior à rotina.",
        "Mon" to "Seg", "Tue" to "Ter", "Wed" to "Qua", "Thu" to "Qui", "Fri" to "Sex", "Sat" to "Sáb", "Sun" to "Dom",
        "Mon–Fri" to "Seg–Sex",
        "Weekends" to "Fins de semana",

        // Trigger groups & triggers
        "Wi-Fi & Network" to "Wi-Fi e Rede",
        "Wi-Fi connects" to "Wi-Fi conecta",
        "Wi-Fi disconnects" to "Wi-Fi desconecta",
        "Wi-Fi out of range" to "Wi-Fi fora de alcance",
        "Wi-Fi is off" to "Wi-Fi desligado",
        "Airplane mode on" to "Modo avião ligado",
        "Airplane mode off" to "Modo avião desligado",
        "Wi-Fi turned on" to "Wi-Fi ligado",
        "Wi-Fi turned off" to "Wi-Fi desligado",
        "Mobile data on" to "Dados móveis ligados",
        "Mobile data off" to "Dados móveis desligados",
        "Bluetooth & Audio" to "Bluetooth e Áudio",
        "Bluetooth connects" to "Bluetooth conecta",
        "Bluetooth disconnects" to "Bluetooth desconecta",
        "Bluetooth turned on" to "Bluetooth ligado",
        "Bluetooth turned off" to "Bluetooth desligado",
        "Headphones connected" to "Fones conectados",
        "Headphones disconnected" to "Fones desconectados",
        "Media starts playing" to "Mídia começa a tocar",
        "Media stops playing" to "Mídia para de tocar",
        "Call starts or rings" to "Chamada inicia ou toca",
        "Call ends" to "Chamada termina",
        "Battery & Power" to "Bateria e Energia",
        "Charger connected" to "Carregador conectado",
        "Charger disconnected" to "Carregador desconectado",
        "Battery drops below" to "Bateria cai abaixo de",
        "Battery rises above" to "Bateria sobe acima de",
        "Battery saver on" to "Economia de bateria lig.",
        "Battery saver off" to "Economia de bateria desl.",
        "Boost mode on" to "Modo Boost ligado",
        "Boost mode off" to "Modo Boost desligado",
        "Battery temperature high" to "Temperatura da bateria alta",
        "Battery cooled down" to "Bateria esfriou",
        "Device & Screen" to "Dispositivo e Tela",
        "Phone unlocked" to "Celular desbloqueado",
        "Phone locked" to "Celular bloqueado",
        "Screen turns on" to "Tela liga",
        "Screen turns off" to "Tela desliga",
        "Screen off for a while" to "Tela desligada há um tempo",
        "Turned to landscape" to "Virou para paisagem",
        "Turned to portrait" to "Virou para retrato",
        "Do Not Disturb on" to "Não perturbe ligado",
        "Do Not Disturb off" to "Não perturbe desligado",
        "Sound mode: ring" to "Som: normal",
        "Sound mode: vibrate" to "Som: vibrar",
        "Sound mode: silent" to "Som: silencioso",
        "Dark mode on" to "Modo escuro ligado",
        "Dark mode off" to "Modo escuro desligado",
        "Apps & Games" to "Apps e Jogos",
        "App opened" to "App aberto",
        "App closed" to "App fechado",
        "Any game opened" to "Qualquer jogo aberto",
        "Game closed" to "Jogo fechado",
        "Schedule" to "Agenda",
        "At a time" to "Em um horário",
        "One-finger gesture" to "Gesto com um dedo",
        "Two-finger gesture" to "Gesto com dois dedos",

        // Condition groups & names
        "Connectivity" to "Conectividade",
        "Wi-Fi state" to "Estado do Wi-Fi",
        "Wi-Fi network" to "Rede Wi-Fi",
        "Bluetooth state" to "Estado do Bluetooth",
        "Headphones / audio" to "Fones / áudio",
        "Mobile data" to "Dados móveis",
        "Airplane mode" to "Modo avião",
        "Device & State" to "Dispositivo e Estado",
        "Screen / lock" to "Tela / bloqueio",
        "Do Not Disturb" to "Não perturbe",
        "Sound mode" to "Modo de som",
        "Dark mode" to "Modo escuro",
        "Orientation" to "Orientação",
        "Media playback" to "Reprodução de mídia",
        "Phone call" to "Chamada",

        // Action groups & names
        "Sound & Media" to "Som e Mídia",
        "Media volume" to "Volume de mídia",
        "Ring volume" to "Volume de toque",
        "Notification volume" to "Volume de notificações",
        "Alarm volume" to "Volume do alarme",
        "Display & Power" to "Tela e Energia",
        "Screen brightness" to "Brilho da tela",
        "Auto-brightness" to "Brilho automático",
        "Screen rotation" to "Rotação da tela",
        "Screen timeout" to "Tempo limite da tela",
        "Keep screen on" to "Manter tela ligada",
        "Extra dim" to "Escurecimento extra",
        "Grayscale" to "Escala de cinza",
        "Battery saver" to "Economia de bateria",
        "Boost mode" to "Modo Boost",
        "Lock screen" to "Bloquear tela",
        "Wake screen" to "Acordar tela",
        "Apps & Navigation" to "Apps e Navegação",
        "Open app" to "Abrir app",
        "Close specific apps" to "Fechar apps específicos",
        "Close all apps" to "Fechar todos os apps",
        "Revive apps" to "Reabrir apps",
        "Go to Home screen" to "Ir para tela inicial",
        "Open link / URL" to "Abrir link / URL",
        "Open link" to "Abrir link",
        "Temperature pill" to "Pílula de temperatura",
        "FPS counter" to "Contador de FPS",
        "Frame interpolation" to "Interpolação de quadros",
        "Super resolution" to "Super resolução",
        "Play together" to "Reproduzir juntos",
        "Stream to laptop" to "Espelhar no notebook",
        "CTS: two-finger hold" to "CTS: dois dedos",
        "CTS: power-button hold" to "CTS: botão ligar",
        "CTS: nav bar hold" to "CTS: barra de nav.",
        "Power button hold" to "Segurar botão ligar",
        "Utilities" to "Utilitários",
        "Show status pill" to "Mostrar pílula de status",
        "Vibrate (done)" to "Vibrar (concluído)",
        "Take screenshot" to "Capturar tela",
        "Run another routine" to "Rodar outra rotina",
        "Wait" to "Aguardar",
        "Run shell command" to "Executar comando shell",
        "16:10 screen" to "Tela 16:10",
        "Portrait lock" to "Retrato fixo",
        "Auto-rotate" to "Rotação auto",
        "Landscape" to "Paisagem",
        "Auto" to "Auto",
        "Play / Pause" to "Tocar / Pausar",
        "Play" to "Tocar",
        "Pause" to "Pausar",
        "Next" to "Próxima",
        "Previous" to "Anterior",
        "Stop" to "Parar",
        "Normal screen timeout" to "Tempo limite normal",
        "Screen timeout back to before" to "Tempo da tela como antes",

        // Gaming & Audio
        "Temperature overlay" to "Pílula de temperatura",
        "Temp pill" to "Temperatura",
        "Super res" to "Super res.",
        "Stream" to "Espelhar",
        "Fine volume" to "Volume fino",
        "Knob" to "Botão",
        "Slider" to "Controle",
        "Media volume in 1 % steps. The volume buttons still jump 10 % at a time." to
            "Volume de mídia em passos de 1 %. Os botões físicos continuam variando 10 %.",
        "Keeps vivo frame interpolation on in listed games" to
            "Mantém a interpolação da vivo ativa nos jogos listados",
        "No listed game running" to "Nenhum jogo listado aberto",
        "vivo super resolution, kept on while enabled" to
            "Super resolução da vivo mantida ativa enquanto ligada",
        "Apps keep playing when another starts sound. Calls still pause them." to
            "Apps continuam tocando quando outro inicia áudio. Chamadas ainda pausam.",
        "No app has asked for audio yet" to "Nenhum app pediu áudio ainda",
        "Frames per second on screen, in its own pill: left of the temperature pill in landscape, above it in portrait." to
            "Quadros por segundo na tela: à esquerda da temperatura em paisagem e acima em retrato.",
        "Games" to "Jogos",
        "Tap the ratio to change it. Only whole multiples work." to
            "Toque na taxa para alterar. Apenas múltiplos inteiros funcionam.",
        "package name, e.g. com.game.app" to "nome do pacote, ex.: com.game.app",
        "Add" to "Add",
        "Enter a package name" to "Digite um nome de pacote",
        "Already in the list" to "Já está na lista",
        "No app with that package is installed" to "Nenhum app com este pacote instalado",
        "Heat pause (battery)" to "Pausa por calor (bateria)",
        "Gray numbers" to "Números cinzas",
        "Show CPU & GPU load" to "Mostrar carga de CPU e GPU",
        "Load as bars (upright in portrait)" to "Carga em barras verticais",
        "Blur background" to "Desfocar fundo",
        "Also a Quick Settings tile" to "Também disponível nos Ajustes Rápidos",
        "Add tile" to "Adicionar bloco",
        "Streaming" to "Espelhando",
        "Laptop shape on" to "Modo 16:10 ativo",
        "Laptop not reachable — screen resets in 15 s" to
            "Notebook inacessível — a tela volta em 15 s",
        "Phone screen matches the laptop. Start the stream when the game is open." to
            "Tela ajustada ao notebook. Inicie o espelhamento com o jogo aberto.",
        "Plug in the cable; the laptop helper must be running." to
            "Conecte o cabo USB; o assistente no notebook deve estar aberto.",
        "Normal screen" to "Tela normal",
        "Stop stream" to "Parar espelh.",
        "Start stream" to "Espelhar",
        "The phone stays the touch controller, dimmed. Exit from the round button on the phone, or Ctrl+Alt+X on the laptop. If the cable is pulled, the screen goes back to normal by itself." to
            "O celular vira um touchpad escurecido. Saia pelo botão redondo ou Ctrl+Alt+X no PC.",
        "Apps on the bubble" to "Apps no botão flutuante",
        "Launch buttons in the bubble's panel, plus Back and Home." to
            "Atalhos rápidos no painel da bolha, além de Voltar e Início.",
        "Laptop helper not found — is the cable in and the helper running?" to
            "Assistente do PC não encontrado — verifique o cabo e o programa.",
    )

    // ------------------------------------------------------------------ dynamic & compound strings

    private val rxAutomation = Regex("""^(\d+)/(\d+) routines · (\d+)/(\d+) profiles(?: · Active: (.+))?$""")
    private val rxVolume = Regex("""^(Mixing · )?Volume (\d+)%$""")
    private val rxGestActive = Regex("""^(\d+) gestures? active$""")
    private val rxListeningGest = Regex("""^Listening for (\d+) gestures?$""")
    private val rxPausedApps = Regex("""^(\d+) apps? paused · tap an app to unpause$""")
    private val rxSelected = Regex("""^(\d+) selected$""")
    private val rxNApps = Regex("""^(\d+) apps$""")
    private val rxNNet = Regex("""^(\d+) net$""")
    private val rxNDev = Regex("""^(\d+) dev$""")
    private val rxHoldMs = Regex("""^Hold (\d+) ms$""")
    private val rxWinMs = Regex("""^Time between presses: (\d+) ms$""")
    private val rxForDur = Regex("""^For (\d+) (min|sec)$""")
    private val rxCheckMin = Regex("""^Check every (\d+) min$""")
    private val rxOffMin = Regex("""^Off for (\d+) min$""")
    private val rxLevel = Regex("""^Level: (\d+)%$""")
    private val rxTempAbove = Regex("""^At or above (\d+) °C$""")
    private val rxTempBelow = Regex("""^At or below (\d+) °C$""")
    private val rxActiveName = Regex("""^Active: (.+)$""")
    private val rxPlayingWith = Regex("""^Playing together: (.+)$""")
    private val rxCtsActive = Regex("""^(\d+) active$""")
    private val rxCtsEnabled = Regex("""^(\d+) enabled$""")
    private val rxFracRunning = Regex("""^(\d+)/(\d+) running$""")
    private val rxRunningNamed = Regex("""^Running “(.+)”$""")
    private val rxKeepN = Regex("""^Keep (\d+)$""")
    private val rxAfter = Regex("""^After (.+)$""")
    private val rxBefore = Regex("""^Before (.+)$""")
    private val rxFingersBadge = Regex("""^(\d+)f$""")
    private val rxUpdateAvail = Regex("""^Update available: (.+)$""")
    private val rxDeleteQuote = Regex("""^Delete “(.+)”\?$""")
    private val rxEditQuote = Regex("""^Edit “(.+)”$""")
    private val rxRemoveApp = Regex("""^Remove (.+) from the list\?$""")
    private val rxForgetDrawings = Regex("""^Forget my drawings \((\d+)\)$""")
    private val rxRunsList = Regex("""^Runs: (.+)$""")
    private val rxNotAvail = Regex("""^Not available with (.+)$""")
    private val rxNothingAssigned = Regex("""^Nothing assigned to it with (.+) yet$""")
    private val rxNotRecognised = Regex("""^Not recognised  ·  (.+)$""")
    private val rxClosest = Regex("""^Closest: (.+)$""")
    private val rxTempDetail = Regex("""^CPU · GPU · Battery °C( \+ CPU · GPU load)? — drag to move, hold 1 s to turn off$""")
    private val rxReviveAct = Regex("""^Revive (\d+) apps$""")
    private val rxCloseAppsCount = Regex("""^Close (\d+) apps$""")
    private val rxCloseAllKeep = Regex("""^Close all apps \(keep (\d+)\)$""")
    private val rxLaunchBgCount = Regex("""^Launch (\d+) apps in bg$""")
    private val rxYestTime = Regex("""^yest (\d\d:\d\d)$""")
    private val rxSolCubeVer = Regex("""^SolCube (.+)$""")

    private fun translateDynamic(s: String, lang: String): String {
        val ru = lang == LANG_RU
        val exactMap = if (ru) ruExact else ptExact

        rxAutomation.matchEntire(s)?.let { m ->
            val (rOn, rAll, pOn, pAll, act) = m.destructured
            val cleanBase = if (ru) "$rOn/$rAll сценариев · $pOn/$pAll профилей" else "$rOn/$rAll rotinas · $pOn/$pAll perfis"
            return if (act.isEmpty()) cleanBase else cleanBase + (if (ru) " · Активен: $act" else " · Ativo: $act")
        }
        rxVolume.matchEntire(s)?.let { m ->
            val mix = m.groupValues[1].isNotEmpty()
            val pct = m.groupValues[2]
            return if (ru) (if (mix) "Микс · " else "") + "Громкость $pct%" else (if (mix) "Mix · " else "") + "Volume $pct%"
        }
        rxGestActive.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            return if (ru) "Активно жестов: $n" else "$n " + (if (n == 1) "gesto ativo" else "gestos ativos")
        }
        rxListeningGest.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            return if (ru) "Активно жестов: $n" else "Ouvindo $n " + (if (n == 1) "gesto" else "gestos")
        }
        rxPausedApps.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            return if (ru) "На паузе в $n прил. · нажмите, чтобы убрать" else "Pausado em $n " + (if (n == 1) "app" else "apps") + " · toque para remover"
        }
        rxSelected.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return if (ru) "Выбрано: $n" else "$n selecionado(s)"
        }
        rxNApps.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return if (ru) "$n прил." else "$n apps"
        }
        rxNNet.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return if (ru) "$n сетей" else "$n redes"
        }
        rxNDev.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return if (ru) "$n устр." else "$n disp."
        }
        rxHoldMs.matchEntire(s)?.let { m ->
            val ms = m.groupValues[1]
            return if (ru) "Удержание $ms мс" else "Segurar $ms ms"
        }
        rxWinMs.matchEntire(s)?.let { m ->
            val ms = m.groupValues[1]
            return if (ru) "Интервал между нажатиями: $ms мс" else "Tempo entre toques: $ms ms"
        }
        rxForDur.matchEntire(s)?.let { m ->
            val v = m.groupValues[1]
            val u = m.groupValues[2]
            return if (ru) "В течение $v " + (if (u == "sec") "с" else "мин") else "Por $v " + (if (u == "sec") "s" else "min")
        }
        rxCheckMin.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return if (ru) "Проверять каждые $n мин" else "Verificar a cada $n min"
        }
        rxOffMin.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return if (ru) "Выключен $n мин" else "Desligada por $n min"
        }
        rxLevel.matchEntire(s)?.let { m ->
            val p = m.groupValues[1]
            return if (ru) "Уровень: $p%" else "Nível: $p%"
        }
        rxTempAbove.matchEntire(s)?.let { m ->
            return if (ru) "От ${m.groupValues[1]} °C и выше" else "A partir de ${m.groupValues[1]} °C"
        }
        rxTempBelow.matchEntire(s)?.let { m ->
            return if (ru) "До ${m.groupValues[1]} °C и ниже" else "Até ${m.groupValues[1]} °C"
        }
        rxActiveName.matchEntire(s)?.let { m ->
            return (if (ru) "Активен: " else "Ativo: ") + m.groupValues[1]
        }
        rxPlayingWith.matchEntire(s)?.let { m ->
            return (if (ru) "Играют вместе: " else "Tocando juntos: ") + m.groupValues[1]
        }
        rxCtsActive.matchEntire(s)?.let { m ->
            return (if (ru) "Активно: " else "Ativos: ") + m.groupValues[1]
        }
        rxCtsEnabled.matchEntire(s)?.let { m ->
            return (if (ru) "Включено: " else "Ativados: ") + m.groupValues[1]
        }
        rxFracRunning.matchEntire(s)?.let { m ->
            return if (ru) "Работает ${m.groupValues[1]}/${m.groupValues[2]}" else "${m.groupValues[1]}/${m.groupValues[2]} ativos"
        }
        rxRunningNamed.matchEntire(s)?.let { m ->
            return if (ru) "Запуск «${m.groupValues[1]}»" else "Executando “${m.groupValues[1]}”"
        }
        rxKeepN.matchEntire(s)?.let { m ->
            return if (ru) "Оставить ${m.groupValues[1]}" else "Manter ${m.groupValues[1]}"
        }
        rxAfter.matchEntire(s)?.let { m ->
            return (if (ru) "После: " else "Após ") + m.groupValues[1]
        }
        rxBefore.matchEntire(s)?.let { m ->
            return (if (ru) "До: " else "Antes ") + m.groupValues[1]
        }
        rxFingersBadge.matchEntire(s)?.let { m ->
            return if (ru) "${m.groupValues[1]}п" else "${m.groupValues[1]}d"
        }
        rxUpdateAvail.matchEntire(s)?.let { m ->
            return (if (ru) "Доступно обновление: " else "Atualização disponível: ") + m.groupValues[1]
        }
        rxDeleteQuote.matchEntire(s)?.let { m ->
            return if (ru) "Удалить «${m.groupValues[1]}»?" else "Excluir “${m.groupValues[1]}”?"
        }
        rxEditQuote.matchEntire(s)?.let { m ->
            return if (ru) "Изменить «${m.groupValues[1]}»" else "Editar “${m.groupValues[1]}”"
        }
        rxRemoveApp.matchEntire(s)?.let { m ->
            return if (ru) "Удалить ${m.groupValues[1]} из списка?" else "Remover ${m.groupValues[1]} da lista?"
        }
        rxForgetDrawings.matchEntire(s)?.let { m ->
            return if (ru) "Удалить мои образцы (${m.groupValues[1]})" else "Esquecer meus desenhos (${m.groupValues[1]})"
        }
        rxRunsList.matchEntire(s)?.let { m ->
            return (if (ru) "Запускает: " else "Executa: ") + m.groupValues[1]
        }
        rxNotAvail.matchEntire(s)?.let { m ->
            return (if (ru) "Недоступно: " else "Indisponível com ") + t(m.groupValues[1])
        }
        rxNothingAssigned.matchEntire(s)?.let { m ->
            return if (ru) "Для «${t(m.groupValues[1])}» пока ничего не назначено"
            else "Nada atribuído com ${t(m.groupValues[1])} ainda"
        }
        rxNotRecognised.matchEntire(s)?.let { m ->
            return (if (ru) "Не распознано  ·  " else "Não reconhecido  ·  ") + t(m.groupValues[1])
        }
        rxClosest.matchEntire(s)?.let { m ->
            return (if (ru) "Похоже на: " else "Mais próximo: ") + m.groupValues[1]
        }
        rxTempDetail.matchEntire(s)?.let { m ->
            val hasLoad = m.groupValues[1].isNotEmpty()
            return if (ru) {
                "CPU · GPU · Батарея °C" + (if (hasLoad) " + нагрузка CPU · GPU" else "") + " — перетащите, удерживайте 1 с для выкл."
            } else {
                "CPU · GPU · Bateria °C" + (if (hasLoad) " + carga CPU · GPU" else "") + " — arraste ou segure 1 s para fechar"
            }
        }
        rxReviveAct.matchEntire(s)?.let { m ->
            return if (ru) "Перезапустить ${m.groupValues[1]} прил." else "Reabrir ${m.groupValues[1]} apps"
        }
        rxCloseAppsCount.matchEntire(s)?.let { m ->
            return if (ru) "Закрыть ${m.groupValues[1]} прил." else "Fechar ${m.groupValues[1]} apps"
        }
        rxCloseAllKeep.matchEntire(s)?.let { m ->
            return if (ru) "Закрыть все (кроме ${m.groupValues[1]})" else "Fechar tudo (manter ${m.groupValues[1]})"
        }
        rxLaunchBgCount.matchEntire(s)?.let { m ->
            return if (ru) "Запустить в фоне: ${m.groupValues[1]}" else "Abrir ${m.groupValues[1]} em 2º plano"
        }
        rxYestTime.matchEntire(s)?.let { m ->
            return (if (ru) "вчера " else "ontem ") + m.groupValues[1]
        }
        rxSolCubeVer.matchEntire(s)?.let {
            return s
        }

        if (s.startsWith("You have ") && s.contains("Install the new APK over this one")) {
            val lines = s.split("\n\n")
            val cur = lines.firstOrNull()?.removePrefix("You have ")?.removeSuffix(".") ?: ""
            val notes = lines.drop(1).dropLast(1).joinToString("\n\n")
            return if (ru) {
                "У вас установлена $cur.\n\n$notes\n\nУстановите новый APK поверх текущей версии — все настройки сохранятся."
            } else {
                "Você tem a versão $cur.\n\n$notes\n\nInstale o novo APK por cima deste — suas configurações serão mantidas."
            }
        }
        if (s.startsWith("Want “") || s.startsWith("Double-press the power button to ") || s.startsWith("Hold the power button (screen on) to ")) {
            val idx = s.indexOf(" to ")
            if (idx > 0) {
                val head = s.substring(0, idx)
                val act = s.substring(idx + 4).removeSuffix(".")
                val tHead = when (head) {
                    "Double-press the power button" -> if (ru) "Двойное нажатие питания: " else "Toque duplo no botão ligar: "
                    "Hold the power button (screen on)" -> if (ru) "Удержание кнопки питания: " else "Segurar botão ligar (tela acesa): "
                    else -> null
                }
                if (tHead != null) return "$tHead${translatePhrase(act, ru)}."
            }
        }
        if ("  →  " in s) {
            val idx = s.indexOf("  →  ")
            val left = s.substring(0, idx)
            val right = s.substring(idx + 5)
            return "${exactMap[left] ?: left}  →  ${exactMap[right] ?: translateDynamic(right, lang)}"
        }
        if (" · " in s) {
            val parts = s.split(" · ")
            if (parts.all { it in exactMap || rxActiveName.matches(it) }) {
                return parts.joinToString(" · ") { p -> exactMap[p] ?: translateDynamic(p, lang) }
            }
        }
        return s
    }

    private fun translatePhrase(phrase: String, ru: Boolean): String {
        val exact = when (phrase) {
            "open Circle to Search" -> if (ru) "открыть Circle to Search" else "abrir o Circle to Search"
            "go Ring → Vibrate → Silent" -> if (ru) "переключить Звук → Вибро → Без звука" else "alternar Som → Vibrar → Silencioso"
            "turn silent mode on or off" -> if (ru) "включить или выключить беззвучный режим" else "ativar ou desativar o modo silencioso"
            "turn vibrate mode on or off" -> if (ru) "включить или выключить вибрацию" else "ativar ou desativar a vibração"
            "open the app picked below" -> if (ru) "открыть выбранное приложение" else "abrir o app escolhido abaixo"
            "run the action picked below" -> if (ru) "выполнить выбранное действие" else "executar a ação escolhida abaixo"
            else -> null
        }
        if (exact != null) return exact
        if (phrase.startsWith("open ")) {
            return (if (ru) "открыть " else "abrir ") + phrase.removePrefix("open ")
        }
        return phrase
    }
}
