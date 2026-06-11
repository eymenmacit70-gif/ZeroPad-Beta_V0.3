#!/usr/bin/env python3
"""ZeroPad Windows receiver.

Receives user-generated Android gamepad JSON events and maps them to either:

- a virtual Xbox 360 controller through ViGEmBus + vgamepad
- keyboard keys through pynput
- logs only through dry-run

ZeroPad intentionally forwards only live user input. It does not include macro,
auto-play, replay, or background data collection features.
"""

from __future__ import annotations

import argparse
import json
import logging
import socket
import sys
import threading
import time
from pathlib import Path
from typing import Any, Callable

try:
    from pynput.keyboard import Controller, Key
except Exception:  # pragma: no cover - dependency may be absent in Xbox mode
    Controller = None
    Key = None


UDP_PORT = 54545
TCP_PORT = 54546
BLUETOOTH_BAUD = 115200
LOG_FILE = Path(__file__).with_name("zeropad_receiver.log")

BUTTON_KEY_MAP = {
    "A": "space",
    "B": "ctrl",
    "X": "e",
    "Y": "r",
    "START": "esc",
    "SELECT": "tab",
    "BACK": "tab",
    "L1": "q",
    "LB": "q",
    "R1": "f",
    "RB": "f",
}


def setup_logging(debug: bool) -> None:
    level = logging.DEBUG if debug else logging.INFO
    formatter = logging.Formatter("%(asctime)s %(levelname)s %(message)s", "%H:%M:%S")

    console = logging.StreamHandler(sys.stdout)
    console.setFormatter(formatter)
    console.setLevel(level)

    file_handler = logging.FileHandler(LOG_FILE, encoding="utf-8")
    file_handler.setFormatter(formatter)
    file_handler.setLevel(logging.DEBUG)

    logging.basicConfig(level=level, handlers=[console, file_handler])


def compact_json(data: dict[str, Any]) -> str:
    return json.dumps(data, separators=(",", ":"))


def clip_axis(value: float) -> float:
    if value < -1.0:
        return -1.0
    if value > 1.0:
        return 1.0
    return value


class InputBackend:
    name = "base"

    def button(self, button_name: str, down: bool) -> None:
        raise NotImplementedError

    def joystick(self, stick_name: str, x: float, y: float) -> None:
        raise NotImplementedError

    def release_all(self) -> None:
        raise NotImplementedError

    def has_active_input(self) -> bool:
        return self.snapshot() != "-"

    def snapshot(self) -> str:
        return "-"


class DryRunBackend(InputBackend):
    name = "dry-run"

    def __init__(self) -> None:
        self.down: set[str] = set()
        self.sticks: dict[str, tuple[float, float]] = {}
        self.lock = threading.Lock()
        logging.info("backend: dry-run, no PC input will be emitted")

    def button(self, button_name: str, down: bool) -> None:
        with self.lock:
            if down:
                self.down.add(button_name)
            else:
                self.down.discard(button_name)
        logging.info("dry button %s %s", button_name, "down" if down else "up")

    def joystick(self, stick_name: str, x: float, y: float) -> None:
        with self.lock:
            self.sticks[stick_name] = (x, y)
        logging.debug("dry joystick %s x=%.3f y=%.3f", stick_name, x, y)

    def release_all(self) -> None:
        with self.lock:
            self.down.clear()
            self.sticks.clear()

    def has_active_input(self) -> bool:
        with self.lock:
            return bool(self.down) or any(abs(x) > 0.01 or abs(y) > 0.01 for x, y in self.sticks.values())

    def snapshot(self) -> str:
        with self.lock:
            buttons = ",".join(sorted(self.down)) if self.down else "-"
            sticks = ",".join(f"{k}=({v[0]:.2f},{v[1]:.2f})" for k, v in self.sticks.items())
        return f"buttons={buttons} sticks={sticks or '-'}"


class KeyboardBackend(InputBackend):
    name = "keyboard"

    def __init__(self, joystick_threshold: float) -> None:
        self.joystick_threshold = joystick_threshold
        self.controller = None if Controller is None else Controller()
        self.down: set[str] = set()
        self.joystick_keys: dict[str, set[str]] = {}
        self.lock = threading.Lock()

        if self.controller is None:
            raise RuntimeError("pynput is not installed. Run: pip install -r pc_receiver/requirements.txt")
        logging.info("backend: keyboard through pynput")

    def button(self, button_name: str, down: bool) -> None:
        key_name = BUTTON_KEY_MAP.get(button_name)
        if key_name is None:
            logging.warning("keyboard backend ignored unknown button: %s", button_name)
            return
        if down:
            self._press(key_name)
        else:
            self._release(key_name)

    def joystick(self, stick_name: str, x: float, y: float) -> None:
        if stick_name != "left":
            return

        target: set[str] = set()
        if y <= -self.joystick_threshold:
            target.add("w")
        if y >= self.joystick_threshold:
            target.add("s")
        if x <= -self.joystick_threshold:
            target.add("a")
        if x >= self.joystick_threshold:
            target.add("d")

        previous = self.joystick_keys.get(stick_name, set())
        for key_name in sorted(target - previous):
            self._press(key_name)
        for key_name in sorted(previous - target):
            self._release(key_name)
        self.joystick_keys[stick_name] = target

    def release_all(self) -> None:
        self.joystick_keys.clear()
        with self.lock:
            keys = list(self.down)
        for key_name in keys:
            self._release(key_name)

    def has_active_input(self) -> bool:
        with self.lock:
            return bool(self.down)

    def snapshot(self) -> str:
        with self.lock:
            return ",".join(sorted(self.down)) if self.down else "-"

    def _press(self, key_name: str) -> None:
        with self.lock:
            if key_name in self.down:
                return
            self.down.add(key_name)
            self.controller.press(self._to_pynput_key(key_name))
        logging.debug("key down: %s", key_name)

    def _release(self, key_name: str) -> None:
        with self.lock:
            if key_name not in self.down:
                return
            self.down.remove(key_name)
            self.controller.release(self._to_pynput_key(key_name))
        logging.debug("key up: %s", key_name)

    def _to_pynput_key(self, key_name: str) -> Any:
        if Key is None:
            return key_name
        special = {
            "space": Key.space,
            "ctrl": Key.ctrl,
            "esc": Key.esc,
            "tab": Key.tab,
        }
        return special.get(key_name, key_name)


class XboxBackend(InputBackend):
    name = "xbox"

    def __init__(self) -> None:
        try:
            import vgamepad as vg
        except Exception as exc:
            raise RuntimeError(
                "vgamepad is not installed. Run: pip install -r pc_receiver/requirements.txt"
            ) from exc

        self.vg = vg
        try:
            self.gamepad = vg.VX360Gamepad()
        except Exception as exc:
            raise RuntimeError(
                "Could not create virtual Xbox controller. Install ViGEmBus, then restart this receiver."
            ) from exc

        self.button_map = {
            "A": vg.XUSB_BUTTON.XUSB_GAMEPAD_A,
            "B": vg.XUSB_BUTTON.XUSB_GAMEPAD_B,
            "X": vg.XUSB_BUTTON.XUSB_GAMEPAD_X,
            "Y": vg.XUSB_BUTTON.XUSB_GAMEPAD_Y,
            "START": vg.XUSB_BUTTON.XUSB_GAMEPAD_START,
            "SELECT": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
            "BACK": vg.XUSB_BUTTON.XUSB_GAMEPAD_BACK,
            "L1": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
            "LB": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
            "R1": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
            "RB": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
            "LS": vg.XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
            "RS": vg.XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
            "DPAD_UP": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
            "DPAD_DOWN": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
            "DPAD_LEFT": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
            "DPAD_RIGHT": vg.XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
        }
        self.down: set[str] = set()
        self.sticks: dict[str, tuple[float, float]] = {"left": (0.0, 0.0), "right": (0.0, 0.0)}
        self.triggers: dict[str, float] = {"LT": 0.0, "RT": 0.0}
        self.lock = threading.Lock()

        if hasattr(self.gamepad, "reset"):
            self.gamepad.reset()
        self.gamepad.update()
        logging.info("backend: virtual Xbox 360 controller through vgamepad")

    def button(self, button_name: str, down: bool) -> None:
        with self.lock:
            if button_name in {"LT", "L2"}:
                self.triggers["LT"] = 1.0 if down else 0.0
                self.gamepad.left_trigger_float(value_float=self.triggers["LT"])
                self.gamepad.update()
                return
            if button_name in {"RT", "R2"}:
                self.triggers["RT"] = 1.0 if down else 0.0
                self.gamepad.right_trigger_float(value_float=self.triggers["RT"])
                self.gamepad.update()
                return

            button = self.button_map.get(button_name)
            if button is None:
                logging.warning("xbox backend ignored unknown button: %s", button_name)
                return
            if down:
                if button_name in self.down:
                    return
                self.down.add(button_name)
                self.gamepad.press_button(button=button)
            else:
                if button_name not in self.down:
                    return
                self.down.remove(button_name)
                self.gamepad.release_button(button=button)
            self.gamepad.update()
        logging.debug("xbox button %s %s", button_name, "down" if down else "up")

    def joystick(self, stick_name: str, x: float, y: float) -> None:
        x = clip_axis(x)
        y = clip_axis(y)
        with self.lock:
            self.sticks[stick_name] = (x, y)
            xbox_y = clip_axis(-y)
            if stick_name == "left":
                self.gamepad.left_joystick_float(x_value_float=x, y_value_float=xbox_y)
            elif stick_name == "right":
                self.gamepad.right_joystick_float(x_value_float=x, y_value_float=xbox_y)
            else:
                logging.warning("xbox backend ignored unknown joystick: %s", stick_name)
                return
            self.gamepad.update()
        logging.debug("xbox joystick %s x=%.3f y=%.3f", stick_name, x, y)

    def release_all(self) -> None:
        with self.lock:
            for button_name in list(self.down):
                button = self.button_map.get(button_name)
                if button is not None:
                    self.gamepad.release_button(button=button)
            self.down.clear()
            self.triggers["LT"] = 0.0
            self.triggers["RT"] = 0.0
            self.sticks["left"] = (0.0, 0.0)
            self.sticks["right"] = (0.0, 0.0)
            self.gamepad.left_trigger_float(value_float=0.0)
            self.gamepad.right_trigger_float(value_float=0.0)
            self.gamepad.left_joystick_float(x_value_float=0.0, y_value_float=0.0)
            self.gamepad.right_joystick_float(x_value_float=0.0, y_value_float=0.0)
            self.gamepad.update()

    def has_active_input(self) -> bool:
        with self.lock:
            if self.down:
                return True
            if any(value > 0.01 for value in self.triggers.values()):
                return True
            return any(abs(x) > 0.01 or abs(y) > 0.01 for x, y in self.sticks.values())

    def snapshot(self) -> str:
        with self.lock:
            buttons = ",".join(sorted(self.down)) if self.down else "-"
            left = self.sticks.get("left", (0.0, 0.0))
            right = self.sticks.get("right", (0.0, 0.0))
            lt = self.triggers.get("LT", 0.0)
            rt = self.triggers.get("RT", 0.0)
        return f"buttons={buttons} L=({left[0]:.2f},{left[1]:.2f}) R=({right[0]:.2f},{right[1]:.2f}) LT={lt:.0f} RT={rt:.0f}"


class EventProcessor:
    def __init__(self, backend: InputBackend) -> None:
        self.backend = backend
        self.last_source = "-"
        self.last_command = "-"
        self.last_seen = 0.0
        self.lock = threading.Lock()

    def mark_seen(self, source: str, command: str) -> None:
        with self.lock:
            self.last_source = source
            self.last_command = command
            self.last_seen = time.monotonic()

    def process(self, event: dict[str, Any], source: str) -> None:
        event_type = str(event.get("type", "")).lower()
        self.mark_seen(source, compact_json(event))

        if event_type == "button":
            self._process_button(event)
            return
        if event_type == "joystick":
            self._process_joystick(event)
            return
        logging.debug("ignored event from %s: %s", source, event)

    def _process_button(self, event: dict[str, Any]) -> None:
        name = str(event.get("name", "")).upper()
        state = str(event.get("state", "")).lower()
        if state in {"down", "press", "pressed"}:
            self.backend.button(name, True)
        elif state in {"up", "release", "released"}:
            self.backend.button(name, False)
        else:
            logging.warning("unknown button state for %s: %s", name, state)

    def _process_joystick(self, event: dict[str, Any]) -> None:
        name = str(event.get("name", "left")).lower()
        try:
            x = float(event.get("x", 0.0))
            y = float(event.get("y", 0.0))
        except (TypeError, ValueError):
            logging.warning("invalid joystick payload: %s", event)
            return
        self.backend.joystick(name, x, y)

    def release_all(self) -> None:
        self.backend.release_all()

    def status(self) -> tuple[str, str, float]:
        with self.lock:
            return self.last_source, self.last_command, self.last_seen


def handle_message(
    raw: str,
    source: str,
    processor: EventProcessor,
    reply: Callable[[dict[str, Any]], None] | None = None,
    input_log: bool = False,
) -> None:
    try:
        event = json.loads(raw)
    except json.JSONDecodeError:
        logging.warning("invalid json from %s: %s", source, raw)
        return

    if not isinstance(event, dict):
        logging.warning("non-object json from %s: %s", source, raw)
        return

    event_type = str(event.get("type", "")).lower()
    if event_type == "ping":
        processor.mark_seen(source, compact_json(event))
        if reply is not None:
            reply(
                {
                    "type": "pong",
                    "time": event.get("time"),
                    "serverTime": int(time.time() * 1000),
                }
            )
        return

    if input_log:
        logging.info("rx %s %s", source, compact_json(event))
    else:
        logging.debug("rx %s %s", source, compact_json(event))
    processor.process(event, source)


def udp_server(
    bind_host: str,
    port: int,
    processor: EventProcessor,
    stop: threading.Event,
    input_log: bool,
) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((bind_host, port))
    sock.settimeout(0.1)
    logging.info("Wi-Fi UDP server listening on %s:%s", bind_host, port)

    try:
        while not stop.is_set():
            try:
                payload, address = sock.recvfrom(4096)
            except socket.timeout:
                continue
            raw = payload.decode("utf-8", errors="replace")
            source = f"udp:{address[0]}:{address[1]}"

            def reply(data: dict[str, Any], addr: tuple[str, int] = address) -> None:
                sock.sendto(compact_json(data).encode("utf-8"), addr)

            handle_message(raw, source, processor, reply, input_log=input_log)
    finally:
        sock.close()


def tcp_server(
    host: str,
    port: int,
    processor: EventProcessor,
    stop: threading.Event,
    input_log: bool,
) -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((host, port))
    server.listen(4)
    server.settimeout(0.2)
    logging.info("USB TCP server listening on %s:%s", host, port)

    try:
        while not stop.is_set():
            try:
                conn, address = server.accept()
            except socket.timeout:
                continue
            thread = threading.Thread(
                target=handle_tcp_client,
                args=(conn, address, processor, stop, input_log),
                daemon=True,
            )
            thread.start()
    finally:
        server.close()


def handle_tcp_client(
    conn: socket.socket,
    address: tuple[str, int],
    processor: EventProcessor,
    stop: threading.Event,
    input_log: bool,
) -> None:
    source = f"tcp:{address[0]}:{address[1]}"
    logging.info("USB client connected: %s", source)
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    conn.settimeout(0.1)
    buffer = ""

    try:
        def reply(data: dict[str, Any]) -> None:
            conn.sendall((compact_json(data) + "\n").encode("utf-8"))

        while not stop.is_set():
            try:
                chunk = conn.recv(4096)
            except socket.timeout:
                continue
            if not chunk:
                break
            buffer += chunk.decode("utf-8", errors="replace")
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                line = line.strip()
                if line:
                    handle_message(line, source, processor, reply, input_log=input_log)
    except Exception as exc:
        logging.warning("USB client error %s: %s", source, exc)
    finally:
        try:
            conn.close()
        except OSError:
            pass
        logging.info("USB client disconnected: %s", source)
        processor.release_all()


def serial_server(
    port_name: str,
    baud: int,
    processor: EventProcessor,
    stop: threading.Event,
    input_log: bool,
) -> None:
    try:
        import serial
    except Exception:
        logging.error("pyserial is not installed. Run: pip install -r pc_receiver/requirements.txt")
        return

    while not stop.is_set():
        try:
            with serial.Serial(port_name, baudrate=baud, timeout=0.1, write_timeout=0.1) as serial_port:
                logging.info("Bluetooth serial listening on %s at %s baud", port_name, baud)

                def reply(data: dict[str, Any]) -> None:
                    serial_port.write((compact_json(data) + "\n").encode("utf-8"))
                    serial_port.flush()

                while not stop.is_set():
                    line = serial_port.readline()
                    if not line:
                        continue
                    raw = line.decode("utf-8", errors="replace").strip()
                    if raw:
                        handle_message(
                            raw,
                            f"bluetooth:{port_name}",
                            processor,
                            reply,
                            input_log=input_log,
                        )
        except Exception as exc:
            if not stop.is_set():
                logging.warning("Bluetooth serial %s not ready: %s", port_name, exc)
                stop.wait(2.0)
        finally:
            processor.release_all()


def status_loop(
    processor: EventProcessor,
    backend: InputBackend,
    stop: threading.Event,
    release_timeout: float,
) -> None:
    next_log = 0.0
    while not stop.wait(0.25):
        last_source, last_command, last_seen = processor.status()
        age = time.monotonic() - last_seen if last_seen else -1.0
        now = time.monotonic()
        if now >= next_log:
            logging.info(
                "status backend=%s source=%s age=%.1fs input=%s last=%s",
                backend.name,
                last_source,
                age,
                backend.snapshot(),
                last_command,
            )
            next_log = now + 5.0

        if last_seen and age > release_timeout and backend.has_active_input():
            logging.warning("no input for %.1fs; releasing held input", age)
            processor.release_all()


def list_serial_ports() -> int:
    try:
        from serial.tools import list_ports
    except Exception:
        print("pyserial is not installed. Run: pip install -r pc_receiver/requirements.txt")
        return 2

    ports = list(list_ports.comports())
    if not ports:
        print("No serial ports found.")
        return 0
    for port in ports:
        print(f"{port.device}\t{port.description}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="ZeroPad Windows receiver")
    parser.add_argument(
        "--backend",
        choices=("xbox", "keyboard", "dry-run"),
        default="xbox",
        help="PC output backend. Default: xbox",
    )
    parser.add_argument("--dry-run", action="store_true", help="compat alias for --backend dry-run")
    parser.add_argument("--udp-bind", default="0.0.0.0", help="Wi-Fi UDP bind address")
    parser.add_argument("--udp-port", type=int, default=UDP_PORT, help="Wi-Fi UDP port")
    parser.add_argument("--tcp-host", default="127.0.0.1", help="USB TCP bind host")
    parser.add_argument("--tcp-port", type=int, default=TCP_PORT, help="USB TCP port")
    parser.add_argument("--bluetooth-serial", help="Windows incoming Bluetooth COM port, e.g. COM7")
    parser.add_argument("--bluetooth-baud", type=int, default=BLUETOOTH_BAUD, help="Bluetooth serial baud")
    parser.add_argument("--list-serial", action="store_true", help="list serial/COM ports and exit")
    parser.add_argument("--threshold", type=float, default=0.45, help="keyboard joystick direction threshold")
    parser.add_argument("--release-timeout", type=float, default=2.5, help="release held input after silence")
    parser.add_argument("--input-log", action="store_true", help="log every input event at INFO level")
    parser.add_argument("--debug", action="store_true", help="verbose logs")
    return parser.parse_args()


def create_backend(args: argparse.Namespace) -> InputBackend:
    backend_name = "dry-run" if args.dry_run else args.backend
    if backend_name == "dry-run":
        return DryRunBackend()
    if backend_name == "keyboard":
        return KeyboardBackend(joystick_threshold=args.threshold)
    if backend_name == "xbox":
        return XboxBackend()
    raise ValueError(f"unknown backend: {backend_name}")


def main() -> int:
    args = parse_args()
    setup_logging(args.debug)

    if args.list_serial:
        return list_serial_ports()

    logging.info("ZeroPad receiver starting")
    logging.info("log file: %s", LOG_FILE)

    try:
        backend = create_backend(args)
    except RuntimeError as exc:
        logging.error("%s", exc)
        logging.error("Use --backend keyboard to run the old keyboard mode.")
        return 2

    processor = EventProcessor(backend=backend)
    stop = threading.Event()

    threads = [
        threading.Thread(
            target=udp_server,
            args=(args.udp_bind, args.udp_port, processor, stop, args.input_log),
            daemon=True,
        ),
        threading.Thread(
            target=tcp_server,
            args=(args.tcp_host, args.tcp_port, processor, stop, args.input_log),
            daemon=True,
        ),
        threading.Thread(
            target=status_loop,
            args=(processor, backend, stop, args.release_timeout),
            daemon=True,
        ),
    ]

    if args.bluetooth_serial:
        threads.append(
            threading.Thread(
                target=serial_server,
                args=(args.bluetooth_serial, args.bluetooth_baud, processor, stop, args.input_log),
                daemon=True,
            )
        )
    else:
        logging.info("Bluetooth serial disabled. Use --bluetooth-serial COMx to enable Bluetooth mode.")

    for thread in threads:
        thread.start()

    logging.info("press Ctrl+C to stop")
    try:
        while True:
            time.sleep(0.5)
    except KeyboardInterrupt:
        logging.info("stopping")
    finally:
        stop.set()
        processor.release_all()
        time.sleep(0.2)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
