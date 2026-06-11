#!/usr/bin/env python3
"""ZeroPad desktop control panel for Windows."""

from __future__ import annotations

import os
import json
import queue
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Callable
from tkinter import ttk
import tkinter as tk

try:
    import winreg
except Exception:  # pragma: no cover - only available on Windows
    winreg = None


ROOT_DIR = Path(__file__).resolve().parents[1]
RECEIVER_SCRIPT = Path(__file__).with_name("zeropad_receiver.py")
LOG_FILE = Path(__file__).with_name("zeropad_receiver.log")
ASSET_DIR = Path(__file__).with_name("assets")
ICON_FILE = ASSET_DIR / "zeropad.ico"
CONFIG_DIR = Path(os.environ.get("APPDATA") or Path.home()) / "ZeroPad"
CONFIG_FILE = CONFIG_DIR / "settings.json"

DEFAULT_UDP_PORT = 54545
DEFAULT_TCP_PORT = 54546

COLORS = {
    "bg": "#0b0f14",
    "panel": "#121923",
    "panel_soft": "#192432",
    "panel_lift": "#202c3b",
    "line": "#2a3a4d",
    "text": "#f4f8fc",
    "muted": "#a6b3c2",
    "faint": "#748293",
    "field": "#070b10",
    "green": "#19c77a",
    "green_hover": "#33db91",
    "blue": "#4aa3ff",
    "blue_hover": "#6db6ff",
    "purple": "#8d7dff",
    "purple_hover": "#a599ff",
    "amber": "#f3b84b",
    "amber_hover": "#ffcc63",
    "red": "#ef5350",
    "red_hover": "#ff6f6b",
    "off": "#53606e",
    "shell": "#243243",
    "shell_deep": "#151f2b",
}

FONT_TITLE = ("Segoe UI", 26, "bold")
FONT_DISPLAY = ("Segoe UI", 28, "bold")
FONT_H2 = ("Segoe UI", 12, "bold")
FONT_BODY = ("Segoe UI", 10)
FONT_SMALL = ("Segoe UI", 9)
FONT_MONO = ("Consolas", 9)

LANGUAGE_LABELS = {"tr": "Türkçe", "en": "English"}
LANGUAGE_CODES = {value: key for key, value in LANGUAGE_LABELS.items()}

TEXT = {
    "tr": {
        "window_title": "ZeroPad Alıcı",
        "subtitle": "Alıcı Kontrol Merkezi",
        "summary_label": "Xbox çıkışı",
        "summary_value": "Düşük gecikmeli köprü",
        "start": "Başlat",
        "stop": "Durdur",
        "dashboard": "Kontrol Paneli",
        "settings": "Ayarlar",
        "output_mode": "Çıkış modu",
        "keyboard": "Klavye",
        "test": "Test",
        "backend_hint": "Xbox modu için ViGEmBus + vgamepad gerekir. Klavye modu eski W/A/S/D eşleşmesini kullanır.",
        "connection": "Bağlantı",
        "wifi_port": "Wi‑Fi UDP portu",
        "usb_port": "USB TCP portu",
        "bluetooth_com": "Bluetooth COM",
        "scan": "Tara",
        "usb_prepare": "USB hazırla (ADB reverse)",
        "tools": "Araçlar",
        "debug_log": "Hata ayıklama logu",
        "input_log": "Her girişi logla",
        "diagnostics": "Teşhis",
        "open_log": "Logu aç",
        "repair_xbox": "Xbox sürücüsünü onar",
        "receiver": "Alıcı",
        "closed": "Kapalı",
        "waiting": "Bekliyor",
        "running": "Çalışıyor",
        "error": "Hata",
        "listening": "Dinliyor",
        "connected": "Bağlandı",
        "phone_ip_hint": "Telefon Wi‑Fi modunda bu PC IP adresini kullanır:",
        "refresh": "Yenile",
        "controller_surface": "Kontrolcü yüzeyi",
        "controller_hint": "A/B/X/Y, analoglar, bumper ve trigger",
        "virtual_pad": "Sanal Xbox pad",
        "virtual_pad_ready": "Wi‑Fi, USB ve Bluetooth girişi için hazır",
        "live_log": "Canlı log",
        "ready_log": "ZeroPad hazır. Alıcıyı başlatmak için Başlat'a bas.",
        "appearance": "Görünüm",
        "language": "Dil",
        "language_hint": "Dil değişince arayüz hemen yenilenir.",
        "receiver_settings": "Alıcı ayarları",
        "save_settings": "Ayarları kaydet",
        "quick_actions": "Hızlı işlemler",
        "create_shortcut": "Masaüstü kısayolu oluştur",
        "pc_env": "PC ortamını kur",
        "github_hint": "GitHub sürümünde gereksiz build çıktıları yok. APK için build_apk.bat çalıştır.",
        "already_running": "Alıcı zaten çalışıyor.",
        "already_stopped": "Alıcı zaten kapalı.",
        "stopping": "Alıcı durduruluyor...",
        "receiver_failed": "Alıcı başlatılamadı: {error}",
        "receiver_stopped": "Alıcı kapandı. exit={code}",
        "ip_refreshed": "IP adresleri yenilendi.",
        "ports_scanning": "Bluetooth COM portları taranıyor...",
        "ports_found": "Bluetooth portları: {ports}",
        "ports_missing": "Bluetooth portları bulunamadı",
        "incoming_guess": "Incoming Bluetooth port tahmini: {ports}",
        "adb_missing": "ADB bulunamadı. Android Studio Platform Tools kurulu mu?",
        "adb_preparing": "ADB hazırlanıyor",
        "adb_ready": "ADB hazır",
        "adb_error": "ADB hatası",
        "task_started": "{label} başladı.",
        "task_done": "{label} bitti. exit={code}",
        "command_failed": "Komut çalışmadı: {error}",
        "settings_saved": "Ayarlar kaydedildi.",
        "language_changed": "Dil değiştirildi: {language}",
        "repair_missing": "Onarım dosyası yok: {path}",
        "repair_windows_only": "Xbox driver onarımı sadece Windows'ta çalışır.",
        "repair_failed": "Xbox driver onarımı başlatılamadı: {error}",
        "repair_prompt": "Xbox driver onarımı için Windows izin penceresi açıldı. Evet de.",
        "open_log_failed": "Log açılamadı: {error}",
        "port_number": "{label} sayı olmalı.",
        "port_range": "{label} 1-65535 arasında olmalı.",
    },
    "en": {
        "window_title": "ZeroPad Receiver",
        "subtitle": "Receiver Control Center",
        "summary_label": "Xbox output",
        "summary_value": "Low-latency bridge",
        "start": "Start",
        "stop": "Stop",
        "dashboard": "Dashboard",
        "settings": "Settings",
        "output_mode": "Output Mode",
        "keyboard": "Keyboard",
        "test": "Test",
        "backend_hint": "Xbox mode requires ViGEmBus + vgamepad. Keyboard mode uses the old W/A/S/D mapping.",
        "connection": "Connection",
        "wifi_port": "Wi‑Fi UDP port",
        "usb_port": "USB TCP port",
        "bluetooth_com": "Bluetooth COM",
        "scan": "Scan",
        "usb_prepare": "Prepare USB (ADB reverse)",
        "tools": "Tools",
        "debug_log": "Debug log",
        "input_log": "Log every input",
        "diagnostics": "Diagnostics",
        "open_log": "Open log",
        "repair_xbox": "Repair Xbox driver",
        "receiver": "Receiver",
        "closed": "Closed",
        "waiting": "Waiting",
        "running": "Running",
        "error": "Error",
        "listening": "Listening",
        "connected": "Connected",
        "phone_ip_hint": "Use this PC IP address in the phone's Wi‑Fi mode:",
        "refresh": "Refresh",
        "controller_surface": "Controller Surface",
        "controller_hint": "A/B/X/Y, sticks, bumpers and triggers",
        "virtual_pad": "Virtual Xbox pad",
        "virtual_pad_ready": "Ready for Wi‑Fi, USB and Bluetooth input",
        "live_log": "Live Log",
        "ready_log": "ZeroPad is ready. Press Start to run the receiver.",
        "appearance": "Appearance",
        "language": "Language",
        "language_hint": "The interface refreshes immediately when language changes.",
        "receiver_settings": "Receiver Settings",
        "save_settings": "Save settings",
        "quick_actions": "Quick Actions",
        "create_shortcut": "Create desktop shortcut",
        "pc_env": "Install PC environment",
        "github_hint": "The GitHub build has no generated clutter. Run build_apk.bat for APK output.",
        "already_running": "Receiver is already running.",
        "already_stopped": "Receiver is already closed.",
        "stopping": "Stopping receiver...",
        "receiver_failed": "Receiver could not start: {error}",
        "receiver_stopped": "Receiver stopped. exit={code}",
        "ip_refreshed": "IP addresses refreshed.",
        "ports_scanning": "Scanning Bluetooth COM ports...",
        "ports_found": "Bluetooth ports: {ports}",
        "ports_missing": "No Bluetooth ports found",
        "incoming_guess": "Incoming Bluetooth port guess: {ports}",
        "adb_missing": "ADB was not found. Is Android Studio Platform Tools installed?",
        "adb_preparing": "Preparing ADB",
        "adb_ready": "ADB ready",
        "adb_error": "ADB error",
        "task_started": "{label} started.",
        "task_done": "{label} finished. exit={code}",
        "command_failed": "Command failed: {error}",
        "settings_saved": "Settings saved.",
        "language_changed": "Language changed: {language}",
        "repair_missing": "Repair file is missing: {path}",
        "repair_windows_only": "Xbox driver repair only works on Windows.",
        "repair_failed": "Xbox driver repair could not start: {error}",
        "repair_prompt": "Windows permission prompt opened for Xbox driver repair. Choose Yes.",
        "open_log_failed": "Log could not be opened: {error}",
        "port_number": "{label} must be a number.",
        "port_range": "{label} must be between 1 and 65535.",
    },
}


def find_adb() -> str | None:
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        sdk_adb = Path(local_app_data) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        if sdk_adb.exists():
            return str(sdk_adb)
    return shutil.which("adb")


def pretty_cmd(command: list[str]) -> str:
    return subprocess.list2cmdline(command) if os.name == "nt" else " ".join(command)


def process_flags(*, new_group: bool = False) -> int:
    if os.name != "nt":
        return 0
    flags = subprocess.CREATE_NO_WINDOW
    if new_group:
        flags |= subprocess.CREATE_NEW_PROCESS_GROUP
    return flags


def local_ip_addresses() -> list[str]:
    addresses: set[str] = set()

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            addresses.add(sock.getsockname()[0])
    except OSError:
        pass

    try:
        host = socket.gethostname()
        for address in socket.gethostbyname_ex(host)[2]:
            if not address.startswith("127."):
                addresses.add(address)
    except OSError:
        pass

    return sorted(addresses) or ["IP bulunamadı"]


def registry_bluetooth_ports() -> list[str]:
    if winreg is None:
        return []

    found: set[str] = set()
    base_path = r"SYSTEM\CurrentControlSet\Enum\BTHENUM"

    def read_port_name(path: str) -> None:
        for candidate in (path, path + r"\Device Parameters"):
            try:
                with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, candidate) as key:
                    value, _ = winreg.QueryValueEx(key, "PortName")
                    if isinstance(value, str) and value.upper().startswith("COM"):
                        found.add(value.upper())
            except OSError:
                continue

    def walk(path: str) -> None:
        try:
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path) as key:
                index = 0
                while True:
                    try:
                        name = winreg.EnumKey(key, index)
                    except OSError:
                        break
                    child = path + "\\" + name
                    upper_child = child.upper()
                    if "00001101" in upper_child and "LOCALMFG" in upper_child:
                        read_port_name(child)
                    walk(child)
                    index += 1
        except OSError:
            return

    walk(base_path)
    return sorted(found, key=lambda item: int(item[3:]) if item[3:].isdigit() else 9999)


def serial_ports() -> list[str]:
    try:
        from serial.tools import list_ports
    except Exception:
        return []

    return sorted(
        {port.device.upper() for port in list_ports.comports() if port.device},
        key=lambda item: int(item[3:]) if item.upper().startswith("COM") and item[3:].isdigit() else 9999,
    )


def load_settings() -> dict[str, object]:
    defaults: dict[str, object] = {
        "language": "tr",
        "backend": "xbox",
        "udp_port": str(DEFAULT_UDP_PORT),
        "tcp_port": str(DEFAULT_TCP_PORT),
        "debug": False,
        "input_log": False,
    }
    try:
        if CONFIG_FILE.exists():
            data = json.loads(CONFIG_FILE.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                defaults.update(data)
    except Exception:
        pass
    if defaults.get("language") not in TEXT:
        defaults["language"] = "tr"
    return defaults


def save_settings(data: dict[str, object]) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_FILE.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def round_rect(canvas: tk.Canvas, x1: float, y1: float, x2: float, y2: float, radius: float, **kwargs: object) -> int:
    points = [
        x1 + radius,
        y1,
        x2 - radius,
        y1,
        x2,
        y1,
        x2,
        y1 + radius,
        x2,
        y2 - radius,
        x2,
        y2,
        x2 - radius,
        y2,
        x1 + radius,
        y2,
        x1,
        y2,
        x1,
        y2 - radius,
        x1,
        y1 + radius,
        x1,
        y1,
    ]
    return canvas.create_polygon(points, smooth=True, splinesteps=16, **kwargs)


def draw_logo(canvas: tk.Canvas, x: int, y: int, size: int) -> None:
    round_rect(canvas, x, y, x + size, y + size, 18, fill=COLORS["field"], outline=COLORS["line"], width=1)
    canvas.create_arc(x + 10, y + 10, x + size - 10, y + size - 10, start=215, extent=255, outline=COLORS["green"], width=3)
    canvas.create_line(x + 18, y + 22, x + size - 18, y + 22, x + 20, y + size - 20, x + size - 17, y + size - 20, fill=COLORS["text"], width=4, capstyle="round", joinstyle="round")
    canvas.create_oval(x + size - 18, y + 12, x + size - 10, y + 20, fill=COLORS["blue"], outline="")


class ControllerPreview(tk.Canvas):
    def __init__(self, parent: tk.Widget, title: str, subtitle: str) -> None:
        super().__init__(parent, height=180, bg=COLORS["panel"], highlightthickness=0)
        self.title_text = title
        self.subtitle_text = subtitle
        self.bind("<Configure>", lambda _event: self.draw())

    def draw(self) -> None:
        self.delete("all")
        width = max(self.winfo_width(), 420)
        height = max(self.winfo_height(), 170)

        for index in range(0, height, 4):
            shade = "#111923" if index % 8 == 0 else "#121b25"
            self.create_rectangle(0, index, width, index + 4, fill=shade, outline="")

        round_rect(self, 8, 8, width - 8, height - 8, 22, fill=COLORS["panel_soft"], outline=COLORS["line"], width=1)
        self.create_text(24, 24, text=self.title_text, fill=COLORS["text"], font=("Segoe UI", 12, "bold"), anchor="w")
        self.create_text(24, 45, text=self.subtitle_text, fill=COLORS["muted"], font=FONT_SMALL, anchor="w")

        cx = width / 2
        cy = 112
        body_w = min(width - 74, 560)
        left = cx - body_w / 2
        right = cx + body_w / 2

        self.create_oval(left + 8, cy - 26, left + 132, cy + 84, fill=COLORS["shell_deep"], outline=COLORS["line"], width=2)
        self.create_oval(right - 132, cy - 26, right - 8, cy + 84, fill=COLORS["shell_deep"], outline=COLORS["line"], width=2)
        round_rect(self, left + 70, cy - 44, right - 70, cy + 54, 36, fill=COLORS["shell"], outline=COLORS["line"], width=2)
        round_rect(self, left + 108, cy - 64, left + 220, cy - 42, 10, fill=COLORS["panel_lift"], outline=COLORS["line"], width=1)
        round_rect(self, right - 220, cy - 64, right - 108, cy - 42, 10, fill=COLORS["panel_lift"], outline=COLORS["line"], width=1)
        self.create_text(left + 164, cy - 53, text="LB", fill=COLORS["muted"], font=("Segoe UI", 8, "bold"))
        self.create_text(right - 164, cy - 53, text="RB", fill=COLORS["muted"], font=("Segoe UI", 8, "bold"))

        self._stick(left + 120, cy, COLORS["green"])
        self._dpad(left + 205, cy + 22)
        self._stick(right - 195, cy + 36, COLORS["blue"])
        self._face_buttons(right - 104, cy + 5)

        round_rect(self, cx - 44, cy - 10, cx - 12, cy + 7, 8, fill=COLORS["panel_lift"], outline=COLORS["line"])
        round_rect(self, cx + 12, cy - 10, cx + 44, cy + 7, 8, fill=COLORS["panel_lift"], outline=COLORS["line"])
        self.create_text(cx - 28, cy - 2, text="View", fill=COLORS["muted"], font=("Segoe UI", 7, "bold"))
        self.create_text(cx + 28, cy - 2, text="Menu", fill=COLORS["muted"], font=("Segoe UI", 7, "bold"))
        self.create_oval(cx - 18, cy + 18, cx + 18, cy + 54, fill=COLORS["field"], outline=COLORS["line"], width=1)
        self.create_text(cx, cy + 36, text="Z", fill=COLORS["green"], font=("Segoe UI", 13, "bold"))

    def _stick(self, x: float, y: float, accent: str) -> None:
        self.create_oval(x - 30, y - 30, x + 30, y + 30, fill=COLORS["field"], outline=COLORS["line"], width=2)
        self.create_oval(x - 17, y - 17, x + 17, y + 17, fill=COLORS["panel_lift"], outline=accent, width=2)
        self.create_oval(x - 4, y - 4, x + 4, y + 4, fill=accent, outline="")

    def _dpad(self, x: float, y: float) -> None:
        round_rect(self, x - 12, y - 34, x + 12, y + 34, 7, fill=COLORS["field"], outline=COLORS["line"])
        round_rect(self, x - 34, y - 12, x + 34, y + 12, 7, fill=COLORS["field"], outline=COLORS["line"])
        self.create_rectangle(x - 10, y - 10, x + 10, y + 10, fill=COLORS["field"], outline="")

    def _face_buttons(self, x: float, y: float) -> None:
        buttons = [
            ("Y", x, y - 28, COLORS["amber"]),
            ("A", x, y + 28, COLORS["green"]),
            ("X", x - 30, y, COLORS["blue"]),
            ("B", x + 30, y, COLORS["red"]),
        ]
        for label, bx, by, color in buttons:
            self.create_oval(bx - 17, by - 17, bx + 17, by + 17, fill=color, outline="#ffffff", width=1)
            self.create_text(bx, by, text=label, fill="#07110c", font=("Segoe UI", 11, "bold"))


class StatusChip(tk.Frame):
    def __init__(self, parent: tk.Widget, title: str, value: str, color: str) -> None:
        super().__init__(parent, bg=COLORS["panel_soft"], highlightthickness=1, highlightbackground=COLORS["line"])
        self.color = color
        self.dot = tk.Canvas(self, width=12, height=12, bg=COLORS["panel_soft"], highlightthickness=0)
        self.dot.grid(row=0, column=0, rowspan=2, padx=(12, 8), pady=10)
        self.dot_id = self.dot.create_oval(2, 2, 11, 11, fill=color, outline=color)

        self.title_label = tk.Label(self, text=title, bg=COLORS["panel_soft"], fg=COLORS["muted"], font=FONT_SMALL)
        self.title_label.grid(
            row=0, column=1, sticky="w", padx=(0, 12), pady=(8, 0)
        )
        self.value_label = tk.Label(
            self,
            text=value,
            bg=COLORS["panel_soft"],
            fg=COLORS["text"],
            font=("Segoe UI", 10, "bold"),
        )
        self.value_label.grid(row=1, column=1, sticky="w", padx=(0, 12), pady=(0, 8))
        self.grid_columnconfigure(1, weight=1)

    def set(self, value: str, color: str | None = None) -> None:
        self.value_label.configure(text=value)
        if color is not None:
            self.dot.itemconfigure(self.dot_id, fill=color, outline=color)

    def set_title(self, title: str) -> None:
        self.title_label.configure(text=title)


class ZeroPadGui(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.settings_data = load_settings()
        self.language = str(self.settings_data.get("language", "tr"))

        self.title(self.t("window_title"))
        self.geometry("1220x760")
        self.minsize(1080, 700)
        self.configure(bg=COLORS["bg"])
        self._load_icon()

        self.process: subprocess.Popen[str] | None = None
        self.reader_thread: threading.Thread | None = None
        self.log_queue: queue.Queue[tuple[str, object]] = queue.Queue()
        self.backend_buttons: list[tk.Radiobutton] = []
        self.nav_buttons: dict[str, tk.Button] = {}
        self.active_page = "dashboard"

        self.backend_var = tk.StringVar(value=str(self.settings_data.get("backend", "xbox")))
        self.udp_port_var = tk.StringVar(value=str(self.settings_data.get("udp_port", DEFAULT_UDP_PORT)))
        self.tcp_port_var = tk.StringVar(value=str(self.settings_data.get("tcp_port", DEFAULT_TCP_PORT)))
        self.com_var = tk.StringVar(value="")
        self.debug_var = tk.BooleanVar(value=bool(self.settings_data.get("debug", False)))
        self.input_log_var = tk.BooleanVar(value=bool(self.settings_data.get("input_log", False)))
        self.ip_var = tk.StringVar(value=", ".join(local_ip_addresses()))
        self.language_var = tk.StringVar(value=LANGUAGE_LABELS.get(self.language, "Türkçe"))

        self._configure_styles()
        self._build_ui()
        self._refresh_backend_segments()
        self.refresh_com_ports()
        self.after(80, self._pump_queue)
        self.protocol("WM_DELETE_WINDOW", self._close)

    def _load_icon(self) -> None:
        if not ICON_FILE.exists():
            return
        try:
            self.iconbitmap(default=str(ICON_FILE))
        except tk.TclError:
            pass

    def t(self, key: str, **kwargs: object) -> str:
        value = TEXT.get(self.language, TEXT["tr"]).get(key, TEXT["tr"].get(key, key))
        return value.format(**kwargs) if kwargs else value

    def _configure_styles(self) -> None:
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure(
            "ZeroPad.TCombobox",
            fieldbackground=COLORS["field"],
            background=COLORS["panel_soft"],
            foreground=COLORS["text"],
            arrowcolor=COLORS["text"],
            bordercolor=COLORS["line"],
            lightcolor=COLORS["line"],
            darkcolor=COLORS["line"],
        )
        style.configure(
            "ZeroPad.TCheckbutton",
            background=COLORS["panel"],
            foreground=COLORS["text"],
            font=FONT_BODY,
        )
        style.map(
            "ZeroPad.TCheckbutton",
            background=[("active", COLORS["panel"])],
            foreground=[("active", COLORS["text"])],
        )

    def _build_ui(self) -> None:
        for child in self.winfo_children():
            child.destroy()
        self.title(self.t("window_title"))
        self.backend_buttons.clear()
        self.nav_buttons.clear()

        header = tk.Frame(self, bg=COLORS["panel"], highlightthickness=1, highlightbackground=COLORS["line"])
        header.pack(fill="x", padx=24, pady=(20, 14))
        header.grid_columnconfigure(1, weight=1)

        logo = tk.Canvas(header, width=78, height=78, bg=COLORS["panel"], highlightthickness=0)
        logo.grid(row=0, column=0, rowspan=2, padx=(18, 14), pady=14)
        draw_logo(logo, 4, 4, 68)

        tk.Label(header, text="ZeroPad", bg=COLORS["panel"], fg=COLORS["text"], font=FONT_DISPLAY).grid(
            row=0, column=1, sticky="sw", pady=(14, 0)
        )
        tk.Label(
            header,
            text=self.t("subtitle"),
            bg=COLORS["panel"],
            fg=COLORS["green"],
            font=("Segoe UI", 11, "bold"),
        ).grid(row=1, column=1, sticky="nw", pady=(0, 14))

        summary = tk.Frame(header, bg=COLORS["panel_soft"], highlightthickness=1, highlightbackground=COLORS["line"])
        summary.grid(row=0, column=2, rowspan=2, sticky="e", padx=(12, 16), pady=16)
        tk.Label(summary, text=self.t("summary_label"), bg=COLORS["panel_soft"], fg=COLORS["muted"], font=FONT_SMALL).grid(
            row=0, column=0, sticky="w", padx=12, pady=(9, 0)
        )
        tk.Label(summary, text=self.t("summary_value"), bg=COLORS["panel_soft"], fg=COLORS["text"], font=("Segoe UI", 11, "bold")).grid(
            row=1, column=0, sticky="w", padx=12, pady=(0, 9)
        )

        button_bar = tk.Frame(header, bg=COLORS["panel"])
        button_bar.grid(row=0, column=3, rowspan=2, sticky="e", padx=(0, 18))
        self.start_button = self._button(button_bar, self.t("start"), self.start_receiver, COLORS["green"], COLORS["green_hover"])
        self.start_button.pack(side="left", padx=(0, 8))
        self.stop_button = self._button(button_bar, self.t("stop"), self.stop_receiver, COLORS["red"], COLORS["red_hover"])
        self.stop_button.pack(side="left")

        nav = tk.Frame(self, bg=COLORS["bg"])
        nav.pack(fill="x", padx=24, pady=(0, 12))
        self.nav_buttons["dashboard"] = self._nav_button(nav, self.t("dashboard"), "dashboard")
        self.nav_buttons["settings"] = self._nav_button(nav, self.t("settings"), "settings")
        self.nav_buttons["dashboard"].pack(side="left", padx=(0, 8))
        self.nav_buttons["settings"].pack(side="left")

        self.page = tk.Frame(self, bg=COLORS["bg"])
        self.page.pack(fill="both", expand=True, padx=24, pady=(0, 18))
        self._show_page(self.active_page)

    def _show_page(self, page_name: str) -> None:
        self.active_page = page_name
        for child in self.page.winfo_children():
            child.destroy()
        for name, button in self.nav_buttons.items():
            active = name == page_name
            button.configure(
                bg=COLORS["green"] if active else COLORS["panel"],
                fg="#07110c" if active else COLORS["text"],
                activebackground=COLORS["green_hover"] if active else COLORS["panel_lift"],
                activeforeground="#07110c" if active else COLORS["text"],
            )
        if page_name == "settings":
            self._build_settings_page(self.page)
        else:
            self._build_dashboard_page(self.page)

    def _build_dashboard_page(self, content: tk.Widget) -> None:
        content.grid_columnconfigure(0, weight=0)
        content.grid_columnconfigure(1, weight=1)
        content.grid_rowconfigure(0, weight=1)

        left = tk.Frame(content, bg=COLORS["bg"], width=330)
        left.grid(row=0, column=0, sticky="nsw", padx=(0, 16))
        left.grid_propagate(False)

        right = tk.Frame(content, bg=COLORS["bg"])
        right.grid(row=0, column=1, sticky="nsew")
        right.grid_columnconfigure(0, weight=1)
        right.grid_rowconfigure(3, weight=1)

        self._build_receiver_section(left)
        self._build_connection_section(left)
        self._build_tools_section(left)
        self._build_status_section(right)
        self._build_controller_panel(right)
        self._build_log_section(right)

    def _build_settings_page(self, parent: tk.Widget) -> None:
        parent.grid_columnconfigure(0, weight=1)
        parent.grid_columnconfigure(1, weight=1)
        parent.grid_rowconfigure(0, weight=1)

        left = tk.Frame(parent, bg=COLORS["bg"])
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 10))
        right = tk.Frame(parent, bg=COLORS["bg"])
        right.grid(row=0, column=1, sticky="nsew", padx=(10, 0))

        appearance, appearance_body = self._section(left, self.t("appearance"))
        appearance.pack(fill="x", pady=(0, 12))
        tk.Label(
            appearance_body,
            text=self.t("language"),
            bg=COLORS["panel"],
            fg=COLORS["muted"],
            font=FONT_SMALL,
        ).pack(anchor="w", pady=(0, 4))
        language_combo = ttk.Combobox(
            appearance_body,
            textvariable=self.language_var,
            style="ZeroPad.TCombobox",
            values=tuple(LANGUAGE_LABELS.values()),
            state="readonly",
            font=FONT_BODY,
        )
        language_combo.pack(fill="x", ipady=5)
        language_combo.bind("<<ComboboxSelected>>", lambda _event: self.change_language())
        tk.Label(
            appearance_body,
            text=self.t("language_hint"),
            bg=COLORS["panel"],
            fg=COLORS["muted"],
            wraplength=460,
            justify="left",
            font=FONT_SMALL,
        ).pack(fill="x", pady=(10, 0))

        receiver, receiver_body = self._section(left, self.t("receiver_settings"))
        receiver.pack(fill="x", pady=(0, 12))
        ttk.Checkbutton(receiver_body, text=self.t("debug_log"), variable=self.debug_var, style="ZeroPad.TCheckbutton").pack(anchor="w", pady=(0, 8))
        ttk.Checkbutton(receiver_body, text=self.t("input_log"), variable=self.input_log_var, style="ZeroPad.TCheckbutton").pack(anchor="w", pady=(0, 12))
        self._button(receiver_body, self.t("save_settings"), self.save_current_settings, COLORS["green"], COLORS["green_hover"], fill=True).pack(fill="x")

        actions, actions_body = self._section(right, self.t("quick_actions"))
        actions.pack(fill="x", pady=(0, 12))
        self._button(actions_body, self.t("create_shortcut"), self.create_desktop_shortcut, COLORS["blue"], COLORS["blue_hover"], fill=True).pack(fill="x", pady=(0, 8))
        self._button(actions_body, self.t("pc_env"), self.setup_pc_environment, COLORS["purple"], COLORS["purple_hover"], fill=True).pack(fill="x", pady=(0, 8))
        self._button(actions_body, self.t("repair_xbox"), self.run_vigembus_repair, COLORS["amber"], COLORS["amber_hover"], fill=True).pack(fill="x", pady=(0, 8))
        self._button(actions_body, self.t("open_log"), self.open_log_file, COLORS["off"], COLORS["line"], fill=True).pack(fill="x")

        info = tk.Frame(right, bg=COLORS["panel"], highlightthickness=1, highlightbackground=COLORS["line"])
        info.pack(fill="both", expand=True)
        canvas = tk.Canvas(info, height=160, bg=COLORS["panel"], highlightthickness=0)
        canvas.pack(fill="x", padx=16, pady=(16, 0))
        draw_logo(canvas, 12, 18, 92)
        canvas.create_text(126, 48, text="ZeroPad", fill=COLORS["text"], font=("Segoe UI", 22, "bold"), anchor="w")
        canvas.create_text(126, 78, text=self.t("github_hint"), fill=COLORS["muted"], font=FONT_BODY, anchor="w")
        round_rect(canvas, 126, 104, 420, 134, 12, fill=COLORS["panel_soft"], outline=COLORS["line"], width=1)
        canvas.create_text(144, 119, text=f"{self.t('language')}: {self.language_var.get()}", fill=COLORS["green"], font=("Segoe UI", 10, "bold"), anchor="w")

    def _build_receiver_section(self, parent: tk.Widget) -> None:
        section, body = self._section(parent, self.t("output_mode"))

        segments = tk.Frame(body, bg=COLORS["panel"])
        segments.pack(fill="x", pady=(2, 10))
        segments.grid_columnconfigure((0, 1, 2), weight=1, uniform="backend")

        for index, (label, value) in enumerate((("Xbox", "xbox"), (self.t("keyboard"), "keyboard"), (self.t("test"), "dry-run"))):
            radio = tk.Radiobutton(
                segments,
                text=label,
                value=value,
                variable=self.backend_var,
                indicatoron=False,
                command=self._refresh_backend_segments,
                bd=0,
                relief="flat",
                padx=12,
                pady=10,
                font=("Segoe UI", 10, "bold"),
                cursor="hand2",
            )
            radio.grid(row=0, column=index, sticky="ew", padx=(0 if index == 0 else 6, 0))
            self.backend_buttons.append(radio)

        hint = self.t("backend_hint")
        tk.Label(body, text=hint, wraplength=278, justify="left", bg=COLORS["panel"], fg=COLORS["muted"], font=FONT_SMALL).pack(
            fill="x"
        )
        section.pack(fill="x", pady=(0, 12))

    def _build_connection_section(self, parent: tk.Widget) -> None:
        section, body = self._section(parent, self.t("connection"))

        self._field(body, self.t("wifi_port"), self.udp_port_var)
        self._field(body, self.t("usb_port"), self.tcp_port_var)

        tk.Label(body, text=self.t("bluetooth_com"), bg=COLORS["panel"], fg=COLORS["muted"], font=FONT_SMALL).pack(
            anchor="w", pady=(8, 4)
        )
        combo_row = tk.Frame(body, bg=COLORS["panel"])
        combo_row.pack(fill="x")
        self.com_combo = ttk.Combobox(
            combo_row,
            textvariable=self.com_var,
            style="ZeroPad.TCombobox",
            values=(),
            state="normal",
            font=FONT_BODY,
        )
        self.com_combo.pack(side="left", fill="x", expand=True, ipady=4)
        self._button(combo_row, self.t("scan"), self.refresh_com_ports, COLORS["blue"], COLORS["blue_hover"], padx=12).pack(
            side="left", padx=(8, 0)
        )

        self._button(
            body,
            self.t("usb_prepare"),
            self.setup_usb_reverse,
            COLORS["amber"],
            COLORS["amber_hover"],
            fill=True,
        ).pack(fill="x", pady=(12, 0))

        tk.Label(
            body,
            textvariable=self.ip_var,
            wraplength=278,
            justify="left",
            bg=COLORS["panel"],
            fg=COLORS["muted"],
            font=FONT_SMALL,
        ).pack(fill="x", pady=(12, 0))

        section.pack(fill="x", pady=(0, 12))

    def _build_tools_section(self, parent: tk.Widget) -> None:
        section, body = self._section(parent, self.t("tools"))
        ttk.Checkbutton(body, text=self.t("debug_log"), variable=self.debug_var, style="ZeroPad.TCheckbutton").pack(
            anchor="w", pady=(0, 6)
        )
        ttk.Checkbutton(body, text=self.t("input_log"), variable=self.input_log_var, style="ZeroPad.TCheckbutton").pack(
            anchor="w", pady=(0, 10)
        )

        tool_row = tk.Frame(body, bg=COLORS["panel"])
        tool_row.pack(fill="x")
        self._button(tool_row, self.t("diagnostics"), self.run_diagnostics, COLORS["blue"], COLORS["blue_hover"], padx=14).pack(
            side="left", fill="x", expand=True
        )
        self._button(tool_row, self.t("open_log"), self.open_log_file, COLORS["off"], COLORS["line"], padx=14).pack(
            side="left", fill="x", expand=True, padx=(8, 0)
        )

        self._button(
            body,
            self.t("repair_xbox"),
            self.run_vigembus_repair,
            COLORS["purple"],
            COLORS["purple_hover"],
            fill=True,
        ).pack(fill="x", pady=(10, 0))
        section.pack(fill="x")

    def _build_status_section(self, parent: tk.Widget) -> None:
        status = tk.Frame(parent, bg=COLORS["bg"])
        status.grid(row=0, column=0, sticky="ew", pady=(0, 14))
        status.grid_columnconfigure((0, 1, 2, 3), weight=1, uniform="status")

        self.receiver_chip = StatusChip(status, self.t("receiver"), self.t("closed"), COLORS["off"])
        self.wifi_chip = StatusChip(status, "Wi-Fi UDP", self.t("waiting"), COLORS["off"])
        self.usb_chip = StatusChip(status, "USB TCP", self.t("waiting"), COLORS["off"])
        self.bt_chip = StatusChip(status, "Bluetooth", self.t("closed"), COLORS["off"])

        for column, chip in enumerate((self.receiver_chip, self.wifi_chip, self.usb_chip, self.bt_chip)):
            chip.grid(row=0, column=column, sticky="ew", padx=(0 if column == 0 else 10, 0))

        ip_panel = tk.Frame(parent, bg=COLORS["panel"], highlightthickness=1, highlightbackground=COLORS["line"])
        ip_panel.grid(row=1, column=0, sticky="ew", pady=(0, 14))
        ip_panel.grid_columnconfigure(0, weight=1)
        tk.Label(ip_panel, text=self.t("phone_ip_hint"), bg=COLORS["panel"], fg=COLORS["muted"], font=FONT_SMALL).grid(
            row=0, column=0, sticky="w", padx=14, pady=(10, 0)
        )
        tk.Label(ip_panel, textvariable=self.ip_var, bg=COLORS["panel"], fg=COLORS["text"], font=("Segoe UI", 12, "bold")).grid(
            row=1, column=0, sticky="w", padx=14, pady=(2, 10)
        )
        self._button(ip_panel, self.t("refresh"), self.refresh_ip_addresses, COLORS["off"], COLORS["line"], padx=12).grid(
            row=0, column=1, rowspan=2, padx=12, pady=12
        )

    def _build_controller_panel(self, parent: tk.Widget) -> None:
        frame = tk.Frame(parent, bg=COLORS["panel"], highlightthickness=1, highlightbackground=COLORS["line"])
        frame.grid(row=2, column=0, sticky="ew", pady=(0, 14))
        frame.grid_columnconfigure(0, weight=1)

        title_row = tk.Frame(frame, bg=COLORS["panel"])
        title_row.grid(row=0, column=0, sticky="ew", padx=14, pady=(12, 0))
        title_row.grid_columnconfigure(0, weight=1)
        tk.Label(title_row, text=self.t("controller_surface"), bg=COLORS["panel"], fg=COLORS["text"], font=FONT_H2).grid(
            row=0, column=0, sticky="w"
        )
        tk.Label(
            title_row,
            text=self.t("controller_hint"),
            bg=COLORS["panel"],
            fg=COLORS["muted"],
            font=FONT_SMALL,
        ).grid(row=0, column=1, sticky="e")

        preview = ControllerPreview(frame, self.t("virtual_pad"), self.t("virtual_pad_ready"))
        preview.grid(row=1, column=0, sticky="ew", padx=14, pady=(10, 14))

    def _build_log_section(self, parent: tk.Widget) -> None:
        frame = tk.Frame(parent, bg=COLORS["panel"], highlightthickness=1, highlightbackground=COLORS["line"])
        frame.grid(row=3, column=0, sticky="nsew")
        frame.grid_columnconfigure(0, weight=1)
        frame.grid_rowconfigure(1, weight=1)

        tk.Label(frame, text=self.t("live_log"), bg=COLORS["panel"], fg=COLORS["text"], font=FONT_H2).grid(
            row=0, column=0, sticky="w", padx=14, pady=(12, 8)
        )

        log_shell = tk.Frame(frame, bg=COLORS["field"], highlightthickness=1, highlightbackground=COLORS["line"])
        log_shell.grid(row=1, column=0, sticky="nsew", padx=14, pady=(0, 14))
        log_shell.grid_columnconfigure(0, weight=1)
        log_shell.grid_rowconfigure(0, weight=1)

        self.log_text = tk.Text(
            log_shell,
            bg=COLORS["field"],
            fg=COLORS["text"],
            insertbackground=COLORS["text"],
            relief="flat",
            wrap="word",
            font=FONT_MONO,
            padx=10,
            pady=10,
            height=14,
        )
        self.log_text.grid(row=0, column=0, sticky="nsew")
        scrollbar = tk.Scrollbar(log_shell, command=self.log_text.yview, bg=COLORS["panel_soft"])
        scrollbar.grid(row=0, column=1, sticky="ns")
        self.log_text.configure(yscrollcommand=scrollbar.set)
        self.log_text.tag_configure("warn", foreground=COLORS["amber"])
        self.log_text.tag_configure("error", foreground=COLORS["red"])
        self.log_text.tag_configure("ok", foreground=COLORS["green"])
        self.log_text.tag_configure("cmd", foreground=COLORS["blue"])

        self.append_log(self.t("ready_log"), "ok")

    def _section(self, parent: tk.Widget, title: str) -> tuple[tk.Frame, tk.Frame]:
        outer = tk.Frame(parent, bg=COLORS["panel"], highlightthickness=1, highlightbackground=COLORS["line"])
        title_bar = tk.Frame(outer, bg=COLORS["panel"])
        title_bar.pack(fill="x", padx=14, pady=(12, 8))
        tk.Frame(title_bar, width=4, height=18, bg=COLORS["green"]).pack(side="left", padx=(0, 8))
        tk.Label(title_bar, text=title, bg=COLORS["panel"], fg=COLORS["text"], font=FONT_H2).pack(side="left")
        body = tk.Frame(outer, bg=COLORS["panel"])
        body.pack(fill="x", padx=14, pady=(0, 14))
        return outer, body

    def _field(self, parent: tk.Widget, label: str, variable: tk.StringVar) -> None:
        tk.Label(parent, text=label, bg=COLORS["panel"], fg=COLORS["muted"], font=FONT_SMALL).pack(
            anchor="w", pady=(8, 4)
        )
        entry = tk.Entry(
            parent,
            textvariable=variable,
            bg=COLORS["field"],
            fg=COLORS["text"],
            insertbackground=COLORS["text"],
            relief="flat",
            highlightthickness=1,
            highlightbackground=COLORS["line"],
            highlightcolor=COLORS["blue"],
            font=FONT_BODY,
        )
        entry.pack(fill="x", ipady=8)

    def _button(
        self,
        parent: tk.Widget,
        text: str,
        command: Callable[[], None],
        color: str,
        hover: str,
        *,
        padx: int = 18,
        fill: bool = False,
    ) -> tk.Button:
        button = tk.Button(
            parent,
            text=text,
            command=command,
            bg=color,
            fg="#07110c" if color in {COLORS["green"], COLORS["amber"], COLORS["blue"]} else COLORS["text"],
            activebackground=hover,
            activeforeground="#07110c" if color in {COLORS["green"], COLORS["amber"], COLORS["blue"]} else COLORS["text"],
            relief="flat",
            bd=0,
            padx=padx,
            pady=9,
            font=("Segoe UI", 10, "bold"),
            cursor="hand2",
        )
        button.bind("<Enter>", lambda _event: button.configure(bg=hover))
        button.bind("<Leave>", lambda _event: button.configure(bg=color))
        if fill:
            button.configure(anchor="center")
        return button

    def _nav_button(self, parent: tk.Widget, text: str, page_name: str) -> tk.Button:
        button = tk.Button(
            parent,
            text=text,
            command=lambda: self._show_page(page_name),
            bg=COLORS["panel"],
            fg=COLORS["text"],
            activebackground=COLORS["panel_lift"],
            activeforeground=COLORS["text"],
            relief="flat",
            bd=0,
            padx=22,
            pady=11,
            font=("Segoe UI", 10, "bold"),
            cursor="hand2",
        )
        button.bind("<Enter>", lambda _event: button.configure(bg=COLORS["panel_lift"]) if self.active_page != page_name else None)
        button.bind("<Leave>", lambda _event: button.configure(bg=COLORS["panel"]) if self.active_page != page_name else None)
        return button

    def _refresh_backend_segments(self) -> None:
        selected = self.backend_var.get()
        accents = {"xbox": COLORS["green"], "keyboard": COLORS["blue"], "dry-run": COLORS["purple"]}
        hovers = {"xbox": COLORS["green_hover"], "keyboard": COLORS["blue_hover"], "dry-run": COLORS["purple_hover"]}
        for button in self.backend_buttons:
            value = str(button.cget("value"))
            is_selected = value == selected
            accent = accents.get(value, COLORS["green"])
            button.configure(
                bg=accent if is_selected else COLORS["panel_soft"],
                fg="#07110c" if is_selected else COLORS["text"],
                activebackground=hovers.get(value, COLORS["green_hover"]) if is_selected else COLORS["panel_lift"],
                activeforeground="#07110c" if is_selected else COLORS["text"],
                selectcolor=accent if is_selected else COLORS["panel_soft"],
            )

    def refresh_ip_addresses(self) -> None:
        self.ip_var.set(", ".join(local_ip_addresses()))
        self.append_log(self.t("ip_refreshed"), "ok")

    def current_settings(self) -> dict[str, object]:
        return {
            "language": self.language,
            "backend": self.backend_var.get(),
            "udp_port": self.udp_port_var.get().strip() or str(DEFAULT_UDP_PORT),
            "tcp_port": self.tcp_port_var.get().strip() or str(DEFAULT_TCP_PORT),
            "debug": self.debug_var.get(),
            "input_log": self.input_log_var.get(),
        }

    def save_current_settings(self) -> None:
        save_settings(self.current_settings())
        self.append_log(self.t("settings_saved"), "ok")

    def change_language(self) -> None:
        selected = self.language_var.get()
        next_language = LANGUAGE_CODES.get(selected, "tr")
        if next_language == self.language:
            return
        self.language = next_language
        self.language_var.set(LANGUAGE_LABELS[self.language])
        save_settings(self.current_settings())
        page = self.active_page
        self._build_ui()
        self._show_page(page)
        self.append_log(self.t("language_changed", language=LANGUAGE_LABELS[self.language]), "ok")

    def create_desktop_shortcut(self) -> None:
        script = Path(__file__).with_name("create_desktop_shortcut.bat")
        self._run_commands(self.t("create_shortcut"), [["cmd", "/c", str(script)]])

    def setup_pc_environment(self) -> None:
        script = Path(__file__).with_name("setup_pc_env.bat")
        self._run_commands(self.t("pc_env"), [["cmd", "/c", str(script)]])

    def refresh_com_ports(self) -> None:
        self.append_log(self.t("ports_scanning"), "cmd")

        def worker() -> None:
            registry_ports = registry_bluetooth_ports()
            all_ports = sorted(
                set(registry_ports + serial_ports()),
                key=lambda item: int(item[3:]) if item.upper().startswith("COM") and item[3:].isdigit() else 9999,
            )
            self.log_queue.put(("ports", (registry_ports, all_ports)))

        threading.Thread(target=worker, daemon=True).start()

    def start_receiver(self) -> None:
        if self.process and self.process.poll() is None:
            self.append_log(self.t("already_running"), "warn")
            return

        udp_port = self._read_port(self.udp_port_var, self.t("wifi_port"))
        tcp_port = self._read_port(self.tcp_port_var, self.t("usb_port"))
        if udp_port is None or tcp_port is None:
            return

        command = [
            sys.executable,
            str(RECEIVER_SCRIPT),
            "--backend",
            self.backend_var.get(),
            "--udp-port",
            str(udp_port),
            "--tcp-port",
            str(tcp_port),
            "--release-timeout",
            "1.2",
        ]

        selected_com = self.com_var.get().strip().upper()
        if selected_com:
            command.extend(["--bluetooth-serial", selected_com])
        if self.debug_var.get():
            command.append("--debug")
        if self.input_log_var.get():
            command.append("--input-log")

        self.append_log("> " + pretty_cmd(command), "cmd")
        try:
            self.process = subprocess.Popen(
                command,
                cwd=str(ROOT_DIR),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
                creationflags=process_flags(new_group=True),
            )
        except OSError as exc:
            self.append_log(self.t("receiver_failed", error=exc), "error")
            self.receiver_chip.set(self.t("error"), COLORS["red"])
            return

        self.receiver_chip.set(self.t("running"), COLORS["green"])
        self.wifi_chip.set(f":{udp_port}", COLORS["amber"])
        self.usb_chip.set(f":{tcp_port}", COLORS["amber"])
        self.bt_chip.set(selected_com or self.t("closed"), COLORS["amber"] if selected_com else COLORS["off"])

        self.reader_thread = threading.Thread(target=self._read_receiver_output, daemon=True)
        self.reader_thread.start()

    def stop_receiver(self) -> None:
        if not self.process or self.process.poll() is not None:
            self.append_log(self.t("already_stopped"), "warn")
            self._mark_stopped()
            return

        self.append_log(self.t("stopping"), "cmd")
        process = self.process

        def worker() -> None:
            try:
                if os.name == "nt":
                    process.send_signal(signal.CTRL_BREAK_EVENT)
                else:
                    process.terminate()
                process.wait(timeout=2.0)
            except Exception:
                try:
                    process.terminate()
                    process.wait(timeout=1.0)
                except Exception:
                    process.kill()
            self.log_queue.put(("exit", process.poll()))

        threading.Thread(target=worker, daemon=True).start()

    def setup_usb_reverse(self) -> None:
        tcp_port = self._read_port(self.tcp_port_var, self.t("usb_port"))
        if tcp_port is None:
            return

        adb = find_adb()
        if not adb:
            self.append_log(self.t("adb_missing"), "error")
            self.usb_chip.set(self.t("adb_error"), COLORS["red"])
            return

        commands = [
            [adb, "start-server"],
            [adb, "devices", "-l"],
            [adb, "reverse", f"tcp:{tcp_port}", f"tcp:{tcp_port}"],
            [adb, "reverse", "--list"],
        ]
        self.usb_chip.set(self.t("adb_preparing"), COLORS["amber"])
        self._run_commands("USB", commands)

    def run_diagnostics(self) -> None:
        commands: list[list[str]] = [[sys.executable, str(RECEIVER_SCRIPT), "--list-serial"]]
        adb = find_adb()
        if adb:
            commands.insert(0, [adb, "devices", "-l"])
            commands.insert(1, [adb, "reverse", "--list"])
        if os.name == "nt":
            commands.append(
                [
                    "powershell",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    f"Get-NetTCPConnection -LocalPort {self.tcp_port_var.get()} -ErrorAction SilentlyContinue | "
                    "Select-Object LocalAddress,LocalPort,State,OwningProcess | Format-Table -AutoSize",
                ]
            )
        self._run_commands(self.t("diagnostics"), commands)

    def open_log_file(self) -> None:
        if not LOG_FILE.exists():
            LOG_FILE.write_text("", encoding="utf-8")
        try:
            if os.name == "nt":
                os.startfile(LOG_FILE)  # type: ignore[attr-defined]
            else:
                subprocess.Popen(["xdg-open", str(LOG_FILE)])
        except OSError as exc:
            self.append_log(self.t("open_log_failed", error=exc), "error")

    def run_vigembus_repair(self) -> None:
        script = Path(__file__).with_name("repair_vigembus_admin.bat")
        if not script.exists():
            self.append_log(self.t("repair_missing", path=script), "error")
            return
        if os.name != "nt":
            self.append_log(self.t("repair_windows_only"), "error")
            return
        try:
            import ctypes

            result = ctypes.windll.shell32.ShellExecuteW(
                None,
                "runas",
                str(script),
                None,
                str(ROOT_DIR),
                1,
            )
        except Exception as exc:
            self.append_log(self.t("repair_failed", error=exc), "error")
            return
        if result <= 32:
            self.append_log(self.t("repair_failed", error=f"ShellExecute={result}"), "error")
            return
        self.append_log(self.t("repair_prompt"), "warn")

    def append_log(self, text: str, tag: str | None = None) -> None:
        timestamp = time.strftime("%H:%M:%S")
        if not hasattr(self, "log_text"):
            return
        lower = text.lower()
        active_tag = tag
        if active_tag is None:
            if "error" in lower or "hata" in lower or "failed" in lower:
                active_tag = "error"
            elif "warning" in lower or "not ready" in lower or "ignored" in lower:
                active_tag = "warn"
            elif "listening" in lower or "connected" in lower or "ready" in lower:
                active_tag = "ok"

        self.log_text.insert("end", f"{timestamp}  {text}\n", active_tag)
        self.log_text.see("end")
        if int(self.log_text.index("end-1c").split(".")[0]) > 1200:
            self.log_text.delete("1.0", "200.0")
        self._status_from_log(text)

    def _status_from_log(self, line: str) -> None:
        lower = line.lower()
        if "wi-fi udp server listening" in lower:
            self.wifi_chip.set(self.t("listening"), COLORS["green"])
        elif "usb tcp server listening" in lower:
            self.usb_chip.set(self.t("listening"), COLORS["green"])
        elif "usb client connected" in lower:
            self.usb_chip.set(self.t("connected"), COLORS["green"])
        elif "usb client disconnected" in lower:
            self.usb_chip.set(self.t("listening"), COLORS["amber"])
        elif "bluetooth serial listening" in lower:
            self.bt_chip.set(self.com_var.get().strip().upper() or self.t("listening"), COLORS["green"])
        elif "bluetooth serial" in lower and "not ready" in lower:
            self.bt_chip.set(self.t("waiting"), COLORS["amber"])
        elif "could not create virtual xbox" in lower or "vgamepad is not installed" in lower:
            self.receiver_chip.set(f"Xbox {self.t('error')}", COLORS["red"])

    def _read_receiver_output(self) -> None:
        process = self.process
        if not process or not process.stdout:
            return
        try:
            for line in process.stdout:
                self.log_queue.put(("log", line.rstrip()))
        finally:
            code = process.wait()
            self.log_queue.put(("exit", code))

    def _run_commands(self, label: str, commands: list[list[str]]) -> None:
        def worker() -> None:
            self.log_queue.put(("log", self.t("task_started", label=label)))
            last_code = 0
            for command in commands:
                self.log_queue.put(("log", "> " + pretty_cmd(command)))
                try:
                    process = subprocess.Popen(
                        command,
                        cwd=str(ROOT_DIR),
                        stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT,
                        text=True,
                        encoding="utf-8",
                        errors="replace",
                        env={**os.environ, "ZEROPAD_NO_PAUSE": "1"},
                        creationflags=process_flags(),
                    )
                except OSError as exc:
                    self.log_queue.put(("log", self.t("command_failed", error=exc)))
                    last_code = 1
                    break

                assert process.stdout is not None
                for line in process.stdout:
                    self.log_queue.put(("log", line.rstrip()))
                last_code = process.wait()
                self.log_queue.put(("log", f"exit code: {last_code}"))
                if last_code != 0:
                    break
            self.log_queue.put(("task", (label, last_code)))

        threading.Thread(target=worker, daemon=True).start()

    def _pump_queue(self) -> None:
        try:
            while True:
                kind, payload = self.log_queue.get_nowait()
                if kind == "log":
                    self.append_log(str(payload))
                elif kind == "exit":
                    self.append_log(self.t("receiver_stopped", code=payload), "warn")
                    self._mark_stopped()
                elif kind == "ports":
                    registry_ports, all_ports = payload  # type: ignore[misc]
                    values = tuple(all_ports)
                    self.com_combo.configure(values=values)
                    if registry_ports and not self.com_var.get().strip():
                        self.com_var.set(registry_ports[0])
                    if values:
                        self.append_log(self.t("ports_found", ports=", ".join(values)), "ok")
                    else:
                        self.append_log(self.t("ports_missing"), "warn")
                    if registry_ports:
                        self.append_log(self.t("incoming_guess", ports=", ".join(registry_ports)), "ok")
                elif kind == "task":
                    label, code = payload  # type: ignore[misc]
                    if label == "USB":
                        self.usb_chip.set(self.t("adb_ready") if code == 0 else self.t("adb_error"), COLORS["green"] if code == 0 else COLORS["red"])
                    self.append_log(self.t("task_done", label=label, code=code), "ok" if code == 0 else "error")
        except queue.Empty:
            pass
        self.after(80, self._pump_queue)

    def _mark_stopped(self) -> None:
        self.receiver_chip.set(self.t("closed"), COLORS["off"])
        self.wifi_chip.set(self.t("waiting"), COLORS["off"])
        self.usb_chip.set(self.t("waiting"), COLORS["off"])
        self.bt_chip.set(self.t("closed"), COLORS["off"])
        self.process = None

    def _read_port(self, variable: tk.StringVar, label: str) -> int | None:
        try:
            port = int(variable.get().strip())
        except ValueError:
            self.append_log(self.t("port_number", label=label), "error")
            return None
        if not 1 <= port <= 65535:
            self.append_log(self.t("port_range", label=label), "error")
            return None
        return port

    def _close(self) -> None:
        if self.process and self.process.poll() is None:
            self.stop_receiver()
            self.after(500, self.destroy)
        else:
            self.destroy()


def main() -> int:
    app = ZeroPadGui()
    app.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
