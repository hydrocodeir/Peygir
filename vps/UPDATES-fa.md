# به‌روزرسانی مستقیم پیگیر — ۴.۱۰.۰

برای ساخت، امضا، انتقال و آماده‌شدن آپدیت با Push تگ نسخه، [راهنمای انتشار خودکار با GitHub Actions](AUTOMATIC-UPDATES-fa.md) را دنبال کنید. مراحل زیر روش انتشار دستی‌اند.

در اندروید، «تنظیمات ← به‌روزرسانی برنامه ← بررسی نسخهٔ جدید» را باز کنید. نسخه، تغییرات و حجم نمایش داده می‌شود؛ «دریافت نسخهٔ جدید» فایل را از سرور پیگیر می‌گیرد و پیشرفت دریافت و گزینهٔ لغو را نمایش می‌دهد. سپس «نصب نسخهٔ جدید» نصب‌کنندهٔ اندروید را باز می‌کند. در نخستین استفاده، اجازهٔ نصب از پیگیر را فعال کنید، برگردید و دوباره نصب را بزنید. تأیید نصب با کاربر است؛ داده‌ها و حساب‌ها حفظ می‌شوند.

برای فعال‌شدن این قابلیت، نسخهٔ ۴.۱۰.۰ را یک بار روی گوشی نصب کنید. نسخه‌های قبلی دکمهٔ به‌روزرسانی ندارند. فایل‌های نسخه‌های بعدی باید با همان کلید نسخهٔ نصب‌شده امضا و با `versionCode` بزرگ‌تر ساخته شوند. APK فعلی مثل نسخه‌های تحویلی قبلی با کلید debug موجود پروژه امضا شده است؛ تغییر کلید مانع ارتقای درجا می‌شود.

## نشانی و بررسی فایل

پیش‌فرض، نشانی سرور حساب فعلی به‌اضافهٔ `/updates/android/latest.json` است؛ در حالت محلی `https://peygir.hydrocode.ir/updates/android/latest.json` استفاده می‌شود. از «نشانی سرور به‌روزرسانی» می‌توان نشانی HTTPS دیگری ذخیره کرد؛ این انتخاب برای همهٔ حساب‌های همان نصب است. حساب‌های Cloudflare هم می‌توانند از سرور VPS عمومی آپدیت استفاده کنند. این مسیر عمومی است و توکن یا اطلاعات ورود شرکت به آن فرستاده نمی‌شود.

نشانی APK باید هم‌مبدأ و دقیقاً `/updates/android/peygir-VERSION_CODE.apk` باشد. تغییرمسیر HTTP، فایل بیش از یک گیگابایت، نسخهٔ نامتناسب با اندروید، شناسهٔ بستهٔ دیگر، حجم یا SHA-256 نامعتبر و امضای متفاوت پذیرفته نمی‌شود. دریافت ناقص قابل نصب نیست؛ لغو یا خطا فایل ناقص را پاک می‌کند و امکان تلاش دوباره هست. حین دریافت، برنامه را باز نگه دارید؛ پس از بسته‌شدن فرایند ممکن است دریافت مجدد لازم باشد. فایل کامل با «بررسی نسخهٔ جدید» دوباره اعتبارسنجی و برای نصب بازیابی می‌شود.

## آماده‌سازی نسخهٔ بعدی روی سیستم توسعه

نسخهٔ اندروید و `versionCode` را افزایش دهید، APK را با کلید قبلی بسازید، و متن تغییرات را در فایل UTF-8 ذخیره کنید. ابزار، مشخصات واقعی APK را با `aapt` می‌خواند و حجم و هش را محاسبه می‌کند:

```powershell
python scripts/publish_android_update.py `
  --apk delivery/Peygir-4.10.0-debug.apk `
  --output .local-build/android-update `
  --aapt D:/Pooya/w/All/Farda/.toolchain/sdk/build-tools/35.0.0/aapt.exe `
  --notes-file verification/settings-updates/release-notes-fa.txt `
  --zip delivery/Peygir-4.10.0-android-update.zip
```

بستهٔ آمادهٔ این نسخه شامل `peygir-39.apk` و `latest.json` است. برای APK متفاوت از کد نسخهٔ تکراری استفاده نکنید.

## انتشار روی VPS/Nginx

ابتدا سورس/API را با بستهٔ `Peygir-4.10.0-nginx-vps.zip` طبق [راهنمای Nginx](NGINX-fa.md) ارتقا دهید. بستهٔ `Peygir-4.10.0-android-update.zip` را در پوشهٔ خانگی سرور قرار دهید. سپس دستورهای زیر را اجرا کنید؛ اگر مسیر نصب شما متفاوت است، دستور `cd` را مطابق آن تغییر دهید:

```bash
(
  set -euo pipefail
  mkdir -p ~/peygir-android-4.10.0
  unzip -o ~/Peygir-4.10.0-android-update.zip -d ~/peygir-android-4.10.0
  cd ~/peygir/Peygir-VPS
  container=$(sudo bash vps/scripts/peygir-nginx.sh ps -q api)
  test -n "$container"
  sudo docker exec --user node "$container" test -f /app/vps/scripts/install-android-update.mjs
  update_dir=$(sudo docker exec --user node "$container" mktemp -d /tmp/peygir-update.XXXXXX)
  tar -C ~/peygir-android-4.10.0 -cf - latest.json peygir-39.apk | \
    sudo docker exec -i --user node "$container" tar --no-same-owner -xf - -C "$update_dir"
  sudo docker exec --user node "$container" node /app/vps/scripts/install-android-update.mjs \
    --source "$update_dir" --data-dir /data
  sudo docker exec --user node "$container" rm -- "$update_dir/latest.json" "$update_dir/peygir-39.apk"
  sudo docker exec --user node "$container" rmdir -- "$update_dir"
  curl --fail https://peygir.hydrocode.ir/updates/android/latest.json
)
```

ریشهٔ کانتینر طبق Compose فقط‌خواندنی است و `/tmp` به‌صورت `tmpfs` قابل‌نوشتن mount شده است. برای انتقال به این mount از `tar` و `docker exec -i` استفاده می‌شود؛ `docker cp` روی این مسیر می‌تواند خطای `container rootfs is marked read-only` بدهد. حالت فقط‌خواندنی را غیرفعال نکنید. انتقال و انتشار با کاربر `node` انجام می‌شوند تا مالکیت فایل‌ها با کاربر API یکسان باشد. نام پوشهٔ موقت یکتا است؛ پس از انتشار موفق فقط همان فایل‌های موقت حذف می‌شوند. اگر انتقال قطع شد، همین بلوک را دوباره اجرا کنید. [محدودیت‌های docker cp و روش انتقال با tar](https://docs.docker.com/reference/cli/docker/container/cp/#corner-cases).

ابزار ابتدا حجم و هش APK را تأیید می‌کند، فایل نسخه‌دار را روی volume پایدار `/data/updates/android` می‌گذارد و در پایان `latest.json` را اتمی جایگزین می‌کند. نسخهٔ موجود با همان کد و فایل متفاوت بازنویسی نمی‌شود. شرکت‌ها و داده‌ها تغییر نمی‌کنند؛ توقف API، تغییر پورت و گواهی یا بارگذاری فایل روی GitHub لازم نیست. فایل‌های آپدیت جزو پشتیبان پایگاه داده نیستند و پس از بازیابی سرور باید دوباره منتشر شوند.

## نسخهٔ وب

در «تنظیمات ← به‌روزرسانی برنامه»، «بررسی به‌روزرسانی وب» را بزنید. اگر پوستهٔ جدید آماده باشد، «اعمال نسخهٔ جدید» نمایش داده می‌شود. پس از تأیید، برنامه دوباره بارگذاری می‌شود؛ داده‌های ذخیره‌شده و صف آفلاین در IndexedDB باقی می‌مانند. کاربران وب قدیمی یک بار پیگیر را ببندند و دوباره باز کنند تا دکمهٔ جدید را بگیرند.

مراجع پیاده‌سازی: [مجوز نصب و بررسی بسته در Android](https://developer.android.com/reference/android/content/pm/PackageManager)، [اشتراک امن فایل با FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider).
