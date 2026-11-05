package dev.localintelligence.core.tool

import java.util.Locale

/**
 * Words people use in their own language to ask for a tool, by tool name.
 *
 * WHY A TABLE: [ToolRequest.asksFor] matches a message against a tool's name and its tags,
 * and the tags are English plus a few Dutch words. A person who writes "明天7點叫醒我" or
 * "Wek me morgen om 7 uur" asked for an alarm, but nothing in the message matched the tags,
 * so the risk guard asked them to approve a plain alarm they had just requested. Each row
 * here is a word a person really types for that tool, in that language.
 *
 * WHY THE MATCH IS A SUBSTRING AND NOT A TOKEN: Chinese, Japanese, Thai and Korean (particles
 * attach to words) have no spaces, so a token split would never see "叫醒" inside a sentence.
 * A substring match works for all scripts. Latin-script words need one extra rule: the match
 * must start at a word start, so "timer" does not fire inside another word, while the entry
 * "wecke" still matches "Wecke mich" and inflections such as "wecken" or "Wecker".
 *
 * WHY THESE WORDS: they are precise. A bare word for "set", "time" or "weather" in a language
 * often appears in unrelated sentences, and every false match lets an unasked call run without
 * a card. Tools that send, call or control the screen are deliberately absent: they keep asking.
 *
 * The words are written with AI assistance and have not been reviewed by native speakers.
 * Entries are lowercase; the matcher lowercases the message the same way.
 */
internal object RequestWords {

    private val table: Map<String, List<String>> = mapOf(
        "timer.set" to listOf(
            // es, pt, fr, de, it, nl
            "temporizador", "timer", "cuenta atrás", "contagem regressiva", "minuteur", "compte à rebours",
            "eieruhr", "countdown", "conto alla rovescia", "temporizzatore", "kookwekker", "aftel",
            // pl, ru, uk, tr, ar, hi, bn
            "minutnik", "odlicz", "таймер", "обратный отсчёт", "обратный отсчет", "відлік",
            "zamanlay", "geri sayım", "مؤقت", "مؤقّت", "عداد تنازلي", "टाइमर", "टाईमर",
            "उलटी गिनती", "টাইমার", "কাউন্টডাউন",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "pengatur waktu", "hitung mundur", "hẹn giờ", "bộ đếm giờ", "đếm ngược", "ตั้งเวลา",
            "ตัวจับเวลา", "นับถอยหลัง", "タイマー", "カウントダウン", "타이머", "카운트다운",
            "计时器", "定时器", "倒计时", "計時器", "定時器", "倒數計時",
        ),
        "alarm.create" to listOf(
            // es, pt, fr, de, it, nl
            "alarma", "despert", "despiért", "alarme", "me acorde", "réveil", "alarme",
            "wecke", "wecker", "weckzeit", "sveglia", "svegli", "wekker", "wek me",
            // pl, ru, uk, tr, ar, hi, bn
            "budzik", "obudź", "obudz", "будильник", "разбуд", "розбуд", "alarm", "uyandır",
            "منبه", "أيقظ", "ايقظ", "अलार्म", "अलारम", "जगा", "অ্যালার্ম", "আলার্ম", "জাগিয়ে", "জাগাও",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "bangunkan", "weker", "báo thức", "đánh thức", "ปลุก", "นาฬิกาปลุก", "アラーム",
            "目覚まし", "起こして", "알람", "깨워", "깨우", "闹钟", "叫醒", "闹铃",
            "鬧鐘", "鬧鈴",
        ),
        "alarm.list" to listOf(
            // es, pt, fr, de, it, nl
            "alarmas", "mis alarmas", "alarmes", "réveils", "wecker", "sveglie", "wekkers",
            // pl, ru, uk, tr, ar, hi, bn
            "budziki", "будильники", "будильники", "alarmlar", "منبهات", "अलार्म", "অ্যালার্ম",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "alarm saya", "báo thức", "นาฬิกาปลุก", "アラーム", "目覚まし", "알람", "闹钟", "鬧鐘",
        ),
        "alarm.cancel" to listOf(
            // es, pt, fr, de, it, nl
            "alarma", "alarme", "réveil", "wecker", "sveglia", "wekker",
            // pl, ru, uk, tr, ar, hi, bn
            "budzik", "будильник", "будильник", "alarm", "منبه", "अलार्म", "অ্যালার্ম",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "weker", "báo thức", "นาฬิกาปลุก", "アラーム", "目覚まし", "알람", "闹钟", "鬧鐘",
        ),
        "weather.get" to listOf(
            // es, pt, fr, de, it, nl
            "clima", "pronóstico", "previsão do tempo", "previsão", "météo", "meteo", "prévisions",
            "wetter", "vorhersage", "previsioni", "weerbericht", "weersverwachting", "temperatuur",
            // pl, ru, uk, tr, ar, hi, bn
            "pogoda", "prognoza", "погода", "прогноз погоды", "прогноз", "hava durumu", "hava nasıl",
            "sıcaklık", "الطقس", "درجة الحرارة", "मौसम", "तापमान", "আবহাওয়া", "তাপমাত্রা",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "cuaca", "suhu", "thời tiết", "nhiệt độ", "พยากรณ์อากาศ", "สภาพอากาศ", "อุณหภูมิ",
            "天気", "気温", "날씨", "기온", "天气", "气温", "天氣", "氣溫",
        ),
        "device.battery" to listOf(
            // es, pt, fr, de, it, nl
            "batería", "bateria", "batterie", "autonomie", "akku", "batteria", "batterij",
            // pl, ru, uk, tr, ar, hi, bn
            "bateri", "батаре", "аккумулятор", "акумулятор", "batarya", "pil seviyesi", "pil durumu",
            "بطاري", "बैटरी", "बैट्री", "ব্যাটারি", "ব্যাটারী",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "baterai", "batre", "dung lượng pin", "pin điện thoại", "pin còn", "แบต",
            "バッテリー", "電池", "배터리", "电量", "电池", "電量", "電池",
        ),
        "phone.flashlight" to listOf(
            // es, pt, fr, de, it, nl
            "linterna", "lanterna", "lampe torche", "lampe de poche", "torche", "taschenlampe",
            "torcia", "zaklamp", "zaklicht",
            // pl, ru, uk, tr, ar, hi, bn
            "latark", "фонар", "ліхтар", "el feneri", "fener", "كشاف", "الفلاش", "مصباح يدوي",
            "टॉर्च", "फ्लैशलाइट", "টর্চ", "ফ্ল্যাশলাইট",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "senter", "đèn pin", "đèn flash", "ไฟฉาย", "懐中電灯", "フラッシュライト",
            "손전등", "플래시라이트", "手电", "手電",
        ),
        "phone.volume" to listOf(
            // es, pt, fr, de, it, nl
            "volumen", "volume", "lautstärke", "leiser machen", "lauter machen", "volume",
            "geluidsniveau",
            // pl, ru, uk, tr, ar, hi, bn
            "głośność", "ścisz", "podgłośn", "громкост", "громче", "гучн", "ses seviyesi", "ses düzeyi",
            "sesi kıs", "sesi aç", "مستوى الصوت", "خفض الصوت", "رفع الصوت", "वॉल्यूम",
            "आवाज़ कम", "आवाज़ बढ़ा", "ভলিউম", "আওয়াজ কম", "আওয়াজ বাড়াও",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "kecilkan suara", "keraskan suara", "âm lượng", "ระดับเสียง", "ปรับเสียง", "ลดเสียง",
            "เพิ่มเสียง", "音量", "ボリューム", "볼륨", "음량", "音量",
        ),
        "phone.media" to listOf(
            // es, pt, fr, de, it, nl
            "música", "canción", "reproduce", "canção", "musique", "chanson", "musik", "lied",
            "musica", "canzone", "muziek", "liedje", "afspeel",
            // pl, ru, uk, tr, ar, hi, bn
            "muzyk", "piosenk", "музык", "песн", "музик", "пісн", "müzik", "şarkı",
            "موسيقى", "أغنية", "اغنية", "गाना", "गाने", "संगीत", "গান", "সংগীত",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "musik", "lagu", "nhạc", "bài hát", "เพลง", "ดนตรี", "音楽", "曲をかけ",
            "音楽をかけ", "음악", "노래", "音乐", "歌曲", "播放", "音樂", "歌曲",
        ),
        "phone.dnd" to listOf(
            // es, pt, fr, de, it, nl
            "no molestar", "modo silencio", "não perturbe", "não incomode", "não incomodar",
            "ne pas déranger", "ne pas deranger", "nicht stören", "nicht stoeren", "non disturb",
            "niet storen",
            // pl, ru, uk, tr, ar, hi, bn
            "nie przeszkadzaj", "tryb cichy", "не беспокоить", "не турбувати", "rahatsız etme",
            "rahatsiz etme", "عدم الإزعاج", "عدم الازعاج", "परेशान न करें", "डू नॉट डिस्टर्ब",
            "বিরক্ত করবেন না", "বিরক্ত করো না",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "jangan ganggu", "không làm phiền", "ห้ามรบกวน", "おやすみモード", "邪魔しないで",
            "방해 금지", "방해금지", "勿扰", "请勿打扰", "免打扰", "勿擾", "請勿打擾", "勿打擾",
        ),
        "message.draft" to listOf(
            // es, pt, fr, de, it, nl
            "mensaje", "escribe a", "escríbele", "mensagem", "escreva para", "escreve pra",
            "message", "écris à", "nachricht", "messaggio", "scrivi a", "bericht",
            // pl, ru, uk, tr, ar, hi, bn
            "wiadomo", "napisz do", "сообщени", "повідомлен", "mesaj", "رسال", "संदेश", "मैसेज",
            "বার্তা", "মেসেজ",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "pesan", "tin nhắn", "ข้อความ", "メッセージ", "메시지", "문자 보내", "消息", "訊息",
        ),
        "email.draft" to listOf(
            // es, pt, fr, de, it, nl
            "correo", "email", "e-mail", "correio eletrônico", "courriel", "mail",
            // pl, ru, uk, tr, ar, hi, bn
            "письмо", "почт", "имейл", "пошт", "e-posta", "eposta", "بريد إلكتروني",
            "بريد الكتروني", "إيميل", "ايميل", "ईमेल", "इमेल", "ইমেইল", "ইমেল",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "surel", "surat elektronik", "thư điện tử", "อีเมล", "メール", "이메일", "메일",
            "邮件", "电子邮件", "邮箱", "郵件", "電子郵件", "信箱",
        ),
        "calendar.search" to listOf(
            // es, pt, fr, de, it, nl
            "calendario", "agenda", "calendário", "calendrier", "kalender", "termin",
            "appuntament", "afspraak",
            // pl, ru, uk, tr, ar, hi, bn
            "kalendarz", "termin", "календар", "расписани", "розклад", "takvim", "randevu", "ajanda",
            "تقويم", "موعد", "कैलेंडर", "अपॉइंटमेंट", "ক্যালেন্ডার", "অ্যাপয়েন্টমেন্ট",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "jadwal", "lịch hẹn", "lịch trình", "lịch làm việc", "ปฏิทิน", "นัดหมาย", "カレンダー",
            "予定", "일정", "캘린더", "달력", "日历", "日程", "行事曆", "日曆", "行程",
        ),
        "calendar.create" to listOf(
            // es, pt, fr, de, it, nl
            "evento", "cita", "reunión", "compromisso", "reunião", "événement", "rendez-vous",
            "réunion", "ereignis", "besprechung", "meeting", "appuntamento", "riunione", "vergadering",
            // pl, ru, uk, tr, ar, hi, bn
            "wydarzenie", "spotkanie", "событи", "собрани", "встреч", "подія", "поді", "нарад",
            "зустріч", "etkinlik", "toplantı", "اجتماع", "فعالية", "मीटिंग", "बैठक", "इवेंट",
            "মিটিং", "সভা", "ইভেন্ট",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "rapat", "acara", "janji temu", "cuộc họp", "sự kiện", "ประชุม", "กิจกรรม", "会議",
            "ミーティング", "予定を入れ", "회의", "미팅", "会议", "會議", "活动",
        ),
        "contacts.search" to listOf(
            // es, pt, fr, de, it, nl
            "contacto", "contactos", "contato", "contatos", "contact", "kontakt", "contatt",
            // pl, ru, uk, tr, ar, hi, bn
            "kontakt", "контакт", "rehber", "kişiler", "جهات الاتصال", "جهة الاتصال", "جهات اتصال",
            "संपर्क", "कॉन्टैक्ट", "কন্টাক্ট", "পরিচিতি",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "kontak", "danh bạ", "รายชื่อ", "ผู้ติดต่อ", "連絡先", "電話帳", "연락처", "联系人",
            "聯絡人", "通訊錄",
        ),
        "maps.navigate" to listOf(
            // es, pt, fr, de, it, nl
            "navega", "cómo llego", "llévame", "como chegar", "me leve", "navigue", "itinéraire",
            "emmène-moi", "navigier", "route nach", "wegbeschreib", "naviga", "portami", "itinerario",
            "navigeer", "route naar", "hoe kom ik",
            // pl, ru, uk, tr, ar, hi, bn
            "nawiguj", "jak dojechać", "trasa do", "маршрут", "навигат", "проложи", "навігац",
            "yol tarifi", "navigasyon", "rota", "اتجاهات", "الاتجاهات", "خريطة", "रास्ता", "नेविगेट",
            "রুট", "নেভিগেট",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "navigasi", "rute", "arahkan", "chỉ đường", "dẫn đường", "นำทาง", "เส้นทาง", "ナビ",
            "道案内", "行き方", "길찾기", "내비", "길 안내", "导航", "怎么去", "路线", "導航", "怎麼去", "路線",
        ),
        "location.get" to listOf(
            // es, pt, fr, de, it, nl
            "dónde estoy", "ubicación", "onde estou", "localização", "où suis", "localisation",
            "wo bin ich", "standort", "dove sono", "posizione", "waar ben ik", "locatie",
            // pl, ru, uk, tr, ar, hi, bn
            "gdzie jestem", "lokalizacj", "где я", "местоположен", "геолокац", "де я",
            "місцезнаходж", "neredeyim", "konum", "أين أنا", "موقعي", "मैं कहाँ", "लोकेशन", "स्थान",
            "আমি কোথায়", "অবস্থান",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "di mana saya", "lokasi", "tôi ở đâu", "vị trí", "ฉันอยู่ที่ไหน", "ตำแหน่ง", "現在地",
            "位置情報", "今どこ", "내 위치", "여기가 어디", "我在哪", "定位",
        ),
        "web.fetch" to listOf(
            // es, pt, fr, de, it, nl
            "enlace", "link", "lien", "tautan", "liên kết", "ลิงก์", "链接", "連結",
            // pl, ru, uk, tr, ar, hi, bn
            "ссылк", "посилан", "bağlantı", "رابط", "लिंक", "লিংক",
            // id, vi, th, ja, ko, zh-CN, zh-TW
            "リンク", "링크",
        ),
    ).mapValues { (_, words) -> words.map { it.lowercase(Locale.ROOT) }.distinct() }

    /**
     * Whether [message] uses one of [toolName]'s request words in some language.
     * A word that starts with a Latin letter must not begin in the middle of another word.
     */
    fun matches(message: String, toolName: String): Boolean {
        val words = table[toolName] ?: return false
        val text = message.lowercase(Locale.ROOT)
        return words.any { occurs(text, it) }
    }

    private fun occurs(text: String, word: String): Boolean {
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return false
            val needsWordStart = Character.UnicodeScript.of(word.first().code) == Character.UnicodeScript.LATIN
            if (!needsWordStart || at == 0 || !text[at - 1].isLetter()) return true
            from = at + 1
        }
    }
}
