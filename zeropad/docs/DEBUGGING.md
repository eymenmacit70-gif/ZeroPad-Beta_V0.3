# ZeroPad debug notlari

## PC alicisi loglari

PC tarafinda loglar hem konsola hem de bu dosyaya yazilir:

```text
pc_receiver/zeropad_receiver.log
```

Ayrintili log:

```powershell
python .\pc_receiver\zeropad_receiver.py --debug --input-log
```

Klavye/gamepad input'u basmadan sadece gelen JSON'u gormek:

```powershell
python .\pc_receiver\zeropad_receiver.py --backend dry-run --debug --input-log
```

## Xbox backend calismiyorsa

Belirti:

```text
Could not create virtual Xbox controller
```

Kontrol listesi:

1. ViGEmBus kurulu mu?
2. PC yeniden baslatildi mi?
3. Sanal ortamda `vgamepad` kurulu mu?

```powershell
.\.venv\Scripts\Activate.ps1
pip install -r .\pc_receiver\requirements.txt
```

Gecici olarak eski klavye moduna donmek:

```powershell
python .\pc_receiver\zeropad_receiver.py --backend keyboard
```

## Wi-Fi baglanti kontrolu

1. Telefon ve PC ayni agda mi?
2. Android uygulamasindaki PC IP dogru mu?
3. Windows Firewall Python UDP portuna izin veriyor mu?
4. PC alicisinda `Wi-Fi UDP server listening on 0.0.0.0:54545` logu var mi?
5. Android Logcat'te `ZeroPad` etiketiyle send error gorunuyor mu?

## USB baglanti kontrolu

ADB reverse aktif mi?

```powershell
.\pc_receiver\start_usb_receiver.bat
```

PC alicisinda su log gorunmeli:

```text
USB TCP server listening on 127.0.0.1:54546
USB client connected
```

Android ekraninda `USB connected` yazmiyorsa:

- USB debugging iznini tekrar onayla.
- `adb reverse tcp:54546 tcp:54546` komutunu tekrar calistir.
- PC alicisinin acik oldugunu kontrol et.
- `adb devices` bos cikarsa Windows telefonu ADB cihazi olarak gormuyor demektir.
- Samsung A serisi telefonlarda gerekirse Samsung USB Driver kur.
- Telefon USB modunu `File transfer` / `Transferring files` yap.

## Bluetooth baglanti kontrolu

Bluetooth modu Windows Incoming COM Port bekler.

COM portlari listele:

```powershell
python .\pc_receiver\zeropad_receiver.py --list-serial
```

Bluetooth ile baslat:

```powershell
.\pc_receiver\start_bluetooth_receiver.bat
```

Kontrol listesi:

1. Telefon ve PC Bluetooth uzerinden eslesmis mi?
2. Windows'ta Incoming COM Port olusturuldu mu?
3. Bu bilgisayarda incoming port buyuk ihtimalle `COM6`, outgoing port `COM5`.
4. Android uygulamasinda Bluetooth izni verildi mi?
5. Android Bluetooth alaninda eslesmis PC butonu secildi mi?

Tum baglantilari tek ekranda kontrol etmek:

```powershell
.\pc_receiver\diagnose_connections.bat
```

## Tus basili kalirsa

PC alicisi baglanti sessiz kalirsa varsayilan olarak 2.5 saniye sonra basili kalan input'u birakir.

Sureyi degistirmek:

```powershell
python .\pc_receiver\zeropad_receiver.py --release-timeout 1.0
```
