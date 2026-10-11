package org.nasonmobile.immich;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Unica schermata: stato del server, avvio/arresto, indirizzo, log e diagnostica. */
public class MainActivity extends Activity {
    private static final int C_TEXT = Color.parseColor("#1F2430");
    private static final int C_MUTED = Color.parseColor("#5B6376");
    private static final int C_OK = Color.parseColor("#1B7F3B");
    private static final int C_WARN = Color.parseColor("#B26A00");
    private static final int C_ERR = Color.parseColor("#B3261E");
    private static final int C_ACCENT = Color.parseColor("#3B4BA8");
    private static final int C_BG = Color.parseColor("#EDF0F8");      // sfondo dietro le card
    private static final int C_CARD = Color.parseColor("#FFFFFF");    // sfondo delle card
    private static final int C_SURFACE = Color.parseColor("#F1F3F9"); // campi/spinner/log dentro una card
    private static final int REQ_STORAGE = 100;
    private static final int REQ_STORAGE_DCIM = 101;

    /** in primo piano: la finestra di conferma di un aggiornamento si può aprire subito (vedi Updater.onStatus) */
    static volatile boolean resumed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Cfg cfg;

    private TextView status;
    private TextView detail;
    private TextView urls;
    private TextView health;
    private TextView storage;
    private TextView exportStatus;
    private TextView pruneStatus;
    private TextView backupStatus;
    private TextView batteryStatus;
    private TextView dcimStatus;
    private TextView missingStatus;
    private TextView updateStatus;
    private Button updateBtn;
    private String installedVersion = "";
    private Button presetPixelBtn;
    private Button presetPrivateBtn;
    private TextView presetCaption;
    private CheckBox pruneEmergencyBox;
    private TextView pruneEmergencyNote;
    private CheckBox dcimBox;
    private EditText apiKeyField;
    private TextView logView;
    private ProgressBar bar;
    private Button mainBtn;
    private Button openBtn;
    private Button backupBtn;
    private Spinner source;
    private String diagText = "";
    private String lastLog = "";
    // per sfumare il colore dello stato invece di farlo scattare (vedi refresh() e animateColor())
    private boolean statusColorShown;
    private int shownStatusColor;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        cfg = new Cfg(this);
        setContentView(buildUi());
        // ricreata (rotazione, ritorno dopo che Android l'ha chiusa): i comandi dell'intent sono già stati eseguiti
        if (b == null) handleIntent(getIntent());
    }

    /** L'attività è singleTask: se è già aperta, "adb shell am start ..." arriva qui invece che in onCreate. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * Comandi da adb (comodi anche a telefono bloccato):
     * "--ez start true" avvia il server; "--ez export_dry true" prova a secco della copia in galleria (solo conteggi);
     * "--ez export_test true [--el export_test_ms MILLISECONDI]" crea l'immagine di prova in DCIM/Immich;
     * "--ez dcim_originals true|false" interruttore degli originali in DCIM (serve il permesso Memoria, da adb:
     * pm grant org.nasonmobile.immich android.permission.WRITE_EXTERNAL_STORAGE); "--ez missing_cleanup true|false"
     * pulizia notturna delle foto senza file; "--ez missing_cleanup_now true" la fa subito; "--ez restart true"
     * riavvia il server (anche dopo gli interruttori, che si applicano all'avvio); "--ez update_check true" controlla
     * subito gli aggiornamenti, con la notifica; "--es update_pretend_installed v3.3.0" finge installata una versione
     * più vecchia, per provare il flusso dell'aggiornamento (vale finché l'app non si chiude).
     */
    private void handleIntent(final Intent in) {
        if (in == null) return;
        if (in.hasExtra("update_pretend_installed")) {
            String p = in.getStringExtra("update_pretend_installed");
            Updater.pretendInstalled = p == null ? "" : p.trim();
        }
        if (in.getBooleanExtra("update_check", false)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    android.util.Log.i(Cfg.TAG, "update check from adb: " + Updater.checkNow(cfg, true));
                }
            }, "immich-update").start();
        }
        // dalla notifica "è disponibile": si ricontrolla (il processo può essere ripartito) e si propone
        if (in.getBooleanExtra("update_install", false)) checkThenOffer();
        if (in.hasExtra("dcim_originals")) {
            boolean on = in.getBooleanExtra("dcim_originals", false);
            DcimMode.onSwitch(cfg, on);
            if (dcimBox != null) dcimBox.setChecked(on);
            android.util.Log.i(Cfg.TAG, "originals in DCIM from adb: " + on);
        }
        if (in.hasExtra("missing_cleanup")) {
            cfg.prefs.edit().putBoolean("missing_cleanup", in.getBooleanExtra("missing_cleanup", false)).apply();
        }
        if (in.getBooleanExtra("missing_cleanup_now", false)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    MissingCleaner.runNow(Stack.I, cfg);
                }
            }, "immich-missing").start();
        }
        if (in.getBooleanExtra("restart", false)) restartServer();
        // "--es prune_api_key VALORE": la salva passando dal codice dell'app (mai scrivere shared_prefs da fuori
        // mentre l'app è viva: un apply() successivo dell'app la sovrascriverebbe con la copia in memoria).
        if (in.hasExtra("prune_api_key")) {
            final String key = in.getStringExtra("prune_api_key").trim();
            cfg.prefs.edit().putString("prune_api_key", key).apply();
            if (apiKeyField != null) apiKeyField.setText(key);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String r = Pruner.testApiKey(cfg, key);
                    android.util.Log.i(Cfg.TAG, "API key saved from adb; test: " + r);
                }
            }).start();
        }
        if (in.getBooleanExtra("export_purge", false) || in.getBooleanExtra("export_purge_all", false)) {
            final boolean all = in.getBooleanExtra("export_purge_all", false);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int n = Exporter.cleanup(cfg, all);
                    android.util.Log.i(Cfg.TAG, "gallery copies cleanup (" + (all ? "all" : "expired") + "): deleted " + n);
                }
            }).start();
        }
        if (in.getBooleanExtra("start", false)) {
            startForegroundService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_START));
        }
        if (in.getBooleanExtra("export_dry", false)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String msg;
                    try {
                        msg = Exporter.dryRun(Stack.I, cfg);
                    } catch (Exception e) {
                        msg = "error: " + e;
                    }
                    android.util.Log.i(Cfg.TAG, "dry run: " + msg);
                }
            }).start();
        }
        if (in.getBooleanExtra("export_test", false)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String msg;
                    try {
                        long now = System.currentTimeMillis();
                        msg = "ok: " + Exporter.testImage(cfg, in.getLongExtra("export_test_ms", now),
                            in.getLongExtra("export_test_staged_ms", now));
                    } catch (Exception e) {
                        msg = "error: " + e;
                    }
                    android.util.Log.i(Cfg.TAG, "test image " + msg);
                    try {
                        Util.write(new File(cfg.logs, "export-test.txt"), msg + "\n");
                    } catch (java.io.IOException ignored) {
                        // solo comodità
                    }
                }
            }).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        installedVersion = Updater.current(cfg);
        handler.post(tick);
        // nessun controllo ancora in questo processo (app appena aperta o aggiornata): uno subito, senza notifica
        if (Updater.status().isEmpty() && cfg.prefs.getBoolean("update_check", true)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Updater.checkNow(cfg, false);
                }
            }, "immich-update").start();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void checkThenOffer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = Updater.checkNow(cfg, false);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (Updater.available() != null) {
                            offerUpdate();
                        } else {
                            Toast.makeText(MainActivity.this, r, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "immich-update").start();
    }

    /** prima il permesso di installare app (una volta sola), poi la conferma, poi il download (vedi Updater) */
    private void offerUpdate() {
        final Updater.Release r = Updater.available();
        if (r == null || Updater.busy() || isFinishing() || isDestroyed()) return;
        if (!getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow Immich Server to install apps, then come back and press Update again", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, "Settings → Apps → Immich Server → Install unknown apps → Allow", Toast.LENGTH_LONG).show();
            }
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(r.title())
            .setMessage("Downloads the new app (" + (r.size > 0 ? (r.size >> 20) + " MB" : "about 300 MB") + ") and asks "
                + "Android to install it over this one. Photos, database and settings stay. During the update the server "
                + "stops for a few minutes, then it starts again by itself.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Download and install", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Updater.downloadAndInstall(cfg.ctx, r);
                        }
                    }, "immich-update").start();
                }
            })
            .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBackup();
            } else {
                Toast.makeText(this, "The Storage permission is needed to copy to the phone", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_STORAGE_DCIM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                applyDcim(true);
            } else {
                dcimBox.setChecked(false);
                Toast.makeText(this, "The Storage permission is needed to keep the originals in DCIM", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** interruttore degli originali in DCIM: prima il permesso Memoria, poi si applica riavviando il server */
    private void onDcimSwitch(boolean on) {
        if (on == cfg.dcimWanted()) return;
        if (on && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                REQ_STORAGE_DCIM);
            return;
        }
        applyDcim(on);
    }

    private void applyDcim(boolean on) {
        DcimMode.onSwitch(cfg, on);
        Stack.State s = Stack.I.state();
        if (s == Stack.State.IDLE || s == Stack.State.ERROR) {
            Toast.makeText(this, "It applies at the next server start", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, on ? "Restarting the server and moving the originals to DCIM (a few minutes)" : "Restarting the server",
                Toast.LENGTH_LONG).show();
            restartServer();
        }
    }

    /** ferma il server e lo riavvia appena è fermo del tutto (gli interruttori che toccano i file si applicano all'avvio) */
    private void restartServer() {
        Stack.State s0 = Stack.I.state();
        if (s0 == Stack.State.IDLE || s0 == Stack.State.ERROR) {
            startForegroundService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_START));
            return;
        }
        startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP));
        handler.postDelayed(new Runnable() {
            int tries;

            @Override
            public void run() {
                Stack.State s = Stack.I.state();
                if (s == Stack.State.IDLE || s == Stack.State.ERROR) {
                    startForegroundService(new Intent(MainActivity.this, ServerService.class).setAction(ServerService.ACTION_START));
                } else if (++tries < 180) {
                    handler.postDelayed(this, 1000);
                }
            }
        }, 1000);
    }

    private void startBackup() {
        if (Backup.running()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Backup.runOnce(cfg);
            }
        }, "immich-backup").start();
    }

    private void toggleBackup() {
        if (Backup.running()) {
            Backup.requestStop();
            return;
        }
        if (Stack.I.state() != Stack.State.RUNNING) {
            Toast.makeText(this, "Start the server first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        } else {
            startBackup();
        }
    }

    // ------------------------------------------------------------------ interfaccia
    //
    // Niente Gradle in questo progetto (apk/build.sh è aapt2+javac+d8+apksigner), quindi niente
    // Compose/Material Components/AppCompat: ogni forma, colore e animazione qui sotto è framework puro,
    // pensata per restare compilabile da quella catena senza aggiungere dipendenze.
    // Vedi .claude/skills/android-view-polish/SKILL.md.

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        return p;
    }

    /** il secondo elemento di una riga di due bottoni/campi, con un piccolo distacco dal primo */
    private LinearLayout.LayoutParams rowItem(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (!first) p.leftMargin = dp(10);
        return p;
    }

    private GradientDrawable shape(int fill, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private Drawable ripple(int fill, int radiusDp, int rippleColorArgb) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColorArgb), shape(fill, radiusDp), null);
    }

    /** una card bianca, leggermente sollevata sullo sfondo: il raggruppamento visivo di ogni sezione */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(shape(C_CARD, 16));
        c.setElevation(dp(2));
        return c;
    }

    /**
     * Colore di testo e sfondo di un bottone: tenue ("tonale", l'azione normale dentro una card) o pieno
     * ("primario", la singola azione più importante di una card, o quale preset è attivo nel selettore in alto).
     * Estratto da button()/primaryButton() così il selettore dei preset può ridipingere un bottone già creato
     * senza ricrearlo.
     */
    private void setButtonLook(Button b, boolean primary) {
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackground(ripple(C_ACCENT, 12, Color.argb(90, 255, 255, 255)));
        } else {
            b.setTextColor(C_ACCENT);
            int tint = Color.argb(28, Color.red(C_ACCENT), Color.green(C_ACCENT), Color.blue(C_ACCENT));
            b.setBackground(ripple(tint, 12, Color.argb(45, 0, 0, 0)));
        }
    }

    /** bottone "tonale" (sfondo tenue, testo colorato): l'azione normale dentro una card */
    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(16), dp(11), dp(16), dp(11));
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setElevation(0);
        setButtonLook(b, false);
        b.setOnClickListener(l);
        return b;
    }

    /** bottone "pieno": la singola azione più importante di una card (avvia il server, installa l'aggiornamento) */
    private Button primaryButton(String label, View.OnClickListener l) {
        Button b = button(label, l);
        setButtonLook(b, true);
        return b;
    }

    /** sfondo tenue per spinner e campi di testo dentro una card: altrimenti sembrano incollati sul bianco */
    private void surface(View v) {
        v.setBackground(shape(C_SURFACE, 10));
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
    }

    /** menu a tendina legato a un'impostazione intera */
    private Spinner prefSpinner(final String key, String[] labels, final int[] values, int def) {
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        int cur = cfg.prefs.getInt(key, def);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == cur) sp.setSelection(i);
        }
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                cfg.prefs.edit().putInt(key, values[pos]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        surface(sp);
        return sp;
    }

    /**
     * I bottoni qui sopra hanno un colore di testo e uno sfondo fissi (non una ColorStateList), quindi
     * setEnabled(false) da solo non li farebbe più sembrare spenti: l'opacità è il segnale al suo posto.
     */
    private void setEnabledLook(Button b, boolean enabled) {
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.45f);
    }

    /** sfuma il colore del testo invece di farlo scattare, quando lo stato cambia (vedi refresh()) */
    private void animateColor(final TextView tv, int from, int to) {
        ValueAnimator va = ValueAnimator.ofArgb(from, to);
        va.setDuration(250);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                tv.setTextColor((Integer) a.getAnimatedValue());
            }
        });
        va.start();
    }

    private void confirmPurge() {
        new AlertDialog.Builder(this)
            .setTitle("Delete the gallery copies")
            .setMessage("Deletes the temporary copies from DCIM/Immich. The photos stay in Immich. If Google Photos hasn't "
                + "uploaded them yet, it won't. \"Delete and copy again\" deletes them and the next copy starts over from all "
                + "the photos.")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete and copy again", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            Exporter.cleanup(cfg, true);
                            Exporter.resetState(cfg);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Done: press \"Copy now\" to start over", Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }).start();
                }
            })
            .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            final int n = Exporter.cleanup(cfg, true);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Deleted " + n + " copies", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }).start();
                }
            })
            .show();
    }

    /**
     * Mostra sempre la conferma, anche se "mode" sembra già attivo: su un'installazione già esistente (il Pixel 5
     * dell'utente) il preset è dedotto da dcim_originals (vedi Cfg.setupMode()) ma prune_emergency non è mai stato
     * acceso da quella deduzione — toccare di nuovo lo stesso bottone resta il modo per far entrare anche quello.
     */
    private void confirmPreset(final String mode) {
        boolean pixel = "pixel".equals(mode);
        String title = pixel ? "Pixel → Google Photos bridge" : "Private photo server";
        String msg = pixel
            ? "Turns on Keep the originals in DCIM/Immich (restarts the server and moves photos already uploaded) and "
                + "the emergency deletion safety net below. \"Delete the original from Immich after\" is left as it is."
            : "Turns off Keep the originals in DCIM/Immich (new photos stop moving to DCIM/Immich; ones already there "
                + "stay where they are) and the emergency deletion safety net below. \"Delete the original from Immich "
                + "after\" is left as it is.";
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    applyPreset(mode);
                }
            })
            .show();
    }

    private void applyPreset(String mode) {
        boolean pixel = "pixel".equals(mode);
        cfg.setSetupMode(mode);
        dcimBox.setChecked(pixel);           // listener esistente onDcimSwitch(): permesso, migrazione, riavvio
        pruneEmergencyBox.setChecked(pixel); // listener nuovo: scrive solo la preferenza prune_emergency
        refresh();
    }

    private void refreshPreset() {
        String mode = cfg.setupMode();
        boolean pixel = "pixel".equals(mode);
        setButtonLook(presetPixelBtn, pixel);
        setButtonLook(presetPrivateBtn, !pixel);
        presetCaption.setText(pixel
            ? "Active: Pixel → Google Photos bridge — originals move to DCIM/Immich for Google Photos' free backup."
            : "Active: Private photo server — originals stay in the app's private storage, nothing is shared.");
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(28));
        sv.addView(root);

        root.addView(text("Immich Server", 24, C_TEXT, true));
        root.addView(text("Your photo and video server, on this phone", 14, C_MUTED, false), lp(2));

        // ---- cosa fa questo telefono: un preset che precompila le impostazioni sotto, nessuna le nasconde ----
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetPixelBtn = button("Pixel bridge", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmPreset("pixel");
            }
        });
        presetPrivateBtn = button("Private server", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmPreset("private");
            }
        });
        presetRow.addView(presetPixelBtn, rowItem(true));
        presetRow.addView(presetPrivateBtn, rowItem(false));
        root.addView(presetRow, lp(14));
        presetCaption = text("", 12, C_MUTED, false);
        root.addView(presetCaption, lp(4));

        // ---- card principale: stato, avanzamento, indirizzo, avvio/arresto ----
        LinearLayout hero = card();
        status = text("", 20, C_TEXT, true);
        hero.addView(status);
        detail = text("", 14, C_MUTED, false);
        hero.addView(detail, lp(2));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        hero.addView(bar, lp(10));
        urls = text("", 15, C_ACCENT, false);
        urls.setTextIsSelectable(true);
        hero.addView(urls, lp(12));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        mainBtn = primaryButton("Install and start", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        openBtn = button("Open Immich", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + cfg.port())));
            }
        });
        row.addView(mainBtn, rowItem(true));
        row.addView(openBtn, rowItem(false));
        hero.addView(row, lp(14));
        root.addView(hero, lp(18));

        // ---- mantenere il server vivo ----
        LinearLayout aliveCard = card();
        aliveCard.addView(text("Keeping the server alive", 16, C_TEXT, true));
        health = text("", 13, C_MUTED, false);
        aliveCard.addView(health, lp(8));
        storage = text("", 13, C_MUTED, false);
        aliveCard.addView(storage, lp(2));
        aliveCard.addView(button("Exclude from battery optimization", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                batterySettings();
            }
        }), lp(10));
        aliveCard.addView(button("Child process restrictions (Android 12+)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                phantomDialog();
            }
        }), lp(8));
        CheckBox auto = new CheckBox(this);
        auto.setText("Start the server when the phone boots");
        auto.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        auto.setChecked(cfg.autostart());
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.setAutostart(checked);
            }
        });
        aliveCard.addView(auto, lp(10));
        root.addView(aliveCard, lp(14));

        // ---- aggiornamenti ----
        LinearLayout updatesCard = card();
        updatesCard.addView(text("Updates", 16, C_TEXT, true));
        updatesCard.addView(text("Every new Immich version is built and published on GitHub automatically, usually within a day. "
            + "The app checks once a day and sends you a notification. Updating takes one tap: the app downloads the new "
            + "version (about 300 MB) and Android asks you to confirm; the first time it also asks you to allow Immich "
            + "Server to install apps. Photos, database and settings stay; the server stops for a few minutes and starts "
            + "again by itself.", 12, C_MUTED, false), lp(6));
        CheckBox upd = new CheckBox(this);
        upd.setText("Check for updates every day");
        upd.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        upd.setChecked(cfg.prefs.getBoolean("update_check", true));
        upd.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.prefs.edit().putBoolean("update_check", checked).apply();
            }
        });
        updatesCard.addView(upd, lp(10));
        updateStatus = text("", 12, C_MUTED, false);
        updatesCard.addView(updateStatus, lp(4));
        LinearLayout updRow = new LinearLayout(this);
        updRow.setOrientation(LinearLayout.HORIZONTAL);
        updRow.addView(button("Check now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String r = Updater.checkNow(cfg, false);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, r, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }, "immich-update").start();
            }
        }), rowItem(true));
        updateBtn = primaryButton("Update", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                offerUpdate();
            }
        });
        updRow.addView(updateBtn, rowItem(false));
        updatesCard.addView(updRow, lp(10));
        root.addView(updatesCard, lp(14));

        // ---- galleria e Google Foto ----
        LinearLayout galleryCard = card();
        galleryCard.addView(text("Gallery and Google Photos", 16, C_TEXT, true));
        galleryCard.addView(text("Photos uploaded to Immich live in the app's private storage: Gallery, Files and Google Photos can't see "
            + "them, and Google Photos can only upload files from shared storage. So the app makes a TEMPORARY copy in "
            + "DCIM/Immich and deletes it by itself later: the original stays in Immich and, after the backup, in Google's "
            + "cloud too. Once, in Google Photos: Settings → Backup → Back up device folders → turn on \"Immich\". To free "
            + "the space earlier use Google Photos' \"Free up space\". On Pixel 2–5 the unlimited storage is in \"Storage "
            + "saver\" quality (photos at 16 MP, videos at 1080p). Don't back up the \"Immich\" folder with the Immich app: "
            + "it would send the photos back to the server.", 12, C_MUTED, false), lp(6));
        CheckBox exp = new CheckBox(this);
        exp.setText("Copy new photos to the gallery automatically");
        exp.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        exp.setChecked(cfg.prefs.getBoolean("export_enabled", false));
        exp.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.prefs.edit().putBoolean("export_enabled", checked).apply();
            }
        });
        galleryCard.addView(exp, lp(10));
        CheckBox expAll = new CheckBox(this);
        expAll.setText("Include the photos of the other Immich users too");
        expAll.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        expAll.setChecked(cfg.prefs.getBoolean("export_all_users", false));
        expAll.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.prefs.edit().putBoolean("export_all_users", checked).apply();
            }
        });
        galleryCard.addView(expAll, lp(2));
        galleryCard.addView(text("Delete the gallery copy after", 13, C_TEXT, false), lp(12));
        galleryCard.addView(prefSpinner("export_keep_days", new String[]{"Never", "3 days", "5 days", "7 days", "30 days"},
            new int[]{0, 3, 5, 7, 30}, 7), lp(4));
        galleryCard.addView(text("Gallery copies waiting for backup: at most", 13, C_TEXT, false), lp(10));
        galleryCard.addView(prefSpinner("export_cap_gb", new String[]{"No cap", "5 GB", "10 GB", "20 GB", "50 GB"},
            new int[]{0, 5, 10, 20, 50}, 10), lp(4));
        LinearLayout expRow = new LinearLayout(this);
        expRow.setOrientation(LinearLayout.HORIZONTAL);
        expRow.addView(button("Copy now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Stack.I.state() != Stack.State.RUNNING) {
                    Toast.makeText(MainActivity.this, "Start the server first", Toast.LENGTH_SHORT).show();
                } else {
                    Stack.I.exportNow();
                    Toast.makeText(MainActivity.this, "Copy started", Toast.LENGTH_SHORT).show();
                }
            }
        }), rowItem(true));
        expRow.addView(button("Test image", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String msg;
                        try {
                            msg = "Created in DCIM/Immich: " + Exporter.testImage(cfg);
                        } catch (Exception e) {
                            msg = "Failed: " + e.getMessage();
                        }
                        final String m = msg;
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, m, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        }), rowItem(false));
        galleryCard.addView(expRow, lp(10));
        galleryCard.addView(button("Delete the gallery copies now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmPurge();
            }
        }), lp(8));
        exportStatus = text("", 12, C_MUTED, false);
        galleryCard.addView(exportStatus, lp(6));
        root.addView(galleryCard, lp(14));

        // ---- backup sul telefono ----
        LinearLayout backupCard = card();
        backupCard.addView(text("Backup on the phone", 16, C_TEXT, true));
        backupCard.addView(text("Copies Immich's originals (the real files, not the thumbnails) to \"" + Backup.DIR
            + "\" in the phone's storage: a normal folder that any file manager shows, or a PC when the phone is "
            + "connected. In addition to the temporary gallery copy above (meant for Google Photos) and to the PC "
            + "copy described in the docs. Manual and incremental: you can stop it and start it again, it resumes "
            + "where it stopped without copying everything again. It never deletes anything.", 12, C_MUTED, false), lp(6));
        backupBtn = button("Copy to the phone now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBackup();
            }
        });
        backupCard.addView(backupBtn, lp(10));
        backupStatus = text("", 12, C_MUTED, false);
        backupCard.addView(backupStatus, lp(6));
        root.addView(backupCard, lp(14));

        // ---- batteria ----
        LinearLayout batteryCard = card();
        batteryCard.addView(text("Battery", 16, C_TEXT, true));
        batteryCard.addView(text("A phone that is always plugged in sits at 100%, which over the years wears the battery more than "
            + "cycling between two thresholds. Android doesn't give a normal app (without root, and this one isn't) "
            + "control over charging: no app can switch the charger on or off by itself. What it can do is remind "
            + "you, so you unplug and plug back in by hand.", 12, C_MUTED, false), lp(6));
        CheckBox battGuard = new CheckBox(this);
        battGuard.setText("Remind me not to keep it charging at 100%");
        battGuard.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        battGuard.setChecked(cfg.prefs.getBoolean("battery_guard_enabled", false));
        battGuard.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.prefs.edit().putBoolean("battery_guard_enabled", checked).apply();
            }
        });
        batteryCard.addView(battGuard, lp(10));
        batteryCard.addView(text("Remind me to unplug above", 13, C_TEXT, false), lp(10));
        batteryCard.addView(prefSpinner("battery_guard_high", new String[]{"60%", "70%", "80%", "90%"},
            new int[]{60, 70, 80, 90}, 80), lp(4));
        batteryCard.addView(text("Remind me to plug back in below", 13, C_TEXT, false), lp(10));
        batteryCard.addView(prefSpinner("battery_guard_low", new String[]{"20%", "30%", "40%", "50%"},
            new int[]{20, 30, 40, 50}, 30), lp(4));
        batteryStatus = text("", 12, C_MUTED, false);
        batteryCard.addView(batteryStatus, lp(10));
        root.addView(batteryCard, lp(14));

        // ---- eliminare anche l'originale da Immich ----
        LinearLayout pruneCard = card();
        pruneCard.addView(text("Also delete the original from Immich", 14, C_TEXT, true));
        pruneCard.addView(text("Optional: some days after a photo reaches the gallery, deletes the original from Immich TOO, "
            + "permanently (not to the trash). From then on no copy of the original is left on the phone: only the "
            + "one Google Photos uploaded (in \"Storage saver\" quality on Pixel 2–5, not the original). Use it only "
            + "once you have checked that the Google Photos backup works.", 12, C_MUTED, false), lp(6));
        pruneCard.addView(text("Immich API key (Account Settings → API Keys → New API Key, permission \"all\" or asset.delete)",
            13, C_TEXT, false), lp(12));
        apiKeyField = new EditText(this);
        apiKeyField.setHint("paste the key here");
        apiKeyField.setSingleLine(true);
        apiKeyField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyField.setText(cfg.prefs.getString("prune_api_key", ""));
        surface(apiKeyField);
        pruneCard.addView(apiKeyField, lp(6));
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.addView(button("Save the key", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.prefs.edit().putString("prune_api_key", apiKeyField.getText().toString().trim()).apply();
                Toast.makeText(MainActivity.this, "Saved", Toast.LENGTH_SHORT).show();
            }
        }), rowItem(true));
        keyRow.addView(button("Test the key", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String k = apiKeyField.getText().toString().trim();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String r = Pruner.testApiKey(cfg, k);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, r, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }).start();
            }
        }), rowItem(false));
        pruneCard.addView(keyRow, lp(8));
        pruneCard.addView(text("Delete the original from Immich after", 13, C_TEXT, false), lp(10));
        pruneCard.addView(prefSpinner("prune_days", new String[]{"Never (default)", "2 days", "3 days", "5 days", "7 days"},
            new int[]{0, 2, 3, 5, 7}, 0), lp(4));
        pruneEmergencyBox = new CheckBox(this);
        pruneEmergencyBox.setText("Emergency: also delete originals when space is critical, even if set to \"Never\"");
        pruneEmergencyBox.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        pruneEmergencyBox.setChecked(cfg.prefs.getBoolean("prune_emergency", false));
        pruneEmergencyBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.prefs.edit().putBoolean("prune_emergency", checked).apply();
            }
        });
        pruneCard.addView(pruneEmergencyBox, lp(14));
        pruneCard.addView(text("Only as a last resort: only originals already safely copied to the gallery, oldest "
            + "first, a limited number per run so a large backlog doesn't disappear all at once, and only below the "
            + "critical threshold above (not just \"running low\"). A notification tells you the first time it "
            + "actually deletes something.", 12, C_MUTED, false), lp(2));
        pruneStatus = text("", 12, C_MUTED, false);
        pruneCard.addView(pruneStatus, lp(10));
        pruneEmergencyNote = text("", 12, C_WARN, true);
        pruneCard.addView(pruneEmergencyNote, lp(4));
        root.addView(pruneCard, lp(14));

        // ---- originali in DCIM/Immich + pulizia delle foto senza file ----
        LinearLayout dcimCard = card();
        dcimCard.addView(text("Originals directly in DCIM/Immich", 14, C_TEXT, true));
        dcimCard.addView(text("Instead of the app's private folder, Immich keeps the originals in DCIM/Immich/<user> (\"admin\" for "
            + "the administrator) with their real names: Gallery and Google Photos see them right away, with no "
            + "temporary copies. In Google Photos turn on the backup of the \"admin\" folder once (Settings → Backup → "
            + "Back up device folders). Careful: other apps can delete them from there too, for example Google "
            + "Photos' \"Free up space\" after its backup; Immich then keeps the photo without its original (see the "
            + "cleanup below). Thumbnails, transcoded videos and the database stay private. Turning it on restarts the "
            + "server and moves the photos already uploaded (a few minutes); it needs the Storage permission.", 12, C_MUTED, false), lp(6));
        dcimBox = new CheckBox(this);
        dcimBox.setText("Keep the originals in DCIM/Immich");
        dcimBox.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        dcimBox.setChecked(cfg.dcimWanted());
        dcimBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                onDcimSwitch(checked);
            }
        });
        dcimCard.addView(dcimBox, lp(10));
        dcimStatus = text("", 12, C_MUTED, false);
        dcimCard.addView(dcimStatus, lp(6));

        dcimCard.addView(text("Cleaning up photos whose file is gone", 14, C_TEXT, true), lp(22));
        CheckBox missing = new CheckBox(this);
        missing.setText("Every night move to Immich's trash the photos whose file is gone");
        missing.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        missing.setChecked(cfg.prefs.getBoolean("missing_cleanup", false));
        missing.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.prefs.edit().putBoolean("missing_cleanup", checked).apply();
            }
        });
        dcimCard.addView(missing, lp(10));
        dcimCard.addView(text("Uses Immich's nightly check (at 3:00: Administration → Maintenance → Integrity Report → Missing Files) "
            + "and the API key above; they can be restored from Immich's trash for 30 days. It does nothing if too many "
            + "are missing at once or if the folders are not readable.", 12, C_MUTED, false), lp(4));
        dcimCard.addView(button("Check now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String r = MissingCleaner.runNow(Stack.I, cfg);
                        handler.post(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(MainActivity.this, r, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }, "immich-missing").start();
            }
        }), lp(8));
        missingStatus = text("", 12, C_MUTED, false);
        dcimCard.addView(missingStatus, lp(6));
        root.addView(dcimCard, lp(14));

        // ---- avanzate ----
        LinearLayout advCard = card();
        advCard.addView(text("Advanced", 16, C_TEXT, true));
        CheckBox noSec = new CheckBox(this);
        noSec.setText("proot without seccomp (only if the server hangs on start)");
        noSec.setButtonTintList(ColorStateList.valueOf(C_ACCENT));
        noSec.setChecked(cfg.noSeccomp());
        noSec.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.setNoSeccomp(checked);
            }
        });
        advCard.addView(noSec, lp(10));
        advCard.addView(button("Repair: reinstall the Debian system (data and photos stay)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRepair();
            }
        }), lp(10));
        root.addView(advCard, lp(14));

        // ---- log e diagnostica ----
        LinearLayout logsCard = card();
        logsCard.addView(text("Logs and diagnostics", 16, C_TEXT, true));
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        source = new Spinner(this);
        source.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Setup", "PostgreSQL", "Valkey", "Immich", "Diagnostics"}));
        source.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                lastLog = "";
                refresh();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        surface(source);
        row2.addView(source, rowItem(true));
        LinearLayout.LayoutParams diagBtnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        diagBtnLp.leftMargin = dp(10);
        row2.addView(button("Run diagnostics", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagnosis();
            }
        }), diagBtnLp);
        logsCard.addView(row2, lp(12));

        logView = text("", 11, Color.parseColor("#222222"), false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackground(shape(C_SURFACE, 10));
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setTextIsSelectable(true);
        logsCard.addView(logView, lp(10));
        root.addView(logsCard, lp(14));

        root.addView(text("Next steps: when the status reads \"Running\", open the address above in a browser or in the Immich "
            + "app and create the admin user. Machine learning is turned off automatically: it is too heavy for this "
            + "phone.", 12, C_MUTED, false), lp(18));
        return sv;
    }

    // ------------------------------------------------------------------ aggiornamento

    private void refresh() {
        refreshPreset();
        Stack.State s = Stack.I.state();
        boolean installed = Stack.installed(cfg);
        String label;
        int color;
        switch (s) {
            case INSTALLING:
                label = "Installing";
                color = C_WARN;
                break;
            case STARTING:
                label = "Starting";
                color = C_WARN;
                break;
            case RUNNING:
                label = "Running";
                color = C_OK;
                break;
            case STOPPING:
                label = "Stopping";
                color = C_WARN;
                break;
            case ERROR:
                label = "Error";
                color = C_ERR;
                break;
            default:
                label = installed ? "Stopped" : "Not installed";
                color = C_MUTED;
                break;
        }
        status.setText(label);
        if (!statusColorShown || color != shownStatusColor) {
            if (statusColorShown) animateColor(status, shownStatusColor, color); else status.setTextColor(color);
            shownStatusColor = color;
            statusColorShown = true;
        }
        detail.setText(s == Stack.State.IDLE && !installed ? "Press the button to install (needs Internet)." : Stack.I.detail());

        int pct = Stack.I.progress();
        bar.setVisibility(pct >= 0 ? View.VISIBLE : View.GONE);
        int shownPct = Math.max(0, pct);
        if (shownPct != bar.getProgress()) {
            ObjectAnimator.ofInt(bar, "progress", bar.getProgress(), shownPct).setDuration(300).start();
        }

        boolean idle = s == Stack.State.IDLE || s == Stack.State.ERROR;
        mainBtn.setText(idle ? (installed ? "Start the server" : "Install and start") : "Stop the server");
        setEnabledLook(mainBtn, s != Stack.State.STOPPING);
        setEnabledLook(openBtn, s == Stack.State.RUNNING);

        StringBuilder u = new StringBuilder();
        List<String> ips = Util.ipv4();
        if (ips.isEmpty()) u.append("No Wi-Fi network: reachable only from this phone.\n");
        for (String ip : ips) u.append("http://").append(ip.substring(ip.indexOf(' ') + 1)).append(':').append(cfg.port()).append("   (").append(ip.substring(0, ip.indexOf(' '))).append(")\n");
        u.append("http://127.0.0.1:").append(cfg.port()).append("   (this phone)");
        urls.setText(u.toString());

        health.setText("Battery optimization: " + (Health.ignoringBattery(this) ? "excluded (ok)" : "active (Android may stop the server)")
            + "\nChild process restrictions: " + Health.phantomText(this));
        int spaceLvl = Health.spaceLevel(cfg.files);
        storage.setText("Storage: " + Health.spaceText(cfg.files));
        storage.setTextColor(spaceLvl == 1 ? C_ERR : spaceLvl == 0 ? C_WARN : C_MUTED);

        exportStatus.setText(Exporter.status() + "\n" + Exporter.stagedInfo(cfg));
        pruneStatus.setText(Pruner.status());
        String shrinkNote = Pruner.effectiveDaysNote(cfg);
        pruneEmergencyNote.setText(shrinkNote);
        pruneEmergencyNote.setVisibility(shrinkNote.isEmpty() ? View.GONE : View.VISIBLE);
        pruneEmergencyNote.setTextColor(spaceLvl == 1 ? C_ERR : C_WARN);
        dcimStatus.setText(DcimMode.status(cfg));
        missingStatus.setText(MissingCleaner.status(cfg));

        String us = Updater.status();
        if (us.isEmpty() && !cfg.prefs.getBoolean("update_check", true)) us = "Automatic checks are off.";
        updateStatus.setText("Installed: Immich " + (installedVersion.isEmpty() ? "(not yet)" : installedVersion)
            + (us.isEmpty() ? "" : "\n" + us));
        Updater.Release up = Updater.available();
        setEnabledLook(updateBtn, up != null && !Updater.busy());
        updateBtn.setText(Updater.busy() ? "Downloading…" : up == null ? "Update" : up.rebuild ? "Install the new build"
            : "Update to " + up.tag);
        Intent confirm = Updater.takeConfirm();
        if (confirm != null) {
            try {
                startActivity(confirm);
            } catch (Exception e) {
                Toast.makeText(this, "Android's install window didn't open: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        backupBtn.setText(Backup.running() ? "Stop the copy" : "Copy to the phone now");
        backupStatus.setText(Backup.status());
        batteryStatus.setText("Battery now: " + BatteryGuard.statusText(this));

        String txt = currentLog();
        if (!txt.equals(lastLog)) {
            lastLog = txt;
            logView.setText(txt.isEmpty() ? "(empty)" : txt);
        }
    }

    private String currentLog() {
        int i = source.getSelectedItemPosition();
        if (i == 4) return diagText;
        String[] files = {"setup.log", "postgres.log", "valkey.log", "immich.log"};
        return Util.tail(new File(cfg.logs, files[Math.max(0, i)]), 6000);
    }

    // ------------------------------------------------------------------ azioni

    private void toggle() {
        Stack.State s = Stack.I.state();
        if (s == Stack.State.IDLE || s == Stack.State.ERROR) {
            startForegroundService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_START));
        } else {
            startService(new Intent(this, ServerService.class).setAction(ServerService.ACTION_STOP));
        }
    }

    private void runDiagnosis() {
        source.setSelection(4);
        diagText = "Running diagnostics…";
        refresh();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String r = Stack.I.diagnose(cfg);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        diagText = r;
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void confirmRepair() {
        new AlertDialog.Builder(this)
            .setTitle("Repair")
            .setMessage("Stops the server and deletes the Debian system and the Immich package, then reinstalls them at the "
                + "next start (needs Internet and 10-30 minutes). The database and the photos are NOT touched.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Repair", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    startService(new Intent(MainActivity.this, ServerService.class).setAction(ServerService.ACTION_STOP));
                    Stack.I.repair(cfg, new Runnable() {
                        @Override
                        public void run() {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Done: press Install and start", Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    });
                }
            })
            .show();
    }

    private void batterySettings() {
        try {
            if (!Health.ignoringBattery(this)) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
            } else {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings → Apps → Immich Server → Battery → Unrestricted", Toast.LENGTH_LONG).show();
        }
    }

    private void phantomDialog() {
        final boolean canWrite = Health.canWriteSecure(this);
        String msg = "Since Android 12 the system kills an app's \"child\" processes when there are more than 32: PostgreSQL, "
            + "Valkey, Node.js and ffmpeg start many, so without this setting the server stops at random.\n\n"
            + "Now: " + Health.phantomText(this) + "\n\n"
            + "Easiest way (Android 14+): Settings → System → Developer options → \"Disable child process restrictions\".\n\n"
            + "Or, from a PC with the phone connected:\n" + Health.adbCommands(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("Child process restrictions")
            .setMessage(msg)
            .setNeutralButton("Copy adb commands", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("adb", Health.adbCommands(MainActivity.this)));
                    Toast.makeText(MainActivity.this, "Copied", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Close", null);
        if (canWrite) {
            b.setPositiveButton("Turn off now", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    boolean ok = Health.setPhantomRestrictions(MainActivity.this, false);
                    Toast.makeText(MainActivity.this, ok ? "Done" : "Failed", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.show();
    }
}
