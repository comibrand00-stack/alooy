# alooytv-proxy

مشروع يشغّل موقع **https://n.alooytv14.xyz/** كامتداد (Provider) لتطبيق **Cloudstream** على أندرويد، مع وكيل Cloudflare Worker اختياري.

## المحتوى

- `AlooTVProvider/` — كود امتداد Cloudstream (Kotlin) يقرأ الأفلام والمسلسلات والحلقات والمشغّل من الموقع.
- `src/index.js` + `wrangler.toml` — Cloudflare Worker وكيل للموقع (اختياري، يُستخدم كـ mainUrl للامتداد).
- `.github/workflows/build.yml` — يبني `.cs3` وينشر `plugins.json` على فرع `builds`.
- `repo.json` — ملف المستودع الذي تُضيفه داخل Cloudstream.

## خطوات التشغيل (Cloudstream)

### 1) رفع المشروع إلى GitHub

أنشئ مستودعًا جديدًا على [GitHub](https://github.com/new) (مثلاً `alooytv-cs3`)، ثم ارفع كل ملفات هذا المجلد إليه.

> ملاحظة: البناء يتم عبر GitHub Actions تلقائيًا عند الرفع إلى فرع `master` أو `main`.

### 2) ضبط `repo.json`

عدّل هذا السطر داخل `repo.json` ليتطابق مع مستودعك:

```json
"https://raw.githubusercontent.com/USERNAME/REPO_NAME/builds/plugins.json"
```

مثال: إذا كان مستودعك `https://github.com/ahmed/alooytv-cs3`

```json
"https://raw.githubusercontent.com/ahmed/alooytv-cs3/builds/plugins.json"
```

### 3) إنشاء فرع `builds` (مرة واحدة)

بعد أول رفع للمستودع، أنشئ فرعًا فارغًا اسمه `builds` حتى يعمل سكريبت GitHub Actions:

```bash
git checkout --orphan builds
git rm -rf .
git commit --allow-empty -m "init builds"
git push origin builds
git checkout master
```

أو أنشئه من صفحة GitHub: **Branches → New branch → اكتب `builds`** دون اختيار أي مصدر (فارغ).

### 4) الإضافة داخل Cloudstream

1. افتح تطبيق Cloudstream.
2. **Settings (الإعدادات)** ⚙️ → **Extensions → Add Repository**.
3. الصق رابط مستودعك الـ JSON، مثل:
   ```
   https://raw.githubusercontent.com/ahmed/alooytv-cs3/master/repo.json
   ```
4. اضغط **Add**، ستظهر إضافة **AlooTV (JoooTV)**.
5. ثبّت الإضافة ثم افتح أي فيلم أو مسلسل.

## وضع الوكيل (اختياري)

الامتداد يقرأ مباشرة من `https://n.alooytv14.xyz` افتراضيًا (mainUrl داخل الكود). إذا كان الموقع محجوبًا في بلدك، غيّر `mainUrl` في `AlooTVProvider.kt` إلى رابط الـ Worker:

```kotlin
override var mainUrl = "https://alooytv-proxy.nu2-proxy.workers.dev"
```

ثم أعد رفع التغيير — GitHub Actions سيعيد البناء تلقائيًا.

## تطوير محلي

المتطلبات: JDK 17 + Android SDK.

```bash
./gradlew AlooTVProvider:make
```

الناتج: `AlooTVProvider/build/*.cs3` يمكنك نسخه للتطبيق يدويًا.

## Worker (اختياري)

```bash
npm install
npx wrangler login
npm run deploy
```

يمنحك رابط وكيل: `https://alooytv-proxy.<subdomain>.workers.dev`