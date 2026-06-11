# ZeroPad JSON protocol

ZeroPad olaylari UTF-8 JSON olarak gonderilir.

Wi-Fi modunda her olay tek bir UDP paketidir. USB modunda her olay tek satir JSON olarak TCP stream icinden gonderilir. Bluetooth modunda ayni satir bazli JSON, Windows Incoming COM Port uzerinden gider.

## Button

```json
{
  "type": "button",
  "name": "A",
  "state": "down",
  "time": 123456789
}
```

`state` degerleri:

- `down`: tus basildi
- `up`: tus birakildi

## Joystick

```json
{
  "type": "joystick",
  "name": "left",
  "x": 0.0,
  "y": -1.0,
  "time": 123456789
}
```

`name` degerleri:

- `left`: Xbox left stick
- `right`: Xbox right stick

Koordinatlar:

- `x`: `-1.0` sol, `1.0` sag
- `y`: `-1.0` yukari, `1.0` asagi

Android uygulamasi joystick olaylarini yalnizca deger anlamli sekilde degisince gonderir.

## Xbox button names

Button olaylarinda desteklenen adlar:

- `A`
- `B`
- `X`
- `Y`
- `L1` / `LB`
- `R1` / `RB`
- `LT`
- `RT`
- `Start`
- `Select`
- `DPAD_UP`
- `DPAD_DOWN`
- `DPAD_LEFT`
- `DPAD_RIGHT`

## Ping

Android:

```json
{
  "type": "ping",
  "time": 123456789
}
```

PC:

```json
{
  "type": "pong",
  "time": 123456789,
  "serverTime": 1710000000000
}
```

Android ping gostergesi `pong.time` ile kendi monotonic saatini karsilastirarak RTT hesaplar.
