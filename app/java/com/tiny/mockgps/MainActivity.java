package com.tiny.mockgps;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 10;
    private static final int REQ_LOCATION = 11;

    private EditText etLng;
    private EditText etLat;
    private TextView tvStatus;
    private TextView tvCoord;
    private TextView tvPerm;
    private TextView btnAction;
    private View statusDot;
    private TextView[] chipCoord;
    private TextView[] chipRate;

    private int coordType = 0;      // 0=WGS84 1=GCJ-02 2=BD-09
    private long interval = 1000L;
    private boolean running = false;

    private LinearLayout savedContainer;
    private TextView tvSavedEmpty;
    private TextView btnSave;
    private JSONArray saved = new JSONArray();

    private SharedPreferences sp;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean ok = intent.getBooleanExtra(MockService.EXTRA_RUNNING, false);
            String msg = intent.getStringExtra(MockService.EXTRA_MESSAGE);
            if (ok) {
                setRunning(true, msg);
            } else {
                setRunning(false, null);
                if (!TextUtils.isEmpty(msg)) {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(0xFF0B1220);
        w.setNavigationBarColor(0xFF0B1220);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        }
        setContentView(R.layout.activity_main);

        sp = getSharedPreferences("mock", MODE_PRIVATE);

        etLng = findViewById(R.id.etLng);
        etLat = findViewById(R.id.etLat);
        tvStatus = findViewById(R.id.tvStatus);
        tvCoord = findViewById(R.id.tvCoord);
        tvPerm = findViewById(R.id.tvPerm);
        btnAction = findViewById(R.id.btnAction);
        statusDot = findViewById(R.id.statusDot);
        chipCoord = new TextView[]{findViewById(R.id.chipWgs), findViewById(R.id.chipGcj), findViewById(R.id.chipBd)};
        chipRate = new TextView[]{findViewById(R.id.chipRate200), findViewById(R.id.chipRate1000), findViewById(R.id.chipRate3000)};

        etLng.setText(sp.getString("lng_v3", "120.148500"));
        etLat.setText(sp.getString("lat_v3", "30.258700"));
        coordType = sp.getInt("coord_v3", 1);
        interval = sp.getLong("interval", 1000L);

        for (int i = 0; i < chipCoord.length; i++) {
            final int idx = i;
            chipCoord[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    coordType = idx;
                    sp.edit().putInt("coord_v3", idx).apply();
                    refreshChips();
                }
            });
        }
        long[] rates = {200L, 1000L, 3000L};
        for (int i = 0; i < chipRate.length; i++) {
            final long value = rates[i];
            chipRate[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    interval = value;
                    sp.edit().putLong("interval", value).apply();
                    refreshChips();
                }
            });
        }
        refreshChips();

        btnAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) {
                    stopMock();
                } else {
                    startMock();
                }
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDevSettings();
            }
        });

        requestNotifPermission();

        // 已保存位置
        savedContainer = findViewById(R.id.savedContainer);
        tvSavedEmpty = findViewById(R.id.tvSavedEmpty);
        btnSave = findViewById(R.id.btnSave);
        loadSaved();
        renderSaved();
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSaveDialog();
            }
        });

        showDisclaimerIfNeeded();

        // 点空白处收起键盘（避免键盘挡住下方按钮）
        etLng.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                hideKeyboard();
                return false;
            }
        });
        etLat.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                hideKeyboard();
                return false;
            }
        });
    }

    // ---------------- 已保存位置 ----------------

    private void loadSaved() {
        String raw = sp.getString("saved_list", "[]");
        try {
            saved = new JSONArray(raw);
        } catch (Throwable t) {
            saved = new JSONArray();
        }
    }

    private void persistSaved() {
        sp.edit().putString("saved_list", saved.toString()).apply();
    }

    private String coordName(int type) {
        if (type == 1) {
            return "GCJ-02";
        }
        if (type == 2) {
            return "BD-09";
        }
        return "WGS84";
    }

    private void renderSaved() {
        if (savedContainer == null) {
            return;
        }
        savedContainer.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < saved.length(); i++) {
            final JSONObject o = saved.optJSONObject(i);
            if (o == null) {
                continue;
            }
            View row = inf.inflate(R.layout.item_saved, savedContainer, false);
            ((TextView) row.findViewById(R.id.itemName)).setText(o.optString("name"));
            ((TextView) row.findViewById(R.id.itemCoord)).setText(
                    o.optString("lng") + ", " + o.optString("lat")
                            + " · " + coordName(o.optInt("coord", 1)));
            final int idx = i;
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    importSaved(idx);
                }
            });
            row.findViewById(R.id.itemDelete).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saved.remove(idx);
                    persistSaved();
                    renderSaved();
                }
            });
            savedContainer.addView(row);
        }
        if (tvSavedEmpty != null) {
            tvSavedEmpty.setVisibility(saved.length() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    private void showSaveDialog() {
        if (parseCoord() == null) {
            Toast.makeText(this, R.string.toast_need_coord, Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint(R.string.dialog_name_hint);
        input.setSingleLine(true);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_save_title)
                .setView(input)
                .setPositiveButton(R.string.dialog_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String name = input.getText().toString().trim();
                        if (name.length() == 0) {
                            name = "位置 " + (saved.length() + 1);
                        }
                        try {
                            JSONObject o = new JSONObject();
                            o.put("name", name);
                            o.put("lng", etLng.getText().toString().trim());
                            o.put("lat", etLat.getText().toString().trim());
                            o.put("coord", coordType);
                            saved.put(o);
                            persistSaved();
                            renderSaved();
                            Toast.makeText(MainActivity.this, R.string.toast_saved,
                                    Toast.LENGTH_SHORT).show();
                        } catch (Throwable ignored) {
                        }
                    }
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void importSaved(int idx) {
        JSONObject o = saved.optJSONObject(idx);
        if (o == null) {
            return;
        }
        etLng.setText(o.optString("lng"));
        etLat.setText(o.optString("lat"));
        coordType = o.optInt("coord", 1);
        sp.edit().putInt("coord_v3", coordType).apply();
        refreshChips();
        hideKeyboard();
        Toast.makeText(this, getString(R.string.toast_imported) + "：" + o.optString("name"),
                Toast.LENGTH_SHORT).show();
    }

    private double[] parseCoord() {
        try {
            double lng = Double.parseDouble(etLng.getText().toString().trim());
            double lat = Double.parseDouble(etLat.getText().toString().trim());
            if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
                return null;
            }
            return new double[]{lng, lat};
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- 免责声明 ----------------

    private void showDisclaimerIfNeeded() {
        if (sp.getBoolean("agreed_v1", false)) {
            return;
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_disclaimer, null);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(0x00000000));
        }
        dialog.show();
        view.findViewById(R.id.btnAgree).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sp.edit().putBoolean("agreed_v1", true).apply();
                dialog.dismiss();
            }
        });
        view.findViewById(R.id.btnDisagree).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                finish();
            }
        });
    }

    private void hideKeyboard() {        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
            if (getCurrentFocus() != null) {
                getCurrentFocus().clearFocus();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            View focus = getCurrentFocus();
            if (focus instanceof EditText) {
                int[] pos = new int[2];
                focus.getLocationOnScreen(pos);
                boolean inside = ev.getRawX() >= pos[0] && ev.getRawX() <= pos[0] + focus.getWidth()
                        && ev.getRawY() >= pos[1] && ev.getRawY() <= pos[1] + focus.getHeight();
                if (!inside) {
                    hideKeyboard();
                }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(MockService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
        running = resolveRunning();
        setRunning(running, running ? String.format("%.6f, %.6f",
                sp.getFloat("run_lng", 0f), sp.getFloat("run_lat", 0f)) : null);
        updatePermHint();
    }

    /**
     * 判断服务是否真的还在跑。
     * 服务被强杀时不会走 onDestroy，因此不能只看 running 标记，
     * 要结合服务每轮注入写下的心跳（last_tick）；超过 5 秒没更新即视为已停止。
     */
    private boolean resolveRunning() {
        if (!sp.getBoolean("running", false)) {
            return false;
        }
        long tick = sp.getLong("last_tick", 0L);
        if (tick <= 0L || System.currentTimeMillis() - tick > 5000L) {
            sp.edit().putBoolean("running", false).putLong("last_tick", 0L).apply();
            return false;
        }
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {
        }
    }

    private void refreshChips() {
        for (int i = 0; i < chipCoord.length; i++) {
            chipCoord[i].setSelected(i == coordType);
        }
        long[] rates = {200L, 1000L, 3000L};
        for (int i = 0; i < chipRate.length; i++) {
            chipRate[i].setSelected(rates[i] == interval);
        }
    }

    private void setRunning(boolean run, String coordText) {
        running = run;
        if (run) {
            tvStatus.setText(R.string.status_running);
            statusDot.setBackgroundResource(R.drawable.dot_running);
            btnAction.setText(R.string.btn_stop);
            btnAction.setBackgroundResource(R.drawable.bg_button_stop);
            if (!TextUtils.isEmpty(coordText)) {
                tvCoord.setText(coordText);
            }
        } else {
            tvStatus.setText(R.string.status_idle);
            statusDot.setBackgroundResource(R.drawable.dot_idle);
            btnAction.setText(R.string.btn_start);
            btnAction.setBackgroundResource(R.drawable.bg_button_primary);
            tvCoord.setText("—");
        }
        sp.edit().putBoolean("running", run)
                .putLong("last_tick", run ? System.currentTimeMillis() : 0L)
                .apply();
    }

    private void startMock() {
        String lngStr = etLng.getText().toString().trim();
        String latStr = etLat.getText().toString().trim();
        double lng;
        double lat;
        try {
            lng = Double.parseDouble(lngStr);
            lat = Double.parseDouble(latStr);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.toast_bad_input, Toast.LENGTH_SHORT).show();
            return;
        }
        if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
            Toast.makeText(this, R.string.toast_bad_input, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mockAllowed()) {
            updatePermHint();
            Toast.makeText(this, "请先在「开发者选项 → 选择模拟位置信息应用」中选中「虚拟定位」",
                    Toast.LENGTH_LONG).show();
            openDevSettings();
            return;
        }

        double[] wgs = Coords.toWgs84(lng, lat, coordType);
        double wgsLng = wgs[0];
        double wgsLat = wgs[1];

        sp.edit().putString("lng_v3", lngStr).putString("lat_v3", latStr)
                .putFloat("run_lng", (float) wgsLng).putFloat("run_lat", (float) wgsLat).apply();

        // Android 14：location 类型的前台服务要求应用已获得定位运行时权限
        if (!hasLocationPermission()) {
            sp.edit().putBoolean("pending_start", true).apply();
            requestPermissions(new String[]{
                    "android.permission.ACCESS_FINE_LOCATION",
                    "android.permission.ACCESS_COARSE_LOCATION"}, REQ_LOCATION);
            return;
        }
        dispatchStart(wgsLng, wgsLat);
    }

    private boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return checkSelfPermission("android.permission.ACCESS_FINE_LOCATION")
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission("android.permission.ACCESS_COARSE_LOCATION")
                == PackageManager.PERMISSION_GRANTED;
    }

    private void dispatchStart(double wgsLng, double wgsLat) {
        Intent i = new Intent(this, MockService.class)
                .setAction(MockService.ACTION_START)
                .putExtra(MockService.EXTRA_LNG, wgsLng)
                .putExtra(MockService.EXTRA_LAT, wgsLat)
                .putExtra(MockService.EXTRA_INTERVAL, interval);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            setRunning(false, null);
            return;
        }
        tvCoord.setText(String.format("%.6f, %.6f", wgsLng, wgsLat));
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_SHORT).show();
        setRunning(true, null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean granted = false;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            boolean pending = sp.getBoolean("pending_start", false);
            sp.edit().putBoolean("pending_start", false).apply();
            if (granted && pending) {
                startMock();
            } else if (!granted) {
                Toast.makeText(this, "未授予定位权限，Android 14+ 无法启动定位前台服务",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stopMock() {
        stopService(new Intent(this, MockService.class));
        Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show();
        setRunning(false, null);
    }

    private boolean mockAllowed() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
                if (ops != null) {
                    int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION,
                            Process.myUid(), getPackageName());
                    // 以 AppOps 为准：设置里的 mock_location 只是全局开关，不能代表本应用被选中
                    return mode == AppOpsManager.MODE_ALLOWED;
                }
            }
            return Settings.Secure.getInt(getContentResolver(), "mock_location", 0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private void updatePermHint() {
        boolean ok = mockAllowed();
        tvPerm.setText(ok ? R.string.tip_ready : R.string.tip_not_ready);
        tvPerm.setTextColor(ok ? 0xFF22C55E : 0xFF8FA3C4);
    }

    private void openDevSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            }
        }
    }
}





