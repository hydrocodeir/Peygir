> نسخهٔ وب قابل نصب: پس از ارتقای API، نشانی `https://peygir.hydrocode.ir/app/` را باز کنید. [راهنمای نصب روی آیفون و اعلان وب](PWA-fa.md).

# راه‌اندازی پیگیر روی peygir.hydrocode.ir

این راهنما مخصوص سرور شماست: Nginx فعلی پورت‌های ۸۰ و ۴۴۳ را دارد؛ پیگیر فقط روی `127.0.0.1:18300` منتشر می‌شود. زیردامنهٔ هر شرکت لازم نیست؛ همهٔ شرکت‌ها از همین نشانی و کد شرکت مستقل استفاده می‌کنند. Caddy در این حالت اجرا نمی‌شود.

دستورها Bash هستند و روی Ubuntu شما اجرا می‌شوند. استقرار عمومی از طرف دستیار انجام نشده است. قبل از ادامه، رکورد A زیردامنه را با IP عمومی VPS تطبیق دهید. اگر AAAA دارید، باید IPv6 همین سرور باشد؛ در غیر این صورت آن رکورد را اصلاح یا حذف کنید. اگر DNS پشت پراکسی Cloudflare است، برای راه‌اندازی اولیه آن را DNS only بگذارید؛ این تنظیم به معنی استفاده از Workers نیست.

## ارتقای نصب موجود به ۴.۱۰ — تنظیمات یادآوری و آپدیت داخلی

بستهٔ `Peygir-4.10.0-nginx-vps.zip` را در همان پوشهٔ نصب قبلی باز کنید و سورس جدید را جایگزین کنید. پیش از ارتقا از داده‌ها پشتیبان بگیرید؛ پوشهٔ `.device-private`، فایل محیط، تنظیمات Firebase، نام پروژهٔ Compose و volumeهای قبلی را حفظ کنید. init و مراحل صدور گواهی را تکرار نکنید. سپس:

```bash
sudo bash vps/scripts/peygir-nginx.sh up -d --build --wait --wait-timeout 90 api
curl --fail http://127.0.0.1:18300/health
```

نسخهٔ سلامت باید `4.10.0` شود. رابط وب جدید پس از بستن و بازکردن PWA فعال می‌شود؛ برای تنظیمات جدید و آپدیت داخلی، APK نسخهٔ ۴.۱۰.۰ را یک بار روی نسخهٔ قبلی نصب و [فایل‌های آپدیت را منتشر کنید](UPDATES-fa.md). وب قابل نصب در `/app/` فعال می‌شود؛ [راهنمای وب و آیفون](PWA-fa.md) را ببینید. در نسخهٔ جدید، رزرو جلسه و درخواست تمدید هم حفظ شده‌اند. پایگاه محلی خودکار به ۹ ارتقا می‌یابد؛ سرور جدول افزایشی midpoint_events را بدون حذف داده ایجاد می‌کند. برای کاربران اندروید قدیمی‌تر از ۴.۶، ارتقای قبلی چندشرکتی همچنان توصیه می‌شود. جدول افزایشی `push_accounts` خودکار روی هر شرکت ایجاد می‌شود؛ مصوبات، جلسات، رمزها و ثبت دستگاه‌های قدیمی حفظ می‌شوند. تغییر Nginx، پورت یا گواهی لازم نیست. از `down -v` استفاده نکنید؛ volume داده باید حفظ شود.

## ۱ - انتقال بسته و اجرای API

بستهٔ `Peygir-4.10.0-nginx-vps.zip` را با SFTP یا WinSCP به پوشهٔ خانگی کاربر سرور منتقل کنید. سپس:

```bash
mkdir -p ~/peygir
unzip ~/Peygir-4.10.0-nginx-vps.zip -d ~/peygir
cd ~/peygir/Peygir-VPS
```

اگر پیگیر را قبلاً روی همین سرور راه انداخته‌اید، پوشهٔ خصوصی و volumeهای موجود را نگه دارید؛ مرحلهٔ init را تکرار نکنید. نام volumeها را تغییر ندهید. `PEYGIR_DOMAIN` در `.device-private/vps/compose.env` باید `peygir.hydrocode.ir` باشد. برای نصب تازه:

```bash
sudo docker run --rm --user "$(id -u):$(id -g)" \
  -v "$PWD:/workspace" -w /workspace \
  node:24.21.0-bookworm-slim \
  node vps/scripts/init.mjs --domain peygir.hydrocode.ir

sudo bash vps/scripts/peygir-nginx.sh up -d --build --wait --wait-timeout 90
sudo bash vps/scripts/peygir-nginx.sh ps
curl --fail http://127.0.0.1:18300/health
```

پاسخ آخر باید JSON با `service: peygir-vps` و نسخهٔ `4.10.0` باشد. اطلاعات ورود مدیر سامانه در `.device-private/vps/owner-login.txt` ساخته می‌شود؛ آن را فقط روی سیستم خودتان بخوانید و در چت نفرستید. رمز در خروجی init چاپ نمی‌شود. فایل‌های خصوصی وارد بسته یا image نمی‌شوند.

برای همهٔ فرمان‌های پیگیر در این سرور، از `sudo bash vps/scripts/peygir-nginx.sh ...` استفاده کنید؛ این میان‌بر همیشه فایل Compose مخصوص Nginx را انتخاب می‌کند. فرمان عمومی `-f vps/compose.yaml up` مربوط به حالت مستقل Caddy است و روی این سرور مناسب نیست.

## رفع خطای EACCES در بستهٔ اولیه

اگر لاگ `permission denied, open '/run/secrets/admin_bootstrap'` دارد، بستهٔ اولیه تحت umask محدودکننده، فایل‌های JSON را ۶۰۰ ساخته است. کانتینر با کاربر غیرریشه اجرا می‌شود و باید فایل‌های mountشده را بخواند. بستهٔ fix1 صریحاً فقط سه JSON موردنیاز را به ۶۴۴ تنظیم می‌کند؛ پوشهٔ خصوصی ۷۰۰ و فایل اطلاعات ورود و compose.env به حالت ۶۰۰ باقی می‌مانند. این تغییر، فایل‌ها را در اینترنت منتشر نمی‌کند؛ مسیر پوشهٔ خصوصی را عمومی نکنید.

روی نصب موجود، init را دوباره اجرا نکنید. از ریشهٔ بسته:

```bash
sudo chmod 700 .device-private/vps
sudo chmod 644 .device-private/vps/admin-bootstrap.json \
  .device-private/vps/fcm-service-account.json \
  .device-private/vps/fcm-public.json
sudo chmod 600 .device-private/vps/compose.env .device-private/vps/owner-login.txt

sudo bash vps/scripts/peygir-nginx.sh up -d --force-recreate --wait --wait-timeout 90 api
sudo bash vps/scripts/peygir-nginx.sh ps
curl --fail http://127.0.0.1:18300/health
```

این کار رمز و داده و نام volume را تغییر نمی‌دهد و به rebuild نیاز ندارد. اگر همچنان خطا دارید، خروجی `logs --tail 30 api` را بررسی کنید؛ از chmod بازگشتی یا اجرای API با root استفاده نکنید. برای کنترل خوانایی بدون چاپ محتوا:

```bash
sudo bash vps/scripts/peygir-nginx.sh run --rm --no-deps api node -e \
  "const fs=require('node:fs'); for(const k of ['ADMIN_BOOTSTRAP_FILE','FCM_SERVICE_ACCOUNT_FILE','FCM_PUBLIC_JSON_FILE']) fs.accessSync(process.env[k],fs.constants.R_OK); console.log('Secrets readable');"
```

## ۲ - آماده‌سازی گواهی HTTPS

پیش از نصب فایل، بررسی کنید زیردامنه از قبل در فایل دیگری تنظیم نشده باشد:

```bash
sudo nginx -T 2>/dev/null | grep -F 'peygir.hydrocode.ir'
```

برای نصب تازه انتظار خروجی نداریم. اگر تنظیم موجود دیدید، همان فایل باید بررسی/به‌روزرسانی شود؛ بلوک تکراری برای یک زیردامنه نسازید. دستورهای نصب زیر برای زیردامنهٔ تازه و فایل‌های جدید هستند؛ فایل سایت دیگری را تغییر ندهید. یک نسخهٔ پشتیبان از تنظیمات فعلی بگیرید:

```bash
sudo tar -C /etc -czf "/root/nginx-before-peygir-$(date +%Y%m%d-%H%M%S).tgz" nginx
sudo install -d -m 755 /var/www/letsencrypt/.well-known/acme-challenge
```

فایل HTTP اولیه، فقط چالش گواهی را پاسخ می‌دهد و ورود/اطلاعات برنامه را روی HTTP در دسترس نمی‌گذارد. این فرمان در صورت وجود فایل یا لینک هم‌نام، آن را بازنویسی نمی‌کند:

```bash
if sudo test -e /etc/nginx/sites-available/peygir.hydrocode.ir.conf || \
   sudo test -L /etc/nginx/sites-available/peygir.hydrocode.ir.conf || \
   sudo test -e /etc/nginx/sites-enabled/peygir.hydrocode.ir.conf || \
   sudo test -L /etc/nginx/sites-enabled/peygir.hydrocode.ir.conf; then
  echo 'Peygir configuration already exists; inspect it before continuing.'
else
  sudo install -m 644 vps/nginx/peygir.hydrocode.ir.http.conf /etc/nginx/sites-available/peygir.hydrocode.ir.conf && \
  sudo ln -s /etc/nginx/sites-available/peygir.hydrocode.ir.conf /etc/nginx/sites-enabled/peygir.hydrocode.ir.conf && \
  sudo nginx -t && sudo systemctl reload nginx
fi
```

در صورت خطای `nginx -t` ادامه ندهید؛ قبل از reload باید پیکربندی معتبر باشد. Nginx موجود متوقف یا restart نمی‌شود.

وجود Certbot را بررسی کنید:

```bash
certbot --version
```

اگر نصب نیست، روی Ubuntu:

```bash
sudo apt update
sudo apt install certbot
```

سپس با روش webroot فقط گواهی همین دامنه را بگیرید؛ روش standalone که به اشغال پورت ۸۰ نیاز دارد، استفاده نمی‌شود:

```bash
sudo certbot certonly --webroot -w /var/www/letsencrypt \
  -d peygir.hydrocode.ir --cert-name peygir.hydrocode.ir
```

ایمیل و شرایط سرویس را در همان ترمینال وارد/تأیید کنید. DNS و دسترسی اینترنتی پورت ۸۰ باید درست باشند. گرفتن گواهی بدون خطا، شرط ادامه است. گواهی و کلید خصوصی روی سرور می‌مانند و وارد اپ نمی‌شوند.

## ۳ - فعال‌سازی HTTPS

```bash
sudo cp -a /etc/nginx/sites-available/peygir.hydrocode.ir.conf \
  /etc/nginx/sites-available/peygir.hydrocode.ir.http-backup
sudo install -m 644 vps/nginx/peygir.hydrocode.ir.conf /etc/nginx/sites-available/peygir.hydrocode.ir.conf
sudo nginx -t && sudo systemctl reload nginx
```

HTTPS به API داخلی هدایت می‌شود، HTTP به HTTPS می‌رود و مسیر چالش گواهی برای تمدید باز می‌ماند. Nginx هدر IP ارسالی کاربر را بازنویسی می‌کند تا محدودسازی تلاش ورود با هدر جعلی دور زده نشود. پورت ۱۸۳۰۰ را عمومی نکنید؛ API به پراکسی داخلی اعتماد دارد. گواهی معتبر برای ورود اندروید ضروری است؛ از نادیده‌گرفتن خطای TLS استفاده نکنید.

برای بارگذاری گواهی تازه پس از تمدید، hook اختصاصی را نصب و تمدید آزمایشی را اجرا کنید:

```bash
sudo install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
sudo install -m 755 vps/nginx/reload-peygir-certificate.sh \
  /etc/letsencrypt/renewal-hooks/deploy/reload-peygir-nginx
sudo certbot renew --cert-name peygir.hydrocode.ir --dry-run
systemctl list-timers --all | grep -E 'certbot|snap.certbot'
```

خروجی آخر باید زمان‌بندی تمدید موجود را نشان دهد؛ در نصب‌های غیرمعمول، cron یا timer روش نصب Certbot را بررسی کنید. hook فقط برای گواهی همین دامنه، پس از `nginx -t`، reload می‌کند.

## ۴ - بررسی و ورود

از کامپیوتر خودتان یا سرور، بدون `-k`:

```bash
curl --fail https://peygir.hydrocode.ir/health
```

در PowerShell ویندوز از `curl.exe --fail https://peygir.hydrocode.ir/health` استفاده کنید. سپس:

- پنل مدیر سامانه: `https://peygir.hydrocode.ir/admin/`
- نشانی سرویس در اپ: `https://peygir.hydrocode.ir` بدون `/admin` یا پورت.
- مدیر سامانه از پنل شرکت و مدیر اولیه را می‌سازد؛ کاربران با کد شرکت و نام کاربری و رمز همان شرکت وارد اپ می‌شوند. حساب `owner` مربوط به پنل است، حساب ورود گوشی نیست.

برای اعلان فوری، فایل‌های خصوصی Firebase را مطابق [راهنمای اصلی](README-fa.md#اعلان-فوری-با-firebase) تنظیم کنید. فایل‌های `{}` اولیه فقط اجازهٔ اجرای سرویس بدون اعلان فوری را می‌دهند. بازسازی API پس از تنظیم فایل‌ها:

```bash
sudo bash vps/scripts/peygir-nginx.sh up -d --force-recreate api
```

## نگهداری در حالت Nginx

همهٔ زیر از ریشهٔ بسته اجرا می‌شوند؛ Nginx میزبان بخشی از Compose پیگیر نیست:

```bash
sudo bash vps/scripts/peygir-nginx.sh logs --tail 100 api
sudo bash vps/scripts/peygir-nginx.sh stop api
sudo bash vps/scripts/peygir-nginx.sh run --rm --no-deps api node vps/scripts/backup.mjs
sudo bash vps/scripts/peygir-nginx.sh start api
```

برای import یا restore نیز فرمان‌های راهنمای اصلی را با همین میان‌بر اجرا کنید. `down` فقط پروژهٔ پیگیر را پایین می‌آورد؛ از `down -v` برای دادهٔ واقعی استفاده نکنید. پس از ارتقای فایل‌ها، `sudo bash vps/scripts/peygir-nginx.sh up -d --build api` اجرا می‌شود. پورت یا دامنه را بدون هماهنگ‌کردن Compose، فایل Nginx، گواهی و `PUBLIC_ORIGIN` تغییر ندهید.

منابع: [پراکسی Nginx](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)، [Certbot webroot](https://eff-certbot.readthedocs.io/en/stable/using.html#webroot)، [انتشار پورت Docker](https://docs.docker.com/engine/network/port-publishing/).
