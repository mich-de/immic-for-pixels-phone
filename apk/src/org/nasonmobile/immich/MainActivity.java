package org.nasonmobile.immich;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
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
    private static final int REQ_STORAGE = 100;

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
    private EditText apiKeyField;
    private TextView logView;
    private ProgressBar bar;
    private Button mainBtn;
    private Button openBtn;
    private Button backupBtn;
    private Spinner source;
    private String diagText = "";
    private String lastLog = "";

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
        handleIntent(getIntent());
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
     * "--ez export_test true [--el export_test_ms MILLISECONDI]" crea l'immagine di prova in DCIM/Immich.
     */
    private void handleIntent(final Intent in) {
        if (in == null) return;
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
                    android.util.Log.i(Cfg.TAG, "chiave API salvata da adb; prova: " + r);
                }
            }).start();
        }
        if (in.getBooleanExtra("export_purge", false) || in.getBooleanExtra("export_purge_all", false)) {
            final boolean all = in.getBooleanExtra("export_purge_all", false);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int n = Exporter.cleanup(cfg, all);
                    android.util.Log.i(Cfg.TAG, "pulizia copie in galleria (" + (all ? "tutte" : "scadute") + "): eliminate " + n);
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
                        msg = "errore: " + e;
                    }
                    android.util.Log.i(Cfg.TAG, "prova a secco: " + msg);
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
                        msg = "errore: " + e;
                    }
                    android.util.Log.i(Cfg.TAG, "immagine di prova " + msg);
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
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBackup();
            } else {
                Toast.makeText(this, "Serve il permesso \"Memoria\" per copiare sul telefono", Toast.LENGTH_LONG).show();
            }
        }
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
            Toast.makeText(this, "Avvia prima il server", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        } else {
            startBackup();
        }
    }

    // ------------------------------------------------------------------ interfaccia

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

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
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
        return sp;
    }

    private void confirmPurge() {
        new AlertDialog.Builder(this)
            .setTitle("Elimina le copie in galleria")
            .setMessage("Elimina da DCIM/Immich le copie temporanee. Le foto restano in Immich. Se Google Foto non le ha ancora "
                + "caricate, non le caricherà. \"Elimina e ricopia da capo\" le elimina e alla prossima copia ricomincia da tutte "
                + "le foto.")
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Elimina e ricopia da capo", new DialogInterface.OnClickListener() {
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
                                    Toast.makeText(MainActivity.this, "Fatto: premi \"Copia ora\" per ricominciare", Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }).start();
                }
            })
            .setPositiveButton("Elimina", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            final int n = Exporter.cleanup(cfg, true);
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Eliminate " + n + " copie", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }).start();
                }
            })
            .show();
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#F6F7FB"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        sv.addView(root);

        root.addView(text("Immich Server", 24, C_TEXT, true));
        root.addView(text("Il tuo server di foto e video, su questo telefono", 14, C_MUTED, false), lp(2));

        status = text("", 20, C_TEXT, true);
        root.addView(status, lp(20));
        detail = text("", 14, C_MUTED, false);
        root.addView(detail, lp(2));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        root.addView(bar, lp(8));

        urls = text("", 15, C_ACCENT, false);
        urls.setTextIsSelectable(true);
        root.addView(urls, lp(12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        mainBtn = button("Installa e avvia", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggle();
            }
        });
        openBtn = button("Apri Immich", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:" + cfg.port())));
            }
        });
        row.addView(mainBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(openBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row, lp(10));

        root.addView(text("Perché il server resti attivo", 16, C_TEXT, true), lp(26));
        health = text("", 13, C_MUTED, false);
        root.addView(health, lp(4));
        storage = text("", 13, C_MUTED, false);
        root.addView(storage, lp(2));
        root.addView(button("Esclui dal risparmio batteria", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                batterySettings();
            }
        }), lp(6));
        root.addView(button("Restrizioni sui processi figli (Android 12+)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                phantomDialog();
            }
        }), lp(0));
        CheckBox auto = new CheckBox(this);
        auto.setText("Avvia il server all'accensione del telefono");
        auto.setChecked(cfg.autostart());
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.setAutostart(checked);
            }
        });
        root.addView(auto, lp(6));

        root.addView(text("Galleria e Google Foto", 16, C_TEXT, true), lp(26));
        root.addView(text("Le foto caricate su Immich stanno nella memoria privata dell'app: Galleria, File e Google Foto non le "
            + "vedono, e Google Foto può caricare solo file della memoria condivisa. Per questo l'app ne fa una copia TEMPORANEA "
            + "in DCIM/Immich e poi la elimina da sola: l'originale resta in Immich e, dopo il backup, anche nel cloud di Google. "
            + "Una volta sola, in Google Foto: Impostazioni → Backup → Cartelle del dispositivo → attiva \"Immich\". Per liberare "
            + "prima puoi usare \"Libera spazio\" di Google Foto. Sul Pixel 5 lo spazio illimitato è in qualità \"Risparmio spazio\" "
            + "(foto a 16 MP, video a 1080p). Nell'app Immich non attivare il backup della cartella \"Immich\": le rimanderebbe "
            + "al server.", 12, C_MUTED, false), lp(4));
        CheckBox exp = new CheckBox(this);
        exp.setText("Copia automaticamente le foto nuove nella galleria");
        exp.setChecked(cfg.prefs.getBoolean("export_enabled", false));
        exp.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.prefs.edit().putBoolean("export_enabled", checked).apply();
            }
        });
        root.addView(exp, lp(6));
        CheckBox expAll = new CheckBox(this);
        expAll.setText("Includi anche le foto degli altri utenti di Immich");
        expAll.setChecked(cfg.prefs.getBoolean("export_all_users", false));
        expAll.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.prefs.edit().putBoolean("export_all_users", checked).apply();
            }
        });
        root.addView(expAll, lp(0));
        root.addView(text("Elimina la copia in galleria dopo", 13, C_TEXT, false), lp(10));
        root.addView(prefSpinner("export_keep_days", new String[]{"Mai", "3 giorni", "5 giorni", "7 giorni", "30 giorni"},
            new int[]{0, 3, 5, 7, 30}, 7), lp(0));
        root.addView(text("Copie in galleria in attesa di backup: al massimo", 13, C_TEXT, false), lp(8));
        root.addView(prefSpinner("export_cap_gb", new String[]{"Nessun tetto", "5 GB", "10 GB", "20 GB", "50 GB"},
            new int[]{0, 5, 10, 20, 50}, 10), lp(0));
        LinearLayout expRow = new LinearLayout(this);
        expRow.setOrientation(LinearLayout.HORIZONTAL);
        expRow.addView(button("Copia ora", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Stack.I.state() != Stack.State.RUNNING) {
                    Toast.makeText(MainActivity.this, "Avvia prima il server", Toast.LENGTH_SHORT).show();
                } else {
                    Stack.I.exportNow();
                    Toast.makeText(MainActivity.this, "Copia avviata", Toast.LENGTH_SHORT).show();
                }
            }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        expRow.addView(button("Immagine di prova", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String msg;
                        try {
                            msg = "Creata in DCIM/Immich: " + Exporter.testImage(cfg);
                        } catch (Exception e) {
                            msg = "Non riuscito: " + e.getMessage();
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
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(expRow, lp(6));
        root.addView(button("Elimina ora le copie in galleria", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmPurge();
            }
        }), lp(0));
        exportStatus = text("", 12, C_MUTED, false);
        root.addView(exportStatus, lp(4));

        root.addView(text("Backup sul telefono", 16, C_TEXT, true), lp(26));
        root.addView(text("Copia gli originali di Immich (quelli veri, non le miniature) in \"" + Backup.DIR
            + "\" nella memoria del telefono: una cartella normale, che vedi con qualunque app Gestione file o "
            + "collegando il telefono al PC. In aggiunta alla copia temporanea in Galleria qui sopra (pensata per "
            + "Google Foto) e a quella su PC descritta nel README. Manuale, incrementale: puoi fermarla e "
            + "rilanciarla, riparte da dove si era fermata senza ricopiare tutto. Non elimina mai nulla.", 12, C_MUTED, false), lp(4));
        backupBtn = button("Copia ora sul telefono", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBackup();
            }
        });
        root.addView(backupBtn, lp(6));
        backupStatus = text("", 12, C_MUTED, false);
        root.addView(backupStatus, lp(4));

        root.addView(text("Batteria", 16, C_TEXT, true), lp(26));
        root.addView(text("Un telefono sempre in carica resta fermo al 100%, che con gli anni stressa la batteria più "
            + "di un giro tra due soglie. Android non dà a un'app normale (senza root, e questa non lo è) il "
            + "controllo della ricarica: nessuna app può accendere o spegnere il caricabatterie da sola. Quello che "
            + "si può fare è avvisare, per scollegare e ricollegare a mano.", 12, C_MUTED, false), lp(4));
        CheckBox battGuard = new CheckBox(this);
        battGuard.setText("Avvisami per non tenerlo sempre in carica al 100%");
        battGuard.setChecked(cfg.prefs.getBoolean("battery_guard_enabled", false));
        battGuard.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.prefs.edit().putBoolean("battery_guard_enabled", checked).apply();
            }
        });
        root.addView(battGuard, lp(6));
        root.addView(text("Avvisa di scollegare sopra", 13, C_TEXT, false), lp(8));
        root.addView(prefSpinner("battery_guard_high", new String[]{"60%", "70%", "80%", "90%"},
            new int[]{60, 70, 80, 90}, 80), lp(0));
        root.addView(text("Avvisa di ricollegare sotto", 13, C_TEXT, false), lp(8));
        root.addView(prefSpinner("battery_guard_low", new String[]{"20%", "30%", "40%", "50%"},
            new int[]{20, 30, 40, 50}, 30), lp(0));
        batteryStatus = text("", 12, C_MUTED, false);
        root.addView(batteryStatus, lp(6));

        root.addView(text("Elimina l'originale anche da Immich", 14, C_TEXT, true), lp(20));
        root.addView(text("Facoltativo: dopo che una foto è stata copiata nella galleria, elimina l'originale ANCHE da "
            + "Immich, in modo definitivo (non nel cestino). Da quel momento sul telefono non resta nessuna copia "
            + "dell'originale: solo quella, compressa, che Google Foto ha caricato (qualità \"Risparmio spazio\", non "
            + "l'originale). Usalo solo se hai già verificato che il backup di Google Foto funziona.", 12, C_MUTED, false), lp(4));
        root.addView(text("Chiave API di Immich (Account → Chiavi API → Nuova chiave, permesso \"Elimina risorse\")",
            13, C_TEXT, false), lp(10));
        apiKeyField = new EditText(this);
        apiKeyField.setHint("incolla qui la chiave");
        apiKeyField.setSingleLine(true);
        apiKeyField.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKeyField.setText(cfg.prefs.getString("prune_api_key", ""));
        root.addView(apiKeyField, lp(4));
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.addView(button("Salva la chiave", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.prefs.edit().putString("prune_api_key", apiKeyField.getText().toString().trim()).apply();
                Toast.makeText(MainActivity.this, "Salvata", Toast.LENGTH_SHORT).show();
            }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        keyRow.addView(button("Prova la chiave", new View.OnClickListener() {
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
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(keyRow, lp(4));
        root.addView(text("Elimina l'originale da Immich dopo", 13, C_TEXT, false), lp(10));
        root.addView(prefSpinner("prune_days", new String[]{"Mai (predefinito)", "2 giorni", "3 giorni", "5 giorni", "7 giorni"},
            new int[]{0, 2, 3, 5, 7}, 0), lp(0));
        pruneStatus = text("", 12, C_MUTED, false);
        root.addView(pruneStatus, lp(6));

        root.addView(text("Avanzate", 16, C_TEXT, true), lp(24));
        CheckBox noSec = new CheckBox(this);
        noSec.setText("proot senza seccomp (solo se il server si blocca all'avvio)");
        noSec.setChecked(cfg.noSeccomp());
        noSec.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                cfg.setNoSeccomp(checked);
            }
        });
        root.addView(noSec, lp(4));
        root.addView(button("Ripara: reinstalla il sistema Debian (dati e foto restano)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRepair();
            }
        }), lp(4));

        root.addView(text("Log e diagnostica", 16, C_TEXT, true), lp(24));
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        source = new Spinner(this);
        source.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Installazione", "PostgreSQL", "Valkey", "Immich", "Diagnostica"}));
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
        row2.addView(source, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(button("Esegui diagnosi", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagnosis();
            }
        }));
        root.addView(row2, lp(6));

        logView = text("", 11, Color.parseColor("#222222"), false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setBackgroundColor(Color.parseColor("#E9ECF3"));
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setTextIsSelectable(true);
        root.addView(logView, lp(8));

        root.addView(text("Prossimi passi: quando lo stato è \"In esecuzione\", apri l'indirizzo qui sopra da un browser "
            + "o dall'app Immich, crea l'utente amministratore e, in Amministrazione → Impostazioni → Machine Learning, "
            + "disattiva il machine learning (su questo telefono è troppo pesante).", 12, C_MUTED, false), lp(18));
        return sv;
    }

    // ------------------------------------------------------------------ aggiornamento

    private void refresh() {
        Stack.State s = Stack.I.state();
        boolean installed = Stack.installed(cfg);
        String label;
        int color;
        switch (s) {
            case INSTALLING:
                label = "Installazione in corso";
                color = C_WARN;
                break;
            case STARTING:
                label = "Avvio in corso";
                color = C_WARN;
                break;
            case RUNNING:
                label = "In esecuzione";
                color = C_OK;
                break;
            case STOPPING:
                label = "Arresto in corso";
                color = C_WARN;
                break;
            case ERROR:
                label = "Errore";
                color = C_ERR;
                break;
            default:
                label = installed ? "Fermo" : "Non installato";
                color = C_MUTED;
                break;
        }
        status.setText(label);
        status.setTextColor(color);
        detail.setText(s == Stack.State.IDLE && !installed ? "Premi il pulsante per installare (serve internet)." : Stack.I.detail());

        int pct = Stack.I.progress();
        bar.setVisibility(pct >= 0 ? View.VISIBLE : View.GONE);
        bar.setProgress(Math.max(0, pct));

        boolean idle = s == Stack.State.IDLE || s == Stack.State.ERROR;
        mainBtn.setText(idle ? (installed ? "Avvia il server" : "Installa e avvia") : "Ferma il server");
        mainBtn.setEnabled(s != Stack.State.STOPPING);
        openBtn.setEnabled(s == Stack.State.RUNNING);

        StringBuilder u = new StringBuilder();
        List<String> ips = Util.ipv4();
        if (ips.isEmpty()) u.append("Nessuna rete Wi-Fi: raggiungibile solo da questo telefono.\n");
        for (String ip : ips) u.append("http://").append(ip.substring(ip.indexOf(' ') + 1)).append(':').append(cfg.port()).append("   (").append(ip.substring(0, ip.indexOf(' '))).append(")\n");
        u.append("http://127.0.0.1:").append(cfg.port()).append("   (questo telefono)");
        urls.setText(u.toString());

        health.setText("Risparmio batteria: " + (Health.ignoringBattery(this) ? "escluso (ok)" : "attivo (Android può fermare il server)")
            + "\nRestrizioni processi figli: " + Health.phantomText(this));
        int spaceLvl = Health.spaceLevel(cfg.files);
        storage.setText("Spazio: " + Health.spaceText(cfg.files));
        storage.setTextColor(spaceLvl == 1 ? C_ERR : spaceLvl == 0 ? C_WARN : C_MUTED);

        exportStatus.setText(Exporter.status() + "\n" + Exporter.stagedInfo(cfg));
        pruneStatus.setText(Pruner.status());

        backupBtn.setText(Backup.running() ? "Ferma la copia" : "Copia ora sul telefono");
        backupStatus.setText(Backup.status());
        batteryStatus.setText("Batteria ora: " + BatteryGuard.statusText(this));

        String txt = currentLog();
        if (!txt.equals(lastLog)) {
            lastLog = txt;
            logView.setText(txt.isEmpty() ? "(vuoto)" : txt);
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
        diagText = "Diagnosi in corso…";
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
            .setTitle("Ripara")
            .setMessage("Ferma il server e cancella il sistema Debian e il pacchetto Immich, poi li reinstalla al "
                + "prossimo avvio (servono internet e 10-30 minuti). Il database e le foto NON vengono toccati.")
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Ripara", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    startService(new Intent(MainActivity.this, ServerService.class).setAction(ServerService.ACTION_STOP));
                    Stack.I.repair(cfg, new Runnable() {
                        @Override
                        public void run() {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Fatto: premi Installa e avvia", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "Apri Impostazioni → App → Immich Server → Batteria → Senza restrizioni", Toast.LENGTH_LONG).show();
        }
    }

    private void phantomDialog() {
        final boolean canWrite = Health.canWriteSecure(this);
        String msg = "Da Android 12 il sistema uccide i processi \"figli\" di un'app quando sono più di 32: PostgreSQL, "
            + "Valkey, Node.js e ffmpeg ne creano molti, quindi senza questa impostazione il server si ferma a caso.\n\n"
            + "Stato attuale: " + Health.phantomText(this) + "\n\n"
            + "Modo più semplice: Impostazioni → Sistema → Opzioni sviluppatore → \"Disattiva restrizioni processi figli\".\n\n"
            + "Oppure, dal PC con il telefono collegato:\n" + Health.adbCommands(this);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("Restrizioni sui processi figli")
            .setMessage(msg)
            .setNeutralButton("Copia comandi adb", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("adb", Health.adbCommands(MainActivity.this)));
                    Toast.makeText(MainActivity.this, "Copiati", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Chiudi", null);
        if (canWrite) {
            b.setPositiveButton("Disattiva ora", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    boolean ok = Health.setPhantomRestrictions(MainActivity.this, false);
                    Toast.makeText(MainActivity.this, ok ? "Fatto" : "Non riuscito", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.show();
    }
}
