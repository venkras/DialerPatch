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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputLayout;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.EditText;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import android.os.Handler;
import android.os.Looper;
import android.widget.SeekBar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

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

    private static final int REQ_PAD = 2000;
    private TabLayout tabs;
    private View emojiPage;
    private View padPage;
    private EditText padSearch;
    private TextView padEmpty;
    private final List<String> padShown = new ArrayList<>();
    private RecyclerView.Adapter<PadHolder> padAdapter;
    private MediaPlayer padPlayer;
    private String padCurrent;
    private View padPlayerView;
    private SeekBar padSeek;
    private TextView padTitle;
    private TextView padTime;
    private MaterialButton padToggle;
    private boolean padSeeking = false;
    private final Handler padHandler = new Handler(Looper.getMainLooper());

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
        setupPad();
        render();
    }

    @Override
    protected void onDestroy() {
        stopPlayer();
        closePadPlayerQuiet();
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
        updatePadTexts();
        list.removeAllViews();
        addModeRow();
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

    private void addModeRow() {
        View item = getLayoutInflater().inflate(R.layout.item_mode, list, false);
        ((TextView) item.findViewById(R.id.mode_label)).setText(
            t("Interrupt previous sound", "Обрывать предыдущий звук"));
        MaterialSwitch sw = item.findViewById(R.id.mode_switch);
        sw.setChecked(prefs.getBoolean("interrupt", false));
        sw.setOnCheckedChangeListener((b, on) -> setInterrupt(on));
        list.addView(item);
    }

    private void setInterrupt(final boolean on) {
        new Thread(() -> {
            String cmd = on ? modeCommand() : "rm -f " + CUSTOM_DIR + "/mode";
            final String err = runSu(cmd);
            runOnUiThread(() -> {
                if (err == null) {
                    prefs.edit().putBoolean("interrupt", on).apply();
                } else {
                    showError(err);
                    render();
                }
            });
        }).start();
    }

    private String modeCommand() {
        String file = CUSTOM_DIR + "/mode";
        return "D=" + CUSTOM_DIR + "; U=$(stat -c %U " + DIALER_FILES + ") && "
            + "mkdir -p $D && chown $U:$U $D && chmod 700 $D"
            + " && echo interrupt > " + file
            + " && chown $U:$U " + file + " && chmod 600 " + file
            + " && (restorecon -R $D || true)";
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
        if (requestCode == REQ_PAD) {
            handlePadResult(resultCode, data);
            return;
        }
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

    private static class PadHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final MaterialButton play;

        PadHolder(View v) {
            super(v);
            name = v.findViewById(R.id.pad_name);
            play = v.findViewById(R.id.pad_play);
        }
    }

    private void setupPad() {
        tabs = findViewById(R.id.tabs);
        emojiPage = findViewById(R.id.emoji_page);
        padPage = findViewById(R.id.pad_page);
        padSearch = findViewById(R.id.pad_search);
        padEmpty = findViewById(R.id.pad_empty);
        RecyclerView rv = findViewById(R.id.pad_list);

        tabs.addTab(tabs.newTab().setText("Emoji"));
        tabs.addTab(tabs.newTab().setText("Soundpad"));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showPage(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        padAdapter = new RecyclerView.Adapter<PadHolder>() {
            @Override
            public PadHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                View row = getLayoutInflater().inflate(R.layout.item_pad, parent, false);
                return new PadHolder(row);
            }

            @Override
            public void onBindViewHolder(PadHolder h, int position) {
                final String file = padShown.get(position);
                h.name.setText(stripExt(file));
                boolean cur = file.equals(padCurrent) && isPadPlaying();
                h.play.setIconResource(cur ? R.drawable.ic_pause : R.drawable.ic_play);
                h.play.setOnClickListener(v -> startPad(file, false));
                h.itemView.setOnClickListener(v -> sendPad(file));
                h.itemView.setOnLongClickListener(v -> {
                    confirmDeletePad(file);
                    return true;
                });
            }

            @Override
            public int getItemCount() {
                return padShown.size();
            }
        };
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(padAdapter);

        padSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                loadPad();
            }
        });
        findViewById(R.id.pad_add).setOnClickListener(v -> pickPad());
        setupPadPlayer();
        showPage(0);
    }

    private void showPage(int pos) {
        emojiPage.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
        padPage.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
        closePadPlayer();
        stopPlayer();
        updatePlayButtons();
    }

    private void updatePadTexts() {
        TabLayout.Tab t0 = tabs.getTabAt(0);
        TabLayout.Tab t1 = tabs.getTabAt(1);
        if (t0 != null) {
            t0.setText(t("Emoji", "Эмодзи"));
        }
        if (t1 != null) {
            t1.setText(t("Soundpad", "Саундпад"));
        }
        ((TextInputLayout) findViewById(R.id.pad_search_layout)).setHint(t("Search sounds", "Поиск по названию"));
        ((MaterialButton) findViewById(R.id.pad_add)).setText(t("Add sounds", "Добавить звуки"));
        ((MaterialSwitch) findViewById(R.id.pad_local)).setText(
            t("Also play on this phone when sent", "Играть и на этом телефоне при отправке"));
        loadPad();
    }

    private File padDir() {
        File d = new File(getFilesDir(), "pad");
        d.mkdirs();
        return d;
    }

    private void loadPad() {
        String q = padSearch.getText() == null ? ""
            : padSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        File[] files = padDir().listFiles();
        List<String> all = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    all.add(f.getName());
                }
            }
        }
        Collections.sort(all, String.CASE_INSENSITIVE_ORDER);
        padShown.clear();
        for (String n : all) {
            if (q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q)) {
                padShown.add(n);
            }
        }
        padAdapter.notifyDataSetChanged();
        padEmpty.setVisibility(padShown.isEmpty() ? View.VISIBLE : View.GONE);
        padEmpty.setText(all.isEmpty()
            ? t("No sounds yet. Tap Add sounds.", "Звуков пока нет. Нажми «Добавить звуки».")
            : t("Nothing found", "Ничего не найдено"));
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static String sanitizeName(String n) {
        String s = n.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        while (s.contains("..")) {
            s = s.replace("..", ".");
        }
        if (s.startsWith(".")) {
            s = "_" + s.substring(1);
        }
        if (s.length() > 80) {
            int dot = s.lastIndexOf('.');
            String ext = (dot > 0 && s.length() - dot <= 8) ? s.substring(dot) : "";
            s = s.substring(0, 80 - ext.length()) + ext;
        }
        return s.isEmpty() ? "sound" : s;
    }

    private void pickPad() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("audio/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PAD);
    }

    private void handlePadResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null) {
            return;
        }
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                uris.add(data.getClipData().getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) {
            return;
        }
        new Thread(() -> {
            int ok = 0;
            String error = null;
            for (Uri u : uris) {
                try {
                    String name = sanitizeName(displayName(u));
                    File out = new File(padDir(), name);
                    try (InputStream in = getContentResolver().openInputStream(u);
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
                    ok++;
                } catch (Exception e) {
                    error = "copy: " + e;
                }
            }
            if (ok > 0) {
                String e2 = runSu(syncPadCommand());
                if (e2 != null) {
                    error = e2;
                }
            }
            final String err = error;
            runOnUiThread(() -> {
                loadPad();
                if (err != null) {
                    showError(err);
                }
            });
        }).start();
    }

    private String syncPadCommand() {
        String src = padDir().getAbsolutePath();
        return "D=" + CUSTOM_DIR + "; U=$(stat -c %U " + DIALER_FILES + ") && "
            + "mkdir -p $D && for f in " + src + "/*; do [ -f \"$f\" ] || continue; "
            + "b=$(basename \"$f\"); "
            + "cp \"$f\" \"$D/pad_$b\" && chown $U:$U \"$D/pad_$b\" && chmod 600 \"$D/pad_$b\" || exit 1; done; "
            + "chown $U:$U $D; chmod 700 $D; (restorecon -R $D || true)";
    }

    private void sendPad(final String file) {
        final String name = "pad_" + file;
        android.util.Log.i("DialerPatchApp", "sendPad " + file + " local=" + prefs.getBoolean("pad_local", false));
        if (prefs.getBoolean("pad_local", false)) {
            startPad(file, true);
        }
        new Thread(() -> {
            String cmd = "D=" + CUSTOM_DIR + "; U=$(stat -c %U " + DIALER_FILES + ") && "
                + "echo \"" + name + "\" > $D/cmd && chown $U:$U $D/cmd && chmod 600 $D/cmd"
                + " && (restorecon $D/cmd || true)";
            final String err = runSu(cmd);
            if (err != null) {
                runOnUiThread(() -> showError(err));
            }
        }).start();
    }

    private void confirmDeletePad(final String file) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(stripExt(file))
            .setMessage(t("Delete this sound?", "Удалить этот звук?"))
            .setPositiveButton(t("Delete", "Удалить"), (d, w) -> {
                if (file.equals(padCurrent)) {
                    closePadPlayer();
                }
                deletePad(file);
            })
            .setNegativeButton(t("Cancel", "Отмена"), null)
            .show();
    }

    private void deletePad(final String file) {
        new Thread(() -> {
            new File(padDir(), file).delete();
            final String e = runSu("rm -f " + CUSTOM_DIR + "/pad_" + file);
            runOnUiThread(() -> {
                loadPad();
                if (e != null) {
                    showError(e);
                }
            });
        }).start();
    }

    private boolean isPadPlaying() {
        try {
            return padPlayer != null && padPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    private final Runnable padTick = new Runnable() {
        @Override
        public void run() {
            updatePadProgress();
            if (isPadPlaying()) {
                padHandler.postDelayed(this, 250);
            }
        }
    };

    private static String fmtTime(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void updatePadProgress() {
        if (padPlayer == null) {
            return;
        }
        try {
            int pos = padPlayer.getCurrentPosition();
            int dur = padPlayer.getDuration();
            if (!padSeeking) {
                padSeek.setProgress(pos);
            }
            padTime.setText(fmtTime(pos) + " / " + fmtTime(dur));
        } catch (Exception ignored) {
        }
    }

    private void setupPadPlayer() {
        padPlayerView = findViewById(R.id.pad_player);
        padSeek = findViewById(R.id.pad_seek);
        padTitle = findViewById(R.id.pad_title);
        padTime = findViewById(R.id.pad_time);
        padToggle = findViewById(R.id.pad_toggle);
        padToggle.setIconResource(R.drawable.ic_play);
        padToggle.setOnClickListener(v -> toggleCurrentPad());
        findViewById(R.id.pad_close).setOnClickListener(v -> closePadPlayer());
        padSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                android.util.Log.i("DialerPatchApp", "seek " + progress + " fromUser=" + fromUser
                    + " playerNull=" + (padPlayer == null));
                if (fromUser && padPlayer != null) {
                    padPlayer.seekTo(progress);
                    padTime.setText(fmtTime(progress) + " / " + fmtTime(sb.getMax()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                padSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                padSeeking = false;
            }
        });
        MaterialSwitch local = findViewById(R.id.pad_local);
        local.setChecked(prefs.getBoolean("pad_local", false));
        local.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("pad_local", on).apply());
    }

    private void startPad(String file, boolean restart) {
        android.util.Log.i("DialerPatchApp", "startPad " + file + " restart=" + restart
            + " cur=" + padCurrent + " playerNull=" + (padPlayer == null));
        if (padPlayer != null && file.equals(padCurrent)) {
            if (restart) {
                padPlayer.seekTo(0);
                padPlayer.start();
                afterPadState();
            } else {
                toggleCurrentPad();
            }
            return;
        }
        closePadPlayerQuiet();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setOnErrorListener((m, what, extra) -> {
                android.util.Log.e("DialerPatchApp", "MediaPlayer error " + what + "/" + extra);
                return false;
            });
            mp.setDataSource(new File(padDir(), file).getAbsolutePath());
            mp.setOnCompletionListener(m -> {
                m.seekTo(0);
                padSeek.setProgress(0);
                afterPadState();
            });
            mp.prepare();
            padPlayer = mp;
            padCurrent = file;
            padSeek.setMax(mp.getDuration());
            padSeek.setProgress(0);
            padTitle.setText(stripExt(file));
            padPlayerView.setVisibility(View.VISIBLE);
            mp.start();
            afterPadState();
        } catch (Exception e) {
            closePadPlayerQuiet();
            toast(t("Cannot play: ", "Не удаётся проиграть: ") + e.getMessage());
        }
    }

    private void toggleCurrentPad() {
        android.util.Log.i("DialerPatchApp", "toggleCurrentPad playerNull=" + (padPlayer == null)
            + " playing=" + isPadPlaying());
        if (padPlayer == null) {
            return;
        }
        if (isPadPlaying()) {
            padPlayer.pause();
        } else {
            padPlayer.start();
        }
        afterPadState();
    }

    private void afterPadState() {
        padToggle.setIconResource(isPadPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
        padHandler.removeCallbacks(padTick);
        padHandler.post(padTick);
        if (padAdapter != null) {
            padAdapter.notifyDataSetChanged();
        }
    }

    private void closePadPlayerQuiet() {
        padHandler.removeCallbacks(padTick);
        if (padPlayer != null) {
            try {
                padPlayer.release();
            } catch (Exception ignored) {
            }
            padPlayer = null;
        }
        padCurrent = null;
    }

    private void closePadPlayer() {
        android.util.Log.i("DialerPatchApp", "closePadPlayer called", new Throwable());
        closePadPlayerQuiet();
        if (padPlayerView != null) {
            padPlayerView.setVisibility(View.GONE);
        }
        if (padAdapter != null) {
            padAdapter.notifyDataSetChanged();
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
