package com.venkras.dialerpatch;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String DIALER_FILES = "/data/user/0/com.google.android.dialer/files";
    private static final String CUSTOM_DIR = DIALER_FILES + "/custom_sounds";
    private static final int REQ_PICK = 1000;

    private static final String[] KEYS = {"applause", "party_popper", "sad", "cry_laugh", "poop", "drumroll"};
    private static final String[] ICONS = {
        "\uD83D\uDC4F", "\uD83C\uDF89", "\uD83D\uDE22", "\uD83D\uDE02", "\uD83D\uDCA9", "\uD83E\uDD41"
    };
    private static final String[] NAMES_EN = {
        "Applause", "Party popper", "Sad", "Cry laugh", "Poop", "Drumroll"
    };
    private static final String[] NAMES_RU = {
        "Аплодисменты", "Хлопушка", "Грустный", "Смех до слёз", "Какашка", "Барабанная дробь"
    };

    private SharedPreferences prefs;
    private MediaPlayer player;
    private int playingIdx = -1;
    private final MaterialButton[] playButtons = new MaterialButton[KEYS.length];
    private LinearLayout list;
    private MaterialButton langButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        applySavedTheme();
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        langButton = findViewById(R.id.lang);
        langButton.setOnClickListener(v -> {
            prefs.edit().putString("lang", isRu() ? "en" : "ru").apply();
            render();
        });
        findViewById(R.id.theme).setOnClickListener(v -> toggleTheme());
        render();
    }

    @Override
    protected void onDestroy() {
        stopPlayer();
        super.onDestroy();
    }

    private void applySavedTheme() {
        String m = prefs.getString("theme", "system");
        int mode = "dark".equals(m) ? AppCompatDelegate.MODE_NIGHT_YES
            : "light".equals(m) ? AppCompatDelegate.MODE_NIGHT_NO
            : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void toggleTheme() {
        int ui = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean night = ui == Configuration.UI_MODE_NIGHT_YES;
        prefs.edit().putString("theme", night ? "light" : "dark").apply();
        applySavedTheme();
    }

    private boolean isRu() {
        return "ru".equals(prefs.getString("lang", "en"));
    }

    private String t(String en, String ru) {
        return isRu() ? ru : en;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void render() {
        stopPlayer();
        langButton.setText(isRu() ? "EN" : "RU");
        list.removeAllViews();
        for (int i = 0; i < KEYS.length; i++) {
            addRow(i);
        }
    }

    private void addRow(final int idx) {
        final String key = KEYS[idx];
        View item = getLayoutInflater().inflate(R.layout.item_sound, list, false);

        ((TextView) item.findViewById(R.id.icon)).setText(ICONS[idx]);
        ((TextView) item.findViewById(R.id.name)).setText(isRu() ? NAMES_RU[idx] : NAMES_EN[idx]);

        final String custom = prefs.getString("name_" + key, null);
        ((TextView) item.findViewById(R.id.status)).setText(
            custom != null ? custom : t("Original", "Оригинал"));

        MaterialButton choose = item.findViewById(R.id.choose);
        choose.setText(t("Choose", "Выбрать"));
        choose.setOnClickListener(v -> pick(idx));

        MaterialButton play = item.findViewById(R.id.play);
        play.setIconResource(R.drawable.ic_play);
        play.setEnabled(new File(getFilesDir(), "sounds/" + key).exists());
        play.setOnClickListener(v -> togglePlay(idx));
        playButtons[idx] = play;

        MaterialButton reset = item.findViewById(R.id.reset);
        reset.setIconResource(R.drawable.ic_reset);
        reset.setEnabled(custom != null);
        reset.setOnClickListener(v -> resetSound(idx));

        list.addView(item);
    }

    private void pick(int idx) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, t("Choose sound", "Выберите звук")), REQ_PICK + idx);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        int idx = requestCode - REQ_PICK;
        if (idx < 0 || idx >= KEYS.length || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        final String key = KEYS[idx];
        new Thread(() -> {
            String fileName = displayName(uri);
            String error = null;
            try {
                copyToLocal(uri, key);
            } catch (Exception e) {
                error = "copy: " + e;
            }
            if (error == null) {
                error = runSu(installCommand(key));
            }
            final String err = error;
            final String shownName = fileName;
            runOnUiThread(() -> {
                if (err == null) {
                    prefs.edit().putString("name_" + key, shownName).apply();
                } else {
                    showError(err);
                }
                render();
            });
        }).start();
    }

    private void resetSound(int idx) {
        final String key = KEYS[idx];
        new Thread(() -> {
            String err = runSu("rm -f " + CUSTOM_DIR + "/" + key + ".ogg");
            if (err == null) {
                new File(getFilesDir(), "sounds/" + key).delete();
            }
            final String e = err;
            runOnUiThread(() -> {
                if (e == null) {
                    prefs.edit().remove("name_" + key).apply();
                } else {
                    showError(e);
                }
                render();
            });
        }).start();
    }

    private String installCommand(String key) {
        String target = CUSTOM_DIR + "/" + key + ".ogg";
        String src = new File(getFilesDir(), "sounds/" + key).getAbsolutePath();
        return "D=" + CUSTOM_DIR + "; U=$(stat -c %U " + DIALER_FILES + ") && "
            + "mkdir -p $D && cp " + src + " " + target
            + " && chown $U:$U $D " + target
            + " && chmod 700 $D && chmod 600 " + target
            + " && (restorecon -R $D || true)";
    }

    private void copyToLocal(Uri uri, String key) throws Exception {
        File dir = new File(getFilesDir(), "sounds");
        dir.mkdirs();
        File out = new File(dir, key);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                throw new Exception("cannot open file");
            }
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) {
                    return c.getString(col);
                }
            }
        } catch (Exception ignored) {
        }
        return "audio";
    }

    private String runSuWith(String suPath, String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(suPath, "-c", wrapForRoot(cmd));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            return code == 0 ? null : (out.length() > 0 ? out.toString().trim() : "su exit code " + code);
        } catch (Exception e) {
            return "su: " + e;
        }
    }

    private void togglePlay(int idx) {
        if (playingIdx == idx) {
            stopPlayer();
            updatePlayButtons();
            return;
        }
        stopPlayer();
        File f = new File(getFilesDir(), "sounds/" + KEYS[idx]);
        if (!f.exists()) {
            return;
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> {
                stopPlayer();
                updatePlayButtons();
            });
            player.prepare();
            player.start();
            playingIdx = idx;
        } catch (Exception e) {
            stopPlayer();
            toast(t("Cannot play: ", "Не удаётся проиграть: ") + e.getMessage());
        }
        updatePlayButtons();
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
        playingIdx = -1;
    }

    private void updatePlayButtons() {
        for (int i = 0; i < playButtons.length; i++) {
            if (playButtons[i] != null) {
                playButtons[i].setIconResource(i == playingIdx ? R.drawable.ic_stop : R.drawable.ic_play);
            }
        }
    }

    private void showError(String err) {
        android.util.Log.e("DialerPatchApp", "failed: " + err);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(err);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(24), dp(12), dp(24), dp(12));
        sv.addView(tv);
        new MaterialAlertDialogBuilder(this)
            .setTitle(t("Failed", "Ошибка"))
            .setView(sv)
            .setPositiveButton("OK", null)
            .show();
    }

    private String wrapForRoot(String cmd) {
        String inner = "sh -c '" + cmd + "'";
        return "if command -v nsenter >/dev/null 2>&1; then nsenter -t 1 -m -- " + inner
            + "; else " + inner + "; fi";
    }

    private String runSu(String cmd) {
        String[] candidates = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su",
            "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su", "su"
        };
        String last = null;
        StringBuilder diag = new StringBuilder();
        for (String c : candidates) {
            String r = runSuWith(c, cmd);
            if (r != null && r.contains("Cannot run program")) {
                last = r;
                diag.append(c).append(" exists=").append(new File(c).exists()).append('\n');
                continue;
            }
            android.util.Log.i("DialerPatchApp", "su used: " + c + ", result: " + r);
            return r;
        }
        return "su not found\n" + diag + last;
    }
}
