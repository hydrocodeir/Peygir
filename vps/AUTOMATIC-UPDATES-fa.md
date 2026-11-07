# انتشار خودکار اپ و سرور با GitHub Actions

Workflow اکنون **Peygir release** نام دارد و پیش از انتشار APK، وب/API را نیز با حفظ تنظیمات و volumeهای نصب فعلی ارتقا می‌دهد. برای انتشار فقط سرور، گزینهٔ دستی `deploy-server` اضافه شده است. [مراحل و جزئیات انتشار سرور](AUTOMATIC-SERVER-fa.md).

Workflow در `.github/workflows/android-update.yml` سه کاربرد دارد:

- اجرای دستی `check` از شاخهٔ `main`: آزمون ابزارها و بررسی اتصال SSH، هویت سرور، مسیر نصب، سلامت API و وجود ابزار انتشار؛ چیزی منتشر نمی‌شود.
- اجرای دستی `deploy-server` از `main`: تست و ارتقای فقط وب/API با پشتیبان‌گیری؛ APK منتشر نمی‌شود.
- Push تگ مانند `v4.11.0`: کنترل نسخه و یادداشت انتشار، تست و ساخت APK، تست و ارتقای وب/API و سپس انتشار APK روی VPS و بررسی فایل عمومی.

اگر سورس سرور تغییر کرده باشد، API در بخش پشتیبان‌گیری و راه‌اندازی وقفهٔ کوتاهی دارد. فایل‌ها از سرور پیگیر دریافت می‌شوند؛ دانلود از GitHub برای کاربران لازم نیست.

## تنظیم یک‌بارهٔ GitHub

در Settings → Secrets and variables → Actions → Repository secrets این موارد را ثبت کنید:

| Secret | مقدار |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 فایل فعلی `.signing/android-user/debug.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | رمز keystore فعلی؛ در نصب فعلی `android` |
| `ANDROID_KEY_ALIAS` | alias فعلی؛ `androiddebugkey` |
| `ANDROID_KEY_PASSWORD` | رمز کلید فعلی؛ `android` |
| `VPS_HOST` | IP عمومی یا نام میزبان SSH؛ بدون scheme، کاربر و پورت |
| `VPS_PORT` | پورت واقعی SSH |
| `VPS_USER` | کاربر اتصال؛ در نصب فعلی `root` |
| `VPS_SSH_KEY` | تمام کلید خصوصی اختصاصی انتشار، با خطوط BEGIN و END |
| `VPS_HOST_KEY` | تمام خط کلید عمومی `/etc/ssh/ssh_host_ed25519_key.pub` که با `ssh-ed25519` شروع می‌شود |

کلید ورود کاربر و کلید عمومی میزبان، دو کلید متفاوت هستند. فایل خصوصی یا Base64 آن را در سورس یا متن issue قرار ندهید. Secret مربوط به keystore قبلی را حفظ کنید؛ Workflow فقط گواهی SHA-256 زیر را برای نصب روی گوشی‌های فعلی می‌پذیرد:

```text
81573fa6a77199b83ef74beda1aa664d20333c0744ac975853c0787b55de81d5
```

کاربر SSH فعلی باید بتواند اسکریپت Compose را از مسیر نصب بخواند و Docker را بدون پرسش رمز اجرا کند. کلید ساخته‌شده با گزینهٔ `restrict` در `authorized_keys` سازگار است؛ اتصال تعاملی و PTY لازم نیست. اتصال فقط با host key ثبت‌شده پذیرفته می‌شود و از `StrictHostKeyChecking=no` استفاده نشده است.

در تب Variables، فقط اگر مسیر یا دامنه با نصب فعلی متفاوت است، این دو Repository variable را تعریف کنید:

| Variable | پیش‌فرض |
|---|---|
| `VPS_APP_DIR` | `/root/peygir/Peygir-VPS` |
| `UPDATE_ORIGIN` | `https://peygir.hydrocode.ir` |

API در VPS باید قبلاً نسخهٔ ۴.۱۰ یا جدیدتر داشته باشد تا `/app/vps/scripts/install-android-update.mjs` و مسیر عمومی آپدیت در کانتینر موجود باشند. کلید جدید به کاربر همان سرور در `authorized_keys` اضافه شده باشد.

## نخستین بررسی اتصال

فایل‌های جدید Workflow و ابزارها را commit و روی `main` push کنید. سپس در GitHub:

1. Actions → Peygir release را باز کنید.
2. Run workflow را بزنید و شاخهٔ `main` را انتخاب کنید.
3. گزینهٔ `check` را نگه دارید و دوباره Run workflow را بزنید.
4. نتیجهٔ job به نام `Check VPS connection (no publication)` باید سبز شود و پیام‌های `VPS_CONNECTION_OK` و `SERVER_CONNECTION_OK` داشته باشد.

این اجرا هنوز APK نمی‌سازد و manifest عمومی را تغییر نمی‌دهد. در صورت خطا، متن مرحلهٔ ناموفق را بررسی کنید؛ Secrets را در چت یا گزارش عمومی قرار ندهید.

## انتشار نسخهٔ بعدی

برای نمونه، نسخهٔ بعدی از ۴.۱۰.۰ با کد ۳۹ به ۴.۱۰.۱ با کد ۴۰ می‌رود:

1. تغییرات اپ را انجام دهید و در `app/build.gradle.kts`، `versionCode` را افزایش و `versionName` را تنظیم کنید.
2. فایل UTF-8 `release-notes/android/4.10.1-fa.txt` را با توضیح تغییرات واقعی بسازید. فایل مخصوص هر نسخه الزامی است؛ از متن قدیمی برای نسخهٔ جدید استفاده نمی‌شود.
3. تغییرات را commit و روی `main` push کنید.
4. تگ نسخه را بسازید و push کنید:

```bash
git tag -a v4.10.1 -m "Peygir 4.10.1"
git push origin v4.10.1
```

تگ باید با `versionName` تطبیق داشته باشد و commit آن در تاریخچهٔ `main` باشد. تگ نسخهٔ ۴.۱۰.۰ را برای امتحان روی کد فعلی دوباره منتشر نکنید؛ کد ۳۹ قبلاً با APK دیگری روی سرور منتشر شده است. برای فایل متفاوت، کد نسخهٔ جدید لازم است.

پس از موفقیت تست‌ها و ساخت، سرور پشتیبان‌گیری و ارتقا داده می‌شود و سپس APK منتشر می‌شود. تنظیمات اپ نسخهٔ جدید را از همان سرور دریافت می‌کند. این Workflow، GitHub Release ایجاد نمی‌کند؛ بستهٔ سرور و فایل APK و manifest به‌صورت artifact همان اجرا برای ۱۴ روز نگهداری می‌شوند. کاربران فقط از مسیر سرور استفاده می‌کنند.

## انتقال و کنترل انتشار

APK release به‌صورت unsigned ساخته و با همان keystore قبلی امضا می‌شود؛ خاموش‌بودن حالت debug در فایل release مانع ارتقای برنامهٔ قبلی با همان شناسه و امضا نیست. گواهی امضا و هم‌ترازی ZIP بررسی می‌شوند. رمزها به `apksigner` از طریق متغیر محیطی داده می‌شوند و فایل موقت keystore پس از امضا حذف می‌شود.

فایل امضاشده در artifact جداگانه نگهداری می‌شود تا در صورت خطای اتصال، Re-run failed jobs فقط مرحلهٔ Publish را با همان فایل تکرار کند. بازسازی فایل متفاوت با همان کد نسخه پذیرفته نمی‌شود. انتشارهای این Workflow هم‌زمان اجرا نمی‌شوند و انتشار نسخهٔ قدیمی‌تر از manifest فعلی مسدود می‌شود.

انتقال با جریان `tar` روی SSH و `docker exec -i --user node` انجام می‌شود تا `/tmp` از نوع tmpfs و ریشهٔ فقط‌خواندنی کانتینر پشتیبانی شوند. ابزار فعلی سرور حجم و هش را تأیید و سپس `latest.json` را اتمی جایگزین می‌کند. در پایان، manifest و تمام APK از نشانی HTTPS عمومی دوباره دریافت و هش آن بررسی می‌شود. فایل‌های موقت انتقال حذف می‌شوند.

در صورت خطا در Build هیچ فایلی به VPS ارسال نمی‌شود. اگر انتقال یا انتشار خطا داد، job Publish را از همان اجرا تکرار کنید. اگر بررسی عمومی بعد از انتشار خطا بدهد، فایل روی سرور ممکن است منتشر شده باشد؛ ابتدا همان job شکست‌خورده را با artifact قبلی تکرار کنید.

اگر تست یا Lint شکست بخورد، گزارش‌های موجود با نام `android-check-reports` در بخش Artifacts همان اجرا برای ۷ روز ذخیره می‌شوند. فایل‌های XML تست، گزارش HTML و گزارش Lint جزئیات خطا را دارند. خطاهای تست مانع امضا و انتشار می‌شوند؛ لازم نیست برای عبور از Build تست‌ها را خاموش کنید.

## بررسی محلی ابزارها

```bash
python3 -m unittest discover -s scripts/ci/tests -v
bash scripts/ci/tests/deploy_smoke.sh
bash scripts/ci/tests/server_deploy_smoke.sh
python3 scripts/ci/android_release.py validate --tag v4.11.0
```

Smoke test از کلیدهای موقت آزمایشی و SSH ساختگی استفاده می‌کند؛ به VPS وصل نمی‌شود. اجرای واقعی GitHub و اتصال به سرور پس از push باید با مرحلهٔ نخستین بررسی اتصال تأیید شوند.

منابع: [ساخت و امضای اندروید](https://developer.android.com/build/building-cmdline)، [GitHub Actions Secrets](https://docs.github.com/en/actions/concepts/security/secrets)، [انتقال با tar و docker exec](https://docs.docker.com/reference/cli/docker/container/cp/#corner-cases).
