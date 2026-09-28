# SolarMovie2 + AlooTV — مستودع CloudStream

مشروع يشغّل موقع **https://ww1.solarmovie2.com** كامتداد (Provider) لتطبيق **CloudStream** على أندرويد، مع دعم **الخوادم المتعددة (Server 1/2/3)** و **الترجمة العربية**، بالإضافة لامتداد AlooTV الموجود مسبقاً. مع وكيل Cloudflare Worker اختياري.

## المحتوى

- `SolarMovieProvider/` — امتداد CloudStream (Kotlin) لموقع SolarMovie2:
  - قوائم: أفلام، مسلسلات، Top IMDb، تصنيفات (أكشن، دراما، رعب...)
  - بحث عبر `/searching` (JSON) مع بوستر وجودة وسنة
  - تفاصيل: عنوان، بوستر، قصة، سنة، تصنيفات، ممثلين، حلقات (للأفلام حلقة واحدة Full HD، للمسلسلات Episode 1..N)
  - مشغّل: خوادم `Server 1 / Server 2 / Server 3` عبر `https://ployan.live` مع فك تشفير `PBKDF2-SHA256 + AES-256-GCM` (نفس طريقة مشغّل الموقع) للحصول على رابط `master.m3u8` مباشر
  - ترجمة: العربية أولاً (عبر OpenSubtitles) + الإنجليزية، عبر `subtitleCallback`
- `AlooTVProvider/` — امتداد AlooTV السابق (كما هو).
- `src/index-solarmovie.js` — Cloudflare Worker وكيل لموقع SolarMovie2 (اختياري، للبلدان المحجوبة) + بروكسي لمشغّل `ployan.live` عبر `/ployan/*`.
- `src/index.js` + `wrangler.toml` — Worker السابق لـ AlooTV.
- `.github/workflows/build.yml` — يبني كل `.cs3` وينشر `plugins.json` على فرع `builds` (تم إصلاح الرابط ليعمل مع أي مستودع تلقائياً عبر `GITHUB_REPOSITORY`).
- `repo.json` — ملف المستودع الذي تُضيفه داخل CloudStream.

## كيف يعمل مشغّل SolarMovie2؟

1. صفحة الفيلم تحتوي `data-mid` (مثال `22472`) وقائمة `Server 1/2/3` وحلقات `Episode 1..N`.
2. الامتداد يولّد توكن لكل خادم: `mid+episode+server+timestamp` مشفّر بـ `PBKDF2("player", salt 8B, 1000, SHA256, 256bit) + AES-256-GCM (iv 12B)` بصيغة `saltHex-ivHex-cipherHex+tagHex` (تم عكسه من مشغّل `ployan.live`).
3. طلب `GET https://ployan.live/get/{token}` يُرجع `{"mode":"direct","info":"..."}` للخادم 1، و `{"mode":"embed",...}` للخادمين 2 و 3.
4. وضع `direct` يُعطي رابط `https://ployan.live/hls/{info}/master.m3u8` مباشر (مجرّب ويعمل، مثال Wolf Warriors 2).
5. الترجمة العربية تُجلب من `rest.opensubtitles.org` (بدون مفتاح) بالبحث عن عنوان الفيلم مع `sublanguageid-ara`، والإنجليزية كاحتياطي.

## خطوات إنشاء مستودع GitHub جديد (Repository)

### 1) إنشاء المستودع على GitHub

1. افتح [GitHub New Repository](https://github.com/new).
2. اسم المستودع (Repository name): مثلاً `solarmovie-cs3`.
3. اختر **Public**.
4. **لا** تفعّل Add README / .gitignore (لأن الملفات موجودة محلياً).
5. اضغط **Create repository**.

### 2) رفع هذا المشروع إلى المستودع الجديد

من مجلد المشروع محلياً (`C:\Users\DELL\Documents\nu2`):

```bash
git remote remove origin
git remote add origin https://github.com/USERNAME/solarmovie-cs3.git
git add .
git commit -m "Add SolarMovie2 provider with servers + Arabic subs"
git branch -M master
git push -u origin master
```

استبدل `USERNAME` باسم حسابك و `solarmovie-cs3` باسم المستودع.

### 3) ضبط `repo.json`

عدّل `repo.json` ليطابق مستودعك الجديد:

```json
"https://raw.githubusercontent.com/USERNAME/solarmovie-cs3/builds/plugins.json"
```

مثال: إذا كان مستودعك `https://github.com/ahmed/solarmovie-cs3`:

```json
"https://raw.githubusercontent.com/ahmed/solarmovie-cs3/builds/plugins.json"
```

> ملاحظة: البناء يتم عبر GitHub Actions تلقائياً عند الرفع إلى فرع `master` أو `main`. ملف `build.yml` أصبح يستخدم `${GITHUB_REPOSITORY}` تلقائياً فلا حاجة لتعديله يدوياً.

### 4) إنشاء فرع `builds` (مرة واحدة)

بعد أول رفع، أنشئ فرعاً فارغاً اسمه `builds`:

```bash
git checkout --orphan builds
git rm -rf .
git commit --allow-empty -m "init builds"
git push origin builds
git checkout master
```

أو من صفحة GitHub: **Branches → New branch → اكتب `builds`**.

بعدها كل push على `master` سيبني `SolarMovieProvider.cs3` + `AlooTVProvider.cs3` وينشر `plugins.json` على فرع `builds`.

### 5) الإضافة داخل CloudStream

1. افتح تطبيق CloudStream.
2. **Settings** ⚙️ → **Extensions → Add Repository**.
3. الصق رابط مستودعك:
   ```
   https://raw.githubusercontent.com/USERNAME/solarmovie-cs3/master/repo.json
   ```
4. اضغط **Add**، ستظهر إضافتا **SolarMovie2** و **AlooTV (JoooTV)**.
5. ثبّت **SolarMovie2** ثم افتح أي فيلم أو مسلسل:
   - ستجد **Server 1 / Server 2 / Server 3** (يعمل Server 1 مباشرة HLS، و 2/3 احتياطي).
   - الترجمة العربية تظهر أولاً عند توفرها (Arabic)، ثم English.

## وضع الوكيل (اختياري - إذا كان الموقع محجوباً)

الامتداد يقرأ مباشرة من `https://ww1.solarmovie2.com` افتراضياً. إذا كان محجوباً:

1. انشر Worker الخاص بـ SolarMovie2:
   ```bash
   npm install
   npx wrangler login
   npx wrangler deploy --config wrangler-solarmovie.toml
   ```
   أو غيّر `main` في `wrangler.toml` إلى `src/index-solarmovie.js` ثم `npm run deploy`.
2. ستحصل على رابط مثل `https://solarmovie-proxy.<subdomain>.workers.dev`.
3. غيّر `mainUrl` في `SolarMovieProvider.kt`:
   ```kotlin
   override var mainUrl = "https://solarmovie-proxy.<subdomain>.workers.dev"
   ```
4. أعد الرفع — GitHub Actions سيعيد البناء تلقائياً.

## تطوير محلي

المتطلبات: JDK 17 + Android SDK.

```bash
./gradlew SolarMovieProvider:make
./gradlew AlooTVProvider:make
```

الناتج: `SolarMovieProvider/build/*.cs3` و `AlooTVProvider/build/*.cs3` يمكنك نسخها للتطبيق يدوياً.

## ملاحظات

- الخوادم الثلاثة تعمل: **Server 1** عبر `ployan.live` مباشرة (HLS)، و **Server 2/3** عبر سلسلة `ployan embed → vidsrc data API → فك تشفير WASM الدوّار (مطبّق بـ Kotlin خالص) → توكن IP لكل مضيف`.
- الترجمة من مشغّل `ployan.live` نفسه (`/sub/{mid-ep}/index.json` — العربية أولاً عند توفرها ثم English وبقية اللغات)، وهي روابط `.vtt` مباشرة.
- أقسام الرئيسية تستخدم الروابط الصحيحة (`/movies/` بدون `/1/` لأن `/movies/1/` صفحة تحويل فارغة) وصفحات التصنيفات صفحة واحدة.
