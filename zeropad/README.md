# ZeroPad

Android telefonu Windows PC icin sanal Xbox 360 kontrolcusune ceviren prototip.

Bu surumde:

- Wi-Fi Mode: Android UDP JSON yollar, PC alicisi bunu sanal Xbox 360 kontrolcusune cevirir.
- USB Mode: Android ADB reverse ile `127.0.0.1:54546` TCP JSON yollar.
- Bluetooth Mode: Android Bluetooth Classic SPP ile eslesmis Windows PC'ye veri yollar. Windows tarafinda Incoming COM Port kullanilir.
- Test Mode: PC baglantisi olmadan tus/joystick JSON olaylarini telefonda gosterir.

Makro, otomasyon veya auto-play yoktur. ZeroPad sadece kullanicinin canli bastigi tus ve joystick hareketlerini iletir.

## Klasor yapisi

```text
zeropad/
  android/                 Android uygulamasi
  pc_receiver/             Windows Python alici
  docs/                    Protokol ve debug notlari
```

## Xbox kontrolcu icin PC kurulumu

ZeroPad'in Windows'ta Xbox 360 kontrolcu gibi gorunmesi icin ViGEmBus surucusu gerekir.

1. ViGEmBus'u kur:
   - https://docs.nefarius.at/projects/ViGEm/How-to-Install/
   - GitHub release: https://github.com/nefarius/ViGEmBus/releases
2. Python sanal ortamini kur:

```powershell
cd C:\Users\lenovo\OneDrive\Belgeler\zeropad
.\pc_receiver\setup_pc_env.bat
%USERPROFILE%\.zeropad-venv311\Scripts\python.exe .\pc_receiver\zeropad_receiver.py
```

Varsayilan PC backend artik `xbox` modudur. Oyun Windows tarafinda sanal Xbox 360 gamepad gormelidir.

## Windows masaustu uygulamasi

Masaustune `ZeroPad Receiver` kisayolu olusturuldu. Kisayoldan direkt PC uygulamasini acabilirsin.

Kisayolu yeniden olusturmak gerekirse:

```text
C:\Users\lenovo\OneDrive\Belgeler\zeropad\pc_receiver\create_desktop_shortcut.bat
```

Konsol yerine daha rahat kontrol paneli kullanmak icin manuel yol:

```text
C:\Users\lenovo\OneDrive\Belgeler\zeropad\pc_receiver\start_gui.bat
```

Bu panelden:

- Xbox / klavye / test backend secilir.
- Wi-Fi UDP ve USB TCP portlari ayarlanir.
- `USB hazirla (ADB reverse)` dugmesi USB modunu hazirlar.
- Bluetooth COM portlari `Tara` ile bulunur.
- Xbox driver sorunu olursa `Xbox driver onar` dugmesi yonetici izinli ViGEmBus onarimi baslatir.
- Alim, baglanti durumu ve son loglar ayni ekranda gorunur.

PC uygulamasi proje klasorunu temiz tutmak icin `%USERPROFILE%\.zeropad-venv311` Python ortamini kullanir.

Eski klavye modunu acmak:

```powershell
python .\pc_receiver\zeropad_receiver.py --backend keyboard
```

Sadece debug/log:

```powershell
python .\pc_receiver\zeropad_receiver.py --backend dry-run --debug --input-log
```

## Android APK

Kolay yol:

```text
C:\Users\lenovo\OneDrive\Belgeler\zeropad\build_apk.bat
```

Bu dosyayi calistirinca APK buraya kopyalanir:

```text
C:\Users\lenovo\OneDrive\Belgeler\zeropad\dist\ZeroPad-debug.apk
```

Gradle'in asil build klasoru OneDrive kilit sorununu azaltmak icin disari tasindi:

```text
C:\Users\lenovo\.zeropad-build\app\outputs\apk\debug\app-debug.apk
```

Tekrar APK almak:

```powershell
cd C:\Users\lenovo\OneDrive\Belgeler\zeropad\android
.\gradlew.bat --stop
Remove-Item .\app\build -Recurse -Force
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon assembleDebug
```

OneDrive yine de final APK kopyasini kilitlerse projeyi `C:\dev\zeropad` gibi OneDrive disi bir klasore tasimak daha sagliklidir.

## Wi-Fi Mode

1. Telefon ve PC ayni Wi-Fi aginda olsun.
2. PC alicisini ac:

```powershell
python .\pc_receiver\zeropad_receiver.py
```

3. Windows Firewall Python icin izin isterse izin ver.
4. PC IP adresini bul:

```powershell
ipconfig
```

5. Android uygulamasinda PC IP alanina bu adresi yaz.
6. `Wi-Fi Mode` sec.

Wi-Fi UDP port varsayilani `54545`.

## USB Mode

USB modu icin:

```powershell
.\pc_receiver\start_usb_receiver.bat
```

Android uygulamasinda `USB Mode` sec.

Bu script once `adb devices` durumunu gosterir, sonra `adb reverse tcp:54546 tcp:54546` kurar ve PC alicisini baslatir.

`adb devices` bos cikarsa veya `no devices/emulators found` derse USB mode calismaz. Telefonda Developer options ve USB debugging acik olmali, telefonda cikan USB debugging/RSA izni onaylanmali, kablo veri kablosu olmali. Samsung telefonlarda gerekirse Samsung USB Driver kurulmalidir.

## Bluetooth Mode

Bluetooth modu Windows'ta Incoming COM Port ile calisir.

1. Telefonu Windows ile Bluetooth uzerinden eslestir.
2. Windows'ta Bluetooth ayarlarinda Incoming COM Port olustur:
   - Bluetooth Settings
   - More Bluetooth settings
   - COM Ports
   - Add
   - Incoming
3. Kolay yol:

```powershell
.\pc_receiver\start_bluetooth_receiver.bat
```

Bu script Windows incoming Bluetooth COM portunu otomatik bulmaya calisir.

Manuel yol:

```powershell
python .\pc_receiver\zeropad_receiver.py --list-serial
python .\pc_receiver\zeropad_receiver.py --bluetooth-serial COM6
```

Bu bilgisayarda incoming port buyuk ihtimalle `COM6`, outgoing port `COM5`. Baska bilgisayarda degisebilir.

5. Android uygulamasinda Bluetooth alaninda eslesmis cihaz butonlarindan PC'yi sec veya PC Bluetooth adini/MAC adresini yaz.
6. `Bluetooth Mode` sec.

Bluetooth, Wi-Fi kadar stabil dusuk gecikmeli olmayabilir. En dusuk gecikme icin Wi-Fi UDP veya duzgun calisan USB hedeflenir.

## Xbox eslesmeleri

| ZeroPad | Xbox 360 |
| --- | --- |
| A | A |
| B | B |
| X | X |
| Y | Y |
| LB | Left bumper |
| RB | Right bumper |
| LT | Left trigger |
| RT | Right trigger |
| View | Back |
| Menu | Start |
| Sol joystick | Left stick |
| Sag joystick | Right stick |
| D-pad | D-pad |

Klavye backend eslesmeleri eski prototipteki gibi kalir:

| ZeroPad | PC klavye |
| --- | --- |
| Sol joystick yukari | W |
| Sol joystick asagi | S |
| Sol joystick sol | A |
| Sol joystick sag | D |
| A | Space |
| B | Ctrl |
| X | E |
| Y | R |
| Start/Menu | Esc |

## Gecikme notlari

- PC tarafinda varsayilan olarak her input konsola yazilmaz; bu log maliyetini azaltir.
- Android tarafinda UDP/TCP/Bluetooth gonderimleri tek, yuksek oncelikli gonderim kuyruğundan akar.
- Joystick JSON'u sadece deger anlamli degisince gider.
- Ping 500 ms aralikla olculur.

## Loglar

PC:

```text
pc_receiver/zeropad_receiver.log
```

Android:

- Ekranda son komut ve ping gorunur.
- Logcat etiketi: `ZeroPad`

## Baglanti teshisi

USB/Bluetooth calismazsa:

```powershell
.\pc_receiver\diagnose_connections.bat
```
