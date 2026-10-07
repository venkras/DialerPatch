package com.venkras.dialerpatch;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class MainActivity extends Activity {

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
    private final Button[] playButtons = new Button[KEYS.length];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        render();
    }

    @Override
    protected void onDestroy() {
        stopPlayer();
        super.onDestroy();
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
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(32), dp(16), dp(24));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Dialer Patch");
        title.setTextSize(24);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button lang = new Button(this);
        lang.setText(isRu() ? "EN" : "RU");
        lang.setOnClickListener(v -> {
            prefs.edit().putString("lang", isRu() ? "en" : "ru").apply();
            render();
        });
        header.addView(lang);
        root.addView(header);

        TextView info = new TextView(this);
        info.setText(t(
            "Custom sounds for Audio Emoji in Phone by Google. Root access is needed to copy sounds into the dialer. The other person hears the sound during the call.",
            "Свои звуки для Аудиоэмодзи в Phone by Google. Для копирования звуков в звонилку нужен root. Собеседник слышит звук во время звонка."));
        info.setPadding(0, dp(8), 0, dp(8));
        root.addView(info);

        for (int i = 0; i < KEYS.length; i++) {
            addRow(root, i);
        }
        setContentView(scroll);
    }

    private void addRow(LinearLayout root, final int idx) {
        final String key = KEYS[idx];
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(12), 0, dp(4));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(8);
        root.addView(card, cp);

        TextView name = new TextView(this);
        name.setText(ICONS[idx] + "  " + (isRu() ? NAMES_RU[idx] : NAMES_EN[idx]));
        name.setTextSize(18);
        name.setTypeface(null, Typeface.BOLD);
        card.addView(name);

        final String custom = prefs.getString("name_" + key, null);
        TextView status = new TextView(this);
        status.setText(custom != null
            ? t("Custom: ", "Свой: ") + custom
            : t("Original sound", "Оригинальный звук"));
        card.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(buttons);

        Button choose = new Button(this);
        choose.setText(t("Choose", "Выбрать"));
        choose.setOnClickListener(v -> pick(idx));
        buttons.addView(choose, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button play = new Button(this);
        play.setText(t("Play", "Играть"));
        play.setEnabled(new File(getFilesDir(), "sounds/" + key).exists());
        play.setOnClickListener(v -> togglePlay(idx));
        playButtons[idx] = play;
        buttons.addView(play, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button reset = new Button(this);
        reset.setText(t("Reset", "Сбросить"));
        reset.setEnabled(custom != null);
        reset.setOnClickListener(v -> resetSound(idx));
        buttons.addView(reset, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
        toast(t("Copying...", "Копирую..."));
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
                    toast(t("Saved", "Сохранено"));
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
                    toast(t("Reset to original", "Возвращён оригинал"));
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

    private String runSu(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", wrapForRoot(cmd));
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
                playButtons[i].setText(i == playingIdx ? t("Stop", "Стоп") : t("Play", "Играть"));
            }
        }
    }

    private void showError(String err) {
        android.util.Log.e("DialerPatchApp", "failed: " + err);
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(err);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
            .setTitle(t("Failed", "Ошибка"))
            .setView(sv)
            .setPositiveButton("OK", null)
            .show();
    }

    private String wrapForRoot(String cmd) {
        String inner = "sh -c '" + cmd + "'";
        return "if command -v nsenter >/dev/null 2>&1; then nsenter -t 1 -m " + inner
            + "; else " + inner + "; fi";
    }
}
