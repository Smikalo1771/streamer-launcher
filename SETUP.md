# Google TV Streamer 4K: debloat, vlastný launcher a casting

Postup, podľa ktorého sa dá celé nastavenie zopakovať na ďalšom Google TV Streamer 4K (kódové meno `kirkwood`).
Overené na Androide 14 (build `UTTK.260317.003`), pripojenom cez Ethernet.

Výsledok:

- Domovská obrazovka je tento launcher (`com.filip.tvhome`). Pôvodný launcher Google TV (`launcherx`) a sprievodca
  nastavením (`setupwraith`) sú vypnuté, nie odinštalované.
- Telemetria, Privacy Sandbox a nepotrebné predinštalované appky sú vypnuté (`tv/debloat.txt`).
- Tlačidlá na ovládači: YouTube otvára YouTube, Netflix otvára IPTV, hviezdička otvára Nuvio.
- Casting z telefónu aj z Chrome funguje. Udržiava ho launcher (`TvGuard`), ktorý cez Shizuku spúšťa
  `tv/tvguard.sh` hneď po štarte a potom každých 10 minút.
- Šetrič obrazovky naskočí po 2 minútach. Túto hodnotu drží ten istý skript.
- Podržanie ikony appky ponúka „Stop app“, ktorá appku cez Shizuku hneď ukončí.

> Všetko sa robí cez ADB. Balíky sa iba vypínajú (`pm disable-user`), takže sa každý krok dá vrátiť (časť 10).
> Pred krokom 6 si prečítaj záchranné príkazy v časti 10.

## 1. Čo treba

Na PC (Linux):

- `adb` (Android platform-tools)
- JDK 17. Novšie JDK zostavenie zhodí (`Failed to transform core-for-system-modules.jar`). Gradle si JDK 17
  stiahne do `~/.gradle/jdks/`, skripty ho hľadajú v `~/.gradle/jdks/eclipse_adoptium-17-amd64-linux.2`.
- Android SDK v `~/Android/Sdk`, alebo cestu k nemu v `ANDROID_HOME`
- tento repozitár

Na TV:

- Google TV Streamer 4K pripojený do rovnakej siete ako PC
- APK na nahratie: [Shizuku, fork thedjchi](https://github.com/thedjchi/Shizuku/releases) (overená verzia
  `13.7.0-thedjchi`). MacroDroid už netreba (časť 8).

Na Windowse to ide v Git Bash: `JAVA_HOME` a `ANDROID_HOME` nastav na JDK 17 a SDK, a pred príkazmi s cestami na
TV (`/data/local/tmp/...`) nastav `export MSYS_NO_PATHCONV=1`, inak ich Git Bash prepíše na cesty Windowsu.

V príkazoch nižšie nahraď IP adresu tou svojou:

```sh
export TV=192.168.100.39:5555
```

## 2. ADB na TV

1. Zapni Možnosti pre vývojárov: **Nastavenia > Systém > Informácie**, sedemkrát stlač **Zostava OS Android TV**.
2. V **Nastavenia > Systém > Možnosti pre vývojárov** zapni **Ladenie USB**.
3. Otvor ADB na porte 5555. Bezdrôtové ladenie (párovanie kódom) funguje len cez Wi-Fi. Na Ethernete ho Android
   odmietne (`Not connected to any wireless network. Not enabling adbwifi.`). Preto TV na tento krok dočasne
   pripoj cez Wi-Fi:
   1. V Možnostiach pre vývojárov zapni **Bezdrôtové ladenie** a otvor **Spárovať zariadenie pomocou kódu**.
   2. Na PC spusti `adb pair <ip>:<port na párovanie> <kód>` a potom `adb connect <ip>:<port z obrazovky ladenia>`.
   3. Spusti `adb tcpip 5555`.
   4. Prepni TV späť na Ethernet a pripoj sa: `adb connect $TV`. Na TV potvrď „Povoliť ladenie“ a zaškrtni
      „Vždy povoliť z tohto počítača“.

Port 5555 pustí len počítač, ktorého kľúč si na TV potvrdil (`ro.adb.secure=1`). Prenos cez neho však nie je
šifrovaný. Cudzí počítač v sieti vyvolá na TV rovnaké okno s otázkou, to nepotvrdzuj. Po reštarte port 5555 znova
otvára Shizuku (krok 7). Bez neho by ADB po reštarte nebolo dostupné.

## 3. Záloha pôvodného stavu

```sh
mkdir -p backup && A="adb -s $TV shell"
$A pm list packages -f            > backup/packages.txt
$A pm list packages -d            > backup/disabled.txt
$A settings list global           > backup/settings-global.txt
$A settings list secure           > backup/settings-secure.txt
$A settings list system           > backup/settings-system.txt
$A getprop                        > backup/getprop.txt
$A cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 > backup/home-before.txt
```

Na pôvodnom kuse boli z výroby vypnuté len `tvrecommendations` a `cast2class`. Domovská aktivita bola
`com.google.android.apps.tv.launcherx/.home.HomeActivity`.

## 4. Debloat, bezpečná dávka

Vypne všetky nezakomentované balíky z `tv/debloat.txt`: telemetriu, Privacy Sandbox, Play Games, Google TV
(Videos) a TalkBack. Po každej skupine skontroluj, že sa dá vrátiť na domovskú obrazovku.

```sh
grep -v '^\s*#' tv/debloat.txt | awk 'NF {print $1}' | while read -r p; do
  echo "$p: $(adb -s $TV shell pm disable-user --user 0 "$p")"
done
```

Riadky pre `setupwraith` a `launcherx` sú v súbore zakomentované schválne. Tie prídu na rad až v kroku 6.

## 5. Launcher

1. V `gradle.properties` nastav `launcherApplicationId` (u nás `com.filip.tvhome`).
2. V `app/src/main/res/values/config.xml` uprav `app_keys`, teda tlačidlá na ovládači:

   | kód | tlačidlo | appka |
   |---|---|---|
   | 190 | YouTube | `com.google.android.youtube.tv` |
   | 191 | Netflix | `com.nuvio.tv` |
   | 313 | hviezdička (`MACRO_1`) | `com.example.iptvplayer` |

3. Zostav a nainštaluj. Skript zostaví release APK, nainštaluje ho, udelí povolenia a zapne `HomeKeyService`
   (pomocnú službu pre tlačidlá):

   ```sh
   TV=$TV ./install.sh
   ```

4. Nastav launcher ako domovskú obrazovku:

   ```sh
   adb -s $TV shell cmd package set-home-activity --user 0 com.filip.tvhome/com.gathin.tvhome.MainActivity
   ```

`HomeKeyService` musí zostať zapnutý, inak tlačidlá YouTube, Netflix a hviezdička nebudú robiť nič. Android ho
vypne pri každom vynútenom zastavení launchera (`am force-stop`, Nastavenia > Aplikácie). `install.sh` preto
launcheru udelí `WRITE_SECURE_SETTINGS` a `restore_key_services` v `config.xml` je zapnuté: pri ďalšom štarte
Home si launcher službu zapne sám. Over to:
`adb -s $TV shell settings get secure enabled_accessibility_services` vráti
`com.filip.tvhome/com.gathin.tvhome.HomeKeyService`.

## 6. Vypnutie pôvodného launchera

Poradie je dôležité. Najprv `setupwraith`, potom `launcherx`. Ak je `launcherx` vypnutý a `setupwraith`
zapnutý, `RecoveryActivity` zo `setupwraith` prevezme Home, v logu hlási „Reenabling disabled home activity“ a
systém môže padať v slučke. `setupwraith` sa nedá vypnúť len čiastočne
(`SecurityException: Shell cannot change component state`), preto ide preč celý.

```sh
A="adb -s $TV shell"
$A pm disable-user --user 0 com.google.android.tungsten.setupwraith
$A pm disable-user --user 0 com.google.android.apps.tv.launcherx
$A cmd package set-home-activity --user 0 com.filip.tvhome/com.gathin.tvhome.MainActivity
sleep 30
adb -s $TV reboot
```

`sleep 30` nevynechávaj. Android zapisuje stav balíkov na disk s oneskorením a pri okamžitom `reboot` sa vypnutie
stratí: po štarte sú `launcherx` aj `setupwraith` znova zapnuté.

Po reštarte over:

```sh
adb connect $TV
adb -s $TV shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
# com.filip.tvhome/com.gathin.tvhome.MainActivity
adb -s $TV shell pm list packages -d | grep -E 'launcherx|setupwraith'   # oba riadky
adb -s $TV shell logcat -d -b crash | grep -c 'FATAL EXCEPTION IN SYSTEM PROCESS'   # 0
```

Vedľajší účinok: prijímač castu (`com.google.android.apps.mediashell`) si overovacie kľúče načíta len pri
štarte a len so zapnutým `setupwraith`. Po každom reštarte TV a po každom reštarte prijímača ich stratí.
Chrome potom TV odmietne. Telefón ju niekedy ešte vidí. Riešia to kroky 7 a 8.

## 7. Shizuku

Shizuku spúšťa príkazy ako používateľ `shell` bez pripojeného PC. Používa ho launcher na `tv/tvguard.sh` a na
voľbu „Stop app“.

1. Nainštaluj fork thedjchi: `adb -s $TV install shizuku-thedjchi.apk`.
2. Udeľ mu povolenie, ktoré potrebuje na štart po reštarte:
   `adb -s $TV shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS`
3. Prvýkrát ho spusti cez ADB:
   ```sh
   adb -s $TV shell 'p=$(pm path moe.shizuku.privileged.api | head -1 | sed "s/^package://; s#/base.apk##"); $p/lib/arm/libshizuku.so'
   ```
4. V nastaveniach Shizuku zapni **štart po zapnutí zariadenia** a **TCP režim** (port 5555). Vďaka TCP režimu
   Shizuku nabehne aj na Ethernete, a pritom nechá otvorený port 5555 pre ADB.

Over po reštarte: `adb -s $TV shell ps -A | grep shizuku_server`. Shizuku nabieha asi 50 s po štarte TV.

## 8. Casting a šetrič: tvguard.sh

```sh
adb -s $TV push tv/tvguard.sh tv/castwatch.sh /data/local/tmp/
adb -s $TV shell chmod 755 /data/local/tmp/tvguard.sh /data/local/tmp/castwatch.sh
```

Launcher (`TvGuard`, spúšťa ho `HomeKeyService` aj domovská obrazovka) zavolá cez Shizuku
`/data/local/tmp/tvguard.sh`, len čo Shizuku po štarte nabehne, a potom každých 10 minút. Skript:

- spustí `castwatch.sh`, ktorý opraví casting, keď prijímač beží bez kľúčov. Pamätá si opravený prijímač
  v `/data/local/tmp/castwatch.pid` (PID a `boot_id`). Keď sa PID alebo štart TV zmení, opravu zopakuje (trvá
  asi 11 s, casting je vtedy na chvíľu nedostupný). Po štarte TV je casting v poriadku asi o minútu;
- drží šetrič na 2 minútach (`screen_off_timeout` = `120000`). Ambient mode (`dreamx`) pri aktualizácii vracia
  10 minút.

Launcher potrebuje povolenie Shizuku. Prvýkrát o neho požiada sám, alebo pri prvom „Stop app“. Potvrď
**Allow all the time**. Stav je v appke Shizuku, v zozname autorizovaných aplikácií má byť zapnutý „Home“.

Prečo nie MacroDroid: TV štartuje s hodinami nastavenými na pevný dátum, kým nedostane čas zo siete. MacroDroid
preto každý štart považuje za opakovanie predchádzajúceho („Ignoring repeated BOOT_COMPLETED for the current
boot“) a spúšťač *Device Boot* sa nespustí. Okrem toho sa MacroDroid po reštarte TV sám prepne na „Vypnuté“.

## 9. Overenie

- Reštartuj TV. Bez dotyku musí naskočiť tento launcher, bez preblikávania pôvodného.
- Po asi 1,5 minúte: `adb -s $TV shell logcat -d | grep ClientAuthSigner` nájde
  `Created singleton instance of ClientAuthSigner` a `adb -s $TV shell cat /data/local/tmp/castwatch.pid`
  ukáže PID prijímača a aktuálny `boot_id`. Oprava prebehla.
- `adb -s $TV shell settings get system screen_off_timeout` vráti `120000`.
- Kontrola, ktorú robí Chrome pred zobrazením TV:
  ```sh
  uvx --with pychromecast python -I tv/cast-auth-check.py 192.168.100.39
  # AUTH OK: cert 956 B, sig 256 B, intermediates 1
  ```
  `cert 0 B` znamená, že prijímač nemá kľúče. Spusti `./cast-fix.sh`, alebo počkaj najviac 10 minút.
- Skutočným ovládačom vyskúšaj tlačidlá YouTube, Netflix a hviezdičku. Stlačenia cez ADB nestačia.
- Telefón aj PC musia byť v sieti, kde je TV. Hľadanie castu (mDNS) nejde medzi dvoma sieťami.

Oprava castu z PC, napríklad keď Shizuku nebeží: `TV=$TV ./cast-fix.sh`.

## 10. Záchrana a návrat

Ak po kroku 6 nefunguje Home alebo systém padá:

```sh
adb connect $TV
adb -s $TV shell pm enable com.google.android.apps.tv.launcherx
adb -s $TV shell pm enable com.google.android.tungsten.setupwraith
adb -s $TV shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
```

Ak ADB nejde, odpoj napájanie a pri zapínaní drž na ovládači **Back + Home**. TV sa spustí do recovery. Druhá
možnosť: **Nastavenia > Aplikácie > Zobraziť systémové aplikácie**, tam oba balíky zapni pôvodným ovládačom.

Úplný návrat:

```sh
grep -v '^\s*#' tv/debloat.txt | awk 'NF {print $1}' | while read -r p; do adb -s $TV shell pm enable "$p"; done
adb -s $TV shell pm enable com.google.android.tungsten.setupwraith
adb -s $TV shell pm enable com.google.android.apps.tv.launcherx
adb -s $TV shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
adb -s $TV shell settings put secure enabled_accessibility_services '""'
adb -s $TV uninstall com.filip.tvhome
adb -s $TV shell rm -f /data/local/tmp/tvguard.sh /data/local/tmp/castwatch.sh /data/local/tmp/castwatch.pid
```

## 11. Známe obmedzenia

- Casting po štarte závisí od Shizuku a od launchera. Ak Shizuku po reštarte nenabehne, Chrome TV neuvidí,
  kým nespustíš `./cast-fix.sh`.
- Port 5555 zostáva otvorený (TCP režim Shizuku). Pustí len potvrdené kľúče, no prenos je nešifrovaný.
- Bez Shizuku vie „Stop app“ na Androide 14 len otvoriť Info o aplikácii. Odtiaľ appku zastavíš tlačidlom
  „Force stop“.
