package com.cassiopeia.vgatebridge;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Set;

/**
 * MainActivity — v9.1 "modo coche" con interruptor manual.
 *
 * - AUTO COCHE OFF (por defecto al instalar): UI normal. El usuario pulsa
 *   INICIAR para arrancar el bridge, como siempre.
 * - AUTO COCHE ON (botón en la UI, estado persistente): al abrirse la app
 *   (lanzada por la ROM del vehículo o manualmente) arranca en silencio:
 *     1. BridgeService (BT->TCP) + WakeService (Termux)
 *     2. 2.5s de gracia: si el usuario toca la pantalla, se cancela todo y
 *        se queda en la UI (puede ver logs, detener, configurar).
 *     3. Si nadie toca: vuelve al launcher del coche.
 *     4. Lanza Tailscale (su IPNService no es exportado; única vía es su
 *        MainActivity) para conectar la VPN.
 *     5. Vuelve al launcher del coche.
 */
public class MainActivity extends Activity {
    private TextView statusText;
    private TextView selectedLabel;
    private ListView deviceList;
    private Button carModeBtn;
    private Handler handler;
    private SharedPreferences prefs;
    private String selectedMac = null;
    private String selectedName = null;
    private boolean serviceRunning = false;
    private boolean autoReturnScheduled = false;
    private boolean carMode = false;

    private static final String PREFS_NAME = "VgatePrefs";
    private static final String KEY_MAC = "vgate_mac";
    private static final String KEY_NAME = "vgate_name";
    private static final String KEY_CAR_MODE = "car_mode";

    // Extra para que WakeService (y scripts) pidan "volver a la app anterior"
    // en vez de abrir el launcher: MainActivity hace moveTaskToBack.
    public static final String EXTRA_GO_BACK = "go_back";

    // Ventana para que el usuario cancele el arranque automático tocando la pantalla
    private static final long USER_GRACE_MS = 2500;

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra(BridgeService.EXTRA_STATUS);
            String logMsg = intent.getStringExtra(BridgeService.EXTRA_LOG);

            if (status != null) {
                serviceRunning = BridgeService.STATUS_CONNECTED.equals(status) ||
                                 BridgeService.STATUS_WAITING.equals(status);
                updateStatusBar();
            }
            if (logMsg != null) {
                appendLog(logMsg);
            }
        }
    };

    private final Runnable carModeSequence = new Runnable() {
        @Override
        public void run() {
            // Ventana de gracia terminada sin interacción: volver al launcher.
            // Termux y Tailscale los gestiona WakeService (espera de red incluida).
            autoReturnScheduled = false;
            goHome();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(getMainLooper());
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        selectedMac = prefs.getString(KEY_MAC, null);
        selectedName = prefs.getString(KEY_NAME, null);
        carMode = prefs.getBoolean(KEY_CAR_MODE, false);

        // Register broadcast receiver for service logs
        registerReceiver(serviceReceiver, new IntentFilter(BridgeService.ACTION_STATUS));

        buildUI();

        // Modo coche: si está activado y hay Vgate configurado, arranque
        // silencioso del stack con ventana de cancelación por toque.
        if (carMode && selectedMac != null) {
            carLaunchMode();
        }

        // Request battery optimization exclusion
        requestBatteryExclusion();
    }

    /**
     * Arranque de la tablet como app del vehículo: dispara bridge y Termux,
     * da una ventana de gracia al usuario, y si nadie toca la pantalla vuelve
     * al launcher y lanza Tailscale.
     */
    private void carLaunchMode() {
        appendLog("AUTO COCHE: arrancando stack...");

        // 1. Bridge BT->TCP (foreground service, idempotente)
        try {
            Intent intent = new Intent(this, BridgeService.class);
            intent.setAction(BridgeService.ACTION_START);
            intent.putExtra("mac", selectedMac);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            appendLog("BridgeService lanzado");
        } catch (Exception e) {
            appendLog("Error BridgeService: " + e.getMessage());
        }

        // 2. WakeService -> RUN_COMMAND -> polar_boot_extra.sh (Termux:
        //    sshd, crond, GPS logger, recolector OBD local)
        try {
            Intent wake = new Intent(this, WakeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(wake);
            } else {
                startService(wake);
            }
            appendLog("WakeService lanzado (Termux)");
        } catch (Exception e) {
            appendLog("Error WakeService: " + e.getMessage());
        }

        // 3. Ventana de gracia: si el usuario toca, onUserInteraction cancela.
        autoReturnScheduled = true;
        handler.postDelayed(carModeSequence, USER_GRACE_MS);
    }

    /** Vuelve a la app anterior sin matar nada (moveTaskToBack revela la tarea
     *  anterior: Maps, Spotify, launcher... lo que hubiera debajo). Solo si
     *  falla, cae al launcher del vehículo. */
    private void goHome() {
        try {
            moveTaskToBack(true);
            return;
        } catch (Exception e) {
            // Si no hay tarea anterior, ir al launcher
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Exception e2) {
            // Nada más que hacer
        }
    }

    /** onNewIntent: el WakeService o scripts piden volver a la app anterior
     *  con EXTRA_GO_BACK (evita abrir el launcher y pisar Maps/Spotify). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_GO_BACK, false)) {
            appendLog("GO_BACK: volviendo a la app anterior");
            goHome();
        }
    }

    /** Cualquier interacción del usuario cancela el auto-arranque. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (autoReturnScheduled) {
            autoReturnScheduled = false;
            handler.removeCallbacks(carModeSequence);
            appendLog("Interacción detectada: modo coche cancelado");
        }
    }

    /** Alterna el modo coche (estado persistente). */
    private void toggleCarMode() {
        carMode = !carMode;
        prefs.edit().putBoolean(KEY_CAR_MODE, carMode).apply();
        updateCarModeButton();
        if (carMode) {
            appendLog("AUTO COCHE ACTIVADO: al abrir la app arrancará todo en silencio");
        } else {
            appendLog("AUTO COCHE DESACTIVADO: uso manual normal");
        }
    }

    private void updateCarModeButton() {
        if (carModeBtn != null) {
            if (carMode) {
                carModeBtn.setText("AUTO COCHE: ON");
                carModeBtn.setBackgroundColor(0xFF006600);
            } else {
                carModeBtn.setText("AUTO COCHE: OFF");
                carModeBtn.setBackgroundColor(0xFF333355);
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(serviceReceiver);
        } catch (Exception e) {}
        super.onDestroy();
    }

    private void buildUI() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(20, 20, 20, 20);
        layout.setBackgroundColor(0xFF1A1A2E);

        // Title
        TextView title = new TextView(this);
        title.setText("VGATE BRIDGE v4.2");
        title.setTextSize(20);
        title.setTextColor(0xFF00FF00);
        title.setPadding(10, 10, 10, 20);
        layout.addView(title);

        // Selected device label
        selectedLabel = new TextView(this);
        updateSelectedLabel();
        selectedLabel.setTextSize(14);
        selectedLabel.setTextColor(0xFFAAAAAA);
        selectedLabel.setPadding(10, 5, 10, 15);
        layout.addView(selectedLabel);

        // Status bar
        statusText = new TextView(this);
        updateStatusBar();
        statusText.setTextSize(16);
        statusText.setPadding(10, 10, 10, 10);
        layout.addView(statusText);

        // Paired devices list
        TextView devLabel = new TextView(this);
        devLabel.setText("Dispositivos Bluetooth emparejados:");
        devLabel.setTextSize(14);
        devLabel.setTextColor(0xFF00FF00);
        devLabel.setPadding(10, 20, 10, 5);
        layout.addView(devLabel);

        deviceList = new ListView(this);
        deviceList.setBackgroundColor(0xFF2A2A3E);
        deviceList.setPadding(5, 5, 5, 5);
        layout.addView(deviceList);

        deviceList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                BluetoothDevice dev = (BluetoothDevice) parent.getItemAtPosition(pos);
                selectedMac = dev.getAddress();
                selectedName = dev.getName();
                if (selectedName == null) selectedName = "(sin nombre)";
                prefs.edit()
                    .putString(KEY_MAC, selectedMac)
                    .putString(KEY_NAME, selectedName)
                    .apply();
                updateSelectedLabel();
                appendLog("Seleccionado: " + selectedName + " (" + selectedMac + ")");
            }
        });

        // Buttons row 1
        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        btnRow1.setPadding(0, 10, 0, 5);

        Button scanBtn = new Button(this);
        scanBtn.setText("REFRESCAR BT");
        scanBtn.setBackgroundColor(0xFF333355);
        scanBtn.setTextColor(0xFF00FF00);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showPairedDevices(); }
        });
        btnRow1.addView(scanBtn);

        Button startBtn = new Button(this);
        startBtn.setText("INICIAR");
        startBtn.setBackgroundColor(0xFF006600);
        startBtn.setTextColor(0xFFFFFFFF);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startService(); }
        });
        btnRow1.addView(startBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("DETENER");
        stopBtn.setBackgroundColor(0xFF660000);
        stopBtn.setTextColor(0xFFFFFFFF);
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { stopService(); }
        });
        btnRow1.addView(stopBtn);

        layout.addView(btnRow1);

        // Buttons row 2: modo coche
        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        btnRow2.setPadding(0, 0, 0, 5);

        carModeBtn = new Button(this);
        carModeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { toggleCarMode(); }
        });
        updateCarModeButton();
        btnRow2.addView(carModeBtn);

        layout.addView(btnRow2);

        // Log area
        TextView logLabel = new TextView(this);
        logLabel.setText("Log:");
        logLabel.setTextSize(14);
        logLabel.setTextColor(0xFF00FF00);
        logLabel.setPadding(10, 20, 10, 5);
        layout.addView(logLabel);

        final TextView logArea = new TextView(this);
        logArea.setTag("logArea");
        logArea.setTextSize(10);
        logArea.setTextColor(0xFFCCCCCC);
        logArea.setBackgroundColor(0xFF111122);
        logArea.setPadding(8, 8, 8, 8);
        logArea.setMinLines(10);
        logArea.setMaxLines(20);
        logArea.setVerticalScrollBarEnabled(true);
        layout.addView(logArea);

        setContentView(layout);

        // Show devices on start
        showPairedDevices();

        // Check if service is already running
        checkServiceRunning();
    }

    private void startService() {
        if (selectedMac == null) {
            appendLog("Selecciona primero un dispositivo Bluetooth");
            return;
        }
        Intent intent = new Intent(this, BridgeService.class);
        intent.setAction(BridgeService.ACTION_START);
        intent.putExtra("mac", selectedMac);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        serviceRunning = true;
        updateStatusBar();
        appendLog("Servicio iniciado -> " + selectedName);
    }

    private void stopService() {
        Intent intent = new Intent(this, BridgeService.class);
        intent.setAction(BridgeService.ACTION_STOP);
        startService(intent);
        serviceRunning = false;
        updateStatusBar();
        appendLog("Servicio detenido");
    }

    private void checkServiceRunning() {
        // Simple heuristic: service state resets on fresh open
        serviceRunning = false;
    }

    private void showPairedDevices() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            appendLog("ERROR: No hay Bluetooth en este dispositivo");
            return;
        }

        if (!adapter.isEnabled()) {
            appendLog("Bluetooth apagado. Intentando activar...");
            adapter.enable();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { showPairedDevices(); }
            }, 3000);
            return;
        }

        Set<BluetoothDevice> paired = adapter.getBondedDevices();
        final ArrayList<BluetoothDevice> devices = new ArrayList<>(paired);

        if (devices.isEmpty()) {
            appendLog("No hay dispositivos BT emparejados");
            appendLog("Empareja el Vgate vLinker desde Ajustes > Bluetooth");
            return;
        }

        ArrayAdapter<BluetoothDevice> adapter_list = new ArrayAdapter<BluetoothDevice>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, devices) {
            @Override
            public View getView(int pos, View convertView, android.view.ViewGroup parent) {
                android.widget.LinearLayout ll = new android.widget.LinearLayout(MainActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setPadding(10, 8, 10, 8);

                BluetoothDevice d = getItem(pos);
                String name = d.getName();
                if (name == null) name = "(sin nombre)";

                TextView tv1 = new TextView(MainActivity.this);
                tv1.setText(name);
                tv1.setTextSize(16);
                tv1.setTextColor(0xFFFFFFFF);
                ll.addView(tv1);

                TextView tv2 = new TextView(MainActivity.this);
                tv2.setText(d.getAddress());
                tv2.setTextSize(12);
                tv2.setTextColor(0xFFAAAAAA);
                ll.addView(tv2);

                if (d.getAddress().equals(selectedMac)) {
                    ll.setBackgroundColor(0xFF004400);
                }
                return ll;
            }
        };

        deviceList.setAdapter(adapter_list);
        appendLog(devices.size() + " dispositivo(s) encontrados");
        if (selectedMac != null) {
            appendLog("Seleccionado: " + selectedName + " (" + selectedMac + ")");
        } else {
            appendLog("Toca un dispositivo para seleccionarlo");
        }
    }

    private void requestBatteryExclusion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(
                    android.net.Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    // Activity may not exist on all ROMs
                }
            }
        }
    }

    private void updateSelectedLabel() {
        if (selectedLabel != null) {
            if (selectedMac != null) {
                selectedLabel.setText("Vgate: " + selectedName + "\nMAC: " + selectedMac);
            } else {
                selectedLabel.setText("Vgate: SIN SELECCIONAR");
            }
        }
    }

    private void updateStatusBar() {
        if (statusText != null) {
            String status;
            int color;
            if (serviceRunning) {
                status = "PUENTE ACTIVO :" + BridgeService.TCP_PORT;
                color = 0xFF00FF00;
            } else {
                status = "DETENIDO";
                color = 0xFFFF4444;
            }
            statusText.setText(status);
            statusText.setTextColor(color);
        }
    }

    private void appendLog(final String msg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                LinearLayout layout = (LinearLayout) statusText.getParent();
                if (layout != null) {
                    for (int i = 0; i < layout.getChildCount(); i++) {
                        View v = layout.getChildAt(i);
                        if (v instanceof TextView && v.getTag() != null && "logArea".equals(v.getTag())) {
                            TextView tv = (TextView) v;
                            String current = tv.getText().toString();
                            String[] lines = current.split("\n");
                            StringBuilder sb = new StringBuilder();
                            sb.append(msg).append("\n");
                            int keep = Math.min(lines.length, 50);
                            for (int j = lines.length - keep; j < lines.length; j++) {
                                String l = lines[j].trim();
                                if (!l.isEmpty()) {
                                    sb.append(lines[j]).append("\n");
                                }
                            }
                            tv.setText(sb.toString());
                            break;
                        }
                    }
                }
            }
        });
    }
}
