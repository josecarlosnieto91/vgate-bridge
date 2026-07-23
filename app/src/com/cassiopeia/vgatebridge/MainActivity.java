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

public class MainActivity extends Activity {
    private TextView statusText;
    private TextView selectedLabel;
    private ListView deviceList;
    private Handler handler;
    private SharedPreferences prefs;
    private String selectedMac = null;
    private String selectedName = null;
    private boolean serviceRunning = false;

    private static final String PREFS_NAME = "VgatePrefs";
    private static final String KEY_MAC = "vgate_mac";
    private static final String KEY_NAME = "vgate_name";

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler = new Handler(getMainLooper());
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        selectedMac = prefs.getString(KEY_MAC, null);
        selectedName = prefs.getString(KEY_NAME, null);

        // Register broadcast receiver for service logs
        registerReceiver(serviceReceiver, new IntentFilter(BridgeService.ACTION_STATUS));

        buildUI();

        // Auto-start from boot
        boolean autoStart = getIntent().getBooleanExtra("auto_start", false);
        if (autoStart && selectedMac != null) {
            appendLog("Auto-arranque desde boot...");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { startService(); }
            }, 8000);
        }

        // Request battery optimization exclusion
        requestBatteryExclusion();
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
        title.setText("VGATE BRIDGE v3");
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

        // Buttons row
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
