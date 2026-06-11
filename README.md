# Made By MACIT


# ZeroPad

A prototype that turns an Android phone into a virtual Xbox 360 controller for a Windows PC.

In this version:

* Wi-Fi Mode: Android sends UDP JSON, and the PC receiver converts it into a virtual Xbox 360 controller.
* USB Mode: Android sends TCP JSON to `127.0.0.1:54546` using ADB reverse.
* Bluetooth Mode: Android sends data to a paired Windows PC using Bluetooth Classic SPP. On the Windows side, an Incoming COM Port is used.
* Test Mode: Shows button and joystick JSON events on the phone without requiring a PC connection.

There are no macros, automation, or auto-play features. ZeroPad only transmits the buttons and joystick movements pressed live by the user.

## Folder structure

```text
zeropad/
  android/                 Android app
  pc_receiver/             Windows Python receiver
  docs/                    Protocol and debug notes
```

## PC setup for Xbox controller support

For ZeroPad to appear as an Xbox 360 controller on Windows, the ViGEmBus driver is required.

1. Install ViGEmBus:

   * https://docs.nefarius.at/projects/ViGEm/How-to-Install/
   * GitHub release: https://github.com/nefarius/ViGEmBus/releases

2. Set up the Python virtual environment:

```powershell
cd C:\Users\lenovo\OneDrive\Belgeler\zeropad
.\pc_receiver\setup_pc_env.bat
%USERPROFILE%\.zeropad-venv311\Scripts\python.exe .\pc_receiver\zeropad_receiver.py
```

The default PC backend is now `xbox` mode. The game should detect a virtual Xbox 360 gamepad on Windows.

## Windows desktop app

A `ZeroPad Receiver` shortcut has been created on the desktop. You can open the PC app directly from this shortcut.

If you need to recreate the shortcut:

```text
C:\Users\lenovo\OneDrive\Belgeler\zeropad\pc_receiver\create_desktop_shortcut.bat
```

To use a more convenient control panel instead of the console, run this manually:

```text
C:\Users\lenovo\OneDrive\Belgeler\zeropad\pc_receiver\start_gui.bat
```

From this panel:

* Xbox / keyboard / test backend can be selected.
* Wi-Fi UDP and USB TCP ports can be configured.
* The `Prepare USB (ADB reverse)` button prepares USB mode.
* Bluetooth COM ports can be found using `Scan`.
* If there is an Xbox driver issue, the `Repair Xbox driver` button starts an administrator-level ViGEmBus repair.
* Input status, connection status, and the latest logs are shown on the same screen.

To keep the project folder clean, the PC app uses the Python environment at `%USERPROFILE%\.zeropad-venv311`.

To enable the old keyboard mode:

```powershell
python .\pc_receiver\zeropad_receiver.py --backend keyboard
```

For debug/log only:

```powershell
python .\pc_receiver\zeropad_receiver.py --backend dry-run --debug --input-log
```

## Android APK

Easy method:

```text
C:\Users\lenovo\OneDrive\Belgeler\zeropad\build_apk.bat
```

When this file is run, the APK is copied here:

```text
C:\Users\lenovo\OneDrive\Belgeler\zeropad\dist\ZeroPad-debug.apk
```

Gradle’s actual build folder has been moved outside OneDrive to reduce file-locking issues:

```text
C:\Users\lenovo\.zeropad-build\app\outputs\apk\debug\app-debug.apk
```

To build the APK again:

```powershell
cd C:\Users\lenovo\OneDrive\Belgeler\zeropad\android
.\gradlew.bat --stop
Remove-Item .\app\build -Recurse -Force
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon assembleDebug
```

If OneDrive still locks the final APK copy, moving the project to a folder outside OneDrive, such as `C:\dev\zeropad`, is healthier.

## Wi-Fi Mode

1. Make sure the phone and PC are on the same Wi-Fi network.

2. Start the PC receiver:

```powershell
python .\pc_receiver\zeropad_receiver.py
```

3. If Windows Firewall asks for permission for Python, allow it.

4. Find the PC IP address:

```powershell
ipconfig
```

5. Enter this address in the PC IP field in the Android app.

6. Select `Wi-Fi Mode`.

The default Wi-Fi UDP port is `54545`.

## USB Mode

For USB mode:

```powershell
.\pc_receiver\start_usb_receiver.bat
```

Select `USB Mode` in the Android app.

This script first shows the `adb devices` status, then sets up `adb reverse tcp:54546 tcp:54546` and starts the PC receiver.

If `adb devices` is empty or says `no devices/emulators found`, USB mode will not work. Developer options and USB debugging must be enabled on the phone. The USB debugging/RSA permission shown on the phone must be approved. The cable must be a data cable. On Samsung phones, the Samsung USB Driver may need to be installed.

## Bluetooth Mode

Bluetooth mode works on Windows using an Incoming COM Port.

1. Pair the phone with Windows over Bluetooth.

2. Create an Incoming COM Port in Windows Bluetooth settings:

   * Bluetooth Settings
   * More Bluetooth settings
   * COM Ports
   * Add
   * Incoming

3. Easy method:

```powershell
.\pc_receiver\start_bluetooth_receiver.bat
```

This script tries to automatically find the Windows incoming Bluetooth COM port.

Manual method:

```powershell
python .\pc_receiver\zeropad_receiver.py --list-serial
python .\pc_receiver\zeropad_receiver.py --bluetooth-serial COM6
```

On this computer, the incoming port is most likely `COM6`, and the outgoing port is `COM5`. This may be different on another computer.

5. In the Android app, select the PC from the paired device buttons in the Bluetooth field, or enter the PC Bluetooth name/MAC address.

6. Select `Bluetooth Mode`.

Bluetooth may not be as stable or low-latency as Wi-Fi. For the lowest latency, Wi-Fi UDP or properly working USB should be targeted.

## Xbox mappings

| ZeroPad        | Xbox 360      |
| -------------- | ------------- |
| A              | A             |
| B              | B             |
| X              | X             |
| Y              | Y             |
| LB             | Left bumper   |
| RB             | Right bumper  |
| LT             | Left trigger  |
| RT             | Right trigger |
| View           | Back          |
| Menu           | Start         |
| Left joystick  | Left stick    |
| Right joystick | Right stick   |
| D-pad          | D-pad         |

The keyboard backend mappings remain the same as in the old prototype:

| ZeroPad             | PC keyboard |
| ------------------- | ----------- |
| Left joystick up    | W           |
| Left joystick down  | S           |
| Left joystick left  | A           |
| Left joystick right | D           |
| A                   | Space       |
| B                   | Ctrl        |
| X                   | E           |
| Y                   | R           |
| Start/Menu          | Esc         |

## Latency notes

* On the PC side, each input is not printed to the console by default. This reduces logging cost.
* On the Android side, UDP/TCP/Bluetooth transmissions go through a single high-priority send queue.
* Joystick JSON is only sent when the value changes meaningfully.
* Ping is measured every 500 ms.

## Logs

PC:

```text
pc_receiver/zeropad_receiver.log
```

Android:

* The latest command and ping are shown on the screen.
* Logcat tag: `ZeroPad`

## Connection diagnosis

If USB/Bluetooth does not work:

```powershell
.\pc_receiver\diagnose_connections.bat
```
