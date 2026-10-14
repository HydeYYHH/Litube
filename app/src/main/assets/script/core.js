/**
 * Shared page runtime: label table, DOM helpers, and the one scheduler
 * every feature module registers with. Injected first.
 */
(() => {
  'use strict';
  if (window.Lite) return;

  const VIEW_BOX = '0 -960 960 960';
  const RETRY_MS = [128, 256, 512, 1024, 2048];
  const POLL_MS = 1000;
  const SPA_LAG_MS = 80;
  const SHEET_WATCH_MS = 1500;
  const ID_RE = /^[a-zA-Z0-9_-]{11}$/;
  const SHORTS_PATH_RE = /^\/shorts(?:\/|$)/;

  /** True while the SPA route is a Shorts page; shared by every shorts-aware module. */
  const isShorts = () => SHORTS_PATH_RE.test(location.pathname);

  const TEXT = {
    "download": {
      "af": "Laai af",
      "am": "አውርድ",
      "ar": "تنزيل",
      "as": "ডাউনলোড কৰক",
      "az": "Yüklə",
      "be": "Спампаваць",
      "bg": "Изтегляне",
      "bn": "ডাউনলোড করুন",
      "bs": "Preuzmi",
      "ca": "Descarrega",
      "ceb": "Pag-download",
      "cs": "Stáhnout",
      "cy": "Lawrlwytho",
      "da": "Download",
      "de": "Herunterladen",
      "el": "Λήψη",
      "en": "Download",
      "es": "Descargar",
      "es-419": "Descargar",
      "et": "Laadi alla",
      "eu": "Deskargatu",
      "fa": "دانلود کنید",
      "fi": "Lataa",
      "fil": "I-download",
      "fr": "Télécharger",
      "ga": "Íoslódáil",
      "gl": "Descargar",
      "gu": "ડાઉનલોડ કરો",
      "ha": "Zazzagewa",
      "he": "הורד",
      "hi": "डाउनलोड",
      "hr": "Preuzimanje",
      "hu": "Letöltés",
      "hy": "Ներբեռնել",
      "id": "Download",
      "ig": "Budata",
      "is": "Sækja",
      "it": "Scarica",
      "ja": "ダウンロード",
      "jv": "Ngundhuh",
      "ka": "ჩამოტვირთვა",
      "kk": "Жүктеп алу",
      "km": "ទាញយក",
      "kn": "ಡೌನ್‌ಲೋಡ್ ಮಾಡಿ",
      "ko": "다운로드",
      "ky": "Жүктөп алуу",
      "lo": "ດາວໂຫຼດ",
      "lt": "Atsisiųsti",
      "lv": "Lejupielādēt",
      "mk": "Преземи",
      "ml": "ഡൗൺലോഡ് ചെയ്യുക",
      "mn": "Татаж авах",
      "mr": "डाउनलोड करा",
      "ms": "Muat turun",
      "my": "ဒေါင်းလုဒ်",
      "nb": "Last ned",
      "ne": "डाउनलोड गर्नुहोस्",
      "nl": "Downloaden",
      "or": "ଡାଉନଲୋଡ୍ କରନ୍ତୁ",
      "pa": "ਡਾਊਨਲੋਡ ਕਰੋ",
      "pl": "Pobierz",
      "ps": "ډاونلوډ کړئ",
      "pt": "Baixar",
      "pt-pt": "Transferir",
      "ro": "Descărcați",
      "ru": "Скачать",
      "si": "බාගන්න",
      "sk": "Stiahnite si",
      "sl": "Prenos",
      "so": "Download",
      "sq": "Shkarko",
      "sr": "Преузми",
      "sr-latn": "Preuzmi",
      "su": "Ngundeur",
      "sv": "Ladda ner",
      "sw": "Pakua",
      "ta": "பதிவிறக்கு",
      "te": "డౌన్‌లోడ్ చేయండి",
      "th": "ดาวน์โหลด",
      "tr": "İndir",
      "uk": "Завантажити",
      "ur": "ڈاؤن لوڈ",
      "uz": "Yuklab olish",
      "vi": "Tải xuống",
      "yo": "Ṣe igbasilẹ",
      "zh": "下载",
      "zh-hant": "下載",
      "zu": "Landa"
    },
    "downloaded": {
      "af": "Afgelaai",
      "am": "ወርዷል",
      "ar": "تم التنزيل",
      "as": "ডাউনলোড কৰা হৈছে",
      "az": "Endirildi",
      "be": "Спампавана",
      "bg": "Изтеглено",
      "bn": "ডাউনলোড করা হয়েছে",
      "bs": "Preuzeto",
      "ca": "Descarregat",
      "ceb": "Gi-download",
      "cs": "Staženo",
      "cy": "Lawrlwythwyd",
      "da": "Downloadet",
      "de": "Heruntergeladen",
      "el": "Λήψη",
      "en": "Downloaded",
      "es": "Descargado",
      "es-419": "Descargado",
      "et": "Allalaaditud",
      "eu": "Deskargatuta",
      "fa": "دانلود شد",
      "fi": "Ladattu",
      "fil": "Na-download",
      "fr": "Téléchargé",
      "ga": "Íosluchtaithe",
      "gl": "Descargado",
      "gu": "ડાઉનલોડ કરેલ",
      "ha": "An sauke",
      "he": "הורד",
      "hi": "डाउनलोड हो गया",
      "hr": "Preuzeto",
      "hu": "Letöltve",
      "hy": "Ներբեռնված է",
      "id": "Diunduh",
      "ig": "ebudatara",
      "is": "Niðurhalað",
      "it": "Scaricato",
      "ja": "ダウンロード済み",
      "jv": "Diundhuh",
      "ka": "ჩამოტვირთულია",
      "kk": "Жүктелген",
      "km": "បានទាញយក",
      "kn": "ಡೌನ್‌ಲೋಡ್ ಮಾಡಲಾಗಿದೆ",
      "ko": "다운로드됨",
      "ky": "Жүктөлгөн",
      "lo": "ດາວໂຫຼດແລ້ວ",
      "lt": "Atsisiųsta",
      "lv": "Lejupielādēts",
      "mk": "Преземено",
      "ml": "ഡൗൺലോഡ് ചെയ്തു",
      "mn": "Татаж авсан",
      "mr": "डाउनलोड केले",
      "ms": "Dimuat turun",
      "my": "ဒေါင်းလုဒ်လုပ်ထားသည်။",
      "nb": "Lastet ned",
      "ne": "डाउनलोड गरियो",
      "nl": "Gedownload",
      "or": "ଡାଉନଲୋଡ୍ ହୋଇଛି",
      "pa": "ਡਾਊਨਲੋਡ ਕੀਤਾ",
      "pl": "Pobrano",
      "ps": "ډاونلوډ شوی",
      "pt": "Baixado",
      "pt-pt": "Transferido",
      "ro": "Descărcat",
      "ru": "Скачано",
      "si": "බාගත කර ඇත",
      "sk": "Stiahnuté",
      "sl": "Preneseno",
      "so": "La soo dejiyay",
      "sq": "E shkarkuar",
      "sr": "Преузето",
      "sr-latn": "Preuzeto",
      "su": "Diunduh",
      "sv": "Nedladdat",
      "sw": "Imepakuliwa",
      "ta": "பதிவிறக்கம் செய்யப்பட்டது",
      "te": "డౌన్‌లోడ్ చేయబడింది",
      "th": "ดาวน์โหลดแล้ว",
      "tr": "İndirildi",
      "uk": "Завантажено",
      "ur": "ڈاؤن لوڈ کیا گیا۔",
      "uz": "Yuklab olingan",
      "vi": "Đã tải xuống",
      "yo": "ti gba lati ayelujara",
      "zh": "已下载",
      "zh-hant": "已下載",
      "zu": "Ilandiwe"
    },
    "addToQueue": {
      "af": "Voeg by tou",
      "am": "ወደ ወረፋ ጨምር",
      "ar": "إضافة إلى قائمة الانتظار",
      "as": "শাৰীত যোগ কৰক",
      "az": "Növbəyə əlavə edin",
      "be": "Дадаць у чаргу",
      "bg": "Добавяне към опашката",
      "bn": "সারিতে যোগ করুন",
      "bs": "Dodaj u red čekanja",
      "ca": "Afegeix a la cua",
      "ceb": "Idugang sa pila",
      "cs": "Přidat do fronty",
      "cy": "Ychwanegu at y ciw",
      "da": "Tilføj til kø",
      "de": "Zur Warteschlange hinzufügen",
      "el": "Προσθήκη στην ουρά",
      "en": "Add to queue",
      "es": "Añadir a la cola",
      "es-419": "Añadir a la cola",
      "et": "Lisa järjekorda",
      "eu": "Gehitu ilarara",
      "fa": "افزودن به صف",
      "fi": "Lisää jonoon",
      "fil": "Idagdag sa queue",
      "fr": "Ajouter à la file",
      "ga": "Cuir leis an scuaine",
      "gl": "Engadir á cola",
      "gu": "કતારમાં ઉમેરો",
      "ha": "Ƙara zuwa jerin gwano",
      "he": "הוספה לתור",
      "hi": "कतार में जोड़ें",
      "hr": "Dodaj u red čekanja",
      "hu": "Hozzáadás a sorhoz",
      "hy": "Ավելացնել հերթին",
      "id": "Tambahkan ke antrean",
      "ig": "Tinye n'ahịrị",
      "is": "Bæta við biðröð",
      "it": "Aggiungi alla coda",
      "ja": "キューに追加",
      "jv": "Tambah ing antrian",
      "ka": "რიგში დამატება",
      "kk": "Кезекке қосу",
      "km": "បន្ថែមទៅជួរ",
      "kn": "ಸರದಿಯಲ್ಲಿ ಸೇರಿಸಿ",
      "ko": "대기열에 추가",
      "ky": "Кезекке кошуу",
      "lo": "ຕື່ມໃສ່ຄິວ",
      "lt": "Įtraukti į eilę",
      "lv": "Pievienot rindai",
      "mk": "Додај во редот",
      "ml": "ക്യൂവിൽ ചേർക്കുക",
      "mn": "Дараалалд нэмэх",
      "mr": "रांगेत जोडा",
      "ms": "Tambahkan pada baris gilir",
      "my": "ဖွင့်ရန်စာရင်းသို့ ထည့်ရန်",
      "nb": "Legg til i kø",
      "ne": "लाममा थप्नुहोस्",
      "nl": "Toevoegen aan wachtrij",
      "or": "ଧାଡିରେ ଯୋଡନ୍ତୁ",
      "pa": "ਕਤਾਰ ਵਿੱਚ ਸ਼ਾਮਲ ਕਰੋ",
      "pl": "Dodaj do kolejki",
      "ps": "په کتار کې شامل کړئ",
      "pt": "Adicionar à fila",
      "pt-pt": "Adicionar à fila",
      "ro": "Adăugați la coadă",
      "ru": "Добавить в очередь",
      "si": "පෝලිමට එකතු කරන්න",
      "sk": "Pridať do poradia",
      "sl": "Dodaj v čakalno vrsto",
      "so": "Ku darso safka",
      "sq": "Shto në radhë",
      "sr": "Додај у ред",
      "sr-latn": "Dodaj u red",
      "su": "Tambahkeun ka antrian",
      "sv": "Lägg till i kö",
      "sw": "Ongeza kwenye foleni",
      "ta": "வரிசையில் சேர்",
      "te": "క్యూలో జోడించండి",
      "th": "เพิ่มลงในคิว",
      "tr": "Kuyruğa ekle",
      "uk": "Додати в чергу",
      "ur": "قطار میں شامل کریں",
      "uz": "Navbatga qoʻshish",
      "vi": "Thêm vào hàng đợi",
      "yo": "Fi kún ìlà ìdúró",
      "zh": "加入队列",
      "zh-hant": "加入佇列",
      "zu": "Engeza kulayini"
    },
    "openWith": {
      "af": "Maak oop met",
      "am": "ክፍት በ",
      "ar": "فتح باستخدام",
      "as": "ৰ সৈতে খোলক",
      "az": "ilə açın",
      "be": "Адкрыць з",
      "bg": "Отворете с",
      "bn": "দিয়ে খুলুন",
      "bs": "Otvori sa",
      "ca": "Obre amb",
      "ceb": "Ablihi sa",
      "cs": "Otevřít pomocí",
      "cy": "Agor gyda",
      "da": "Åbn med",
      "de": "Öffnen mit",
      "el": "Άνοιγμα με",
      "en": "Open with",
      "es": "Abrir con",
      "es-419": "Abrir con",
      "et": "Avage koos",
      "eu": "Ireki honekin",
      "fa": "باز کردن با",
      "fi": "Avaa kanssa",
      "fil": "Buksan gamit ang",
      "fr": "Ouvrir avec",
      "ga": "Oscail le",
      "gl": "Abrir con",
      "gu": "સાથે ખોલો",
      "ha": "Bude da",
      "he": "פתח עם",
      "hi": "अन्य ऐप से खोलें",
      "hr": "Otvori s",
      "hu": "Nyitva ezzel",
      "hy": "Բացեք հետ",
      "id": "Buka dengan",
      "ig": "Meghere ya",
      "is": "Opið með",
      "it": "Apri con",
      "ja": "他のアプリで開く",
      "jv": "Bukak karo",
      "ka": "გახსენით",
      "kk": "ашыңыз",
      "km": "បើកជាមួយ",
      "kn": "ಇದರೊಂದಿಗೆ ತೆರೆಯಿರಿ",
      "ko": "다른 앱으로 열기",
      "ky": "менен ачуу",
      "lo": "ເປີດດ້ວຍ",
      "lt": "Atidaryti su",
      "lv": "Atvērt ar",
      "mk": "Отвори со",
      "ml": "ഉപയോഗിച്ച് തുറക്കുക",
      "mn": "Нээх",
      "mr": "यासह उघडा",
      "ms": "Buka dengan",
      "my": "ဖြင့်ဖွင့်သည်။",
      "nb": "Åpne med",
      "ne": "सँग खोल्नुहोस्",
      "nl": "Openen met",
      "or": "ସହିତ ଖୋଲ",
      "pa": "ਨਾਲ ਖੋਲ੍ਹੋ",
      "pl": "Otwórz za pomocą",
      "ps": "سره خلاص",
      "pt": "Abrir com",
      "pt-pt": "Abrir com",
      "ro": "Deschide cu",
      "ru": "Открыть с помощью",
      "si": "සමඟ විවෘත කරන්න",
      "sk": "Otvoriť s",
      "sl": "Odpri z",
      "so": "Ku furan",
      "sq": "Hap me",
      "sr": "Отвори са",
      "sr-latn": "Otvori sa",
      "su": "Buka jeung",
      "sv": "Öppna med",
      "sw": "Fungua na",
      "ta": "உடன் திறக்கவும்",
      "te": "దీనితో తెరవండి",
      "th": "เปิดด้วย",
      "tr": "Birlikte aç",
      "uk": "Відкрити за допомогою",
      "ur": "کے ساتھ کھولیں۔",
      "uz": "bilan ochish",
      "vi": "Mở bằng",
      "yo": "Ṣii pẹlu",
      "zh": "打开方式",
      "zh-hant": "開啟方式",
      "zu": "Vula nge"
    },
    "chat": {
      "af": "Regstreekse klets",
      "am": "የቀጥታ ውይይት",
      "ar": "الدردشة المباشرة",
      "as": "লাইভ চেট",
      "az": "Canlı söhbət",
      "be": "Жывы чат",
      "bg": "Чат на живо",
      "bn": "লাইভ চ্যাট",
      "bs": "Chat uživo",
      "ca": "Xat en directe",
      "ceb": "Live chat",
      "cs": "Živý chat",
      "cy": "Sgwrs fyw",
      "da": "Live chat",
      "de": "Livechat",
      "el": "Ζωντανή συνομιλία",
      "en": "Live chat",
      "es": "Chat en directo",
      "es-419": "Chat en directo",
      "et": "Reaalajas vestlus",
      "eu": "Zuzeneko txata",
      "fa": "چت زنده",
      "fi": "Live-chat",
      "fil": "Live chat",
      "fr": "Chat en direct",
      "ga": "Comhrá beo",
      "gl": "Chat en directo",
      "gu": "લાઇવ ચેટ",
      "ha": "Tattaunawa kai tsaye",
      "he": "צ'אט חי",
      "hi": "लाइव चैट",
      "hr": "Razgovor uživo",
      "hu": "Élő csevegés",
      "hy": "Ուղիղ զրույց",
      "id": "Obrolan langsung",
      "ig": "Mkparịta ụka dị ndụ",
      "is": "Spjall í beinni",
      "it": "Chat dal vivo",
      "ja": "チャット",
      "jv": "Obrolan langsung",
      "ka": "პირდაპირი ჩატი",
      "kk": "Тікелей чат",
      "km": "ការជជែកផ្ទាល់",
      "kn": "ಲೈವ್ ಚಾಟ್",
      "ko": "실시간 채팅",
      "ky": "Жандуу баарлашуу",
      "lo": "ສົນທະນາສົດ",
      "lt": "Tiesioginis pokalbis",
      "lv": "Tiešraides tērzēšana",
      "mk": "Разговор во живо",
      "ml": "തത്സമയ ചാറ്റ്",
      "mn": "Шууд чат",
      "mr": "थेट गप्पा",
      "ms": "Sembang langsung",
      "my": "တိုက်ရိုက်ချတ်",
      "nb": "Live chat",
      "ne": "प्रत्यक्ष कुराकानी",
      "nl": "Livechat",
      "or": "ଲାଇଭ୍ ଚାଟ୍",
      "pa": "ਲਾਈਵ ਚੈਟ",
      "pl": "Czat na żywo",
      "ps": "ژوندۍ خبرې",
      "pt": "Chat ao vivo",
      "pt-pt": "Chat ao vivo",
      "ro": "Chat live",
      "ru": "Чат трансляции",
      "si": "සජීවී කතාබස්",
      "sk": "Živý chat",
      "sl": "Klepet v živo",
      "so": "Sheeko toos ah",
      "sq": "Bisedë e drejtpërdrejtë",
      "sr": "Ћаскање уживо",
      "sr-latn": "Ćaskanje uživo",
      "su": "Live chat",
      "sv": "Livechatt",
      "sw": "Gumzo la moja kwa moja",
      "ta": "நேரலை அரட்டை",
      "te": "లైవ్ చాట్",
      "th": "แชทสด",
      "tr": "Canlı sohbet",
      "uk": "Живий чат",
      "ur": "لائیو چیٹ",
      "uz": "Jonli suhbat",
      "vi": "Trò chuyện trực tiếp",
      "yo": "Live iwiregbe",
      "zh": "直播聊天",
      "zh-hant": "即時聊天室",
      "zu": "Ingxoxo ebukhoma"
    },
    "about": {
      "af": "Oor",
      "am": "ስለዚህ መተግበሪያ",
      "ar": "حول التطبيق",
      "as": "এই এপটোৰ বিষয়ে",
      "az": "Haqqında",
      "be": "Пра праграму",
      "bg": "Относно",
      "bn": "অ্যাপ সম্পর্কে",
      "bs": "O aplikaciji",
      "ca": "Quant a",
      "ceb": "Bahin niini nga app",
      "cs": "O aplikaci",
      "cy": "Ynglŷn",
      "da": "Om",
      "de": "Info",
      "el": "Σχετικά",
      "en": "About",
      "es": "Acerca de",
      "es-419": "Acerca de",
      "et": "Teave",
      "eu": "Honi buruz",
      "fa": "درباره",
      "fi": "Tietoja",
      "fil": "Tungkol",
      "fr": "À propos",
      "ga": "Maidir leis",
      "gl": "Sobre",
      "gu": "ઍપ વિશે",
      "ha": "Game da",
      "he": "אודות",
      "hi": "ऐप के बारे में",
      "hr": "O aplikaciji",
      "hu": "Névjegy",
      "hy": "Հավելվածի մասին",
      "id": "Tentang",
      "ig": "Banyere",
      "is": "Um forritið",
      "it": "Informazioni",
      "ja": "このアプリについて",
      "jv": "Babagan",
      "ka": "შესახებ",
      "kk": "Қолданба туралы",
      "km": "អំពីកម្មវិធីនេះ",
      "kn": "ಕುರಿತು",
      "ko": "앱 정보",
      "ky": "Колдонмо жөнүндө",
      "lo": "ກ່ຽວ​ກັບ app ນີ້​",
      "lt": "Apie",
      "lv": "Par lietotni",
      "mk": "За апликацијата",
      "ml": "കുറിച്ച്",
      "mn": "Тухай",
      "mr": "अ‍ॅपबद्दल",
      "ms": "Perihal",
      "my": "အကြောင်း",
      "nb": "Om",
      "ne": "एपबारे",
      "nl": "Over",
      "or": "ଏହି ଆପ୍ ବିଷୟରେ",
      "pa": "ਇਸ ਐਪ ਬਾਰੇ",
      "pl": "O aplikacji",
      "ps": "د اپ په اړه",
      "pt": "Sobre",
      "pt-pt": "Sobre",
      "ro": "Despre",
      "ru": "О приложении",
      "si": "මෙම යෙදුම ගැන",
      "sk": "O aplikácii",
      "sl": "O aplikaciji",
      "so": "Ku saabsan",
      "sq": "Rreth",
      "sr": "О апликацији",
      "sr-latn": "O aplikaciji",
      "su": "Ngeunaan",
      "sv": "Om",
      "sw": "Kuhusu",
      "ta": "அறிமுகம்",
      "te": "యాప్ గురించి",
      "th": "เกี่ยวกับ",
      "tr": "Hakkında",
      "uk": "Про застосунок",
      "ur": "تعارف",
      "uz": "Ilova haqida",
      "vi": "Giới thiệu",
      "yo": "Nípa",
      "zh": "关于",
      "zh-hant": "關於",
      "zu": "Mayelana"
    },
    "extension": {
      "af": "Uitbreidings",
      "am": "ቅጥያዎች",
      "ar": "الإضافات",
      "as": "সম্প্ৰসাৰণ",
      "az": "Genişləndirmələr",
      "be": "Пашырэнні",
      "bg": "Разширения",
      "bn": "এক্সটেনশন",
      "bs": "Proširenja",
      "ca": "Extensions",
      "ceb": "Mga Extension",
      "cs": "Rozšíření",
      "cy": "Estyniadau",
      "da": "Udvidelser",
      "de": "Erweiterungen",
      "el": "Επεκτάσεις",
      "en": "Extensions",
      "es": "Extensiones",
      "es-419": "Extensiones",
      "et": "Laiendused",
      "eu": "Luzapenak",
      "fa": "افزونه‌ها",
      "fi": "Laajennukset",
      "fil": "Mga extension",
      "fr": "Extensions",
      "ga": "Eisínteachtaí",
      "gl": "Extensións",
      "gu": "એક્સ્ટેંશન",
      "ha": "Ƙari",
      "he": "הרחבות",
      "hi": "एक्सटेंशन",
      "hr": "Proširenja",
      "hu": "Bővítmények",
      "hy": "Ընդլայնումներ",
      "id": "Ekstensi",
      "ig": "Mgbatị",
      "is": "Viðbætur",
      "it": "Estensioni",
      "ja": "拡張機能",
      "jv": "Ekstensi",
      "ka": "გაფართოებები",
      "kk": "Кеңейтімдер",
      "km": "ផ្នែកបន្ថែម",
      "kn": "ವಿಸ್ತರಣೆಗಳು",
      "ko": "확장 기능",
      "ky": "Кеңейтүүлөр",
      "lo": "ສ່ວນຂະຫຍາຍ",
      "lt": "Plėtiniai",
      "lv": "Paplašinājumi",
      "mk": "Проширувања",
      "ml": "വിപുലീകരണങ്ങൾ",
      "mn": "Өргөтгөлүүд",
      "mr": "विस्तार",
      "ms": "Sambungan",
      "my": "တိုးချဲ့မှုများ",
      "nb": "Utvidelser",
      "ne": "विस्तारहरू",
      "nl": "Uitbreidingen",
      "or": "ବିସ୍ତୃତକରଣ",
      "pa": "ਐਕਸਟੈਂਸ਼ਨਾਂ",
      "pl": "Rozszerzenia",
      "ps": "غزول",
      "pt": "Extensões",
      "pt-pt": "Extensões",
      "ro": "Extensii",
      "ru": "Расширения",
      "si": "දිගු",
      "sk": "Rozšírenia",
      "sl": "Razširitve",
      "so": "Kordhinno",
      "sq": "Shtesa",
      "sr": "Проширења",
      "sr-latn": "Proširenja",
      "su": "Éksténsi",
      "sv": "Tillägg",
      "sw": "Viendelezi",
      "ta": "நீட்டிப்புகள்",
      "te": "పొడిగింపులు",
      "th": "ส่วนขยาย",
      "tr": "Uzantılar",
      "uk": "Розширення",
      "ur": "ایکسٹینشنز",
      "uz": "Kengaytmalar",
      "vi": "Tiện ích",
      "yo": "Awọn amugbooro",
      "zh": "扩展",
      "zh-hant": "擴充功能",
      "zu": "Izandiso"
    },
    "downloads": {
      "af": "Aflaaie",
      "am": "ውርዶች",
      "ar": "التنزيلات",
      "as": "ডাউনলোডসমূহ",
      "az": "Yükləmələr",
      "be": "Спампоўкі",
      "bg": "Изтегляния",
      "bn": "ডাউনলোড",
      "bs": "Preuzimanja",
      "ca": "Descàrregues",
      "ceb": "Mga Pag-download",
      "cs": "Stahování",
      "cy": "Lawrlwythiadau",
      "da": "Downloads",
      "de": "Downloads",
      "el": "Λήψεις",
      "en": "Downloads",
      "es": "Descargas",
      "es-419": "Descargas",
      "et": "Allalaadimised",
      "eu": "Deskargak",
      "fa": "دانلودها",
      "fi": "Lataukset",
      "fil": "Mga download",
      "fr": "Téléchargements",
      "ga": "Íoslódálacha",
      "gl": "Descargas",
      "gu": "ડાઉનલોડ્સ",
      "ha": "Zazzagewa",
      "he": "הורדות",
      "hi": "डाउनलोड",
      "hr": "Preuzimanja",
      "hu": "Letöltések",
      "hy": "Ներբեռնումներ",
      "id": "Download",
      "ig": "Nbudata",
      "is": "Niðurhal",
      "it": "Download",
      "ja": "ダウンロード",
      "jv": "Unduh",
      "ka": "ჩამოტვირთვები",
      "kk": "Жүктеулер",
      "km": "ទាញយក",
      "kn": "ಡೌನ್‌ಲೋಡ್‌ಗಳು",
      "ko": "다운로드",
      "ky": "Жүктөөлөр",
      "lo": "ດາວໂຫຼດ",
      "lt": "Atsisiuntimai",
      "lv": "Lejupielādes",
      "mk": "Преземања",
      "ml": "ഡൗൺലോഡുകൾ",
      "mn": "Татаж авах",
      "mr": "डाउनलोड",
      "ms": "Muat Turun",
      "my": "ဒေါင်းလုဒ်များ",
      "nb": "Nedlastinger",
      "ne": "डाउनलोड",
      "nl": "Downloaden",
      "or": "ଡାଉନଲୋଡ୍",
      "pa": "ਡਾਊਨਲੋਡ",
      "pl": "Pobieranie",
      "ps": "کښته کول",
      "pt": "Downloads",
      "pt-pt": "Transferências",
      "ro": "Descărcări",
      "ru": "Загрузки",
      "si": "බාගැනීම්",
      "sk": "Sťahovanie",
      "sl": "Prenosi",
      "so": "Soodejin",
      "sq": "Shkarkime",
      "sr": "Преузимања",
      "sr-latn": "Preuzimanja",
      "su": "Undeuran",
      "sv": "Nedladdningar",
      "sw": "Vipakuliwa",
      "ta": "பதிவிறக்கங்கள்",
      "te": "డౌన్‌లోడ్‌లు",
      "th": "ดาวน์โหลด",
      "tr": "İndirilenler",
      "uk": "Завантаження",
      "ur": "ڈاؤن لوڈز",
      "uz": "Yuklashlar",
      "vi": "Tải về",
      "yo": "gbigba lati ayelujara",
      "zh": "下载",
      "zh-hant": "下載",
      "zu": "Okulandiwe"
    },
    "closeChat": {
      "af": "Maak regstreekse klets toe",
      "am": "የቀጥታ ውይይት ዝጋ",
      "ar": "إغلاق الدردشة المباشرة",
      "as": "লাইভ চেট বন্ধ কৰক",
      "az": "Canlı söhbəti bağlayın",
      "be": "Закрыць жывы чат",
      "bg": "Затворете чата на живо",
      "bn": "লাইভ চ্যাট বন্ধ করুন",
      "bs": "Zatvori chat uživo",
      "ca": "Tanca el xat en directe",
      "ceb": "Isira ang live chat",
      "cs": "Zavřete živý chat",
      "cy": "Cau sgwrs fyw",
      "da": "Luk live chat",
      "de": "Livechat schließen",
      "el": "Κλείσιμο ζωντανής συνομιλίας",
      "en": "Close live chat",
      "es": "Cerrar chat en directo",
      "es-419": "Cerrar chat en directo",
      "et": "Sulge reaalajas vestlus",
      "eu": "Itxi zuzeneko txata",
      "fa": "چت زنده را ببندید",
      "fi": "Sulje live-chat",
      "fil": "Isara ang live chat",
      "fr": "Fermer le chat",
      "ga": "Dún an comhrá beo",
      "gl": "Pecha o chat en directo",
      "gu": "લાઈવ ચેટ બંધ કરો",
      "ha": "Rufe taɗi kai tsaye",
      "he": "סגור צ'אט חי",
      "hi": "लाइव चैट बंद करें",
      "hr": "Zatvori live chat",
      "hu": "Az élő csevegés bezárása",
      "hy": "Փակեք ուղիղ զրույցը",
      "id": "Tutup obrolan langsung",
      "ig": "Mechie nkata ndụ",
      "is": "Lokaðu lifandi spjalli",
      "it": "Chiudi la chat dal vivo",
      "ja": "チャットを閉じる",
      "jv": "Nutup obrolan langsung",
      "ka": "დახურეთ პირდაპირი ჩატი",
      "kk": "Тікелей чатты жабу",
      "km": "បិទការជជែកផ្ទាល់",
      "kn": "ಲೈವ್ ಚಾಟ್ ಅನ್ನು ಮುಚ್ಚಿ",
      "ko": "실시간 채팅 닫기",
      "ky": "Жандуу чатты жабуу",
      "lo": "ປິດການສົນທະນາສົດ",
      "lt": "Uždaryti tiesioginį pokalbį",
      "lv": "Aizvērt tiešraides tērzēšanu",
      "mk": "Затворете го разговорот во живо",
      "ml": "തത്സമയ ചാറ്റ് അടയ്‌ക്കുക",
      "mn": "Шууд чатыг хаах",
      "mr": "थेट गप्पा बंद करा",
      "ms": "Tutup sembang langsung",
      "my": "တိုက်ရိုက်ချတ်ကို ပိတ်ပါ။",
      "nb": "Lukk live chat",
      "ne": "लाइभ च्याट बन्द गर्नुहोस्",
      "nl": "Sluit livechat",
      "or": "ଲାଇଭ୍ ଚାଟ୍ ବନ୍ଦ କରନ୍ତୁ",
      "pa": "ਲਾਈਵ ਚੈਟ ਬੰਦ ਕਰੋ",
      "pl": "Zamknij czat na żywo",
      "ps": "ژوندۍ خبرې بندې کړئ",
      "pt": "Fechar chat ao vivo",
      "pt-pt": "Fechar chat ao vivo",
      "ro": "Închide chatul live",
      "ru": "Закрыть чат",
      "si": "සජීවී කතාබස් වසන්න",
      "sk": "Zavrieť živý chat",
      "sl": "Zapri klepet v živo",
      "so": "Xidh wada sheekaysiga tooska ah",
      "sq": "Mbyll bisedën drejtpërdrejt",
      "sr": "Затворите ћаскање",
      "sr-latn": "Zatvorite ćaskanje",
      "su": "Tutup obrolan langsung",
      "sv": "Stäng livechatt",
      "sw": "Funga gumzo la moja kwa moja",
      "ta": "நேரலை அரட்டையை மூடு",
      "te": "ప్రత్యక్ష ప్రసార చాట్‌ను మూసివేయండి",
      "th": "ปิดแชทสด",
      "tr": "Canlı sohbeti kapat",
      "uk": "Закрити чат",
      "ur": "لائیو چیٹ بند کریں۔",
      "uz": "Jonli suhbatni yoping",
      "vi": "Đóng trò chuyện trực tiếp",
      "yo": "Pa ifiwe iwiregbe",
      "zh": "关闭直播聊天",
      "zh-hant": "關閉聊天室",
      "zu": "Vala ingxoxo ebukhoma"
    },
    "quality": {
      "af": "Kwaliteit",
      "am": "ጥራት",
      "ar": "الجودة",
      "as": "গুণগত মান",
      "az": "Keyfiyyət",
      "be": "Якасць",
      "bg": "Качество",
      "bn": "গুণমান",
      "bs": "Kvaliteta",
      "ca": "Qualitat",
      "ceb": "Kalidad",
      "cs": "Kvalita",
      "cy": "Ansawdd",
      "da": "Kvalitet",
      "de": "Qualität",
      "el": "Ποιότητα",
      "en": "Quality",
      "es": "Calidad",
      "es-419": "Calidad",
      "et": "Kvaliteet",
      "eu": "Kalitatea",
      "fa": "کیفیت",
      "fi": "Laatu",
      "fil": "Kalidad",
      "fr": "Qualité",
      "ga": "Cáilíocht",
      "gl": "Calidade",
      "gu": "ગુણવત્તા",
      "ha": "Kyau",
      "he": "איכות",
      "hi": "क्वालिटी",
      "hr": "Kvaliteta",
      "hu": "Minőség",
      "hy": "Որակ",
      "id": "Kualitas",
      "ig": "Ogo",
      "is": "Gæði",
      "it": "Qualità",
      "ja": "画質",
      "jv": "Kualitas",
      "ka": "ხარისხი",
      "kk": "Сапа",
      "km": "គុណភាព",
      "kn": "ಗುಣಮಟ್ಟ",
      "ko": "화질",
      "ky": "Сапат",
      "lo": "ຄຸນະພາບ",
      "lt": "Kokybė",
      "lv": "Kvalitāte",
      "mk": "Квалитет",
      "ml": "ഗുണനിലവാരം",
      "mn": "Чанар",
      "mr": "गुणवत्ता",
      "ms": "Kualiti",
      "my": "အရည်အသွေး",
      "nb": "Kvalitet",
      "ne": "गुणस्तर",
      "nl": "Kwaliteit",
      "or": "ଗୁଣବତ୍ତା",
      "pa": "ਗੁਣ",
      "pl": "Jakość",
      "ps": "کیفیت",
      "pt": "Qualidade",
      "pt-pt": "Qualidade",
      "ro": "Calitate",
      "ru": "Качество",
      "si": "ගුණාත්මකභාවය",
      "sk": "Kvalita",
      "sl": "Kakovost",
      "so": "Tayada",
      "sq": "Cilësi",
      "sr": "Квалитет",
      "sr-latn": "Kvalitet",
      "su": "Kualitas",
      "sv": "Kvalitet",
      "sw": "Ubora",
      "ta": "தரம்",
      "te": "నాణ్యత",
      "th": "คุณภาพ",
      "tr": "Kalite",
      "uk": "Якість",
      "ur": "معیار",
      "uz": "Sifat",
      "vi": "Chất lượng",
      "yo": "Didara",
      "zh": "画质",
      "zh-hant": "畫質",
      "zu": "Ikhwalithi"
    },
    "auto": {
      "af": "Auto",
      "am": "መኪና",
      "ar": "تلقائي",
      "as": "অটো",
      "az": "Avtomatik",
      "be": "Аўт",
      "bg": "Авто",
      "bn": "অটো",
      "bs": "Auto",
      "ca": "Automàtic",
      "ceb": "Awto",
      "cs": "Auto",
      "cy": "Auto",
      "da": "Auto",
      "de": "Automatisch",
      "el": "Αυτόματ",
      "en": "Auto",
      "es": "Automática",
      "es-419": "Automática",
      "et": "Auto",
      "eu": "Auto",
      "fa": "خودکار",
      "fi": "Auto",
      "fil": "Auto",
      "fr": "Auto",
      "ga": "Uath",
      "gl": "Automático",
      "gu": "ઓટો",
      "ha": "Motoci",
      "he": "אוטומטי",
      "hi": "अपने-आप",
      "hr": "Automatski",
      "hu": "Auto",
      "hy": "Ավտո",
      "id": "Otomatis",
      "ig": "Akpaaka",
      "is": "Sjálfvirk",
      "it": "Automatico",
      "ja": "自動",
      "jv": "Otomatis",
      "ka": "ავტო",
      "kk": "Авто",
      "km": "ស្វ័យប្រវត្តិ",
      "kn": "ಆಟೋ",
      "ko": "자동",
      "ky": "Авто",
      "lo": "ອັດຕະໂນມັດ",
      "lt": "Auto",
      "lv": "Auto",
      "mk": "Автоматски",
      "ml": "ഓട്ടോ",
      "mn": "Автомат",
      "mr": "ऑटो",
      "ms": "Auto",
      "my": "အလိုအလျောက်",
      "nb": "Auto",
      "ne": "अटो",
      "nl": "Automatisch",
      "or": "ଅଟୋ",
      "pa": "ਆਟੋ",
      "pl": "Automat",
      "ps": "اتومات",
      "pt": "Automática",
      "pt-pt": "Automática",
      "ro": "Auto",
      "ru": "Авто",
      "si": "ඔටෝ",
      "sk": "Auto",
      "sl": "Samodejno",
      "so": "Baabuur",
      "sq": "Auto",
      "sr": "Ауто",
      "sr-latn": "Auto",
      "su": "Otomatis",
      "sv": "Auto",
      "sw": "Otomatiki",
      "ta": "ஆட்டோ",
      "te": "ఆటో",
      "th": "อัตโนมัติ",
      "tr": "Otomatik",
      "uk": "Авто",
      "ur": "آٹو",
      "uz": "Avtomatik",
      "vi": "Tự động",
      "yo": "Aifọwọyi",
      "zh": "自动",
      "zh-hant": "自動",
      "zu": "Okuzenzakalelayo"
    },
    "unavailable": {
      "af": "Onbeskikbaar",
      "am": "አይገኝም",
      "ar": "غير متاح",
      "as": "উপলব্ধ নহয়",
      "az": "Əlçatan deyil",
      "be": "Недаступны",
      "bg": "Недостъпно",
      "bn": "অনুপলব্ধ",
      "bs": "Nedostupno",
      "ca": "No disponible",
      "ceb": "Dili magamit",
      "cs": "Není k dispozici",
      "cy": "Ddim ar gael",
      "da": "Ikke tilgængelig",
      "de": "Nicht verfügbar",
      "el": "Μη διαθέσιμο",
      "en": "Unavailable",
      "es": "No disponible",
      "es-419": "No disponible",
      "et": "Pole saadaval",
      "eu": "Ez dago erabilgarri",
      "fa": "در دسترس نیست",
      "fi": "Ei saatavilla",
      "fil": "Hindi magagamit",
      "fr": "Indisponible",
      "ga": "Níl fáil air",
      "gl": "Non dispoñible",
      "gu": "અનુપલબ્ધ",
      "ha": "Babu",
      "he": "לא זמין",
      "hi": "उपलब्ध नहीं",
      "hr": "Nedostupno",
      "hu": "Nem elérhető",
      "hy": "Անհասանելի է",
      "id": "Tidak tersedia",
      "ig": "Ọ dịghị",
      "is": "Ekki tiltækt",
      "it": "Non disponibile",
      "ja": "取得できません",
      "jv": "Ora kasedhiya",
      "ka": "მიუწვდომელია",
      "kk": "Қолжетімсіз",
      "km": "មិនអាចប្រើបាន",
      "kn": "ಲಭ್ಯವಿಲ್ಲ",
      "ko": "사용 불가",
      "ky": "Жеткиликсиз",
      "lo": "ບໍ່ສາມາດໃຊ້ໄດ້",
      "lt": "Nepasiekiamas",
      "lv": "Nav pieejams",
      "mk": "Недостапно",
      "ml": "ലഭ്യമല്ല",
      "mn": "Боломжгүй",
      "mr": "अनुपलब्ध",
      "ms": "Tidak tersedia",
      "my": "မရနိုင်ပါ",
      "nb": "Utilgjengelig",
      "ne": "अनुपलब्ध",
      "nl": "Niet beschikbaar",
      "or": "ଉପଲବ୍ଧ ନାହିଁ",
      "pa": "ਅਣਉਪਲਬਧ",
      "pl": "Niedostępne",
      "ps": "شتون نه لري",
      "pt": "Indisponível",
      "pt-pt": "Indisponível",
      "ro": "Indisponibil",
      "ru": "Недоступно",
      "si": "නොමැත",
      "sk": "Nedostupné",
      "sl": "Ni na voljo",
      "so": "Lama heli karo",
      "sq": "E padisponueshme",
      "sr": "Недоступно",
      "sr-latn": "Nedostupno",
      "su": "Teu sadia",
      "sv": "Ej tillgänglig",
      "sw": "Haipatikani",
      "ta": "கிடைக்கவில்லை",
      "te": "అందుబాటులో లేదు",
      "th": "ไม่พร้อมใช้งาน",
      "tr": "Kullanılamıyor",
      "uk": "Недоступно",
      "ur": "دستیاب نہیں",
      "uz": "Mavjud emas",
      "vi": "Không có sẵn",
      "yo": "Ko si",
      "zh": "不可用",
      "zh-hant": "無法取得",
      "zu": "Ayitholakali"
    },
    "close": {
      "af": "Sluit",
      "am": "ዝጋ",
      "ar": "إغلاق",
      "as": "বন্ধ কৰক",
      "az": "Bağlayın",
      "be": "Закрыць",
      "bg": "Затвори",
      "bn": "বন্ধ",
      "bs": "Zatvori",
      "ca": "Tancar",
      "ceb": "Duol",
      "cs": "Zavřít",
      "cy": "Cau",
      "da": "Luk",
      "de": "Schließen",
      "el": "Κλείσιμο",
      "en": "Close",
      "es": "Cerrar",
      "es-419": "Cerrar",
      "et": "Sule",
      "eu": "Itxi",
      "fa": "بستن",
      "fi": "Sulje",
      "fil": "Isara",
      "fr": "Fermer",
      "ga": "Dún",
      "gl": "Pechar",
      "gu": "બંધ",
      "ha": "Kusa",
      "he": "סגור",
      "hi": "बंद करें",
      "hr": "Zatvori",
      "hu": "Bezárás",
      "hy": "Փակել",
      "id": "Tutup",
      "ig": "Mechie",
      "is": "Lokaðu",
      "it": "Chiudi",
      "ja": "閉じる",
      "jv": "Tutup",
      "ka": "დახურვა",
      "kk": "Жабу",
      "km": "បិទ",
      "kn": "ಮುಚ್ಚಿ",
      "ko": "닫기",
      "ky": "Жабуу",
      "lo": "ປິດ",
      "lt": "Uždaryti",
      "lv": "Aizvērt",
      "mk": "Затвори",
      "ml": "അടയ്ക്കുക",
      "mn": "Хаах",
      "mr": "बंद करा",
      "ms": "Tutup",
      "my": "ပိတ်ပါ။",
      "nb": "Lukk",
      "ne": "बन्द",
      "nl": "Sluiten",
      "or": "ବନ୍ଦ କରନ୍ତୁ",
      "pa": "ਬੰਦ ਕਰੋ",
      "pl": "Zamknij",
      "ps": "تړل",
      "pt": "Fechar",
      "pt-pt": "Fechar",
      "ro": "Închide",
      "ru": "Закрыть",
      "si": "වසන්න",
      "sk": "Zavrieť",
      "sl": "Zapri",
      "so": "Xir",
      "sq": "Mbylle",
      "sr": "Затвори",
      "sr-latn": "Zatvori",
      "su": "Tutup",
      "sv": "Stäng",
      "sw": "Funga",
      "ta": "மூடு",
      "te": "మూసివేయండి",
      "th": "ปิด",
      "tr": "Kapat",
      "uk": "Закрити",
      "ur": "بند",
      "uz": "Yopish",
      "vi": "Đóng",
      "yo": "sunmọ",
      "zh": "关闭",
      "zh-hant": "關閉",
      "zu": "Vala"
    }
  };

  const LOCALE_ALIASES = {
    "es-us": "es-419",
    "es-mx": "es-419",
    "es-ar": "es-419",
    "es-cl": "es-419",
    "es-co": "es-419",
    "es-pe": "es-419",
    "es-ve": "es-419",
    "es-ec": "es-419",
    "es-bo": "es-419",
    "es-py": "es-419",
    "es-uy": "es-419",
    "es-cr": "es-419",
    "es-pa": "es-419",
    "es-do": "es-419",
    "es-gt": "es-419",
    "es-hn": "es-419",
    "es-sv": "es-419",
    "es-ni": "es-419",
    "es-cu": "es-419",
    "es-pr": "es-419",
    "tl": "fil",
    "iw": "he",
    "in": "id",
    "jw": "jv",
    "no": "nb",
    "sh": "sr-latn",
    "zh-tw": "zh-hant",
    "zh-hk": "zh-hant",
    "zh-mo": "zh-hant"
  };

  function langKey() {
    const tag = (document.documentElement.lang || '').trim() || navigator.language || 'en';
    let parts = tag.toLowerCase().split(/[-_]/).filter(Boolean);
    const extension = parts.findIndex((part, index) => index > 0 && part.length === 1);
    if (extension >= 0) parts = parts.slice(0, extension);
    const alias = LOCALE_ALIASES[parts[0]];
    if (alias) parts = alias.split('-').concat(parts.slice(1));
    if (parts[0] === 'zh' && parts.includes('hans')) return 'zh';
    if (parts[0] === 'zh' && (parts.includes('hant') ||
        parts.some(part => ['tw', 'hk', 'mo'].includes(part)))) return 'zh-hant';
    const script = parts.slice(1).find(part => /^[a-z]{4}$/.test(part));
    const region = parts.slice(1).find(part => /^[a-z]{2}$|^\d{3}$/.test(part));
    const candidates = [parts.join('-'), [parts[0], script, region].filter(Boolean).join('-'),
      [parts[0], script].filter(Boolean).join('-'), [parts[0], region].filter(Boolean).join('-'), parts[0]];
    for (const candidate of candidates) {
      const key = LOCALE_ALIASES[candidate] || candidate;
      if (Object.prototype.hasOwnProperty.call(TEXT.download, key)) return key;
    }
    return 'en';
  }

  // A glyph two features share — "add to queue" (watch-bar entry and
  // the ⋮ sheet row). Deliberately the playlist-add glyph, not the native
  // queue icon: keep its stroke and viewBox aligned with adjacent controls.
  const queueIcon =
    'M120-320v-80h280v80H120Zm0-160v-80h440v80H120Zm0-160v-80h440v80H120Zm520 480v-160H480v-80h160v-160h80v160h160v80H720v160h-80Z';

  const downloadIcon =
    'M480-320 280-520l56-58 104 104v-326h80v326l104-104 56 58-200 200ZM240-160q-33 0-56.5-23.5T160-240v-120h80v120h480v-120h80v120q0 33-23.5 56.5T720-160H240Z';

  function text(key) {
    const row = TEXT[key] || {};
    return row[langKey()] || row.en || key;
  }

  function bridge() {
    return window.Bridge || null;
  }

  /** Parsed getPreferences payload; {} when the bridge is missing or fails. */
  function prefs() {
    try { return JSON.parse(bridge()?.getPreferences() || '{}') || {}; }
    catch {
      window.__litubeDiagnostic?.('bridge_failed', 'exception', 'core');
      return {};
    }
  }

  function id(url) {
    try {
      const u = new URL(url || location.href, location.href);
      let id = u.searchParams.get('v');
      if (!id) {
        const segs = u.pathname.split('/').filter(Boolean);
        if (/youtu\.be$/.test(u.hostname)) id = segs[0];
        else {
          for (const kind of ['shorts', 'live', 'embed']) {
            const at = segs.indexOf(kind);
            if (at >= 0) { id = segs[at + 1]; break; }
          }
        }
      }
      return id && ID_RE.test(id) ? id : null;
    } catch { return null; }
  }

  const isId = (id) => !!id && ID_RE.test(id);

  /** The current video's action-bar row. */
  function bar() {
    return document.querySelector('ytm-slim-video-action-bar-renderer .slim-video-action-bar-actions') ||
      document.querySelector('.slim-video-action-bar-actions') ||
      document.querySelector('ytm-slim-video-action-bar-renderer');
  }

  const HOSTS = '.ytSpecButtonViewModelHost, .ytButtonViewModelHost, button-view-model, ' +
    'ytm-button-renderer, ytm-toggle-button-renderer, ytm-slim-toggle-button-renderer';
  const NESTED = 'like-button-view-model, dislike-button-view-model, ' +
    'segmented-like-dislike-button-view-model, ytm-subscribe-button-renderer, ' +
    'ytm-slim-video-metadata-section-renderer, ytm-toggle-button-renderer, ytm-slim-toggle-button-renderer';

  /** Clone source for an action-bar entry: the first free native chip
   *  (share, more, …); injected entries never qualify. When the
   *  bar has no free chip yet, the last nested host — the dislike entry,
   *  after which the entries belong — is the template of last resort. */
  function chip(row) {
    if (!(row instanceof Element)) return null;
    let nested = null;
    for (const host of row.querySelectorAll(HOSTS)) {
      if (host.closest('[data-injected]')) continue;
      if (host.closest(NESTED)) {
        nested = host;
        continue;
      }
      return host;
    }
    return nested;
  }

  function strip(el) {
    el.removeAttribute('href');
    el.removeAttribute('target');
    for (const a of el.querySelectorAll('a[href]')) {
      a.removeAttribute('href');
      a.removeAttribute('target');
    }
  }

  /** Own the cloned icon's DOM: c3-icon can render after cloneNode(),
   *  leaving an empty 24px slot beside a fallback SVG or overwriting a
   *  stamped glyph. Replace it in place before the clone is connected.
   *  Plain SVG templates keep their native layout. */
  function icon(el, path, box) {
    const c3 = el.querySelector('c3-icon');
    if (c3) {
      const host = document.createElement('span');
      host.className = (c3.getAttribute('class') || '') + ' yt-icon-shape';
      host.style.cssText = c3.style.cssText;
      host.style.display = 'inline-flex';
      host.style.fill = 'currentColor';
      host.appendChild(svg(path, box));
      c3.replaceWith(host);
      return true;
    }
    const target = Array.from(el.querySelectorAll('svg')).find(
      (node) => !node.closest('lottie-component'));
    if (target) {
      target.setAttribute('viewBox', box || VIEW_BOX);
      let pathEl = target.querySelector('path');
      if (!(pathEl instanceof Element)) {
        pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        target.appendChild(pathEl);
      }
      pathEl.setAttribute('d', path);
      return true;
    }
    const host = el.querySelector('.yt-icon-shape') ||
      el.querySelector('.ytSpecButtonShapeNextIcon');
    if (!(host instanceof Element)) return false;
    host.appendChild(svg(path, box));
    return true;
  }

  function svg(path, box) {
    const el = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    el.setAttribute('viewBox', box || VIEW_BOX);
    el.setAttribute('width', '24');
    el.setAttribute('height', '24');
    el.setAttribute('aria-hidden', 'true');
    el.setAttribute('focusable', 'false');
    el.setAttribute('fill', 'currentColor');
    const pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    pathEl.setAttribute('d', path);
    el.appendChild(pathEl);
    return el;
  }

  /** Keep a cloned menu's icon gutter after replacing its custom element. */
  function menuIcon(item, path, template) {
    const selector = 'c3-icon,.yt-icon-shape,.yt-spec-button-shape-next__icon,yt-icon';
    const source = template?.querySelector(selector);
    const metrics = source && window.getComputedStyle?.(source);
    if (!icon(item, path)) {
      const button = item.querySelector('button,[role="button"]') || item;
      const host = document.createElement('span');
      host.className = 'yt-icon-shape';
      host.appendChild(svg(path));
      button.prepend(host);
    }
    const target = item.querySelector(selector) || item.querySelector('svg');
    if (!target) return;
    target.style.display = 'inline-flex';
    target.style.alignItems = 'center';
    target.style.justifyContent = 'center';
    target.style.flex = '0 0 24px';
    target.style.width = '24px';
    target.style.height = '24px';
    if (metrics) {
      for (const side of ['Top', 'Right', 'Bottom', 'Left']) {
        target.style['margin' + side] = metrics['margin' + side];
      }
    } else {
      target.style.marginInlineEnd = '16px';
    }
    const glyph = target.matches('svg') ? target : target.querySelector('svg');
    if (glyph) {
      glyph.style.width = glyph.style.height = '24px';
      glyph.style.flex = 'none';
    }
  }

  function fit(el) {
    if (!(el instanceof Element)) return;
    // Some bar variants squeeze entries below icon size, overlapping
    // glyphs; native flexible items absorb the space instead.
    el.style.flex = 'none';
    for (const host of [el.querySelector('c3-icon'), el.querySelector('.yt-icon-shape')]) {
      if (host instanceof Element) {
        host.style.width = '24px';
        host.style.height = '24px';
      }
    }
    const svgEl = el.querySelector('svg');
    if (svgEl instanceof Element) {
      svgEl.setAttribute('width', '24');
      svgEl.setAttribute('height', '24');
      svgEl.style.width = '24px';
      svgEl.style.height = '24px';
    }
  }

  // -- scheduler --

  const state = { mods: new Map(), raf: 0, timer: 0, step: 0 };

  let reportedAppearance = null;
  function syncAppearance() {
    const b = bridge(), root = document.documentElement;
    if (!b?.setPageAppearance || !root || !window.getComputedStyle) return;
    const background = [document.body, root].filter(Boolean)
      .map(node => getComputedStyle(node).backgroundColor)
      .find(color => color && color !== 'transparent' && color !== 'rgba(0, 0, 0, 0)');
    const rgb = background?.match(/^rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    const dark = rgb ? (Number(rgb[1]) * .299 + Number(rgb[2]) * .587 + Number(rgb[3]) * .114 < 128)
      : root.hasAttribute('dark') ? root.getAttribute('dark') !== 'false' : null;
    if (dark == null || dark === reportedAppearance) return;
    b.setPageAppearance(location.href, dark);
    reportedAppearance = dark;
  }

  function run() {
    syncAppearance();
    clearTimeout(state.timer);
    state.timer = 0;
    let pending = false;
    for (const [name, ensure] of state.mods) {
      let ok = true;
      try { ok = ensure() !== false; } catch {
        window.__litubeDiagnostic?.('script_failed', 'exception', /^[a-z_-]{1,40}$/.test(name) ? name : 'core');
        ok = false;
      }
      if (!ok) pending = true;
    }
    if (pending) {
      state.timer = setTimeout(wake, RETRY_MS[Math.min(state.step++, RETRY_MS.length - 1)]);
    } else {
      state.step = 0;
    }
  }

  function wake() {
    if (state.raf || !document.documentElement) return;
    state.raf = requestAnimationFrame(() => {
      state.raf = 0;
      run();
    });
  }

  /** Registers (or replaces) module [name]; ensure() returning false
   *  schedules the backoff retry. */
  function module(name, ensure) {
    state.mods.set(name, ensure);
    wake();
  }

  /** Runs [probe] on the backoff chain until it stops returning false,
   *  at most [cap] attempts. For transient targets, not modules. */
  function retry(probe, cap) {
    let step = 0;
    let timer = 0;
    let alive = true;
    function tick() {
      if (!alive) return;
      if (probe() !== false) return;
      if (cap != null && step >= cap) return;
      timer = setTimeout(tick, RETRY_MS[Math.min(step++, RETRY_MS.length - 1)]);
    }
    tick();
    return { cancel() { alive = false; clearTimeout(timer); } };
  }

  /** Runs [task] once the bridge answers: the bridge can lag behind
   *  document-start injection, and a preference-driven feature without a
   *  poll would stay off until the user flips any toggle. After the poll
   *  budget, re-runs once on the next navigation. */
  function bridgeReady(task) {
    let attempts = 0;
    (function poll() {
      if (task()) return;
      if (attempts++ < 25) {
        setTimeout(poll, 300);
        return;
      }
      window.__litubeDiagnostic?.('bridge_failed', 'missing_bridge', 'core');
      window.addEventListener('yt-navigate-finish',
        () => setTimeout(task, 300), { once: true, capture: true });
    })();
  }

  const sheetHooks = new Map();
  let sheetWatch = null;
  let sheetWatchTimer = 0;

  /** Registers (or replaces) sheet hook [name]; ensure() runs as soon as a
   *  tap adds nodes, before the sheet sizes itself on its first frame. */
  function sheet(name, ensure) {
    sheetHooks.set(name, ensure);
  }

  /** Closes the open sheet the way its own rows do: pops its #hash entry. */
  function closeSheet() {
    if (location.hash) history.back();
  }

  function watchSheets() {
    if (!sheetHooks.size) return;
    clearTimeout(sheetWatchTimer);
    sheetWatchTimer = setTimeout(() => {
      sheetWatch?.disconnect();
      sheetWatch = null;
    }, SHEET_WATCH_MS);
    if (sheetWatch) return;
    sheetWatch = new MutationObserver((records) => {
      if (!records.some((record) => record.addedNodes.length)) return;
      for (const [name, ensure] of sheetHooks) {
        try { ensure(); } catch {
          window.__litubeDiagnostic?.('script_failed', 'exception', /^[a-z_-]{1,40}$/.test(name) ? name : 'core');
        }
      }
    });
    sheetWatch.observe(document.documentElement, { childList: true, subtree: true });
  }

  function start() {
    const root = document.documentElement;
    if (!root) {
      document.addEventListener('DOMContentLoaded', start, { once: true });
      return;
    }
    if (!document.getElementById('lite-touch-style')) {
      const style = document.createElement('style');
      style.id = 'lite-touch-style';
      style.textContent = 'html {-webkit-tap-highlight-color:transparent;}' +
        '.slim-video-action-bar-actions{overflow-x:auto!important;overflow-y:hidden!important;scrollbar-width:none;}' +
        '.slim-video-action-bar-actions::-webkit-scrollbar{display:none;}' +
        '.slim-video-action-bar-actions > *{flex-shrink:0!important;}' +
        '.slim-video-action-bar-actions .segmented-buttons,' +
        '.slim-video-action-bar-actions .segmented-buttons-wrapper,' +
        '.slim-video-action-bar-actions segmented-like-dislike-button-view-model{' +
        'flex:0 0 auto!important;width:auto!important;min-width:max-content!important;max-width:none!important;overflow:visible!important;}' +
        '.slim-video-action-bar-actions like-button-view-model,' +
        '.slim-video-action-bar-actions dislike-button-view-model,' +
        '.slim-video-action-bar-actions ytm-toggle-button-renderer,' +
        '.slim-video-action-bar-actions ytm-slim-toggle-button-renderer{' +
        'display:inline-flex!important;flex:0 0 auto!important;min-width:max-content!important;max-width:none!important;}' +
        '[data-lite-vote-count]{white-space:nowrap;flex-shrink:0;font:inherit;}' +
        '[data-injected="entry"] button:active{opacity:.65;}';
      root.appendChild(style);
    }
    new MutationObserver(wake).observe(root, { childList: true, subtree: true,
      attributes: true, attributeFilter: ['lang', 'dark'] });
    for (const type of ['pointerdown', 'click']) window.addEventListener(type, watchSheets, true);
    for (const type of ['yt-navigate-finish', 'yt-page-data-updated', 'popstate']) {
      window.addEventListener(type, () => setTimeout(wake, SPA_LAG_MS));
    }
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        reportedAppearance = null;
        wake();
      }
    });
    // Backstop when neither the observer nor an SPA event fires.
    setInterval(() => { if (document.visibilityState === 'visible') wake(); }, POLL_MS);
  }

  window.Lite = {
    text, bridge, prefs, id, isId, isShorts, bar, chip, strip, icon, svg, fit, menuIcon,
    queueIcon, downloadIcon, module, retry, bridgeReady, wake, sheet, closeSheet,
  };
  start();
})();
