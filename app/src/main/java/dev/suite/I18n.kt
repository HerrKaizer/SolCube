package dev.suite

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import org.json.JSONObject
import java.util.Locale

/**
 * In-app localization loaded lazily per active language from assets/i18n/<lang>.json.
 * English strings stay canonical UI keys so switching languages via recreate() is instant.
 */
object I18n {
    const val LANG_SYSTEM = "system"
    const val LANG_EN = "en"
    const val LANG_RU = "ru"
    const val LANG_PT = "pt"
    const val LANG_PL = "pl"
    const val LANG_ES = "es"
    const val LANG_DE = "de"
    const val LANG_FR = "fr"
    const val LANG_IT = "it"
    const val LANG_ZH = "zh"

    val SUPPORTED = listOf(LANG_EN, LANG_RU, LANG_PT, LANG_PL, LANG_ES, LANG_DE, LANG_FR, LANG_IT, LANG_ZH)

    private const val PREF_UI = "ui"
    private const val KEY_LANG = "app_lang"

    @Volatile
    private var activeLang: String = LANG_EN

    @Volatile
    private var loadedLang: String = LANG_EN

    @Volatile
    private var exactMap: Map<String, String> = emptyMap()

    data class Grammar(
        val code: String,
        val whenWord: String,
        val doWord: String,
        val orWord: String,
        val andWord: String,
        val ifWord: String,
        val afterWord: String,
        val beforeWord: String,
        val secUnit: String,
        val secWord: String,
        val minUnit: String,
        val msUnit: String,
        val fingersBadge: String,
        val openVerb: String,
        val closeVerb: String,
        val runVerb: String,
        val launchVerb: String,
        val launchBgSuffix: String,
        val togglePrefix: String,
        val mediaPrefix: String,
        val pillWord: String,
        val useColonForActionValue: Boolean,
        val activePrefix: String,
        val playingTogetherPrefix: String,
        val activeCountPrefix: String,
        val enabledCountPrefix: String,
        val runningVerbPrefix: String,
        val keepCountPrefix: String,
        val afterPrefix: String,
        val beforePrefix: String,
        val updateAvailPrefix: String,
        val notAvailWith: String,
        val notRecognisedPrefix: String,
        val closestPrefix: String,
        val runsPrefix: String,
        val batteryLabel: String,
        val tempLabel: String,
        val wifiAnyLabel: String,
        val wifiNotAnyLabel: String,
        val wifiOnPrefix: String,
        val wifiNotOnPrefix: String,
        val btAnyLabel: String,
        val btNoneLabel: String,
        val btConnSuffix: String,
        val btDiscSuffix: String,
        val appInPrefix: String,
        val appNotInPrefix: String,
        val appEmptyCond: String,
        val gesturePrefix: String,
        val gestureNone: String,
        val customGestureName: String,
        val yestWord: String,
        val doublePressPrompt: String,
        val holdPowerPrompt: String,
    )

    private val EN_GRAMMAR = Grammar(
        code = LANG_EN, whenWord = "When", doWord = "do", orWord = " or ", andWord = " and ",
        ifWord = "if ", afterWord = "after ", beforeWord = "before ",
        secUnit = "s", secWord = "sec", minUnit = "min", msUnit = "ms", fingersBadge = "f",
        openVerb = "Open", closeVerb = "Close", runVerb = "Run", launchVerb = "Launch",
        launchBgSuffix = "in bg", togglePrefix = "Toggle ", mediaPrefix = "Media: ", pillWord = "Pill",
        useColonForActionValue = false,
        activePrefix = "Active: ", playingTogetherPrefix = "Playing together: ",
        activeCountPrefix = "active", enabledCountPrefix = "enabled", runningVerbPrefix = "Running",
        keepCountPrefix = "Keep ", afterPrefix = "After ", beforePrefix = "Before ",
        updateAvailPrefix = "Update available: ", notAvailWith = "Not available with ",
        notRecognisedPrefix = "Not recognised  ·  ", closestPrefix = "Closest: ",
        runsPrefix = "Runs: ", batteryLabel = "Battery", tempLabel = "Temp",
        wifiAnyLabel = "On any Wi-Fi", wifiNotAnyLabel = "Not on any Wi-Fi",
        wifiOnPrefix = "On ", wifiNotOnPrefix = "Not on ",
        btAnyLabel = "Any BT connected", btNoneLabel = "No BT connected",
        btConnSuffix = "connected", btDiscSuffix = "disconnected",
        appInPrefix = "In ", appNotInPrefix = "Not in ", appEmptyCond = "App condition",
        gesturePrefix = "Gesture", gestureNone = "Gesture (none picked)", customGestureName = "custom gesture",
        yestWord = "yest ", doublePressPrompt = "Double-press the power button",
        holdPowerPrompt = "Hold the power button (screen on)",
    )

    private val GRAMMARS: Map<String, Grammar> = mapOf(
        LANG_EN to EN_GRAMMAR,
        LANG_RU to Grammar(
            code = LANG_RU, whenWord = "Когда", doWord = "выполнить:", orWord = " или ", andWord = " и ",
            ifWord = "если ", afterWord = "после ", beforeWord = "до ",
            secUnit = "с", secWord = "с", minUnit = "мин", msUnit = "мс", fingersBadge = "п",
            openVerb = "Открыть", closeVerb = "Закрыть", runVerb = "Запустить", launchVerb = "Запустить",
            launchBgSuffix = "в фоне", togglePrefix = "Переключить: ", mediaPrefix = "Медиа: ", pillWord = "Островок",
            useColonForActionValue = true,
            activePrefix = "Активно: ", playingTogetherPrefix = "Звучат вместе: ",
            activeCountPrefix = "активно", enabledCountPrefix = "включено", runningVerbPrefix = "Запуск",
            keepCountPrefix = "Оставить ", afterPrefix = "После: ", beforePrefix = "До: ",
            updateAvailPrefix = "Доступно обновление: ", notAvailWith = "Недоступно: ",
            notRecognisedPrefix = "Не распознано · ", closestPrefix = "Похоже на: ",
            runsPrefix = "Запускает: ", batteryLabel = "Заряд", tempLabel = "Темп.",
            wifiAnyLabel = "В любой сети Wi-Fi", wifiNotAnyLabel = "Не подключён к Wi-Fi",
            wifiOnPrefix = "В ", wifiNotOnPrefix = "Не в ",
            btAnyLabel = "Любое устройство Bluetooth", btNoneLabel = "Bluetooth не подключён",
            btConnSuffix = "подкл.", btDiscSuffix = "откл.",
            appInPrefix = "В ", appNotInPrefix = "Не в ", appEmptyCond = "Текущее приложение",
            gesturePrefix = "Жест", gestureNone = "Жест (не выбран)", customGestureName = "свой жест",
            yestWord = "вчера ", doublePressPrompt = "Двойное нажатие питания: ",
            holdPowerPrompt = "Удержание кнопки питания: ",
        ),
        LANG_PT to Grammar(
            code = LANG_PT, whenWord = "Quando", doWord = "executar:", orWord = " ou ", andWord = " e ",
            ifWord = "se ", afterWord = "após ", beforeWord = "antes de ",
            secUnit = "s", secWord = "s", minUnit = "min", msUnit = "ms", fingersBadge = "d",
            openVerb = "Abrir", closeVerb = "Fechar", runVerb = "Executar", launchVerb = "Abrir",
            launchBgSuffix = "em 2º plano", togglePrefix = "Alternar ", mediaPrefix = "Mídia: ", pillWord = "Pílula",
            useColonForActionValue = true,
            activePrefix = "Ativo: ", playingTogetherPrefix = "Tocando juntos: ",
            activeCountPrefix = "ativos", enabledCountPrefix = "ativados", runningVerbPrefix = "Executando",
            keepCountPrefix = "Manter ", afterPrefix = "Após ", beforePrefix = "Antes de ",
            updateAvailPrefix = "Atualização disponível: ", notAvailWith = "Indisponível com ",
            notRecognisedPrefix = "Não reconhecido · ", closestPrefix = "Mais próximo: ",
            runsPrefix = "Executa: ", batteryLabel = "Bateria", tempLabel = "Temp.",
            wifiAnyLabel = "Em qualquer Wi-Fi", wifiNotAnyLabel = "Sem Wi-Fi",
            wifiOnPrefix = "Em ", wifiNotOnPrefix = "Fora de ",
            btAnyLabel = "Qualquer Bluetooth", btNoneLabel = "Nenhum Bluetooth",
            btConnSuffix = "conectado", btDiscSuffix = "desconectado",
            appInPrefix = "Em ", appNotInPrefix = "Fora de ", appEmptyCond = "App atual",
            gesturePrefix = "Gesto", gestureNone = "Gesto (nenhum)", customGestureName = "gesto personalizado",
            yestWord = "ontem ", doublePressPrompt = "Toque duplo no botão ligar: ",
            holdPowerPrompt = "Segurar botão ligar (tela acesa): ",
        ),
        LANG_PL to Grammar(
            code = LANG_PL, whenWord = "Kiedy", doWord = "wykonaj:", orWord = " lub ", andWord = " i ",
            ifWord = "jeśli ", afterWord = "po ", beforeWord = "przed ",
            secUnit = "s", secWord = "s", minUnit = "min", msUnit = "ms", fingersBadge = "p",
            openVerb = "Otwórz", closeVerb = "Zamknij", runVerb = "Uruchom", launchVerb = "Uruchom",
            launchBgSuffix = "w tle", togglePrefix = "Przełącz: ", mediaPrefix = "Media: ", pillWord = "Pigułka",
            useColonForActionValue = true,
            activePrefix = "Aktywne: ", playingTogetherPrefix = "Odtwarzane razem: ",
            activeCountPrefix = "aktywne", enabledCountPrefix = "włączone", runningVerbPrefix = "Uruchamianie",
            keepCountPrefix = "Zostaw ", afterPrefix = "Po: ", beforePrefix = "Przed: ",
            updateAvailPrefix = "Dostępna aktualizacja: ", notAvailWith = "Niedostępne: ",
            notRecognisedPrefix = "Nierozpoznano · ", closestPrefix = "Najbliżej: ",
            runsPrefix = "Uruchamia: ", batteryLabel = "Bateria", tempLabel = "Temp.",
            wifiAnyLabel = "W dowolnym Wi-Fi", wifiNotAnyLabel = "Poza Wi-Fi",
            wifiOnPrefix = "W ", wifiNotOnPrefix = "Poza ",
            btAnyLabel = "Dowolny Bluetooth", btNoneLabel = "Brak Bluetooth",
            btConnSuffix = "połączono", btDiscSuffix = "rozłączono",
            appInPrefix = "W ", appNotInPrefix = "Poza ", appEmptyCond = "Bieżąca aplikacja",
            gesturePrefix = "Gest", gestureNone = "Gest (brak)", customGestureName = "własny gest",
            yestWord = "wczoraj ", doublePressPrompt = "Dwuklik przycisku zasilania: ",
            holdPowerPrompt = "Przytrzymanie zasilania: ",
        ),
        LANG_ES to Grammar(
            code = LANG_ES, whenWord = "Cuando", doWord = "hacer:", orWord = " o ", andWord = " y ",
            ifWord = "si ", afterWord = "después de ", beforeWord = "antes de ",
            secUnit = "s", secWord = "s", minUnit = "min", msUnit = "ms", fingersBadge = "d",
            openVerb = "Abrir", closeVerb = "Cerrar", runVerb = "Ejecutar", launchVerb = "Abrir",
            launchBgSuffix = "en 2.º plano", togglePrefix = "Alternar ", mediaPrefix = "Multimedia: ", pillWord = "Píldora",
            useColonForActionValue = true,
            activePrefix = "Activo: ", playingTogetherPrefix = "Sonando juntos: ",
            activeCountPrefix = "activos", enabledCountPrefix = "activados", runningVerbPrefix = "Ejecutando",
            keepCountPrefix = "Mantener ", afterPrefix = "Después de ", beforePrefix = "Antes de ",
            updateAvailPrefix = "Actualización disponible: ", notAvailWith = "No disponible con ",
            notRecognisedPrefix = "No reconocido · ", closestPrefix = "Más parecido: ",
            runsPrefix = "Ejecuta: ", batteryLabel = "Batería", tempLabel = "Temp.",
            wifiAnyLabel = "En cualquier Wi-Fi", wifiNotAnyLabel = "Sin Wi-Fi",
            wifiOnPrefix = "En ", wifiNotOnPrefix = "Fuera de ",
            btAnyLabel = "Cualquier Bluetooth", btNoneLabel = "Sin Bluetooth",
            btConnSuffix = "conectado", btDiscSuffix = "desconectado",
            appInPrefix = "En ", appNotInPrefix = "Fuera de ", appEmptyCond = "App actual",
            gesturePrefix = "Gesto", gestureNone = "Gesto (sin elegir)", customGestureName = "gesto personalizado",
            yestWord = "ayer ", doublePressPrompt = "Doble toque de encendido: ",
            holdPowerPrompt = "Mantener encendido: ",
        ),
        LANG_DE to Grammar(
            code = LANG_DE, whenWord = "Wenn", doWord = "dann:", orWord = " oder ", andWord = " und ",
            ifWord = "falls ", afterWord = "nach ", beforeWord = "vor ",
            secUnit = "s", secWord = "Sek.", minUnit = "Min.", msUnit = "ms", fingersBadge = "F",
            openVerb = "Öffnen", closeVerb = "Schließen", runVerb = "Ausführen", launchVerb = "Starten",
            launchBgSuffix = "im Hintergrund", togglePrefix = "Umschalten: ", mediaPrefix = "Medien: ", pillWord = "Pille",
            useColonForActionValue = true,
            activePrefix = "Aktiv: ", playingTogetherPrefix = "Parallele Wiedergabe: ",
            activeCountPrefix = "aktiv", enabledCountPrefix = "aktiviert", runningVerbPrefix = "Starte",
            keepCountPrefix = "Behalte ", afterPrefix = "Nach ", beforePrefix = "Vor ",
            updateAvailPrefix = "Update verfügbar: ", notAvailWith = "Nicht verfügbar mit ",
            notRecognisedPrefix = "Nicht erkannt · ", closestPrefix = "Ähnlich: ",
            runsPrefix = "Startet: ", batteryLabel = "Akku", tempLabel = "Temp.",
            wifiAnyLabel = "In beliebigem WLAN", wifiNotAnyLabel = "Kein WLAN",
            wifiOnPrefix = "In ", wifiNotOnPrefix = "Nicht in ",
            btAnyLabel = "Beliebiges Bluetooth", btNoneLabel = "Kein Bluetooth",
            btConnSuffix = "verbunden", btDiscSuffix = "getrennt",
            appInPrefix = "In ", appNotInPrefix = "Nicht in ", appEmptyCond = "Aktuelle App",
            gesturePrefix = "Geste", gestureNone = "Geste (keine)", customGestureName = "eigene Geste",
            yestWord = "gestern ", doublePressPrompt = "Doppeldruck auf Ein/Aus: ",
            holdPowerPrompt = "Ein/Aus-Taste gedrückt halten: ",
        ),
        LANG_FR to Grammar(
            code = LANG_FR, whenWord = "Quand", doWord = "faire :", orWord = " ou ", andWord = " et ",
            ifWord = "si ", afterWord = "après ", beforeWord = "avant ",
            secUnit = "s", secWord = "s", minUnit = "min", msUnit = "ms", fingersBadge = "d",
            openVerb = "Ouvrir", closeVerb = "Fermer", runVerb = "Exécuter", launchVerb = "Ouvrir",
            launchBgSuffix = "en arrière-plan", togglePrefix = "Basculer : ", mediaPrefix = "Média : ", pillWord = "Pilule",
            useColonForActionValue = true,
            activePrefix = "Actif : ", playingTogetherPrefix = "Lecture simultanée : ",
            activeCountPrefix = "actifs", enabledCountPrefix = "activés", runningVerbPrefix = "Exécution de",
            keepCountPrefix = "Garder ", afterPrefix = "Après ", beforePrefix = "Avant ",
            updateAvailPrefix = "Mise à jour disponible : ", notAvailWith = "Indisponible avec ",
            notRecognisedPrefix = "Non reconnu · ", closestPrefix = "Plus proche : ",
            runsPrefix = "Lance : ", batteryLabel = "Batterie", tempLabel = "Temp.",
            wifiAnyLabel = "Sur tout Wi-Fi", wifiNotAnyLabel = "Hors Wi-Fi",
            wifiOnPrefix = "Sur ", wifiNotOnPrefix = "Hors de ",
            btAnyLabel = "Tout Bluetooth connecté", btNoneLabel = "Aucun Bluetooth",
            btConnSuffix = "connecté", btDiscSuffix = "déconnecté",
            appInPrefix = "Dans ", appNotInPrefix = "Hors de ", appEmptyCond = "App actuelle",
            gesturePrefix = "Geste", gestureNone = "Geste (aucun)", customGestureName = "geste perso",
            yestWord = "hier ", doublePressPrompt = "Double appui sur marche/arrêt : ",
            holdPowerPrompt = "Maintien du bouton marche/arrêt : ",
        ),
        LANG_IT to Grammar(
            code = LANG_IT, whenWord = "Quando", doWord = "esegui:", orWord = " o ", andWord = " e ",
            ifWord = "se ", afterWord = "dopo ", beforeWord = "prima di ",
            secUnit = "s", secWord = "s", minUnit = "min", msUnit = "ms", fingersBadge = "d",
            openVerb = "Apri", closeVerb = "Chiudi", runVerb = "Avvia", launchVerb = "Avvia",
            launchBgSuffix = "in background", togglePrefix = "Inverti: ", mediaPrefix = "Media: ", pillWord = "Pillola",
            useColonForActionValue = true,
            activePrefix = "Attivo: ", playingTogetherPrefix = "In riproduzione insieme: ",
            activeCountPrefix = "attivi", enabledCountPrefix = "attivati", runningVerbPrefix = "Avvio",
            keepCountPrefix = "Tieni ", afterPrefix = "Dopo ", beforePrefix = "Prima ",
            updateAvailPrefix = "Aggiornamento disponibile: ", notAvailWith = "Non disponibile con ",
            notRecognisedPrefix = "Non riconosciuto · ", closestPrefix = "Più simile: ",
            runsPrefix = "Avvia: ", batteryLabel = "Batteria", tempLabel = "Temp.",
            wifiAnyLabel = "Su qualsiasi Wi-Fi", wifiNotAnyLabel = "Senza Wi-Fi",
            wifiOnPrefix = "Su ", wifiNotOnPrefix = "Fuori da ",
            btAnyLabel = "Qualsiasi Bluetooth", btNoneLabel = "Nessun Bluetooth",
            btConnSuffix = "connesso", btDiscSuffix = "disconnesso",
            appInPrefix = "In ", appNotInPrefix = "Fuori da ", appEmptyCond = "App attuale",
            gesturePrefix = "Gesto", gestureNone = "Gesto (nessuno)", customGestureName = "gesto personalizzato",
            yestWord = "ieri ", doublePressPrompt = "Doppio tocco accensione: ",
            holdPowerPrompt = "Tieni tasto accensione: ",
        ),
        LANG_ZH to Grammar(
            code = LANG_ZH, whenWord = "当", doWord = "执行：", orWord = " 或 ", andWord = " 且 ",
            ifWord = "若", afterWord = "在 ", beforeWord = "在 ",
            secUnit = "秒", secWord = "秒", minUnit = "分钟", msUnit = "毫秒", fingersBadge = "指",
            openVerb = "打开", closeVerb = "关闭", runVerb = "运行", launchVerb = "后台启动",
            launchBgSuffix = "", togglePrefix = "切换：", mediaPrefix = "媒体：", pillWord = "胶囊",
            useColonForActionValue = true,
            activePrefix = "当前：", playingTogetherPrefix = "同时播放：",
            activeCountPrefix = "个已激活", enabledCountPrefix = "个已启用", runningVerbPrefix = "正在运行",
            keepCountPrefix = "保留 ", afterPrefix = "之后：", beforePrefix = "之前：",
            updateAvailPrefix = "有可用更新：", notAvailWith = "不可用于",
            notRecognisedPrefix = "未识别 · ", closestPrefix = "最接近：",
            runsPrefix = "运行：", batteryLabel = "电量", tempLabel = "温度",
            wifiAnyLabel = "已连接任意 Wi-Fi", wifiNotAnyLabel = "未连接任何 Wi-Fi",
            wifiOnPrefix = "连接 ", wifiNotOnPrefix = "未连接 ",
            btAnyLabel = "已连接任意蓝牙", btNoneLabel = "未连接蓝牙",
            btConnSuffix = "已连接", btDiscSuffix = "已断开",
            appInPrefix = "位于 ", appNotInPrefix = "不在 ", appEmptyCond = "当前应用",
            gesturePrefix = "手势", gestureNone = "手势（未选择）", customGestureName = "自定义手势",
            yestWord = "昨天 ", doublePressPrompt = "双击电源键：",
            holdPowerPrompt = "亮屏长按电源键：",
        ),
    )

    fun g(): Grammar = GRAMMARS[activeLang] ?: EN_GRAMMAR

    fun init(ctx: Context): String {
        val code = resolve(saved(ctx))
        activeLang = code
        ensureLoaded(ctx, code)
        return code
    }

    fun saved(ctx: Context): String =
        ctx.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE).getString(KEY_LANG, LANG_SYSTEM) ?: LANG_SYSTEM

    fun setLang(ctx: Context, code: String) {
        val norm = if (code in SUPPORTED) code else LANG_SYSTEM
        ctx.getSharedPreferences(PREF_UI, Context.MODE_PRIVATE).edit().putString(KEY_LANG, norm).commit()
        val resolved = resolve(norm)
        activeLang = resolved
        ensureLoaded(ctx, resolved)
    }

    fun current(): String = activeLang

    private fun deviceLang(): String {
        val sysLoc = runCatching { android.content.res.Resources.getSystem().configuration.locales.get(0) }.getOrNull()
            ?: Locale.getDefault()
        return sysLoc.language.lowercase(Locale.ROOT)
    }

    private fun resolve(savedCode: String): String {
        if (savedCode in SUPPORTED) return savedCode
        val sys = deviceLang()
        return if (sys in SUPPORTED) sys else LANG_EN
    }

    private fun ensureLoaded(ctx: Context, code: String) {
        if (code == LANG_EN) {
            exactMap = emptyMap()
            loadedLang = LANG_EN
            return
        }
        if (loadedLang == code && exactMap.isNotEmpty()) return
        val loaded = runCatching {
            val raw = ctx.assets.open("i18n/$code.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val j = JSONObject(raw)
            val m = HashMap<String, String>(j.length() * 4 / 3 + 1)
            val keys = j.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                m[k] = j.optString(k, k)
            }
            m
        }.getOrNull()
        if (loaded != null) {
            exactMap = loaded
            loadedLang = code
        }
    }

    fun nativeName(code: String): String = when (code) {
        LANG_EN -> "English"
        LANG_RU -> "Русский"
        LANG_PT -> "Português"
        LANG_PL -> "Polski"
        LANG_ES -> "Español"
        LANG_DE -> "Deutsch"
        LANG_FR -> "Français"
        LANG_IT -> "Italiano"
        LANG_ZH -> "中文"
        else -> t("System")
    }

    fun displaySummary(ctx: Context): String {
        val s = saved(ctx)
        val cur = init(ctx)
        return if (s == LANG_SYSTEM) "${t("System")} (${nativeName(cur)})" else nativeName(cur)
    }

    fun summaryKeywords(): Pair<String, String> = g().let { it.whenWord to it.doWord }

    fun buildSummary(whenClause: String, doClause: String, accentColor: Int): SpannableStringBuilder {
        val gr = g()
        val sb = SpannableStringBuilder()
        fun appendAccent(word: String) {
            val s = sb.length
            sb.append(word)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(accentColor), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        appendAccent(gr.whenWord)
        val afterWhen = if (gr.code == LANG_ZH && whenClause.firstOrNull()?.let { it.code >= 0x4E00 } == true) "" else " "
        val beforeDo = if (gr.code == LANG_ZH) "，" else ", "
        val afterDo = if (gr.code == LANG_ZH) "" else " "
        val endPunct = if (gr.code == LANG_ZH) "。" else "."
        sb.append(afterWhen).append(whenClause).append(beforeDo)
        appendAccent(gr.doWord)
        sb.append(afterDo).append(doClause).append(endPunct)
        return sb
    }

    fun pressActionDetail(
        isDouble: Boolean,
        kind: String?,
        targetName: String?,
        customDesc: String?,
    ): String {
        val gr = g()
        if (gr.code == LANG_EN) {
            val head = if (isDouble) "Double-press the power button to " else "Hold the power button (screen on) to "
            val phrase = when (kind) {
                "cts" -> "open Circle to Search"
                "ring:cycle" -> "go Ring → Vibrate → Silent"
                "ring:silent" -> "turn silent mode on or off"
                "ring:vibrate" -> "turn vibrate mode on or off"
                "custom" -> customDesc ?: "run the action picked below"
                else -> if (!targetName.isNullOrEmpty()) "open $targetName" else "open the app picked below"
            }
            return "$head$phrase."
        }
        val prompt = if (isDouble) gr.doublePressPrompt else gr.holdPowerPrompt
        val phrase = when (kind) {
            "cts" -> translatePhrase("open Circle to Search", gr.code)
            "ring:cycle" -> translatePhrase("go Ring → Vibrate → Silent", gr.code)
            "ring:silent" -> translatePhrase("turn silent mode on or off", gr.code)
            "ring:vibrate" -> translatePhrase("turn vibrate mode on or off", gr.code)
            "custom" -> customDesc ?: translatePhrase("run the action picked below", gr.code)
            else -> if (!targetName.isNullOrEmpty()) {
                if (gr.code == LANG_DE) "$targetName öffnen" else "${gr.openVerb} $targetName"
            } else {
                translatePhrase("open the app picked below", gr.code)
            }
        }
        return "$prompt$phrase"
    }

    private fun pluralRu(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        return when {
            m100 in 11..14 -> many
            n % 10 == 1 -> one
            n % 10 in 2..4 -> few
            else -> many
        }
    }

    private fun pluralPl(n: Int, one: String, few: String, many: String): String {
        val m100 = n % 100
        return when {
            n == 1 -> one
            m100 in 12..14 -> many
            n % 10 in 2..4 -> few
            else -> many
        }
    }

    private fun quote(s: String, lang: String): String = when (lang) {
        LANG_RU -> "«$s»"
        LANG_FR -> "« $s »"
        LANG_PL -> "„$s”"
        LANG_DE -> "„$s“"
        else -> "“$s”"
    }

    fun t(en: String): String {
        val l = activeLang
        if (l == LANG_EN || en.isBlank()) return en
        exactMap[en]?.let { return it }
        return translateDynamic(en, l)
    }

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
    private val rxRunsList = Regex("""^Runs: (.+?)( \(turned off\))?$""")
    private val rxNotAvail = Regex("""^Not available with (.+)$""")
    private val rxNothingAssigned = Regex("""^Nothing assigned to it with (.+) yet$""")
    private val rxNotRecognised = Regex("""^Not recognised  ·  (.+)$""")
    private val rxClosest = Regex("""^Closest: (.+?)(?: · then (.+))?$""")
    private val rxTempDetail = Regex("""^CPU · GPU · Battery °C( \+ CPU · GPU load)? — drag to move, hold 1 s to turn off$""")
    private val rxReviveAct = Regex("""^Revive (\d+) apps$""")
    private val rxCloseAppsCount = Regex("""^Close (\d+) apps$""")
    private val rxCloseAllKeep = Regex("""^Close all apps \(keep (\d+)\)$""")
    private val rxLaunchBgCount = Regex("""^Launch (\d+) apps in bg$""")
    private val rxYestTime = Regex("""^yest (\d\d:\d\d)$""")
    private val rxLatestVer = Regex("""^SolCube (.+) is the latest version\.$""")
    private val rxSolCubeVer = Regex("""^SolCube \d[\w.\-]*$""")
    private val rxRightNowGoesTo = Regex("""^Right now it goes to (.+)\.$""")
    private val rxDrawingOf = Regex("""^Drawing (\d+) of (\d+)$""")
    private val rxDoneDrawings = Regex("""^Done — (\d+) drawings$""")
    private val rxAlreadyGesture = Regex("""^There's already a gesture called (.+)$""")
    private val rxTeachName = Regex("""^Teach (.+)$""")
    private val rxDrawYourWay = Regex("""^Draw (.+) your way, (\d+) times\. SolCube keeps these next to its own version\.$""")
    private val rxLooksMoreLike = Regex("""^Note: these look more like (.+) \((\d+)%\)\. They might get mixed up\.$""")
    private val rxHeadsUpAlike = Regex("""^Heads-up: this is a lot like (.+) \((\d+)% alike\), so the two may get mixed up\. Start over to draw something more different, or save anyway\.$""")
    private val rxGestureAlreadyUsed = Regex("""^(.+) is already used with (.+) \((.+)\)\. A gesture belongs to one card at a time, so it can't be drawn both ways\.$""")
    private val rxAudioFocusErr = Regex("""^Couldn't take over audio focus: (.+)$""")
    private val rxBrightnessPct = Regex("""^Brightness (\d+)%$""")
    private val rxBgOpacityPct = Regex("""^Background opacity: (\d+)%$""")
    private val rxSizePct = Regex("""^Size: (\d+)%$""")

    private fun translateDynamic(s: String, lang: String): String {
        val gr = g()
        val map = exactMap

        rxAutomation.matchEntire(s)?.let { m ->
            val (rOn, rAll, pOn, pAll, act) = m.destructured
            val base = when (lang) {
                LANG_RU -> "Сценарии: $rOn/$rAll · Профили: $pOn/$pAll"
                LANG_PT -> "$rOn/$rAll rotinas · $pOn/$pAll perfis"
                LANG_PL -> "Rutyny: $rOn/$rAll · Profile: $pOn/$pAll"
                LANG_ES -> "$rOn/$rAll rutinas · $pOn/$pAll perfiles"
                LANG_DE -> "$rOn/$rAll Routinen · $pOn/$pAll Profile"
                LANG_FR -> "$rOn/$rAll routines · $pOn/$pAll profils"
                LANG_IT -> "$rOn/$rAll routine · $pOn/$pAll profili"
                LANG_ZH -> "$rOn/$rAll 个场景 · $pOn/$pAll 个配置"
                else -> "$rOn/$rAll routines · $pOn/$pAll profiles"
            }
            return if (act.isEmpty()) base else "$base · ${gr.activePrefix}$act"
        }
        rxVolume.matchEntire(s)?.let { m ->
            val mix = m.groupValues[1].isNotEmpty()
            val pct = m.groupValues[2]
            val mixTag = when (lang) {
                LANG_RU -> "Микшер · "
                LANG_PT -> "Mix · "
                LANG_PL -> "Miks · "
                LANG_ES -> "Mezcla · "
                LANG_DE -> "Mix · "
                LANG_FR -> "Mix · "
                LANG_IT -> "Mix · "
                LANG_ZH -> "混音 · "
                else -> "Mixing · "
            }
            val volWord = when (lang) {
                LANG_RU -> "Громкость "
                LANG_ES -> "Volumen "
                LANG_PL -> "Głośność "
                LANG_DE -> "Lautstärke "
                LANG_FR -> "Volume : "
                LANG_PT, LANG_IT -> "Volume "
                LANG_ZH -> "音量 "
                else -> "Volume "
            }
            return (if (mix) mixTag else "") + "$volWord$pct%"
        }
        rxGestActive.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "$n ${pluralRu(n, "активный жест", "активных жеста", "активных жестов")}"
                LANG_PT -> "$n " + if (n == 1) "gesto ativo" else "gestos ativos"
                LANG_PL -> "$n ${pluralPl(n, "aktywny gest", "aktywne gesty", "aktywnych gestów")}"
                LANG_ES -> "$n " + if (n == 1) "gesto activo" else "gestos activos"
                LANG_DE -> "$n " + if (n == 1) "Geste aktiv" else "Gesten aktiv"
                LANG_FR -> "$n " + if (n == 1) "geste actif" else "gestes actifs"
                LANG_IT -> "$n " + if (n == 1) "gesto attivo" else "gesti attivi"
                LANG_ZH -> "已激活 $n 个手势"
                else -> s
            }
        }
        rxListeningGest.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "Отслеживается: $n ${pluralRu(n, "жест", "жеста", "жестов")}"
                LANG_PT -> "Monitorando $n " + if (n == 1) "gesto" else "gestos"
                LANG_PL -> "Wykrywanie: $n ${pluralPl(n, "gest", "gesty", "gestów")}"
                LANG_ES -> "Detectando $n " + if (n == 1) "gesto" else "gestos"
                LANG_DE -> "Erkennt $n " + if (n == 1) "Geste" else "Gesten"
                LANG_FR -> "Détection de $n " + if (n == 1) "geste" else "gestes"
                LANG_IT -> "Rilevamento di $n " + if (n == 1) "gesto" else "gesti"
                LANG_ZH -> "正在监测 $n 个手势"
                else -> s
            }
        }
        rxPausedApps.matchEntire(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "На паузе в $n прил. · нажмите для снятия"
                LANG_PT -> "Pausado em $n " + (if (n == 1) "app" else "apps") + " · toque para retomar"
                LANG_PL -> "Wstrzymano w $n apl. · dotknij, aby wznowić"
                LANG_ES -> "En pausa en $n " + (if (n == 1) "app" else "apps") + " · toca para quitar"
                LANG_DE -> "In $n " + (if (n == 1) "App" else "Apps") + " pausiert · tippen zum Entfernen"
                LANG_FR -> "En pause dans $n " + (if (n == 1) "app" else "apps") + " · touchez pour retirer"
                LANG_IT -> "In pausa su $n app · tocca per rimuovere"
                LANG_ZH -> "已在 $n 个应用中暂停 · 点击移出"
                else -> s
            }
        }
        rxSelected.matchEntire(s)?.let { m ->
            val nStr = m.groupValues[1]
            val n = nStr.toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "Выбрано: $nStr"
                LANG_PT -> if (n == 1) "1 selecionado" else "$nStr selecionados"
                LANG_PL -> "Wybrano: $nStr"
                LANG_ES -> if (n == 1) "1 seleccionada" else "$nStr seleccionadas"
                LANG_DE -> "$nStr ausgewählt"
                LANG_FR -> if (n == 1) "1 sélectionnée" else "$nStr sélectionnées"
                LANG_IT -> if (n == 1) "1 selezionata" else "$nStr selezionate"
                LANG_ZH -> "已选 $nStr 个"
                else -> s
            }
        }
        rxNApps.matchEntire(s)?.let { m ->
            val nStr = m.groupValues[1]
            val n = nStr.toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "$nStr прил."
                LANG_PT, LANG_ES, LANG_FR -> if (n == 1) "1 app" else "$nStr apps"
                LANG_PL -> "$nStr apl."
                LANG_DE -> if (n == 1) "1 App" else "$nStr Apps"
                LANG_IT -> "$nStr app"
                LANG_ZH -> "$nStr 个应用"
                else -> "$nStr apps"
            }
        }
        rxNNet.matchEntire(s)?.let { m ->
            val nStr = m.groupValues[1]
            val n = nStr.toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "$nStr ${pluralRu(n, "сеть", "сети", "сетей")}"
                LANG_PT, LANG_ES -> if (n == 1) "1 rede" else "$nStr redes"
                LANG_PL -> "$nStr ${pluralPl(n, "sieć", "sieci", "sieci")}"
                LANG_DE -> if (n == 1) "1 Netz" else "$nStr Netze"
                LANG_FR -> if (n == 1) "1 réseau" else "$nStr réseaux"
                LANG_IT -> if (n == 1) "1 rete" else "$nStr reti"
                LANG_ZH -> "$nStr 个网络"
                else -> s
            }
        }
        rxNDev.matchEntire(s)?.let { m ->
            val nStr = m.groupValues[1]
            val n = nStr.toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "$nStr устр."
                LANG_PT, LANG_ES, LANG_IT -> "$nStr disp."
                LANG_PL -> "$nStr urz."
                LANG_DE -> "$nStr Ger."
                LANG_FR -> "$nStr pér."
                LANG_ZH -> "$nStr 个设备"
                else -> s
            }
        }
        rxHoldMs.matchEntire(s)?.let { m ->
            val ms = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Удержание $ms мс"
                LANG_PT -> "Segurar $ms ms"
                LANG_PL -> "Przytrzymanie $ms ms"
                LANG_ES -> "Mantener $ms ms"
                LANG_DE -> "Halten $ms ms"
                LANG_FR -> "Maintien $ms ms"
                LANG_IT -> "Pressione $ms ms"
                LANG_ZH -> "长按 $ms 毫秒"
                else -> s
            }
        }
        rxWinMs.matchEntire(s)?.let { m ->
            val ms = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Интервал между нажатиями: $ms мс"
                LANG_PT -> "Tempo entre toques: $ms ms"
                LANG_PL -> "Czas między kliknięciami: $ms ms"
                LANG_ES -> "Tiempo entre toques: $ms ms"
                LANG_DE -> "Zeit zwischen Klicks: $ms ms"
                LANG_FR -> "Délai entre appuis : $ms ms"
                LANG_IT -> "Intervallo tra tocchi: $ms ms"
                LANG_ZH -> "连击间隔时间：$ms 毫秒"
                else -> s
            }
        }
        rxForDur.matchEntire(s)?.let { m ->
            val v = m.groupValues[1]
            val unit = if (m.groupValues[2] == "sec") gr.secWord else gr.minUnit
            return when (lang) {
                LANG_RU -> "В течение $v $unit"
                LANG_PT -> "Por $v $unit"
                LANG_PL -> "Przez $v $unit"
                LANG_ES -> "Durante $v $unit"
                LANG_DE -> "Für $v $unit"
                LANG_FR -> "Pendant $v $unit"
                LANG_IT -> "Per $v $unit"
                LANG_ZH -> "持续 $v $unit"
                else -> s
            }
        }
        rxCheckMin.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Проверять каждые $n мин"
                LANG_PT -> "Verificar a cada $n min"
                LANG_PL -> "Sprawdzaj co $n min"
                LANG_ES -> "Comprobar cada $n min"
                LANG_DE -> "Alle $n Min. prüfen"
                LANG_FR -> "Vérifier toutes les $n min"
                LANG_IT -> "Controlla ogni $n min"
                LANG_ZH -> "每 $n 分钟检查"
                else -> s
            }
        }
        rxOffMin.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Выключен $n мин"
                LANG_PT -> "Desligada por $n min"
                LANG_PL -> "Wyłączony przez $n min"
                LANG_ES -> "Apagada durante $n min"
                LANG_DE -> "Aus für $n Min."
                LANG_FR -> "Éteint pendant $n min"
                LANG_IT -> "Spento per $n min"
                LANG_ZH -> "熄屏已达 $n 分钟"
                else -> s
            }
        }
        rxLevel.matchEntire(s)?.let { m ->
            val p = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Уровень: $p%"
                LANG_PT -> "Nível: $p%"
                LANG_PL -> "Poziom: $p%"
                LANG_ES -> "Nivel: $p%"
                LANG_DE -> "Stand: $p%"
                LANG_FR -> "Niveau : $p%"
                LANG_IT -> "Livello: $p%"
                LANG_ZH -> "电量：$p%"
                else -> s
            }
        }
        rxTempAbove.matchEntire(s)?.let { m ->
            val v = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "От $v °C и выше"
                LANG_PT -> "A partir de $v °C"
                LANG_PL -> "Od $v °C wzwyż"
                LANG_ES -> "$v °C o más"
                LANG_DE -> "Ab $v °C oder höher"
                LANG_FR -> "$v °C ou plus"
                LANG_IT -> "Da $v °C o oltre"
                LANG_ZH -> "达到或高于 $v °C"
                else -> s
            }
        }
        rxTempBelow.matchEntire(s)?.let { m ->
            val v = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "До $v °C и ниже"
                LANG_PT -> "Até $v °C"
                LANG_PL -> "Do $v °C lub niżej"
                LANG_ES -> "$v °C o menos"
                LANG_DE -> "$v °C oder kühler"
                LANG_FR -> "$v °C ou moins"
                LANG_IT -> "$v °C o meno"
                LANG_ZH -> "降至或低于 $v °C"
                else -> s
            }
        }
        rxActiveName.matchEntire(s)?.let { m -> return gr.activePrefix + m.groupValues[1] }
        rxPlayingWith.matchEntire(s)?.let { m -> return gr.playingTogetherPrefix + m.groupValues[1] }
        rxCtsActive.matchEntire(s)?.let { m ->
            val nStr = m.groupValues[1]
            val n = nStr.toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "Активно: $nStr"
                LANG_PT -> if (n == 1) "1 ativo" else "$nStr ativos"
                LANG_PL -> "Aktywne: $nStr"
                LANG_ES -> if (n == 1) "1 activo" else "$nStr activos"
                LANG_FR -> if (n == 1) "1 actif" else "$nStr actifs"
                LANG_IT -> if (n == 1) "1 attivo" else "$nStr attivi"
                LANG_ZH -> "$nStr 个已激活"
                else -> "$nStr ${gr.activeCountPrefix}"
            }
        }
        rxCtsEnabled.matchEntire(s)?.let { m ->
            val nStr = m.groupValues[1]
            val n = nStr.toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "Включено: $nStr"
                LANG_PT -> if (n == 1) "1 ativado" else "$nStr ativados"
                LANG_PL -> "Włączone: $nStr"
                LANG_ES -> if (n == 1) "1 activado" else "$nStr activados"
                LANG_FR -> if (n == 1) "1 activé" else "$nStr activés"
                LANG_IT -> if (n == 1) "1 attivato" else "$nStr attivati"
                LANG_ZH -> "$nStr 个已启用"
                else -> "$nStr ${gr.enabledCountPrefix}"
            }
        }
        rxFracRunning.matchEntire(s)?.let { m ->
            val (a, b) = m.destructured
            return when (lang) {
                LANG_RU -> "Работает $a/$b"
                LANG_PT -> "$a/$b ativos"
                LANG_PL -> "Działa $a/$b"
                LANG_ES -> "$a/$b activos"
                LANG_DE -> "$a/$b aktiv"
                LANG_FR -> "$a/$b actifs"
                LANG_IT -> "$a/$b attivi"
                LANG_ZH -> "运行中 $a/$b"
                else -> s
            }
        }
        rxRunningNamed.matchEntire(s)?.let { m -> return "${gr.runningVerbPrefix} ${quote(m.groupValues[1], lang)}" }
        rxKeepN.matchEntire(s)?.let { m -> return gr.keepCountPrefix + m.groupValues[1] }
        rxAfter.matchEntire(s)?.let { m -> return if (lang == LANG_ZH) "${m.groupValues[1]} 之后" else gr.afterPrefix + m.groupValues[1] }
        rxBefore.matchEntire(s)?.let { m -> return if (lang == LANG_ZH) "${m.groupValues[1]} 之前" else gr.beforePrefix + m.groupValues[1] }
        rxFingersBadge.matchEntire(s)?.let { m -> return m.groupValues[1] + gr.fingersBadge }
        rxUpdateAvail.matchEntire(s)?.let { m -> return gr.updateAvailPrefix + m.groupValues[1] }
        rxDeleteQuote.matchEntire(s)?.let { m ->
            val v = quote(m.groupValues[1], lang)
            return when (lang) {
                LANG_RU -> "Удалить $v?"
                LANG_PT -> "Excluir $v?"
                LANG_PL -> "Usunąć $v?"
                LANG_ES -> "¿Eliminar $v?"
                LANG_DE -> "$v löschen?"
                LANG_FR -> "Supprimer $v ?"
                LANG_IT -> "Eliminare $v?"
                LANG_ZH -> "删除 $v？"
                else -> s
            }
        }
        rxEditQuote.matchEntire(s)?.let { m ->
            val v = quote(m.groupValues[1], lang)
            return when (lang) {
                LANG_RU -> "Изменить $v"
                LANG_PT, LANG_ES -> "Editar $v"
                LANG_PL -> "Edytuj $v"
                LANG_DE -> "$v bearbeiten"
                LANG_FR -> "Modifier $v"
                LANG_IT -> "Modifica $v"
                LANG_ZH -> "编辑 $v"
                else -> s
            }
        }
        rxRemoveApp.matchEntire(s)?.let { m ->
            val v = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Удалить $v из списка?"
                LANG_PT -> "Remover $v da lista?"
                LANG_PL -> "Usunąć $v z listy?"
                LANG_ES -> "¿Quitar $v de la lista?"
                LANG_DE -> "$v aus der Liste entfernen?"
                LANG_FR -> "Retirer $v de la liste ?"
                LANG_IT -> "Rimuovere $v dalla lista?"
                LANG_ZH -> "从列表中移除 $v？"
                else -> s
            }
        }
        rxForgetDrawings.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Удалить мои образцы ($n)"
                LANG_PT -> "Esquecer meus desenhos ($n)"
                LANG_PL -> "Usuń moje rysunki ($n)"
                LANG_ES -> "Olvidar mis dibujos ($n)"
                LANG_DE -> "Meine Zeichnungen löschen ($n)"
                LANG_FR -> "Oublier mes tracés ($n)"
                LANG_IT -> "Dimentica i miei disegni ($n)"
                LANG_ZH -> "清除我的手势样本 ($n)"
                else -> s
            }
        }
        rxRunsList.matchEntire(s)?.let { m ->
            val list = m.groupValues[1]
            val off = m.groupValues[2].isNotEmpty()
            val offTag = if (!off) "" else when (lang) {
                LANG_RU -> " (выключен)"
                LANG_PT -> " (desativada)"
                LANG_PL -> " (wyłączona)"
                LANG_ES -> " (desactivada)"
                LANG_DE -> " (aus)"
                LANG_FR -> " (désactivée)"
                LANG_IT -> " (spenta)"
                LANG_ZH -> "（已关闭）"
                else -> " (turned off)"
            }
            return gr.runsPrefix + list + offTag
        }
        rxNotAvail.matchEntire(s)?.let { m -> return gr.notAvailWith + t(m.groupValues[1]) }
        rxNothingAssigned.matchEntire(s)?.let { m ->
            val v = t(m.groupValues[1])
            return when (lang) {
                LANG_RU -> "Для жеста (${v}) пока ничего не назначено"
                LANG_PT -> "Nada atribuído com $v ainda"
                LANG_PL -> "Brak przypisania dla: $v"
                LANG_ES -> "Nada asignado con $v aún"
                LANG_DE -> "Noch keine Aktion für $v"
                LANG_FR -> "Rien d'assigné avec $v pour l'instant"
                LANG_IT -> "Nulla assegnato con $v ancora"
                LANG_ZH -> "$v 尚未绑定任何动作"
                else -> s
            }
        }
        rxNotRecognised.matchEntire(s)?.let { m -> return gr.notRecognisedPrefix + t(m.groupValues[1]) }
        rxClosest.matchEntire(s)?.let { m ->
            val first = m.groupValues[1]
            val second = m.groupValues[2]
            val thenWord = when (lang) {
                LANG_RU -> " · далее "
                LANG_PT -> " · depois "
                LANG_PL -> " · potem "
                LANG_ES -> " · luego "
                LANG_DE -> " · dann "
                LANG_FR -> " · puis "
                LANG_IT -> " · poi "
                LANG_ZH -> " · 其次 "
                else -> " · then "
            }
            return gr.closestPrefix + first + if (second.isEmpty()) "" else "$thenWord$second"
        }
        rxTempDetail.matchEntire(s)?.let { m ->
            val hasLoad = m.groupValues[1].isNotEmpty()
            return when (lang) {
                LANG_RU -> "CPU · GPU · Батарея °C" + (if (hasLoad) " + нагрузка CPU · GPU" else "") + " — перетащите для перемещения, удерживайте 1 с, чтобы выключить"
                LANG_PT -> "CPU · GPU · Bateria °C" + (if (hasLoad) " + carga de CPU · GPU" else "") + " — arraste para mover, segure 1 s para desativar"
                LANG_PL -> "CPU · GPU · Bateria °C" + (if (hasLoad) " + użycie CPU · GPU" else "") + " — przeciągnij, przytrzymaj 1 s, aby wyłączyć"
                LANG_ES -> "CPU · GPU · Batería °C" + (if (hasLoad) " + carga CPU · GPU" else "") + " — arrastra para mover, mantén 1 s para desactivar"
                LANG_DE -> "CPU · GPU · Akku °C" + (if (hasLoad) " + CPU- & GPU-Last" else "") + " — ziehen zum Bewegen, 1 s halten zum Ausschalten"
                LANG_FR -> "CPU · GPU · Batterie °C" + (if (hasLoad) " + charge CPU · GPU" else "") + " — glisser pour déplacer, maintenir 1 s pour désactiver"
                LANG_IT -> "CPU · GPU · Batteria °C" + (if (hasLoad) " + carico CPU · GPU" else "") + " — trascina per spostare, tieni 1 s per disattivare"
                LANG_ZH -> "CPU · GPU · 电池 °C" + (if (hasLoad) " + CPU · GPU 负载" else "") + " — 可拖动，长按 1 秒关闭"
                else -> s
            }
        }
        rxReviveAct.matchEntire(s)?.let { m ->
            val nStr = m.groupValues[1]
            val n = nStr.toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "Перезапустить $nStr прил."
                LANG_PT -> if (n == 1) "Reabrir 1 app" else "Reabrir $nStr apps"
                LANG_PL -> "Wznów $nStr apl."
                LANG_ES -> if (n == 1) "Reabrir 1 app" else "Reabrir $nStr apps"
                LANG_DE -> if (n == 1) "1 App reaktivieren" else "$nStr Apps reaktivieren"
                LANG_FR -> if (n == 1) "Relancer 1 app" else "Relancer $nStr apps"
                LANG_IT -> "Riapri $nStr app"
                LANG_ZH -> "唤醒 $nStr 个应用"
                else -> s
            }
        }
        rxCloseAppsCount.matchEntire(s)?.let { m ->
            val nStr = m.groupValues[1]
            val n = nStr.toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "Закрыть $nStr прил."
                LANG_PT -> if (n == 1) "Fechar 1 app" else "Fechar $nStr apps"
                LANG_PL -> "Zamknij $nStr apl."
                LANG_ES -> if (n == 1) "Cerrar 1 app" else "Cerrar $nStr apps"
                LANG_DE -> if (n == 1) "1 App schließen" else "$nStr Apps schließen"
                LANG_FR -> if (n == 1) "Fermer 1 app" else "Fermer $nStr apps"
                LANG_IT -> "Chiudi $nStr app"
                LANG_ZH -> "关闭 $nStr 个应用"
                else -> s
            }
        }
        rxCloseAllKeep.matchEntire(s)?.let { m ->
            val n = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Закрыть все (кроме $n)"
                LANG_PT -> "Fechar tudo (manter $n)"
                LANG_PL -> "Zamknij wszystkie (zostaw $n)"
                LANG_ES -> "Cerrar todo (mantener $n)"
                LANG_DE -> "Alle schließen (behalte $n)"
                LANG_FR -> "Tout fermer (garder $n)"
                LANG_IT -> "Chiudi tutto (tieni $n)"
                LANG_ZH -> "关闭全部（保留 $n 个）"
                else -> s
            }
        }
        rxLaunchBgCount.matchEntire(s)?.let { m ->
            val nStr = m.groupValues[1]
            val n = nStr.toIntOrNull() ?: 0
            return when (lang) {
                LANG_RU -> "Запустить в фоне: $nStr"
                LANG_PT -> if (n == 1) "Abrir 1 em 2º plano" else "Abrir $nStr em 2º plano"
                LANG_PL -> "Uruchom $nStr w tle"
                LANG_ES -> if (n == 1) "Abrir 1 en 2.º plano" else "Abrir $nStr en 2.º plano"
                LANG_DE -> "$nStr im Hintergrund starten"
                LANG_FR -> if (n == 1) "Ouvrir 1 en arrière-plan" else "Ouvrir $nStr en arrière-plan"
                LANG_IT -> "Avvia $nStr in background"
                LANG_ZH -> "后台启动 $nStr 个应用"
                else -> s
            }
        }
        rxYestTime.matchEntire(s)?.let { m -> return gr.yestWord + m.groupValues[1] }
        rxLatestVer.matchEntire(s)?.let { m ->
            val v = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "SolCube $v — самая актуальная версия."
                LANG_PT -> "SolCube $v já é a versão mais recente."
                LANG_PL -> "SolCube $v to najnowsza wersja."
                LANG_ES -> "SolCube $v ya es la última versión."
                LANG_DE -> "SolCube $v ist die neueste Version."
                LANG_FR -> "SolCube $v est la dernière version."
                LANG_IT -> "SolCube $v è l'ultima versione."
                LANG_ZH -> "SolCube $v 已是最新版本。"
                else -> s
            }
        }
        rxSolCubeVer.matchEntire(s)?.let { return s }
        rxRightNowGoesTo.matchEntire(s)?.let { m ->
            val app = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Сейчас открывает $app."
                LANG_PT -> "No momento abre $app."
                LANG_PL -> "Obecnie otwiera $app."
                LANG_ES -> "Ahora mismo abre $app."
                LANG_DE -> "Öffnet aktuell $app."
                LANG_FR -> "Ouvre actuellement $app."
                LANG_IT -> "Al momento apre $app."
                LANG_ZH -> "当前关联应用：$app。"
                else -> s
            }
        }
        rxDrawingOf.matchEntire(s)?.let { m ->
            val (cur, total) = m.destructured
            return when (lang) {
                LANG_RU -> "Образец $cur из $total"
                LANG_PT -> "Desenho $cur de $total"
                LANG_PL -> "Rysunek $cur z $total"
                LANG_ES -> "Dibujo $cur de $total"
                LANG_DE -> "Zeichnung $cur von $total"
                LANG_FR -> "Tracé $cur sur $total"
                LANG_IT -> "Disegno $cur di $total"
                LANG_ZH -> "第 $cur / $total 次绘制"
                else -> s
            }
        }
        rxDoneDrawings.matchEntire(s)?.let { m ->
            val total = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Готово — $total образца"
                LANG_PT -> "Concluído — $total desenhos"
                LANG_PL -> "Gotowe — $total rysunki"
                LANG_ES -> "Listo — $total dibujos"
                LANG_DE -> "Fertig — $total Zeichnungen"
                LANG_FR -> "Terminé — $total tracés"
                LANG_IT -> "Fatto — $total disegni"
                LANG_ZH -> "完成 — 已录制 $total 次"
                else -> s
            }
        }
        rxAlreadyGesture.matchEntire(s)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Жест «$name» уже существует"
                LANG_PT -> "Já existe um gesto chamado $name"
                LANG_PL -> "Gest „$name” już istnieje"
                LANG_ES -> "Ya existe un gesto llamado $name"
                LANG_DE -> "Es gibt bereits eine Geste namens „$name“"
                LANG_FR -> "Un geste nommé « $name » existe déjà"
                LANG_IT -> "Esiste già un gesto chiamato “$name”"
                LANG_ZH -> "已存在名为“$name”的手势"
                else -> s
            }
        }
        rxTeachName.matchEntire(s)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Обучить жест «$name»"
                LANG_PT -> "Ensinar $name"
                LANG_PL -> "Naucz gestu „$name”"
                LANG_ES -> "Enseñar $name"
                LANG_DE -> "„$name“ anlernen"
                LANG_FR -> "Apprendre « $name »"
                LANG_IT -> "Insegna “$name”"
                LANG_ZH -> "学习“$name”手势"
                else -> s
            }
        }
        rxDrawYourWay.matchEntire(s)?.let { m ->
            val (name, need) = m.destructured
            return when (lang) {
                LANG_RU -> "Нарисуйте «$name» по-своему $need раза. SolCube сохранит образцы вместе со стандартным."
                LANG_PT -> "Desenhe “$name” do seu jeito, $need vezes. O SolCube mantém junto da versão padrão."
                LANG_PL -> "Narysuj „$name” po swojemu $need razy. SolCube zachowa je obok domyślnego wzoru."
                LANG_ES -> "Dibuja “$name” a tu manera $need veces. SolCube los guarda junto al original."
                LANG_DE -> "Zeichne „$name“ $need-mal auf deine Art. SolCube speichert diese zusätzlich zum Standard."
                LANG_FR -> "Tracez « $name » à votre façon, $need fois. SolCube les conserve avec le modèle de base."
                LANG_IT -> "Disegna “$name” a modo tuo $need volte. SolCube li salva insieme a quello predefinito."
                LANG_ZH -> "按您的习惯绘制 $need 次“$name”。SolCube 会将其与默认模板一起保存。"
                else -> s
            }
        }
        rxLooksMoreLike.matchEntire(s)?.let { m ->
            val (other, pct) = m.destructured
            return when (lang) {
                LANG_RU -> "Примечание: похоже больше на «$other» ($pct%). Возможны путаницы."
                LANG_PT -> "Aviso: parece mais com “$other” ($pct%). Pode haver confusão."
                LANG_PL -> "Uwaga: przypomina bardziej „$other” ($pct%). Może się mylić."
                LANG_ES -> "Nota: se parece más a “$other” ($pct%). Podrían confundirse."
                LANG_DE -> "Hinweis: Ähnelt eher „$other“ ($pct %). Könnte verwechselt werden."
                LANG_FR -> "Note : cela ressemble davantage à « $other » ($pct %). Risque de confusion."
                LANG_IT -> "Nota: assomiglia di più a “$other” ($pct%). Potrebbero confondersi."
                LANG_ZH -> "提示：这更像是“$other”（相似度 $pct%），可能会发生混淆。"
                else -> s
            }
        }
        rxHeadsUpAlike.matchEntire(s)?.let { m ->
            val (other, pct) = m.destructured
            return when (lang) {
                LANG_RU -> "Внимание: фигура очень похожа на «$other» ($pct%), жесты могут путаться. Начните заново или сохраните так."
                LANG_PT -> "Atenção: muito parecido com “$other” ($pct% similar); podem se confundir. Recomece para diferenciar ou salve mesmo assim."
                LANG_PL -> "Uwaga: bardzo podobne do „$other” ($pct% zgodności), gesty mogą się mylić. Zacznij od nowa lub zapisz mimo to."
                LANG_ES -> "Aviso: se parece mucho a “$other” ($pct%) y podrían confundirse. Reinicia o guarda de todos modos."
                LANG_DE -> "Achtung: Sehr ähnlich zu „$other“ ($pct % Ähnlichkeit). Könnte verwechselt werden — neu zeichnen oder trotzdem speichern."
                LANG_FR -> "Attention : très proche de « $other » ($pct %), risque de confusion. Recommencez ou enregistrez quand même."
                LANG_IT -> "Attenzione: molto simile a “$other” ($pct%), potrebbero confondersi. Ricomincia o salva comunque."
                LANG_ZH -> "注意：此图形与“$other”非常相似（$pct%），可能混淆。可重画或直接保存。"
                else -> s
            }
        }
        rxGestureAlreadyUsed.matchEntire(s)?.let { m ->
            val (name, how, cards) = m.destructured
            return when (lang) {
                LANG_RU -> "Жест «$name» уже используется ($how: $cards). Один жест привязывается только к одной карточке."
                LANG_PT -> "“$name” já está em uso com $how ($cards). Cada gesto pertence a um card por vez."
                LANG_PL -> "Gest „$name” jest już używany ($how: $cards). Gest może należeć tylko do jednej karty naraz."
                LANG_ES -> "“$name” ya se usa con $how ($cards). Un gesto solo puede usarse en una tarjeta."
                LANG_DE -> "„$name“ wird bereits mit $how verwendet ($cards). Eine Geste gilt immer nur für eine Karte."
                LANG_FR -> "« $name » est déjà utilisé avec $how ($cards). Un geste ne sert qu'une carte à la fois."
                LANG_IT -> "“$name” è già usato con $how ($cards). Un gesto vale per una sola scheda alla volta."
                LANG_ZH -> "“$name”已绑定到$how（$cards）。同一手势只能分配给一张卡片。"
                else -> s
            }
        }
        rxAudioFocusErr.matchEntire(s)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Не удалось перехватить аудиофокус: $err"
                LANG_PT -> "Não foi possível obter foco de áudio: $err"
                LANG_PL -> "Nie udało się przejąć fokusu audio: $err"
                LANG_ES -> "No se pudo tomar el foco de audio: $err"
                LANG_DE -> "Audiofokus nicht übernommen: $err"
                LANG_FR -> "Impossible de capturer le focus audio : $err"
                LANG_IT -> "Impossibile ottenere il focus audio: $err"
                LANG_ZH -> "无法接管音频焦点：$err"
                else -> s
            }
        }
        rxBrightnessPct.matchEntire(s)?.let { m ->
            val p = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Яркость $p%"
                LANG_PT -> "Brilho $p%"
                LANG_PL -> "Jasność $p%"
                LANG_ES -> "Brillo $p%"
                LANG_DE -> "Helligkeit $p%"
                LANG_FR -> "Luminosité $p %"
                LANG_IT -> "Luminosità $p%"
                LANG_ZH -> "亮度 $p%"
                else -> s
            }
        }
        rxBgOpacityPct.matchEntire(s)?.let { m ->
            val p = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Непрозрачность фона: $p%"
                LANG_PT -> "Opacidade do fundo: $p%"
                LANG_PL -> "Krycie tła: $p%"
                LANG_ES -> "Opacidad del fondo: $p%"
                LANG_DE -> "Hintergrunddeckkraft: $p%"
                LANG_FR -> "Opacité du fond : $p %"
                LANG_IT -> "Opacità sfondo: $p%"
                LANG_ZH -> "背景不透明度：$p%"
                else -> s
            }
        }
        rxSizePct.matchEntire(s)?.let { m ->
            val p = m.groupValues[1]
            return when (lang) {
                LANG_RU -> "Размер: $p%"
                LANG_PT -> "Tamanho: $p%"
                LANG_PL -> "Rozmiar: $p%"
                LANG_ES -> "Tamaño: $p%"
                LANG_DE -> "Größe: $p%"
                LANG_FR -> "Taille : $p %"
                LANG_IT -> "Dimensione: $p%"
                LANG_ZH -> "大小：$p%"
                else -> s
            }
        }

        if (s.startsWith("You have ") && s.contains("Install the new APK over this one")) {
            val lines = s.split("\n\n")
            val cur = lines.firstOrNull()?.removePrefix("You have ")?.removeSuffix(".") ?: ""
            val notes = lines.drop(1).dropLast(1).joinToString("\n\n")
            return when (lang) {
                LANG_RU -> "У вас установлена $cur.\n\n$notes\n\nУстановите новый APK поверх текущей версии — все настройки сохранятся."
                LANG_PT -> "Você tem a versão $cur.\n\n$notes\n\nInstale o novo APK por cima deste — suas configurações serão mantidas."
                LANG_PL -> "Masz wersję $cur.\n\n$notes\n\nZainstaluj nowy APK na obecną wersję — ustawienia zostaną zachowane."
                LANG_ES -> "Tienes la versión $cur.\n\n$notes\n\nInstala el nuevo APK encima — se conservarán tus ajustes."
                LANG_DE -> "Installiert ist $cur.\n\n$notes\n\nInstalliere die neue APK einfach drüber — alle Einstellungen bleiben erhalten."
                LANG_FR -> "Vous avez la version $cur.\n\n$notes\n\nInstallez le nouvel APK par-dessus — vos réglages seront conservés."
                LANG_IT -> "Hai la versione $cur.\n\n$notes\n\nInstalla il nuovo APK sopra questo — le impostazioni restano."
                LANG_ZH -> "当前版本：$cur。\n\n$notes\n\n直接覆盖安装新 APK 即可，所有设置都会保留。"
                else -> s
            }
        }
        if (s.startsWith("Double-press the power button to ") || s.startsWith("Hold the power button (screen on) to ")) {
            val idx = s.indexOf(" to ")
            if (idx > 0) {
                val head = s.substring(0, idx)
                val act = s.substring(idx + 4).removeSuffix(".")
                val tHead = when (head) {
                    "Double-press the power button" -> gr.doublePressPrompt
                    "Hold the power button (screen on)" -> gr.holdPowerPrompt
                    else -> null
                }
                if (tHead != null) return "$tHead${translatePhrase(act, lang)}."
            }
        }
        if ("  →  " in s) {
            val idx = s.indexOf("  →  ")
            val left = s.substring(0, idx)
            val right = s.substring(idx + 5)
            return "${map[left] ?: left}  →  ${map[right] ?: translateDynamic(right, lang)}"
        }
        if (" · " in s) {
            val parts = s.split(" · ")
            if (parts.all { it in map || rxActiveName.matches(it) }) {
                return parts.joinToString(" · ") { p -> map[p] ?: translateDynamic(p, lang) }
            }
        }
        return s
    }

    private fun translatePhrase(phrase: String, lang: String): String {
        val exact = when (phrase) {
            "open Circle to Search" -> when (lang) {
                LANG_RU -> "открыть Circle to Search"
                LANG_PT -> "abrir o Circle to Search"
                LANG_PL -> "otwórz Circle to Search"
                LANG_ES -> "abrir Circle to Search"
                LANG_DE -> "Circle to Search öffnen"
                LANG_FR -> "ouvrir Circle to Search"
                LANG_IT -> "aprire Circle to Search"
                LANG_ZH -> "打开即圈即搜"
                else -> phrase
            }
            "go Ring → Vibrate → Silent" -> t("Ring › Vibrate › Silent")
            "turn silent mode on or off" -> t("Silent on/off")
            "turn vibrate mode on or off" -> t("Vibrate on/off")
            "open the app picked below" -> when (lang) {
                LANG_RU -> "открыть выбранное приложение"
                LANG_PT -> "abrir o app escolhido abaixo"
                LANG_PL -> "otwórz wybraną aplikację"
                LANG_ES -> "abrir la app elegida"
                LANG_DE -> "gewählte App öffnen"
                LANG_FR -> "ouvrir l'app choisie"
                LANG_IT -> "aprire l'app scelta"
                LANG_ZH -> "打开下方所选应用"
                else -> phrase
            }
            "run the action picked below" -> when (lang) {
                LANG_RU -> "выполнить выбранное действие"
                LANG_PT -> "executar a ação escolhida abaixo"
                LANG_PL -> "wykonaj wybraną akcję"
                LANG_ES -> "ejecutar la acción elegida"
                LANG_DE -> "gewählte Aktion ausführen"
                LANG_FR -> "exécuter l'action choisie"
                LANG_IT -> "eseguire l'azione scelta"
                LANG_ZH -> "执行下方所选动作"
                else -> phrase
            }
            else -> null
        }
        if (exact != null) return exact
        if (phrase.startsWith("open ")) {
            return "${g().openVerb} ${phrase.removePrefix("open ")}"
        }
        return phrase
    }
}
