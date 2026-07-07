package com.stm32.btcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Controls an STM32 robot over Bluetooth Low Energy (BLE) using a
 * JDY-23 module wired to the STM32 UART.
 *
 * The JDY-23 exposes a transparent serial passthrough on the standard
 * FFE0 service / FFE1 characteristic (same layout as HM-10). Whatever
 * bytes we write to FFE1 come out of the module's TX pin into the STM32.
 *
 * Each command is a 2-byte ASCII string (see the motion command table):
 *   Stand "ll", Forward "ff", Backward "aa", Left "ls", Right "rs",
 *   Sway "rr", Handshake "lu", Sit "ru", Sleep "ld", Stop "pp".
 */
public class MainActivity extends Activity {

    private static final UUID SERVICE_UUID =
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_UUID =
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    // Client Characteristic Configuration Descriptor - enables notifications.
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int REQ_BT_PERMS = 1001;
    private static final long SCAN_MS = 8000;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;

    private TextView statusView;
    private Button connectButton;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Serialize BLE writes: only one may be outstanding at a time.
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private boolean writing = false;
    private volatile String lastSentLabel = "";
    private volatile String lastSentAscii = "";

    // Scanning state.
    private boolean scanning = false;
    private final LinkedHashMap<String, DevInfo> found = new LinkedHashMap<>();
    private ScanCallback scanCallback;

    /** What we learned about one advertising device during a scan. */
    private static final class DevInfo {
        final BluetoothDevice device;
        String name;
        int rssi;
        boolean hasSerialService;
        DevInfo(BluetoothDevice device) { this.device = device; }
    }

    // HTT brand palette (green circuit theme).
    private static final int GREEN       = Color.parseColor("#2E9E4F");
    private static final int GREEN_DARK  = Color.parseColor("#1B7A38");
    private static final int RED         = Color.parseColor("#E53935");
    private static final int PAGE_BG     = Color.parseColor("#EAF1EC");
    private static final int CARD_BG     = Color.parseColor("#FFFFFF");
    private static final int TEXT_DARK   = Color.parseColor("#1D2B22");
    private static final int TEXT_MUTED  = Color.parseColor("#6B7B71");
    private static final int OK_GREEN    = Color.parseColor("#1B5E20");
    private static final int WARN_ORANGE = Color.parseColor("#E65100");
    private static final int ERR_RED     = Color.parseColor("#B71C1C");

    // arrow, label, chinese, command bytes
    private static final String[][] DPAD = {
            {"▲", "Forward",  "前进", "ff"},
            {"◀", "Left",     "左转", "ls"},
            {"■", "Stop",     "制动", "pp"},
            {"▶", "Right",    "右转", "rs"},
            {"▼", "Backward", "后退", "aa"},
    };

    // icon, label, chinese, command bytes
    private static final String[][] ACTIONS = {
            {"🐕", "Stand",     "立正", "ll"},
            {"🎵", "Sway",      "摇摆", "rr"},
            {"🤝", "Handshake", "握手", "lu"},
            {"🦴", "Sit",       "坐",   "ru"},
            {"💤", "Sleep",     "睡觉", "ld"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        BluetoothManagerCompatInit();
    }

    private void BluetoothManagerCompatInit() {
        android.bluetooth.BluetoothManager bm =
                (android.bluetooth.BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = (bm != null) ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            setStatus("No Bluetooth on this device", Color.RED);
            connectButton.setEnabled(false);
        }
    }

    // ---------- UI ----------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAGE_BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);

        root.addView(buildHeader());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        body.setPadding(p, p, p, dp(24));
        root.addView(body);

        body.addView(buildConnectCard());
        body.addView(buildMovementCard());
        body.addView(buildActionsCard());
        return scroll;
    }

    /** Branded header: chip background image + HTT logo + company name. */
    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150)));

        ImageView bg = new ImageView(this);
        bg.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setImageResource(R.drawable.bg_header);
        header.addView(bg);

        // Green scrim for text legibility.
        View scrim = new View(this);
        scrim.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        GradientDrawable grad = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF20E3A1E, 0x66103A20});
        scrim.setBackground(grad);
        header.addView(scrim);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int hp = dp(16);
        bar.setPadding(hp, hp, hp, hp);
        bar.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_VERTICAL));

        ImageView logo = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(58));
        lp.setMargins(0, 0, dp(14), 0);
        logo.setLayoutParams(lp);
        logo.setImageResource(R.drawable.htt_logo);
        bar.addView(logo);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("Robot Control");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText("High Tech Technology Limited");
        sub.setTextColor(0xFFB9F5CC);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        texts.addView(sub);

        TextView sub2 = new TextView(this);
        sub2.setText("STM32 · JDY-23 Bluetooth LE");
        sub2.setTextColor(0xCCFFFFFF);
        sub2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        texts.addView(sub2);

        bar.addView(texts);
        header.addView(bar);
        return header;
    }

    private View buildConnectCard() {
        LinearLayout card = card();

        connectButton = new Button(this);
        connectButton.setText("Scan & Connect");
        connectButton.setAllCaps(false);
        connectButton.setTextColor(Color.WHITE);
        connectButton.setTypeface(Typeface.DEFAULT_BOLD);
        connectButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        connectButton.setBackground(pill(GREEN, GREEN_DARK));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        connectButton.setLayoutParams(blp);
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onConnectClicked(); }
        });
        card.addView(connectButton);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setPadding(dp(2), dp(12), dp(2), dp(2));
        card.addView(statusView);
        setStatus("Not connected", ERR_RED);
        return card;
    }

    private View buildMovementCard() {
        LinearLayout card = card();
        card.addView(sectionLabel("MOVEMENT"));

        // Row 1: Forward
        card.addView(dpadRow(new String[][]{null, DPAD[0], null}));
        // Row 2: Left, Stop, Right
        card.addView(dpadRow(new String[][]{DPAD[1], DPAD[2], DPAD[3]}));
        // Row 3: Backward
        card.addView(dpadRow(new String[][]{null, DPAD[4], null}));
        return card;
    }

    private View dpadRow(String[][] cells) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final String[] c : cells) {
            if (c == null) {
                View spacer = new View(this);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, dp(66), 1f));
                row.addView(spacer);
            } else {
                boolean stop = "Stop".equals(c[1]);
                Button b = dpadButton(c[0], c[1], c[3],
                        stop ? RED : GREEN, stop ? Color.parseColor("#B71C1C") : GREEN_DARK);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(66), 1f);
                int m = dp(5);
                lp.setMargins(m, m, m, m);
                b.setLayoutParams(lp);
                row.addView(b);
            }
        }
        return row;
    }

    private Button dpadButton(final String arrow, final String label,
                              final String ascii, int bg, int pressed) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(arrow + "\n" + label);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackground(pill(bg, pressed));
        // Remove the button's default horizontal padding/min-width so longer
        // labels ("Backward") aren't clipped in the narrow D-pad cells.
        b.setPadding(dp(2), dp(6), dp(2), dp(6));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setEllipsize(null);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendCommand(ascii, label); }
        });
        return b;
    }

    private View buildActionsCard() {
        LinearLayout card = card();
        card.addView(sectionLabel("ACTIONS"));

        LinearLayout row = null;
        for (int i = 0; i < ACTIONS.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                card.addView(row);
            }
            row.addView(actionTile(ACTIONS[i]));
        }
        // Pad the final odd cell so the last tile keeps its column width.
        if (ACTIONS.length % 2 == 1 && row != null) {
            View spacer = new View(this);
            LinearLayout.LayoutParams sp =
                    new LinearLayout.LayoutParams(0, dp(92), 1f);
            spacer.setLayoutParams(sp);
            row.addView(spacer);
        }
        return card;
    }

    /** A modern icon tile: emoji on top, English + Chinese label below. */
    private View actionTile(final String[] a) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);

        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor("#F2F8F4"));
        g.setStroke(dp(1), Color.parseColor("#CFE6D6"));
        g.setCornerRadius(dp(18));
        tile.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x332E9E4F), g, null));
        tile.setClickable(true);
        tile.setFocusable(true);

        TextView icon = new TextView(this);
        icon.setText(a[0]);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        icon.setGravity(Gravity.CENTER);
        tile.addView(icon);

        TextView en = new TextView(this);
        en.setText(a[1]);
        en.setTextColor(GREEN_DARK);
        en.setTypeface(Typeface.DEFAULT_BOLD);
        en.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        en.setGravity(Gravity.CENTER);
        en.setSingleLine(true);
        tile.addView(en);

        TextView zh = new TextView(this);
        zh.setText(a[2]);
        zh.setTextColor(TEXT_MUTED);
        zh.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        zh.setGravity(Gravity.CENTER);
        zh.setSingleLine(true);
        tile.addView(zh);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(92), 1f);
        int m = dp(5);
        lp.setMargins(m, m, m, m);
        tile.setLayoutParams(lp);

        tile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendCommand(a[3], a[1]); }
        });
        return tile;
    }

    // ---------- UI helpers ----------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD_BG);
        g.setCornerRadius(dp(20));
        c.setBackground(g);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        c.setElevation(dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(TEXT_MUTED);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(2), 0, 0, dp(10));
        return t;
    }

    /** Filled rounded button with a ripple, using a pressed-colour ripple. */
    private RippleDrawable pill(int fill, int pressed) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(16));
        return new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF), g, null);
    }

    /** Outlined rounded button (white fill, green border) with ripple. */
    private RippleDrawable pillOutline() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.WHITE);
        g.setStroke(dp(2), GREEN);
        g.setCornerRadius(dp(16));
        return new RippleDrawable(ColorStateList.valueOf(0x332E9E4F), g, null);
    }

    private void setStatus(final String text, final int color) {
        ui.post(new Runnable() {
            @Override public void run() {
                statusView.setText(text);
                statusView.setTextColor(color);
            }
        });
    }

    // ---------- Permissions ----------

    private boolean hasScanConnectPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestBtPermissions() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT};
        } else {
            perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
        requestPermissions(perms, REQ_BT_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode == REQ_BT_PERMS) {
            boolean granted = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) granted = false;
            }
            if (granted) startScan();
            else Toast.makeText(this, "Bluetooth permission is required",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onConnectClicked() {
        if (adapter == null) return;
        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Please turn on Bluetooth first",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (!hasScanConnectPermissions()) {
            requestBtPermissions();
            return;
        }
        startScan();
    }

    // ---------- BLE scan ----------

    private void startScan() {
        if (scanning) return;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Toast.makeText(this, "BLE scanner unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        found.clear();
        scanning = true;
        setStatus("Scanning for BLE devices...", Color.parseColor("#E65100"));

        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                if (d == null || d.getAddress() == null) return;
                DevInfo info = found.get(d.getAddress());
                if (info == null) {
                    info = new DevInfo(d);
                    found.put(d.getAddress(), info);
                }
                info.rssi = result.getRssi();
                ScanRecord rec = result.getScanRecord();
                if (rec != null) {
                    String advName = rec.getDeviceName();
                    if (advName != null && !advName.isEmpty()) info.name = advName;
                    List<ParcelUuid> uuids = rec.getServiceUuids();
                    if (uuids != null) {
                        for (ParcelUuid p : uuids) {
                            if (p.getUuid().equals(SERVICE_UUID)) {
                                info.hasSerialService = true;
                            }
                        }
                    }
                }
                if (info.name == null) {
                    try {
                        String n = d.getName();
                        if (n != null && !n.isEmpty()) info.name = n;
                    } catch (SecurityException ignored) {}
                }
            }
            @Override public void onScanFailed(int errorCode) {
                scanning = false;
                setStatus("Scan failed (code " + errorCode + ")",
                        Color.parseColor("#B71C1C"));
            }
        };

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCallback);
        } catch (SecurityException e) {
            scanning = false;
            setStatus("Scan permission denied", Color.parseColor("#B71C1C"));
            return;
        }

        ui.postDelayed(new Runnable() {
            @Override public void run() { stopScanAndPick(); }
        }, SCAN_MS);
    }

    private void stopScanAndPick() {
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null && scanCallback != null) {
                scanner.stopScan(scanCallback);
            }
        } catch (SecurityException ignored) {}

        if (found.isEmpty()) {
            setStatus("No BLE devices found", Color.parseColor("#B71C1C"));
            new AlertDialog.Builder(this)
                    .setTitle("Nothing found")
                    .setMessage("Make sure the JDY-23 is powered and not already "
                            + "connected to another phone, then scan again.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        final ArrayList<DevInfo> devices = new ArrayList<>(found.values());
        // Most likely robot first: advertises the serial service, then
        // strongest signal (closest to the phone).
        Collections.sort(devices, new Comparator<DevInfo>() {
            @Override public int compare(DevInfo a, DevInfo b) {
                if (a.hasSerialService != b.hasSerialService) {
                    return a.hasSerialService ? -1 : 1;
                }
                return Integer.compare(b.rssi, a.rssi); // higher (closer) first
            }
        });

        final String[] names = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            DevInfo info = devices.get(i);
            String label = (info.name == null ? "(no name)" : info.name);
            String tag = info.hasSerialService ? "  ⭐ serial" : "";
            names[i] = label + tag + "   " + info.rssi + " dBm\n"
                    + info.device.getAddress();
        }
        setStatus("Select a device", Color.parseColor("#E65100"));
        new AlertDialog.Builder(this)
                .setTitle("Choose your JDY-23 (top = most likely)")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        connect(devices.get(which).device);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---------- GATT connection ----------

    private void connect(final BluetoothDevice device) {
        setStatus("Connecting...", Color.parseColor("#E65100"));
        closeGatt();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                gatt = device.connectGatt(this, false, gattCallback,
                        BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(this, false, gattCallback);
            }
        } catch (SecurityException e) {
            setStatus("Connect permission denied", Color.parseColor("#B71C1C"));
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                setStatus("Discovering services...", Color.parseColor("#E65100"));
                try { g.discoverServices(); }
                catch (SecurityException e) {
                    setStatus("Permission denied", Color.parseColor("#B71C1C"));
                }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                writeChar = null;
                synchronized (MainActivity.this) { writing = false; queue.clear(); }
                setStatus("Disconnected", Color.parseColor("#B71C1C"));
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            final String dump = dumpGatt(g);
            BluetoothGattCharacteristic c = findWriteCharacteristic(g);
            if (c == null) {
                setStatus("No writable characteristic found", Color.parseColor("#B71C1C"));
                showGattDialog(dump, "(none)");
                return;
            }
            int props = c.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            writeChar = c;

            // Mirror the original app: enable notifications on the serial
            // characteristic. Some JDY-23 firmware won't pass data until this
            // is done. Notifications enable on the same FFE1 characteristic.
            enableNotifications(g, c);

            final String chosen = c.getUuid().toString();
            setStatus("Connected - ready (writing to "
                    + shortUuid(chosen) + ")", Color.parseColor("#1B5E20"));
            showGattDialog(dump, chosen);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g,
                                          BluetoothGattCharacteristic c, int status) {
            final int st = status;
            ui.post(new Runnable() {
                @Override public void run() {
                    if (st == BluetoothGatt.GATT_SUCCESS) {
                        setStatus("Sent OK: " + lastSentLabel + " (" + lastSentAscii
                                + ")", Color.parseColor("#1B5E20"));
                    } else {
                        setStatus("Write returned error " + st,
                                Color.parseColor("#B71C1C"));
                    }
                }
            });
            synchronized (MainActivity.this) {
                writing = false;
                flushQueue();
            }
        }
    };

    private void enableNotifications(BluetoothGatt g, BluetoothGattCharacteristic c) {
        try {
            int p = c.getProperties();
            boolean canNotify =
                    (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    || (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
            if (!canNotify) return;
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor cccd = c.getDescriptor(CCCD_UUID);
            if (cccd == null) return;
            byte[] value = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value);
            } else {
                cccd.setValue(value);
                g.writeDescriptor(cccd);
            }
        } catch (Exception ignored) {}
    }

    private void showGattDialog(final String dump, final String chosen) {
        ui.post(new Runnable() {
            @Override public void run() {
                TextView tv = new TextView(MainActivity.this);
                tv.setText("Writing to: " + chosen + "\n\n" + dump);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                int pad = dp(16);
                tv.setPadding(pad, pad, pad, pad);
                tv.setTextIsSelectable(true);
                ScrollView sv = new ScrollView(MainActivity.this);
                sv.addView(tv);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("BLE services found")
                        .setView(sv)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    private String dumpGatt(BluetoothGatt g) {
        StringBuilder sb = new StringBuilder();
        List<BluetoothGattService> services;
        try { services = g.getServices(); }
        catch (SecurityException e) { return "permission denied"; }
        if (services == null) return "none";
        for (BluetoothGattService s : services) {
            sb.append("SVC ").append(shortUuid(s.getUuid().toString())).append("\n");
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int p = c.getProperties();
                sb.append("  CHR ").append(shortUuid(c.getUuid().toString()))
                        .append(" [");
                if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) sb.append("W");
                if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) sb.append("w");
                if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) sb.append("N");
                if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) sb.append("I");
                if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) sb.append("R");
                sb.append("]\n");
            }
        }
        return sb.toString();
    }

    /** Show the short 16-bit form (e.g. "ffe1") when it is a standard UUID. */
    private String shortUuid(String uuid) {
        String u = uuid.toLowerCase();
        if (u.startsWith("0000") && u.endsWith("-0000-1000-8000-00805f9b34fb")) {
            return u.substring(4, 8);
        }
        return uuid;
    }

    /**
     * Prefer FFE1 in the FFE0 service. Some cheap modules misreport their
     * property flags, so if FFE1 exists we use it even when it doesn't
     * advertise WRITE. Only as a last resort do we pick another writable one.
     */
    private BluetoothGattCharacteristic findWriteCharacteristic(BluetoothGatt g) {
        List<BluetoothGattService> services;
        try { services = g.getServices(); }
        catch (SecurityException e) { return null; }
        if (services == null) return null;

        BluetoothGattService svc = g.getService(SERVICE_UUID);
        if (svc != null) {
            BluetoothGattCharacteristic c = svc.getCharacteristic(CHAR_UUID);
            if (c != null) return c; // FFE1 present -> trust it
        }
        for (BluetoothGattService s : services) {
            BluetoothGattCharacteristic c = s.getCharacteristic(CHAR_UUID);
            if (c != null) return c;
        }
        for (BluetoothGattService s : services) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if (isWritable(c)) return c;
            }
        }
        return null;
    }

    private boolean isWritable(BluetoothGattCharacteristic c) {
        int p = c.getProperties();
        return (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
    }

    // ---------- Sending ----------

    private void sendCommand(String ascii, String label) {
        if (writeChar == null || gatt == null) {
            Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] bytes;
        try { bytes = ascii.getBytes("US-ASCII"); }
        catch (Exception e) { bytes = ascii.getBytes(); }
        lastSentLabel = label;
        lastSentAscii = ascii;
        synchronized (this) {
            queue.add(bytes);
            flushQueue();
        }
    }

    /** Must be called while holding the monitor on `this`. */
    @SuppressWarnings("deprecation")
    private void flushQueue() {
        if (writing || writeChar == null || gatt == null) return;
        byte[] next = queue.poll();
        if (next == null) return;
        writing = true;
        try {
            boolean ok;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int r = gatt.writeCharacteristic(writeChar, next, writeChar.getWriteType());
                ok = (r == BluetoothGatt.GATT_SUCCESS);
            } else {
                writeChar.setValue(next);
                ok = gatt.writeCharacteristic(writeChar);
            }
            if (!ok) {
                writing = false;
                setStatus("Send failed", Color.parseColor("#B71C1C"));
            }
        } catch (SecurityException e) {
            writing = false;
            setStatus("Send permission denied", Color.parseColor("#B71C1C"));
        }
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanning && scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        closeGatt();
    }

    private void closeGatt() {
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        writeChar = null;
        synchronized (this) { writing = false; queue.clear(); }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
