package com.zeropad.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String TAG = "ZeroPad";
    private static final String PREFS_NAME = "zeropad_settings";
    private static final String KEY_SETTINGS_VERSION = "settings_version";
    private static final String KEY_VIBRATION = "vibration_enabled";
    private static final String KEY_LOW_LATENCY = "low_latency_enabled";
    private static final String KEY_SHOW_EVENT_LOG = "show_event_log";
    private static final String KEY_DEADZONE = "joystick_deadzone";
    private static final String KEY_CONTROL_SCALE = "control_scale";
    private static final String KEY_TOUCH_BOOST = "touch_boost";
    private static final int SETTINGS_VERSION = 4;
    private static final int WIFI_PORT = 54545;
    private static final int USB_PORT = 54546;
    private static final long EVENT_UI_INTERVAL_MS = 70L;
    private static final int REQUEST_BLUETOOTH_CONNECT = 7;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> buttonDown = new HashSet<>();

    private EditText wifiHostInput;
    private EditText wifiPortInput;
    private EditText usbPortInput;
    private EditText bluetoothTargetInput;
    private TextView statusText;
    private TextView pingText;
    private TextView lastEventText;

    private SharedPreferences prefs;
    private Transport transport;
    private ScheduledExecutorService pingExecutor;
    private Mode currentMode;
    private FrameLayout controlLayer;
    private double lastLeftX = 99.0;
    private double lastLeftY = 99.0;
    private double lastRightX = 99.0;
    private double lastRightY = 99.0;
    private long lastUiEventMs = 0L;
    private String lastStatusValue = "Ready";
    private long lastPingValue = Long.MIN_VALUE;
    private String lastEventValue = "--";
    private boolean settingsVisible = false;
    private boolean editLayoutMode = false;
    private boolean vibrationEnabled = true;
    private boolean lowLatencyEnabled = true;
    private boolean showEventLogEnabled = true;
    private int joystickDeadzonePercent = 5;
    private int controlScalePercent = 100;
    private int touchBoostDp = 18;

    private enum Mode {
        WIFI,
        USB,
        BLUETOOTH,
        TEST
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        loadAppSettings();
        ensureBluetoothPermission();
        showModeScreen();
        String launchMode = getIntent().getStringExtra("mode");
        if (launchMode != null && "test".equalsIgnoreCase(launchMode)) {
            mainHandler.post(() -> startMode(Mode.TEST));
        }
    }

    @Override
    protected void onDestroy() {
        stopTransport();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (currentMode != null) {
            showModeScreen();
        } else if (settingsVisible) {
            showModeScreen();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT && currentMode == null) {
            showModeScreen();
        }
    }

    private void loadAppSettings() {
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int savedVersion = prefs.getInt(KEY_SETTINGS_VERSION, 0);
        if (savedVersion < SETTINGS_VERSION) {
            SharedPreferences.Editor editor = prefs.edit()
                    .putInt(KEY_SETTINGS_VERSION, SETTINGS_VERSION)
                    .putBoolean(KEY_SHOW_EVENT_LOG, false)
                    .putInt(KEY_CONTROL_SCALE, 100)
                    .putInt(KEY_TOUCH_BOOST, 22)
                    .putInt(KEY_DEADZONE, 6);
            clearSavedLayout(editor);
            editor.apply();
        }
        vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, true);
        lowLatencyEnabled = prefs.getBoolean(KEY_LOW_LATENCY, true);
        showEventLogEnabled = prefs.getBoolean(KEY_SHOW_EVENT_LOG, false);
        joystickDeadzonePercent = clampInt(prefs.getInt(KEY_DEADZONE, 6), 0, 25);
        controlScalePercent = clampInt(prefs.getInt(KEY_CONTROL_SCALE, 100), 85, 125);
        touchBoostDp = clampInt(prefs.getInt(KEY_TOUCH_BOOST, 22), 6, 36);
    }

    private void saveBooleanSetting(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    private void saveIntSetting(String key, int value) {
        prefs.edit().putInt(key, value).apply();
    }

    private void showModeScreen() {
        stopTransport();
        currentMode = null;
        settingsVisible = false;
        editLayoutMode = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        enterMenuChrome();

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.rgb(13, 15, 18));
        screen.addView(new MenuBackdropView(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.TRANSPARENT);
        screen.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(20), dp(22), dp(24));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LogoMarkView logo = new LogoMarkView(this);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(86), dp(86));
        logoParams.setMargins(0, 0, 0, dp(8));
        root.addView(logo, logoParams);

        TextView title = text("ZeroPad", 40, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView subtitle = text("Mobile Xbox controller bridge", 15, Color.rgb(176, 185, 200), false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, dp(10));
        root.addView(subtitle, matchWrap());

        TextView modes = text("Wi-Fi  |  USB  |  Bluetooth  |  Offline Test", 12, Color.rgb(105, 232, 154), true);
        modes.setGravity(Gravity.CENTER);
        modes.setPadding(dp(12), dp(7), dp(12), dp(7));
        modes.setBackground(roundRectStroke(Color.rgb(16, 24, 34), dp(18), Color.rgb(47, 86, 67), 1));
        LinearLayout.LayoutParams modesParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        modesParams.setMargins(0, 0, 0, dp(12));
        root.addView(modes, modesParams);

        root.addView(label("PC IP for Wi-Fi"));
        wifiHostInput = field("192.168.1.24", "255.255.255.255");
        root.addView(wifiHostInput, matchWrap());

        root.addView(label("Wi-Fi UDP port"));
        wifiPortInput = field(String.valueOf(WIFI_PORT), String.valueOf(WIFI_PORT));
        wifiPortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(wifiPortInput, matchWrap());

        root.addView(label("USB TCP port"));
        usbPortInput = field(String.valueOf(USB_PORT), String.valueOf(USB_PORT));
        usbPortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(usbPortInput, matchWrap());

        root.addView(label("Bluetooth PC name or MAC"));
        bluetoothTargetInput = field("DESKTOP name or 00:11:22:33:44:55", "");
        root.addView(bluetoothTargetInput, matchWrap());

        TextView paired = text(pairedBluetoothSummary(), 12, Color.rgb(153, 163, 181), false);
        paired.setPadding(0, dp(6), 0, dp(8));
        paired.setSingleLine(false);
        root.addView(paired, matchWrap());
        addPairedBluetoothButtons(root);

        addSpace(root, 10);

        Button wifi = modeButton("Wi-Fi Mode", Color.rgb(21, 137, 78));
        wifi.setOnClickListener(v -> startMode(Mode.WIFI));
        root.addView(wifi, buttonParams());

        Button usb = modeButton("USB Mode", Color.rgb(38, 126, 214));
        usb.setOnClickListener(v -> startMode(Mode.USB));
        root.addView(usb, buttonParams());

        Button bluetooth = modeButton("Bluetooth Mode", Color.rgb(93, 96, 220));
        bluetooth.setOnClickListener(v -> startMode(Mode.BLUETOOTH));
        root.addView(bluetooth, buttonParams());

        Button test = modeButton("Test Mode", Color.rgb(84, 92, 108));
        test.setOnClickListener(v -> startMode(Mode.TEST));
        root.addView(test, buttonParams());

        Button settings = modeButton("Ayarlar", Color.rgb(38, 48, 62));
        settings.setOnClickListener(v -> showSettingsScreen());
        root.addView(settings, buttonParams());

        setContentView(screen);
    }

    private void showSettingsScreen() {
        stopTransport();
        currentMode = null;
        settingsVisible = true;
        editLayoutMode = false;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        enterMenuChrome();

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.rgb(10, 12, 16));
        screen.addView(new MenuBackdropView(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.TRANSPARENT);
        screen.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(24));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(top, matchWrap());

        Button back = smallButton("<");
        back.setOnClickListener(v -> showModeScreen());
        top.addView(back, new LinearLayout.LayoutParams(dp(46), dp(46)));

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(12), 0, 0, 0);
        top.addView(heading, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = text("Ayarlar", 30, Color.WHITE, true);
        heading.addView(title, matchWrap());

        TextView subtitle = text("Kontrol, gecikme ve görünüm ayarları", 13, Color.rgb(171, 183, 201), false);
        heading.addView(subtitle, matchWrap());

        addSpace(root, 14);

        LinearLayout behaviorCard = settingsCard("Oyun Kontrolü");
        root.addView(behaviorCard, matchWrap());

        behaviorCard.addView(settingCheckBox(
                "Titreşim",
                "Tuşa basınca kısa dokunsal geri bildirim verir.",
                vibrationEnabled,
                (buttonView, isChecked) -> {
                    vibrationEnabled = isChecked;
                    saveBooleanSetting(KEY_VIBRATION, isChecked);
                }
        ), matchWrap());

        behaviorCard.addView(settingCheckBox(
                "Düşük gecikme modu",
                "Joystick değişimlerini daha küçük aralıklarla gönderir.",
                lowLatencyEnabled,
                (buttonView, isChecked) -> {
                    lowLatencyEnabled = isChecked;
                    saveBooleanSetting(KEY_LOW_LATENCY, isChecked);
                }
        ), matchWrap());

        behaviorCard.addView(settingCheckBox(
                "Son komutu göster",
                "Oyun ekranının altında son JSON olayını gösterir.",
                showEventLogEnabled,
                (buttonView, isChecked) -> {
                    showEventLogEnabled = isChecked;
                    saveBooleanSetting(KEY_SHOW_EVENT_LOG, isChecked);
                }
        ), matchWrap());

        addSpace(root, 12);

        LinearLayout tuningCard = settingsCard("Hassasiyet");
        root.addView(tuningCard, matchWrap());

        tuningCard.addView(seekSetting(
                "Joystick ölü bölgesi",
                "Ortadaki küçük titreşimleri yok sayar.",
                joystickDeadzonePercent,
                0,
                25,
                "%",
                value -> {
                    joystickDeadzonePercent = value;
                    saveIntSetting(KEY_DEADZONE, value);
                }
        ), matchWrap());

        tuningCard.addView(seekSetting(
                "Tuş boyutu",
                "Oyun ekranındaki kontrolleri büyütür veya küçültür.",
                controlScalePercent,
                85,
                125,
                "%",
                value -> {
                    controlScalePercent = value;
                    saveIntSetting(KEY_CONTROL_SCALE, value);
                }
        ), matchWrap());

        tuningCard.addView(seekSetting(
                "Dokunma alanı",
                "Parmak biraz dışarı kaysa bile tuşun basılı kalmasını sağlar.",
                touchBoostDp,
                6,
                36,
                " dp",
                value -> {
                    touchBoostDp = value;
                    saveIntSetting(KEY_TOUCH_BOOST, value);
                }
        ), matchWrap());

        addSpace(root, 12);

        LinearLayout layoutCard = settingsCard("Tuş Düzeni");
        root.addView(layoutCard, matchWrap());

        TextView layoutHelp = text("Oyun ekranında Düzenle düğmesine basıp tuşları istediğin yere sürükleyebilirsin.", 13, Color.rgb(186, 197, 214), false);
        layoutHelp.setPadding(0, dp(2), 0, dp(10));
        layoutHelp.setSingleLine(false);
        layoutCard.addView(layoutHelp, matchWrap());

        Button resetLayout = modeButton("Tuş düzenini sıfırla", Color.rgb(57, 68, 86));
        resetLayout.setTextSize(15);
        resetLayout.setOnClickListener(v -> resetControlLayout());
        layoutCard.addView(resetLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        ));

        setContentView(screen);
    }

    private void startMode(Mode mode) {
        if (mode == Mode.BLUETOOTH && !hasBluetoothConnectPermission()) {
            ensureBluetoothPermission();
            return;
        }

        stopTransport();
        currentMode = mode;
        lastStatusValue = "Starting...";
        lastPingValue = mode == Mode.TEST ? -1L : Long.MIN_VALUE;
        lastEventValue = "--";
        showGamepadScreen(mode);

        if (mode == Mode.WIFI) {
            String host = wifiHostInput.getText().toString().trim();
            if (host.isEmpty()) {
                host = "255.255.255.255";
            }
            int port = parsePort(wifiPortInput, WIFI_PORT);
            transport = new UdpTransport(host, port);
        } else if (mode == Mode.USB) {
            int port = parsePort(usbPortInput, USB_PORT);
            transport = new TcpTransport("127.0.0.1", port, "USB");
        } else if (mode == Mode.BLUETOOTH) {
            String target = bluetoothTargetInput.getText().toString().trim();
            transport = new BluetoothTransport(target);
        } else {
            transport = new TestTransport();
        }

        transport.start(new TransportListener() {
            @Override
            public void onStatus(String status) {
                postStatus(status);
            }

            @Override
            public void onPing(long ms) {
                postPing(ms);
            }

            @Override
            public void onLog(String message) {
                postLast(message);
            }
        });

        sendJoystick("left", 0.0, 0.0);
        sendJoystick("right", 0.0, 0.0);
        startPingLoop(mode);
    }

    private void showGamepadScreen(Mode mode) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        enterGamepadChrome();

        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(Color.rgb(9, 10, 12));
        screen.addView(new ControllerShellView(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(8), dp(14), dp(7));
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72),
                Gravity.TOP
        );
        screen.addView(top, topParams);

        Button back = smallButton("<");
        back.setOnClickListener(v -> showModeScreen());
        top.addView(back, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.HORIZONTAL);
        statusBox.setGravity(Gravity.CENTER_VERTICAL);
        statusBox.setPadding(dp(12), 0, dp(14), 0);
        statusBox.setBackground(topCardBackground(true));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(0, dp(56), 1f);
        statusParams.setMargins(dp(10), 0, dp(10), 0);
        top.addView(statusBox, statusParams);

        TextView modeIcon = text("Z", 18, Color.rgb(84, 229, 142), true);
        modeIcon.setGravity(Gravity.CENTER);
        modeIcon.setBackground(roundRectStroke(Color.rgb(18, 34, 36), dp(9), Color.rgb(46, 198, 126), 1));
        statusBox.addView(modeIcon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout statusCopy = new LinearLayout(this);
        statusCopy.setOrientation(LinearLayout.VERTICAL);
        statusCopy.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        copyParams.setMargins(dp(12), 0, 0, 0);
        statusBox.addView(statusCopy, copyParams);

        TextView title = text(modeTitle(mode), 15, Color.WHITE, true);
        title.setIncludeFontPadding(false);
        statusCopy.addView(title, matchWrap());

        statusText = text(lastStatusValue, 11, Color.rgb(188, 197, 212), false);
        statusText.setSingleLine(true);
        statusText.setIncludeFontPadding(false);
        statusCopy.addView(statusText, matchWrap());

        pingText = text(formatPingText(lastPingValue, mode), 12, Color.rgb(185, 193, 207), false);
        pingText.setGravity(Gravity.CENTER);
        pingText.setPadding(dp(10), 0, dp(10), 0);
        pingText.setBackground(topCardBackground(false));
        top.addView(pingText, new LinearLayout.LayoutParams(dp(136), dp(56)));

        Button edit = smallButton(editLayoutMode ? "Bitti" : "✎  Düzenle");
        edit.setTextSize(13);
        edit.setOnClickListener(v -> {
            editLayoutMode = !editLayoutMode;
            showGamepadScreen(mode);
        });
        LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(dp(118), dp(56));
        editParams.setMargins(dp(10), 0, 0, 0);
        top.addView(edit, editParams);

        if (editLayoutMode) {
            Button reset = smallButton("Sıfırla");
            reset.setTextSize(13);
            reset.setOnClickListener(v -> {
                resetControlLayout();
                showGamepadScreen(mode);
            });
            LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(dp(96), dp(56));
            resetParams.setMargins(dp(10), 0, 0, 0);
            top.addView(reset, resetParams);
        }

        controlLayer = new FrameLayout(this);
        controlLayer.setClipChildren(false);
        controlLayer.setClipToPadding(false);
        FrameLayout.LayoutParams layerParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        boolean shouldShowEventLog = showEventLogEnabled || mode == Mode.TEST;
        layerParams.setMargins(dp(14), dp(80), dp(14), shouldShowEventLog ? dp(62) : dp(12));
        screen.addView(controlLayer, layerParams);

        Button lt = padButton("LT", Color.rgb(45, 50, 60));
        attachButton(lt, "LT");
        addControl(controlLayer, "lt", lt, 78, 36, 0.15f, 0.02f);

        Button lb = padButton("LB", Color.rgb(62, 69, 82));
        attachButton(lb, "L1");
        addControl(controlLayer, "lb", lb, 78, 36, 0.28f, 0.02f);

        Button rb = padButton("RB", Color.rgb(62, 69, 82));
        attachButton(rb, "R1");
        addControl(controlLayer, "rb", rb, 78, 36, 0.58f, 0.02f);

        Button rt = padButton("RT", Color.rgb(45, 50, 60));
        attachButton(rt, "RT");
        addControl(controlLayer, "rt", rt, 78, 36, 0.71f, 0.02f);

        JoystickView leftStick = new JoystickView(this, "L");
        leftStick.setOnMoveListener((x, y) -> sendJoystick("left", x, y));
        addControl(controlLayer, "left_stick", leftStick, 116, 116, 0.035f, 0.34f);

        addControl(controlLayer, "dpad", createDpad(), 100, 100, 0.235f, 0.58f);

        Button select = padButton("View", Color.rgb(58, 65, 78));
        attachButton(select, "Select");
        addControl(controlLayer, "select", select, 70, 34, 0.39f, 0.34f);

        TextView xboxMark = text("Z", 32, Color.rgb(80, 200, 120), true);
        xboxMark.setGravity(Gravity.CENTER);
        xboxMark.setBackground(padButtonBackground(Color.rgb(26, 58, 42), dp(80), true));
        addControl(controlLayer, "guide", xboxMark, 56, 56, 0.47f, 0.52f);

        Button start = padButton("Menu", Color.rgb(58, 65, 78));
        attachButton(start, "Start");
        addControl(controlLayer, "start", start, 70, 34, 0.54f, 0.34f);

        addControl(controlLayer, "face", createFaceButtons(), 128, 128, 0.91f, 0.25f);

        JoystickView rightStick = new JoystickView(this, "R");
        rightStick.setOnMoveListener((x, y) -> sendJoystick("right", x, y));
        addControl(controlLayer, "right_stick", rightStick, 92, 92, 0.64f, 0.70f);

        if (editLayoutMode) {
            TextView hint = text("Düzenleme modu: kontrolleri sürükle, Bitti ile kaydet.", 12, Color.rgb(210, 222, 238), true);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(12), 0, dp(12), 0);
            hint.setBackground(roundRectStroke(Color.rgb(22, 29, 39), dp(8), Color.rgb(74, 135, 218), 1));
            FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    dp(34),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
            );
            hintParams.setMargins(0, 0, 0, dp(4));
            controlLayer.addView(hint, hintParams);
        }

        if (shouldShowEventLog) {
            lastEventText = text(formatLastEventText(lastEventValue), 11, Color.rgb(190, 200, 214), false);
            lastEventText.setGravity(Gravity.CENTER_VERTICAL);
            lastEventText.setSingleLine(true);
            lastEventText.setTypeface(Typeface.MONOSPACE);
            lastEventText.setPadding(dp(18), 0, dp(18), 0);
            lastEventText.setBackground(topCardBackground(false));
            FrameLayout.LayoutParams eventParams = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(44),
                    Gravity.BOTTOM
            );
            eventParams.setMargins(dp(18), 0, dp(18), dp(12));
            screen.addView(lastEventText, eventParams);
        } else {
            lastEventText = null;
        }

        setContentView(screen);
    }

    private void addControl(FrameLayout layer, String id, View view, int widthDp, int heightDp, float defaultX, float defaultY) {
        FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        holder.setTag(id);
        holder.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        int width = scaledDp(widthDp);
        int height = scaledDp(heightDp);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        layer.addView(holder, params);

        if (editLayoutMode) {
            holder.setPadding(dp(3), dp(3), dp(3), dp(3));
            holder.setBackground(roundRectStroke(Color.argb(90, 36, 47, 62), dp(12), Color.rgb(78, 139, 218), 1));
            holder.setClickable(true);
            holder.setOnTouchListener(new ControlDragTouchListener(holder, id));
            setChildViewsEnabled(holder, false);
            view.setAlpha(0.78f);
        }

        holder.post(() -> placeControl(layer, holder, id, defaultX, defaultY));
    }

    private void placeControl(FrameLayout layer, View view, String id, float defaultX, float defaultY) {
        int maxX = Math.max(0, layer.getWidth() - view.getWidth());
        int maxY = Math.max(0, layer.getHeight() - view.getHeight());
        float xPercent = prefs.getFloat(layoutKey(id, "x"), defaultX);
        float yPercent = prefs.getFloat(layoutKey(id, "y"), defaultY);

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.leftMargin = Math.round(maxX * clamp01(xPercent));
        params.topMargin = Math.round(maxY * clamp01(yPercent));
        view.setLayoutParams(params);
    }

    private void saveControlPosition(String id, View view) {
        if (controlLayer == null) {
            return;
        }
        int maxX = Math.max(1, controlLayer.getWidth() - view.getWidth());
        int maxY = Math.max(1, controlLayer.getHeight() - view.getHeight());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        prefs.edit()
                .putFloat(layoutKey(id, "x"), clamp01(params.leftMargin / (float) maxX))
                .putFloat(layoutKey(id, "y"), clamp01(params.topMargin / (float) maxY))
                .apply();
    }

    private String layoutKey(String id, String axis) {
        return "layout_v4_" + id + "_" + axis;
    }

    private void resetControlLayout() {
        SharedPreferences.Editor editor = prefs.edit();
        clearSavedLayout(editor);
        editor.apply();
        Toast.makeText(this, "Tuş düzeni sıfırlandı", Toast.LENGTH_SHORT).show();
    }

    private void clearSavedLayout(SharedPreferences.Editor editor) {
        String[] ids = new String[]{
                "lt", "lb", "rb", "rt",
                "left_stick", "dpad", "select", "guide", "start",
                "face", "right_stick"
        };
        for (String id : ids) {
            editor.remove(layoutKey(id, "x"));
            editor.remove(layoutKey(id, "y"));
            editor.remove("layout_v3_" + id + "_x");
            editor.remove("layout_v3_" + id + "_y");
            editor.remove("layout_v2_" + id + "_x");
            editor.remove("layout_v2_" + id + "_y");
            editor.remove("layout_" + id + "_x");
            editor.remove("layout_" + id + "_y");
        }
    }

    private void setChildViewsEnabled(View root, boolean enabled) {
        if (!(root instanceof ViewGroup)) {
            root.setEnabled(enabled);
            return;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            child.setEnabled(enabled);
            setChildViewsEnabled(child, enabled);
        }
    }

    private FrameLayout createFaceButtons() {
        FrameLayout frame = new FrameLayout(this);
        int buttonSize = scaledDp(46);

        Button y = faceButton("Y", Color.rgb(230, 184, 40));
        attachButton(y, "Y");
        frame.addView(y, frameParams(buttonSize, buttonSize, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

        Button x = faceButton("X", Color.rgb(52, 137, 225));
        attachButton(x, "X");
        frame.addView(x, frameParams(buttonSize, buttonSize, Gravity.CENTER_VERTICAL | Gravity.LEFT, 0, 0, 0, 0));

        Button b = faceButton("B", Color.rgb(216, 73, 73));
        attachButton(b, "B");
        frame.addView(b, frameParams(buttonSize, buttonSize, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 0, 0));

        Button a = faceButton("A", Color.rgb(50, 168, 91));
        attachButton(a, "A");
        frame.addView(a, frameParams(buttonSize, buttonSize, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

        return frame;
    }

    private FrameLayout createDpad() {
        FrameLayout frame = new FrameLayout(this);
        int buttonWidth = scaledDp(40);
        int buttonHeight = scaledDp(36);

        Button up = dpadButton("^");
        attachButton(up, "DPAD_UP");
        frame.addView(up, frameParams(buttonWidth, buttonHeight, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

        Button left = dpadButton("<");
        attachButton(left, "DPAD_LEFT");
        frame.addView(left, frameParams(buttonWidth, buttonHeight, Gravity.CENTER_VERTICAL | Gravity.LEFT, 0, 0, 0, 0));

        Button right = dpadButton(">");
        attachButton(right, "DPAD_RIGHT");
        frame.addView(right, frameParams(buttonWidth, buttonHeight, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 0, 0));

        Button down = dpadButton("v");
        attachButton(down, "DPAD_DOWN");
        frame.addView(down, frameParams(buttonWidth, buttonHeight, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 0));

        return frame;
    }

    private FrameLayout.LayoutParams frameParams(int width, int height, int gravity, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, gravity);
        params.setMargins(left, top, right, bottom);
        return params;
    }

    private void attachButton(Button button, String name) {
        button.setFocusable(false);
        button.setSoundEffectsEnabled(false);
        button.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            int actionIndex = event.getActionIndex();
            Object tag = view.getTag();
            int activePointerId = tag instanceof Integer ? (Integer) tag : MotionEvent.INVALID_POINTER_ID;

            if (action == MotionEvent.ACTION_DOWN) {
                activePointerId = event.getPointerId(0);
                view.setTag(activePointerId);
                view.getParent().requestDisallowInterceptTouchEvent(true);
                pressButtonView(view, name);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN && activePointerId == MotionEvent.INVALID_POINTER_ID) {
                activePointerId = event.getPointerId(actionIndex);
                view.setTag(activePointerId);
                pressButtonView(view, name);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0) {
                    releaseButtonView(view, name);
                    view.setTag(MotionEvent.INVALID_POINTER_ID);
                    return true;
                }
                if (isInsideExpandedTouchArea(view, event, pointerIndex)) {
                    pressButtonView(view, name);
                } else {
                    releaseButtonView(view, name);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP
                    && activePointerId != MotionEvent.INVALID_POINTER_ID
                    && event.getPointerId(actionIndex) == activePointerId) {
                releaseButtonView(view, name);
                view.setTag(MotionEvent.INVALID_POINTER_ID);
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                releaseButtonView(view, name);
                view.setTag(MotionEvent.INVALID_POINTER_ID);
                view.performClick();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                releaseButtonView(view, name);
                view.setTag(MotionEvent.INVALID_POINTER_ID);
                return true;
            }
            return true;
        });
    }

    private void pressButtonView(View view, String name) {
        if (vibrationEnabled && !buttonDown.contains(name)) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
        view.setAlpha(0.76f);
        view.setScaleX(0.97f);
        view.setScaleY(0.97f);
        sendButton(name, true);
    }

    private void releaseButtonView(View view, String name) {
        view.setAlpha(1.0f);
        view.setScaleX(1.0f);
        view.setScaleY(1.0f);
        sendButton(name, false);
    }

    private boolean isInsideExpandedTouchArea(View view, MotionEvent event, int pointerIndex) {
        int boost = dp(touchBoostDp);
        float x = event.getX(pointerIndex);
        float y = event.getY(pointerIndex);
        return x >= -boost
                && x <= view.getWidth() + boost
                && y >= -boost
                && y <= view.getHeight() + boost;
    }

    private void sendButton(String name, boolean down) {
        boolean alreadyDown = buttonDown.contains(name);
        if (down && alreadyDown) {
            return;
        }
        if (!down && !alreadyDown) {
            return;
        }
        if (down) {
            buttonDown.add(name);
        } else {
            buttonDown.remove(name);
        }

        try {
            JSONObject event = new JSONObject();
            event.put("type", "button");
            event.put("name", name);
            event.put("state", down ? "down" : "up");
            event.put("time", SystemClock.elapsedRealtime());
            emit(event);
        } catch (Exception e) {
            Log.e(TAG, "button json error", e);
        }
    }

    private void sendJoystick(String name, double rawX, double rawY) {
        double x = round3(applyDeadzone(rawX));
        double y = round3(applyDeadzone(rawY));
        double delta = lowLatencyEnabled ? 0.012 : 0.028;

        if ("right".equals(name)) {
            if (Math.abs(x - lastRightX) < delta && Math.abs(y - lastRightY) < delta) {
                return;
            }
            lastRightX = x;
            lastRightY = y;
        } else {
            if (Math.abs(x - lastLeftX) < delta && Math.abs(y - lastLeftY) < delta) {
                return;
            }
            lastLeftX = x;
            lastLeftY = y;
        }

        try {
            JSONObject event = new JSONObject();
            event.put("type", "joystick");
            event.put("name", name);
            event.put("x", x);
            event.put("y", y);
            event.put("time", SystemClock.elapsedRealtime());
            emit(event);
        } catch (Exception e) {
            Log.e(TAG, "joystick json error", e);
        }
    }

    private void sendPing() {
        if (transport == null) {
            return;
        }
        try {
            JSONObject event = new JSONObject();
            event.put("type", "ping");
            event.put("time", SystemClock.elapsedRealtime());
            transport.send(event);
        } catch (Exception e) {
            Log.e(TAG, "ping json error", e);
        }
    }

    private void emit(JSONObject event) {
        String compact = event.toString();
        String type = event.optString("type", "");
        long now = SystemClock.elapsedRealtime();
        boolean shouldUpdateUi = currentMode == Mode.TEST
                || !"joystick".equals(type)
                || now - lastUiEventMs >= EVENT_UI_INTERVAL_MS;
        if (shouldUpdateUi) {
            lastUiEventMs = now;
            postLast(compact);
        }
        if (!"joystick".equals(type)) {
            Log.d(TAG, compact);
        }
        if (transport != null) {
            transport.send(event);
        }
    }

    private void startPingLoop(Mode mode) {
        if (mode == Mode.TEST) {
            postPing(-1);
            return;
        }
        pingExecutor = Executors.newSingleThreadScheduledExecutor();
        pingExecutor.scheduleAtFixedRate(this::sendPing, 350, 500, TimeUnit.MILLISECONDS);
    }

    private void stopTransport() {
        if (pingExecutor != null) {
            pingExecutor.shutdownNow();
            pingExecutor = null;
        }
        if (transport != null) {
            transport.stop();
            transport = null;
        }
        buttonDown.clear();
        lastLeftX = 99.0;
        lastLeftY = 99.0;
        lastRightX = 99.0;
        lastRightY = 99.0;
        lastUiEventMs = 0L;
    }

    private void postStatus(String text) {
        lastStatusValue = text;
        mainHandler.post(() -> {
            if (statusText != null) {
                statusText.setText(text);
            }
        });
        Log.d(TAG, "status: " + text);
    }

    private void postPing(long ms) {
        lastPingValue = ms;
        mainHandler.post(() -> {
            if (pingText == null) {
                return;
            }
            pingText.setText(formatPingText(ms, currentMode));
        });
    }

    private void postLast(String text) {
        lastEventValue = text;
        mainHandler.post(() -> {
            if (lastEventText != null) {
                lastEventText.setText(formatLastEventText(text));
            }
        });
    }

    private String formatLastEventText(String text) {
        return "●  Son:  " + text;
    }

    private String formatPingText(long ms, Mode mode) {
        if (mode == Mode.TEST || ms < 0) {
            return "Ping: offline";
        }
        if (ms == Long.MIN_VALUE) {
            return "Ping: -- ms";
        }
        return String.format(Locale.US, "Ping: %d ms", ms);
    }

    private String modeTitle(Mode mode) {
        if (mode == Mode.WIFI) {
            return "Wi-Fi Mode";
        }
        if (mode == Mode.USB) {
            return "USB Mode";
        }
        if (mode == Mode.BLUETOOTH) {
            return "Bluetooth Mode";
        }
        return "Test Mode";
    }

    private int parsePort(EditText input, int fallback) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value > 0 && value <= 65535) {
                return value;
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private double applyDeadzone(double value) {
        double deadzone = joystickDeadzonePercent / 100.0;
        double abs = Math.abs(value);
        if (abs < deadzone) {
            return 0.0;
        }
        if (deadzone >= 0.99) {
            return value;
        }
        double adjusted = (abs - deadzone) / (1.0 - deadzone);
        return Math.signum(value) * Math.min(1.0, adjusted);
    }

    private float clamp01(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean hasBluetoothConnectPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureBluetoothPermission() {
        if (!hasBluetoothConnectPermission() && Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH_CONNECT);
        }
    }

    private String pairedBluetoothSummary() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return "Bluetooth: this phone has no Bluetooth adapter";
        }
        if (!adapter.isEnabled()) {
            return "Bluetooth: turn Bluetooth on before using Bluetooth Mode";
        }
        if (!hasBluetoothConnectPermission()) {
            return "Bluetooth: permission needed to list paired devices";
        }
        try {
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            if (devices == null || devices.isEmpty()) {
                return "Bluetooth paired devices: none";
            }
            StringBuilder builder = new StringBuilder("Bluetooth paired: ");
            int count = 0;
            for (BluetoothDevice device : devices) {
                if (count > 0) {
                    builder.append(", ");
                }
                builder.append(device.getName()).append(" [").append(device.getAddress()).append("]");
                count++;
                if (count >= 4) {
                    builder.append(", ...");
                    break;
                }
            }
            return builder.toString();
        } catch (SecurityException e) {
            return "Bluetooth: permission needed to list paired devices";
        }
    }

    private void addPairedBluetoothButtons(LinearLayout root) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled() || !hasBluetoothConnectPermission()) {
            return;
        }
        try {
            Set<BluetoothDevice> devices = adapter.getBondedDevices();
            if (devices == null || devices.isEmpty()) {
                return;
            }

            int count = 0;
            for (BluetoothDevice device : devices) {
                String name = device.getName() == null ? "Bluetooth device" : device.getName();
                String address = device.getAddress();
                if (address == null || address.isEmpty()) {
                    continue;
                }
                Button button = modeButton("Use " + name, Color.rgb(42, 48, 58));
                button.setTextSize(14);
                button.setOnClickListener(v -> bluetoothTargetInput.setText(address));
                root.addView(button, compactButtonParams());
                count++;
                if (count >= 5) {
                    break;
                }
            }
        } catch (SecurityException ignored) {
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int scaledDp(int value) {
        int scaled = Math.round(value * (controlScalePercent / 100f));
        return dp(Math.max(1, scaled));
    }

    private void enterMenuChrome() {
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    private void enterGamepadChrome() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private LinearLayout settingsCard(String title) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(14));
        card.setBackground(roundRectStroke(Color.rgb(17, 22, 30), dp(14), Color.rgb(43, 57, 76), 1));

        TextView titleView = text(title, 18, Color.WHITE, true);
        titleView.setPadding(0, 0, 0, dp(8));
        card.addView(titleView, matchWrap());
        return card;
    }

    private LinearLayout settingCheckBox(String title, String description, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(8));

        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(title);
        checkBox.setTextColor(Color.WHITE);
        checkBox.setTextSize(16);
        checkBox.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        checkBox.setChecked(checked);
        checkBox.setOnCheckedChangeListener(listener);
        row.addView(checkBox, matchWrap());

        TextView desc = text(description, 12, Color.rgb(156, 169, 188), false);
        desc.setPadding(dp(36), 0, 0, 0);
        desc.setSingleLine(false);
        row.addView(desc, matchWrap());
        return row;
    }

    private LinearLayout seekSetting(String title, String description, int value, int min, int max, String suffix, IntSettingListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(7), 0, dp(10));

        TextView valueText = text(title + ": " + value + suffix, 15, Color.WHITE, true);
        row.addView(valueText, matchWrap());

        TextView desc = text(description, 12, Color.rgb(156, 169, 188), false);
        desc.setPadding(0, dp(2), 0, dp(4));
        desc.setSingleLine(false);
        row.addView(desc, matchWrap());

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max - min);
        seekBar.setProgress(value - min);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int newValue = min + progress;
                valueText.setText(title + ": " + newValue + suffix);
                if (fromUser) {
                    listener.onChanged(newValue);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                listener.onChanged(min + seekBar.getProgress());
            }
        });
        row.addView(seekBar, matchWrap());
        return row;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sp);
        textView.setTextColor(color);
        textView.setIncludeFontPadding(true);
        if (bold) {
            textView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return textView;
    }

    private TextView label(String value) {
        TextView label = text(value, 13, Color.rgb(170, 184, 204), true);
        label.setPadding(0, dp(10), 0, dp(4));
        return label;
    }

    private EditText field(String hint, String value) {
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setText(value);
        editText.setHint(hint);
        editText.setTextColor(Color.WHITE);
        editText.setHintTextColor(Color.rgb(118, 132, 151));
        editText.setTextSize(16);
        editText.setPadding(dp(12), 0, dp(12), 0);
        editText.setBackground(roundRectStroke(Color.rgb(14, 20, 30), dp(10), Color.rgb(46, 61, 80), 1));
        return editText;
    }

    private Button modeButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(18);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(roundRectStroke(color, dp(12), lighten(color, 34), 1));
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        if (Build.VERSION.SDK_INT >= 21) {
            button.setElevation(dp(2));
            button.setStateListAnimator(null);
        }
        return button;
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(20);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(topCardBackground(false));
        button.setPadding(0, 0, 0, dp(1));
        button.setMinHeight(0);
        button.setMinWidth(0);
        if (Build.VERSION.SDK_INT >= 21) {
            button.setElevation(dp(6));
            button.setStateListAnimator(null);
        }
        return button;
    }

    private Button padButton(String label, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(label.length() > 3 ? 12 : 15);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(padButtonBackground(color, dp(10), false));
        if (Build.VERSION.SDK_INT >= 21) {
            button.setElevation(dp(7));
            button.setStateListAnimator(null);
        }
        return button;
    }

    private Button faceButton(String label, int color) {
        Button button = padButton(label, color);
        button.setTextSize(20);
        button.setBackground(padButtonBackground(color, dp(80), true));
        return button;
    }

    private Button dpadButton(String label) {
        Button button = padButton(label, Color.rgb(43, 49, 59));
        button.setTextSize(16);
        button.setBackground(padButtonBackground(Color.rgb(38, 45, 56), dp(9), false));
        return button;
    }

    private GradientDrawable topCardBackground(boolean accent) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(23, 31, 43), Color.rgb(13, 18, 27)}
        );
        drawable.setCornerRadius(dp(12));
        drawable.setStroke(dp(1), accent ? Color.rgb(50, 214, 133) : Color.rgb(57, 70, 91));
        return drawable;
    }

    private GradientDrawable padButtonBackground(int color, int radius, boolean oval) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{lighten(color, 42), color, darken(color, 18)}
        );
        if (oval) {
            drawable.setShape(GradientDrawable.OVAL);
        } else {
            drawable.setCornerRadius(radius);
        }
        drawable.setStroke(dp(2), lighten(color, 38));
        return drawable;
    }

    private GradientDrawable roundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable roundRectStroke(int color, int radius, int strokeColor, int strokeDp) {
        GradientDrawable drawable = roundRect(color, radius);
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable ovalStroke(int color, int strokeColor, int strokeDp) {
        GradientDrawable drawable = oval(color);
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private int lighten(int color, int amount) {
        int r = Math.min(255, Color.red(color) + amount);
        int g = Math.min(255, Color.green(color) + amount);
        int b = Math.min(255, Color.blue(color) + amount);
        return Color.rgb(r, g, b);
    }

    private int darken(int color, int amount) {
        int r = Math.max(0, Color.red(color) - amount);
        int g = Math.max(0, Color.green(color) - amount);
        int b = Math.max(0, Color.blue(color) - amount);
        return Color.rgb(r, g, b);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams compactButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
        );
        params.setMargins(0, dp(6), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams shoulderParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(34), 1f);
        params.setMargins(dp(5), 0, dp(5), 0);
        return params;
    }

    private LinearLayout.LayoutParams systemParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(76), dp(34));
        params.setMargins(0, 0, 0, 0);
        return params;
    }

    private void addSpace(LinearLayout root, int heightDp) {
        View space = new View(this);
        root.addView(space, new LinearLayout.LayoutParams(1, dp(heightDp)));
    }

    private class ControlDragTouchListener implements View.OnTouchListener {
        private final View target;
        private final String id;
        private float downRawX;
        private float downRawY;
        private int startLeft;
        private int startTop;

        ControlDragTouchListener(View target, String id) {
            this.target = target;
            this.id = id;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (controlLayer == null) {
                return true;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) target.getLayoutParams();
                startLeft = params.leftMargin;
                startTop = params.topMargin;
                if (vibrationEnabled) {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                int maxX = Math.max(0, controlLayer.getWidth() - target.getWidth());
                int maxY = Math.max(0, controlLayer.getHeight() - target.getHeight());
                int nextLeft = clampInt(Math.round(startLeft + event.getRawX() - downRawX), 0, maxX);
                int nextTop = clampInt(Math.round(startTop + event.getRawY() - downRawY), 0, maxY);
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) target.getLayoutParams();
                params.leftMargin = nextLeft;
                params.topMargin = nextTop;
                target.setLayoutParams(params);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                saveControlPosition(id, target);
                view.performClick();
                return true;
            }
            return true;
        }
    }

    private interface IntSettingListener {
        void onChanged(int value);
    }

    private static ExecutorService singleExecutor(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, name);
            thread.setPriority(Thread.MAX_PRIORITY);
            return thread;
        });
    }

    private interface Transport {
        void start(TransportListener listener);

        void send(JSONObject event);

        void stop();
    }

    private interface TransportListener {
        void onStatus(String status);

        void onPing(long ms);

        void onLog(String message);
    }

    private static class TestTransport implements Transport {
        private TransportListener listener;

        @Override
        public void start(TransportListener listener) {
            this.listener = listener;
            listener.onStatus("Offline test mode active");
            listener.onPing(-1);
        }

        @Override
        public void send(JSONObject event) {
            if (listener != null && !"ping".equals(event.optString("type"))) {
                listener.onLog(event.toString());
            }
        }

        @Override
        public void stop() {
            listener = null;
        }
    }

    private static class UdpTransport implements Transport {
        private final String host;
        private final int port;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private ExecutorService sendExecutor;
        private ExecutorService receiveExecutor;
        private DatagramSocket socket;
        private InetAddress targetAddress;
        private TransportListener listener;

        UdpTransport(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public void start(TransportListener listener) {
            this.listener = listener;
            sendExecutor = singleExecutor("ZeroPadUdpSend");
            receiveExecutor = singleExecutor("ZeroPadUdpReceive");
            try {
                targetAddress = InetAddress.getByName(host);
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(100);
                try {
                    socket.setTrafficClass(0x10);
                } catch (Exception ignored) {
                }
                running.set(true);
                listener.onStatus("Wi-Fi UDP ready -> " + host + ":" + port);
                receiveExecutor.execute(this::receiveLoop);
            } catch (Exception e) {
                listener.onStatus("Wi-Fi socket error: " + e.getMessage());
                Log.e(TAG, "udp start error", e);
            }
        }

        @Override
        public void send(JSONObject event) {
            if (!running.get() || sendExecutor == null || socket == null || targetAddress == null) {
                return;
            }
            String raw = event.toString();
            sendExecutor.execute(() -> {
                try {
                    byte[] data = raw.getBytes(StandardCharsets.UTF_8);
                    socket.send(new DatagramPacket(data, data.length, targetAddress, port));
                } catch (Exception e) {
                    if (listener != null && running.get()) {
                        listener.onStatus("Wi-Fi send error: " + e.getMessage());
                    }
                    Log.e(TAG, "udp send error", e);
                }
            });
        }

        @Override
        public void stop() {
            running.set(false);
            if (socket != null) {
                socket.close();
                socket = null;
            }
            if (sendExecutor != null) {
                sendExecutor.shutdownNow();
                sendExecutor = null;
            }
            if (receiveExecutor != null) {
                receiveExecutor.shutdownNow();
                receiveExecutor = null;
            }
        }

        private void receiveLoop() {
            byte[] buffer = new byte[2048];
            while (running.get() && socket != null) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String raw = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    JSONObject object = new JSONObject(raw);
                    if ("pong".equals(object.optString("type"))) {
                        long sentAt = object.optLong("time", -1L);
                        if (sentAt > 0 && listener != null) {
                            listener.onPing(SystemClock.elapsedRealtime() - sentAt);
                        }
                    } else if (listener != null) {
                        listener.onLog("UDP rx " + raw);
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (running.get()) {
                        Log.e(TAG, "udp receive error", e);
                    }
                }
            }
        }
    }

    private static class TcpTransport implements Transport {
        private final String host;
        private final int port;
        private final String label;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final Object writeLock = new Object();
        private ExecutorService ioExecutor;
        private ExecutorService sendExecutor;
        private Socket socket;
        private BufferedWriter writer;
        private TransportListener listener;

        TcpTransport(String host, int port, String label) {
            this.host = host;
            this.port = port;
            this.label = label;
        }

        @Override
        public void start(TransportListener listener) {
            this.listener = listener;
            ioExecutor = singleExecutor("ZeroPad" + label + "Io");
            sendExecutor = singleExecutor("ZeroPad" + label + "Send");
            running.set(true);
            ioExecutor.execute(this::connectLoop);
        }

        @Override
        public void send(JSONObject event) {
            if (!running.get() || sendExecutor == null) {
                return;
            }
            String raw = event.toString();
            sendExecutor.execute(() -> {
                synchronized (writeLock) {
                    if (writer == null) {
                        if (listener != null && !"ping".equals(event.optString("type"))) {
                            listener.onStatus(label + " waiting for connection");
                        }
                        return;
                    }
                    try {
                        writer.write(raw);
                        writer.newLine();
                        writer.flush();
                    } catch (Exception e) {
                        if (listener != null) {
                            listener.onStatus(label + " send error: " + e.getMessage());
                        }
                        closeCurrentSocket();
                        Log.e(TAG, "tcp send error", e);
                    }
                }
            });
        }

        @Override
        public void stop() {
            running.set(false);
            closeCurrentSocket();
            if (ioExecutor != null) {
                ioExecutor.shutdownNow();
                ioExecutor = null;
            }
            if (sendExecutor != null) {
                sendExecutor.shutdownNow();
                sendExecutor = null;
            }
        }

        private void connectLoop() {
            while (running.get()) {
                Socket nextSocket = null;
                try {
                    if (listener != null) {
                        listener.onStatus(label + " connecting to " + host + ":" + port);
                    }
                    nextSocket = new Socket();
                    nextSocket.setTcpNoDelay(true);
                    nextSocket.setPerformancePreferences(0, 1, 0);
                    nextSocket.connect(new InetSocketAddress(host, port), 800);
                    nextSocket.setSoTimeout(100);

                    BufferedWriter nextWriter = new BufferedWriter(new OutputStreamWriter(
                            nextSocket.getOutputStream(),
                            StandardCharsets.UTF_8
                    ));
                    synchronized (writeLock) {
                        socket = nextSocket;
                        writer = nextWriter;
                    }

                    if (listener != null) {
                        listener.onStatus(label + " connected");
                    }

                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            nextSocket.getInputStream(),
                            StandardCharsets.UTF_8
                    ));
                    readLoop(reader);
                } catch (Exception e) {
                    if (listener != null && running.get()) {
                        listener.onStatus(label + " waiting: " + e.getMessage());
                    }
                    if (nextSocket != null) {
                        try {
                            nextSocket.close();
                        } catch (Exception ignored) {
                        }
                    }
                    sleep(450);
                } finally {
                    closeCurrentSocket();
                }
            }
        }

        private void readLoop(BufferedReader reader) {
            while (running.get()) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    JSONObject object = new JSONObject(line);
                    if ("pong".equals(object.optString("type"))) {
                        long sentAt = object.optLong("time", -1L);
                        if (sentAt > 0 && listener != null) {
                            listener.onPing(SystemClock.elapsedRealtime() - sentAt);
                        }
                    } else if (listener != null) {
                        listener.onLog(label + " rx " + line);
                    }
                } catch (SocketTimeoutException ignored) {
                } catch (Exception e) {
                    if (running.get()) {
                        Log.e(TAG, "tcp read error", e);
                    }
                    break;
                }
            }
        }

        private void closeCurrentSocket() {
            synchronized (writeLock) {
                writer = null;
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                    socket = null;
                }
            }
        }

        private void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class BluetoothTransport implements Transport {
        private final String targetText;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final Object writeLock = new Object();
        private ExecutorService ioExecutor;
        private ExecutorService sendExecutor;
        private BluetoothSocket socket;
        private OutputStream outputStream;
        private TransportListener listener;

        BluetoothTransport(String targetText) {
            this.targetText = targetText == null ? "" : targetText.trim();
        }

        @Override
        public void start(TransportListener listener) {
            this.listener = listener;
            ioExecutor = singleExecutor("ZeroPadBtIo");
            sendExecutor = singleExecutor("ZeroPadBtSend");
            running.set(true);
            ioExecutor.execute(this::connectLoop);
        }

        @Override
        public void send(JSONObject event) {
            if (!running.get() || sendExecutor == null) {
                return;
            }
            String raw = event.toString();
            sendExecutor.execute(() -> {
                synchronized (writeLock) {
                    if (outputStream == null) {
                        if (listener != null && !"ping".equals(event.optString("type"))) {
                            listener.onStatus("Bluetooth waiting for PC");
                        }
                        return;
                    }
                    try {
                        outputStream.write((raw + "\n").getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    } catch (Exception e) {
                        if (listener != null) {
                            listener.onStatus("Bluetooth send error: " + e.getMessage());
                        }
                        closeCurrentSocket();
                        Log.e(TAG, "bluetooth send error", e);
                    }
                }
            });
        }

        @Override
        public void stop() {
            running.set(false);
            closeCurrentSocket();
            if (ioExecutor != null) {
                ioExecutor.shutdownNow();
                ioExecutor = null;
            }
            if (sendExecutor != null) {
                sendExecutor.shutdownNow();
                sendExecutor = null;
            }
        }

        private void connectLoop() {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                if (listener != null) {
                    listener.onStatus("Bluetooth not available on this phone");
                }
                return;
            }
            if (!adapter.isEnabled()) {
                if (listener != null) {
                    listener.onStatus("Turn Bluetooth on");
                }
                return;
            }

            while (running.get()) {
                BluetoothSocket nextSocket = null;
                try {
                    BluetoothDevice device = findTargetDevice(adapter);
                    if (device == null) {
                        if (listener != null) {
                            listener.onStatus("Bluetooth PC not paired/found");
                        }
                        sleep(1500);
                        continue;
                    }

                    if (listener != null) {
                        listener.onStatus("Bluetooth connecting to " + device.getName());
                    }
                    adapter.cancelDiscovery();
                    nextSocket = openSocket(device);

                    synchronized (writeLock) {
                        socket = nextSocket;
                        outputStream = nextSocket.getOutputStream();
                    }

                    if (listener != null) {
                        listener.onStatus("Bluetooth connected");
                    }

                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            nextSocket.getInputStream(),
                            StandardCharsets.UTF_8
                    ));
                    readLoop(reader);
                } catch (SecurityException e) {
                    if (listener != null) {
                        listener.onStatus("Bluetooth permission needed");
                    }
                    Log.e(TAG, "bluetooth permission error", e);
                    sleep(1200);
                } catch (Exception e) {
                    if (listener != null && running.get()) {
                        listener.onStatus("Bluetooth waiting: " + e.getMessage());
                    }
                    Log.e(TAG, "bluetooth connect/read error", e);
                    sleep(1200);
                } finally {
                    if (nextSocket != null) {
                        try {
                            nextSocket.close();
                        } catch (Exception ignored) {
                        }
                    }
                    closeCurrentSocket();
                }
            }
        }

        private BluetoothDevice findTargetDevice(BluetoothAdapter adapter) {
            try {
                Set<BluetoothDevice> devices = adapter.getBondedDevices();
                if (devices == null || devices.isEmpty()) {
                    return null;
                }
                BluetoothDevice first = null;
                String wanted = targetText.toLowerCase(Locale.US);
                for (BluetoothDevice device : devices) {
                    if (first == null) {
                        first = device;
                    }
                    if (wanted.isEmpty()) {
                        continue;
                    }
                    String name = device.getName() == null ? "" : device.getName().toLowerCase(Locale.US);
                    String address = device.getAddress() == null ? "" : device.getAddress().toLowerCase(Locale.US);
                    if (name.contains(wanted) || address.equals(wanted)) {
                        return device;
                    }
                }
                return wanted.isEmpty() ? first : null;
            } catch (SecurityException e) {
                return null;
            }
        }

        private BluetoothSocket openSocket(BluetoothDevice device) throws Exception {
            BluetoothSocket secureSocket = null;
            try {
                secureSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                secureSocket.connect();
                return secureSocket;
            } catch (Exception secureError) {
                if (secureSocket != null) {
                    try {
                        secureSocket.close();
                    } catch (Exception ignored) {
                    }
                }
                BluetoothSocket insecureSocket = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                insecureSocket.connect();
                return insecureSocket;
            }
        }

        private void readLoop(BufferedReader reader) {
            while (running.get()) {
                try {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    JSONObject object = new JSONObject(line);
                    if ("pong".equals(object.optString("type"))) {
                        long sentAt = object.optLong("time", -1L);
                        if (sentAt > 0 && listener != null) {
                            listener.onPing(SystemClock.elapsedRealtime() - sentAt);
                        }
                    } else if (listener != null) {
                        listener.onLog("Bluetooth rx " + line);
                    }
                } catch (Exception e) {
                    if (running.get()) {
                        Log.e(TAG, "bluetooth read error", e);
                    }
                    break;
                }
            }
        }

        private void closeCurrentSocket() {
            synchronized (writeLock) {
                outputStream = null;
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                    socket = null;
                }
            }
        }

        private void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class MenuBackdropView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        MenuBackdropView(Activity activity) {
            super(activity);
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(2f);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(1.2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();

            fillPaint.setColor(Color.rgb(17, 20, 25));
            canvas.drawRect(0, 0, w, h, fillPaint);

            linePaint.setColor(Color.argb(80, 57, 68, 84));
            float step = Math.max(40f, w / 9f);
            for (float x = -w; x < w * 1.5f; x += step) {
                canvas.drawLine(x, 0, x + w * 0.36f, h, linePaint);
            }

            fillPaint.setColor(Color.argb(125, 24, 29, 36));
            strokePaint.setColor(Color.argb(150, 58, 71, 88));
            rect.set(w * 0.09f, h * 0.60f, w * 0.91f, h * 0.93f);
            canvas.drawRoundRect(rect, h * 0.08f, h * 0.08f, fillPaint);
            canvas.drawRoundRect(rect, h * 0.08f, h * 0.08f, strokePaint);

            fillPaint.setColor(Color.argb(150, 72, 218, 132));
            rect.set(w * 0.09f, h * 0.58f, w * 0.91f, h * 0.59f);
            canvas.drawRoundRect(rect, 3f, 3f, fillPaint);
        }
    }

    private static class LogoMarkView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        LogoMarkView(Activity activity) {
            super(activity);
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(4f);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            float size = Math.min(w, h);
            float left = (w - size) / 2f;
            float top = (h - size) / 2f;
            rect.set(left + size * 0.06f, top + size * 0.06f, left + size * 0.94f, top + size * 0.94f);

            fillPaint.setColor(Color.rgb(13, 20, 30));
            strokePaint.setColor(Color.rgb(37, 199, 122));
            canvas.drawRoundRect(rect, size * 0.20f, size * 0.20f, fillPaint);
            canvas.drawRoundRect(rect, size * 0.20f, size * 0.20f, strokePaint);

            strokePaint.setColor(Color.argb(140, 74, 163, 255));
            strokePaint.setStrokeWidth(size * 0.05f);
            rect.set(left + size * 0.18f, top + size * 0.18f, left + size * 0.82f, top + size * 0.82f);
            canvas.drawArc(rect, 205f, 250f, false, strokePaint);

            textPaint.setColor(Color.WHITE);
            textPaint.setTextSize(size * 0.48f);
            canvas.drawText("Z", w / 2f, h / 2f + size * 0.17f, textPaint);
        }
    }

    private static class ControllerShellView extends View {
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        ControllerShellView(Activity activity) {
            super(activity);
            fillPaint.setStyle(Paint.Style.FILL);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(3f);
            accentPaint.setStyle(Paint.Style.STROKE);
            accentPaint.setStrokeWidth(2f);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();

            fillPaint.setShader(new LinearGradient(
                    0, 0, 0, h,
                    new int[]{Color.rgb(5, 9, 14), Color.rgb(10, 15, 22), Color.rgb(4, 7, 12)},
                    null,
                    Shader.TileMode.CLAMP
            ));
            canvas.drawRect(0, 0, w, h, fillPaint);
            fillPaint.setShader(null);

            fillPaint.setShader(new RadialGradient(
                    w * 0.50f, h * 0.55f, w * 0.55f,
                    Color.argb(120, 30, 48, 67),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
            ));
            canvas.drawRect(0, 0, w, h, fillPaint);
            fillPaint.setShader(null);

            fillPaint.setShadowLayer(22f, 0f, 10f, Color.argb(180, 0, 0, 0));
            fillPaint.setShader(new LinearGradient(
                    0, h * 0.04f, 0, h * 0.97f,
                    new int[]{Color.rgb(17, 25, 36), Color.rgb(9, 13, 20)},
                    null,
                    Shader.TileMode.CLAMP
            ));
            strokePaint.setColor(Color.rgb(33, 45, 61));
            strokePaint.setStrokeWidth(2f);
            rect.set(w * 0.015f, h * 0.03f, w * 0.985f, h * 0.965f);
            canvas.drawRoundRect(rect, h * 0.018f, h * 0.018f, fillPaint);
            fillPaint.clearShadowLayer();
            fillPaint.setShader(null);
            canvas.drawRoundRect(rect, h * 0.018f, h * 0.018f, strokePaint);

            fillPaint.setShader(new LinearGradient(
                    0, h * 0.24f, 0, h * 0.90f,
                    new int[]{Color.rgb(30, 40, 54), Color.rgb(12, 17, 25)},
                    null,
                    Shader.TileMode.CLAMP
            ));
            strokePaint.setColor(Color.rgb(61, 75, 96));
            strokePaint.setStrokeWidth(3f);
            rect.set(w * 0.03f, h * 0.26f, w * 0.97f, h * 0.88f);
            canvas.drawRoundRect(rect, h * 0.16f, h * 0.16f, fillPaint);
            fillPaint.setShader(null);
            canvas.drawRoundRect(rect, h * 0.16f, h * 0.16f, strokePaint);

            fillPaint.setColor(Color.argb(150, 9, 14, 22));
            rect.set(w * 0.05f, h * 0.36f, w * 0.38f, h * 0.88f);
            canvas.drawRoundRect(rect, h * 0.11f, h * 0.11f, fillPaint);
            rect.set(w * 0.62f, h * 0.36f, w * 0.95f, h * 0.88f);
            canvas.drawRoundRect(rect, h * 0.11f, h * 0.11f, fillPaint);

            fillPaint.setShader(new LinearGradient(
                    0, h * 0.23f, 0, h * 0.29f,
                    new int[]{Color.rgb(38, 50, 65), Color.rgb(15, 21, 31)},
                    null,
                    Shader.TileMode.CLAMP
            ));
            strokePaint.setColor(Color.rgb(54, 66, 84));
            rect.set(w * 0.20f, h * 0.245f, w * 0.80f, h * 0.295f);
            canvas.drawRoundRect(rect, h * 0.012f, h * 0.012f, fillPaint);
            fillPaint.setShader(null);
            canvas.drawRoundRect(rect, h * 0.012f, h * 0.012f, strokePaint);

            fillPaint.setColor(Color.argb(150, 7, 11, 17));
            strokePaint.setColor(Color.rgb(51, 64, 83));
            strokePaint.setStrokeWidth(3f);
            rect.set(w * 0.08f, h * 0.35f, w * 0.31f, h * 0.74f);
            canvas.drawOval(rect, fillPaint);
            canvas.drawOval(rect, strokePaint);

            rect.set(w * 0.75f, h * 0.35f, w * 0.93f, h * 0.74f);
            canvas.drawOval(rect, fillPaint);
            canvas.drawOval(rect, strokePaint);

            rect.set(w * 0.60f, h * 0.53f, w * 0.75f, h * 0.82f);
            canvas.drawOval(rect, fillPaint);
            canvas.drawOval(rect, strokePaint);

            fillPaint.setShader(new LinearGradient(
                    0, h * 0.38f, 0, h * 0.79f,
                    new int[]{Color.rgb(20, 30, 39), Color.rgb(10, 15, 22)},
                    null,
                    Shader.TileMode.CLAMP
            ));
            strokePaint.setColor(Color.rgb(40, 215, 123));
            strokePaint.setStrokeWidth(2f);
            rect.set(w * 0.39f, h * 0.39f, w * 0.61f, h * 0.80f);
            canvas.drawRoundRect(rect, h * 0.055f, h * 0.055f, fillPaint);
            fillPaint.setShader(null);
            canvas.drawRoundRect(rect, h * 0.055f, h * 0.055f, strokePaint);

            accentPaint.setColor(Color.argb(80, 70, 230, 145));
            accentPaint.setStrokeWidth(1.2f);
            for (int i = 0; i < 9; i++) {
                float y = h * (0.51f + i * 0.025f);
                canvas.drawLine(w * 0.43f, y, w * 0.57f, y, accentPaint);
            }
        }
    }

    private static class JoystickView extends View {
        private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private OnMoveListener listener;
        private float normX = 0f;
        private float normY = 0f;
        private int activePointerId = MotionEvent.INVALID_POINTER_ID;

        JoystickView(Activity activity, String label) {
            super(activity);
            this.label = label;
            ringPaint.setStyle(Paint.Style.STROKE);
            textPaint.setColor(Color.rgb(80, 200, 120));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            setFocusable(true);
            setFocusableInTouchMode(false);
        }

        void setOnMoveListener(OnMoveListener listener) {
            this.listener = listener;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float size = Math.min(getWidth(), getHeight());
            float radius = size * 0.42f;
            float travel = radius * 0.68f;
            float knobRadius = radius * 0.42f;
            float knobX = cx + normX * travel;
            float knobY = cy + normY * travel;

            basePaint.setShadowLayer(size * 0.09f, 0f, size * 0.04f, Color.argb(190, 0, 0, 0));
            basePaint.setShader(new RadialGradient(
                    cx, cy, radius * 1.35f,
                    new int[]{Color.rgb(29, 39, 51), Color.rgb(10, 14, 21), Color.rgb(4, 6, 10)},
                    null,
                    Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(cx, cy, radius, basePaint);
            basePaint.clearShadowLayer();
            basePaint.setShader(null);

            ringPaint.setStrokeWidth(size * 0.035f);
            ringPaint.setColor(Color.rgb(66, 82, 108));
            canvas.drawCircle(cx, cy, radius * 0.98f, ringPaint);
            ringPaint.setStrokeWidth(size * 0.014f);
            ringPaint.setColor(Color.rgb(42, 219, 127));
            canvas.drawCircle(cx, cy, radius * 0.72f, ringPaint);

            ringPaint.setStrokeWidth(size * 0.012f);
            ringPaint.setColor(Color.argb(95, 176, 191, 210));
            for (int i = 0; i < 28; i++) {
                double angle = Math.PI * 2.0 * i / 28.0;
                float inner = radius * 0.82f;
                float outer = radius * 0.96f;
                float sx = cx + (float) Math.cos(angle) * inner;
                float sy = cy + (float) Math.sin(angle) * inner;
                float ex = cx + (float) Math.cos(angle) * outer;
                float ey = cy + (float) Math.sin(angle) * outer;
                canvas.drawLine(sx, sy, ex, ey, ringPaint);
            }

            knobPaint.setShadowLayer(size * 0.05f, 0f, size * 0.03f, Color.argb(190, 0, 0, 0));
            knobPaint.setShader(new RadialGradient(
                    knobX - knobRadius * 0.22f,
                    knobY - knobRadius * 0.28f,
                    knobRadius * 1.25f,
                    new int[]{Color.rgb(239, 245, 253), Color.rgb(178, 188, 205), Color.rgb(95, 110, 132)},
                    null,
                    Shader.TileMode.CLAMP
            ));
            canvas.drawCircle(knobX, knobY, knobRadius, knobPaint);
            knobPaint.clearShadowLayer();
            knobPaint.setShader(null);

            textPaint.setTextSize(radius * 0.45f);
            textPaint.setColor(Color.rgb(65, 206, 118));
            canvas.drawText(label, knobX, knobY + radius * 0.17f, textPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                activePointerId = event.getPointerId(0);
                getParent().requestDisallowInterceptTouchEvent(true);
                updateFromPointer(event, 0);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN && activePointerId == MotionEvent.INVALID_POINTER_ID) {
                int pointerIndex = event.getActionIndex();
                activePointerId = event.getPointerId(pointerIndex);
                updateFromPointer(event, pointerIndex);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && activePointerId != MotionEvent.INVALID_POINTER_ID) {
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex >= 0) {
                    updateFromPointer(event, pointerIndex);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_UP
                    && activePointerId != MotionEvent.INVALID_POINTER_ID
                    && event.getPointerId(event.getActionIndex()) == activePointerId) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                resetStick();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                resetStick();
                performClick();
                return true;
            }
            return true;
        }

        private void updateFromPointer(MotionEvent event, int pointerIndex) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = Math.min(getWidth(), getHeight()) * 0.42f;
            float dx = event.getX(pointerIndex) - cx;
            float dy = event.getY(pointerIndex) - cy;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length > radius && length > 0f) {
                dx = dx / length * radius;
                dy = dy / length * radius;
            }
            normX = clamp(dx / radius);
            normY = clamp(dy / radius);
            notifyMove();
            invalidate();
        }

        private void resetStick() {
                normX = 0f;
                normY = 0f;
                notifyMove();
                invalidate();
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        private float clamp(float value) {
            if (value < -1f) {
                return -1f;
            }
            if (value > 1f) {
                return 1f;
            }
            return value;
        }

        private void notifyMove() {
            if (listener != null) {
                listener.onMove(normX, normY);
            }
        }
    }

    private interface OnMoveListener {
        void onMove(float x, float y);
    }
}
