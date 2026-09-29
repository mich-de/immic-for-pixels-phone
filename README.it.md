# Immich Server per Android

[English](README.md) · **Italiano**

Un APK che fa girare **il server** [Immich](https://immich.app) (v3.2.4) direttamente su un telefono Android:
il Pixel 5 diventa il tuo server di foto e video. Niente Docker, niente Termux: si installa l'APK, si preme
un pulsante, e dopo qualche minuto il server risponde sulla rete di casa.

Testato su **Pixel 5 (Android 14, arm64)**. Progetto non ufficiale, non affiliato a Immich.

APK già pronti per ogni versione di Immich: [Releases](https://github.com/mich-de/immic-for-pixels-phone/releases)
(li compila e pubblica da solo il workflow di GitHub, vedi [Rilasci automatici](#rilasci-automatici-su-github)).

```
┌──────────────────────── APK "Immich Server" (Java, ~290 MB) ────────────────────────┐
│  app: servizio in primo piano · wake lock · avvio automatico · log · diagnosi       │
│  proot (binari Termux) ── esegue una userland Debian 13 (arm64) senza root          │
│      ├─ PostgreSQL 17 + pgvector + VectorChord   (dati: files/immich/postgres)      │
│      ├─ Valkey 8                                                                    │
│      └─ Node 24 + Immich v3.2.4 (server, web, plugin) + jellyfin-ffmpeg + exiftool  │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

Immich non supporta ufficialmente Android come server: qui è compilato dai sorgenti e installato "ad hoc"
(vedi [Come funziona](#come-funziona)).

## Requisiti

- Telefono **arm64** con Android 8+ (provato su Android 14), almeno 6 GB di RAM, ~5 GB liberi più lo spazio per le foto.
- Internet al primo avvio (l'installazione scarica i pacchetti Debian, circa 500 MB).
- Per costruire l'APK: un PC Linux con `git curl tar xz python3`, un JDK e l'Android SDK (build-tools, `platforms;android-34`).
  Non serve Docker né Gradle.

## Costruire

```sh
pc/build-app.sh        # compila l'ultima versione di Immich per arm64 → dist/immich-android-<versione>-arm64.tar.gz (~230 MB)
apk/build.sh           # assembla e firma l'APK                → dist/immich-server.apk (~290 MB)
```

`pc/build-app.sh` scarica Node/pnpm (in `.build/`), compila server, web e plugin come nel Dockerfile ufficiale e
prende le dipendenze native (sharp, bcrypt...) già per arm64: niente emulazione. Scarica anche l'ffmpeg di Jellyfin
per Debian arm64, lo stesso .deb dell'immagine ufficiale (versione e checksum ricavati da quella, vedi [Aggiornare](#aggiornare-immich)). `apk/build.sh --lite` produce
un APK di 380 KB senza i due pacchetti grossi (vedi [Aggiornare](#aggiornare-immich)).

L'APK è firmato con la chiave `apk/immich-server.keystore`, creata al primo build: **conservala**, serve per installare
gli aggiornamenti sopra la versione vecchia senza perdere i dati.

## Installare

Collega il telefono (USB, o *Debug wireless*) e:

```sh
apk/install.sh                # installa e prepara Android (vedi sotto)
```

(oppure scarica `immich-server.apk` da [Releases](https://github.com/mich-de/immic-for-pixels-phone/releases)
direttamente sul telefono; le impostazioni di Android qui sotto le fai poi dai pulsanti dell'app).

Sul telefono premi **Installa e avvia**. La prima volta estrae Debian e Immich (~40 s), installa PostgreSQL, Valkey e
ffmpeg (7-12 min sul Pixel 5) e crea il database; poi lo stato diventa **In esecuzione** e l'app mostra l'indirizzo, per esempio
`http://192.168.1.20:2283`. Apri quell'indirizzo, crea l'utente amministratore, e nell'app Immich del telefono/PC usa
lo stesso indirizzo come "URL del server".

Gli avvii successivi richiedono circa 40 secondi.

`apk/install.sh` fa quattro cose, tutte reversibili (i comandi per annullarle sono nell'intestazione dello script): installa
l'APK, concede all'app `WRITE_SECURE_SETTINGS`, disattiva le *restrizioni sui processi figli* di Android 12+ (senza, il
sistema uccide PostgreSQL e gli altri processi a caso) ed esclude l'app dal risparmio batteria. Attenzione: il comando che
impedisce ad Android di rimettere il limite (`device_config set_sync_disabled_for_tests persistent`) sospende anche
l'aggiornamento remoto degli altri flag di sistema; `--no-tweaks` salta tutto e puoi fare le stesse cose dai pulsanti dell'app. Se preferisci farlo a mano: `adb install dist/immich-server.apk`, poi in
*Opzioni sviluppatore → Disattiva restrizioni processi figli* e *Impostazioni → App → Immich Server → Batteria → Senza restrizioni*
(l'app ha i pulsanti per entrambe).

## Raggiungerlo dalla rete

- **In casa**: sì. Immich ascolta su tutte le interfacce (`0.0.0.0:2283`); PostgreSQL e Valkey solo su `127.0.0.1`.
  Da qualsiasi dispositivo sul Wi-Fi: `http://<ip-del-telefono>:2283`. L'IP è quello che mostra l'app.
  Conviene riservarlo nel router (DHCP statico), altrimenti può cambiare.
- **Fuori casa**: usa una VPN (Tailscale, WireGuard) e raggiungi il telefono con l'IP della VPN. Non aprire la porta 2283
  su Internet: il server parla in chiaro (HTTP) e il telefono non è pensato per essere esposto.
- Il telefono deve restare acceso, sul Wi-Fi, e possibilmente in carica (vedi sotto).

## Tenerlo attivo

- L'app tiene un **servizio in primo piano** (notifica fissa "Immich Server") con wake lock CPU e Wi-Fi: non chiuderla
  dai processi recenti con "Forza arresto".
- Spunta *Avvia il server all'accensione del telefono* per il riavvio automatico dopo un riavvio.
  Dopo un **aggiornamento dell'app** il server riparte da solo (se era in funzione) in circa un minuto; se l'aggiornamento
  cambia i programmi del sistema Debian (per esempio ffmpeg) il primo avvio li aggiorna e richiede qualche minuto e internet.
- Un telefono sempre in carica al 100% stressa la batteria. Android non lascia a un'app normale (senza root) il
  controllo della ricarica — nessuna app, questa compresa, può accendere o spegnere il caricabatterie da sola — ma la
  sezione *Batteria* dell'app può avvisarti: attiva *Avvisami per non tenerlo sempre in carica al 100%*, scegli sopra
  quale percentuale scollegare (default 80%) e sotto quale ricollegare (default 30%), e scollega/ricollega a mano
  quando arriva la notifica. In alternativa: una presa smart, o carica al 80% se la tua ROM lo permette. Tienilo
  comunque in un posto fresco: il server a riposo consuma poco, ma le operazioni pesanti (miniature, transcodifica
  video) scaldano.
- **Machine learning**: è disattivato al primo avvio. Su questo telefono sarebbe troppo lento e il servizio non è
  incluso. Senza, non ci sono ricerca intelligente, riconoscimento dei volti né rilevamento dei duplicati (*Utilità →
  Esamina duplicati* non trova nulla; *Revisiona file pesanti* funziona). Puoi puntare Immich a un server ML su un altro
  PC dalle impostazioni.
- **Spazio libero**: sotto i 2 GB l'app lo segnala in giallo nel menu e nella notifica del server («spazio in
  esaurimento»); sotto i 500 MB in rosso («SPAZIO QUASI FINITO») — a quel punto Postgres e le copie possono bloccarsi.
  Libera spazio (per esempio dalle copie in galleria, vedi sotto) prima che si esaurisca del tutto.

## Dove sono le foto, e Google Foto

Le foto e i video che carichi su Immich stanno nella **memoria privata dell'app**:
`files/immich/library/upload/<id-utente>/<xx>/<yy>/<uuid>.jpg` (Immich rinomina gli originali con un UUID; il nome vero è
nel database). Galleria, File e Google Foto **non li vedono**. Per guardarli usa Immich (web o app); per copiarli sul PC
vedi [Dati e backup](#dati-e-backup).

Per portarli su **Google Foto tramite il Pixel 5** l'app ha la sezione *Galleria e Google Foto*: copia gli originali nella
galleria del telefono, cartella `DCIM/Immich`, e da lì l'app Google Foto può farne il backup. Gli originali sono copiati
byte per byte, quindi la data di scatto resta quella dei metadati (EXIF, o creazione del video); per i file senza metadati
(screenshot, PNG) Android usa la data della copia.

1. Premi *Immagine di prova*: in Galleria compare la cartella «Immich» con un'immagine di test.
2. In Google Foto: *Impostazioni → Backup → Cartelle del dispositivo* e attiva «Immich» (una volta sola).
3. Spunta *Copia automaticamente le foto nuove nella galleria* (ogni 5 minuti), oppure usa *Copia ora*.

Note: si copiano solo le foto dell'amministratore (le altre solo se spunti l'opzione, per non mescolare gli album degli altri
utenti al tuo Google Foto) e mai quelle «bloccate» di Immich. Sul Pixel 5 lo spazio illimitato di Google Foto è in qualità *Risparmio
spazio* (foto ridotte a 16 MP, video a 1080p), non in originale. **Nell'app Immich non attivare il backup della cartella
«Immich»**: le rimanderebbe al server.

### Non tenere due copie: la copia in galleria è temporanea

Google Foto può caricare solo file della memoria condivisa, quindi una copia sul telefono è inevitabile; ma non serve
tenerla. L'app la **elimina da sola** dopo il tempo scelto (*Elimina la copia in galleria dopo*: mai / 3 / 5 / **7** / 30 giorni).
L'originale resta in Immich e, se Google Foto l'ha già caricata, anche nel cloud. Il costo permanente sul telefono è quindi
solo Immich: le copie in galleria sono una piccola coda di pochi giorni.

- **Tetto** (*Copie in galleria in attesa di backup: al massimo*: nessuno / 5 / **10** / 20 / 50 GB): evita di raddoppiare
  di colpo una libreria grande. Raggiunto il tetto la copia si mette in pausa e riprende quando le copie vecchie vengono
  eliminate — occhio comunque allo spazio libero (vedi sotto).
- **Libera spazio di Google Foto** elimina subito le copie che ha già caricato; l'app se ne accorge e riprende a copiare.
- **Elimina ora le copie in galleria** le toglie tutte (le foto restano in Immich); *Elimina e ricopia da capo* fa anche
  ripartire la copia da tutte le foto.
- Google Foto non dice quando ha finito di caricare, quindi la scadenza è un tempo, non un evento. Se il backup di Google Foto
  resta spento oltre la scadenza, la copia viene eliminata prima del caricamento: alza i giorni o controlla il backup.
- Le foto scattate dal Pixel e caricate su Immich dalla sua stessa app sono già nella Galleria e già in Google Foto: non
  servono copie (Google Foto riconosce comunque i duplicati dal contenuto).

### Non tenere la foto nemmeno su Immich (facoltativo, definitivo)

Se vuoi che il telefono non tenga *nessuna* copia a lungo termine — solo un passaggio verso Google Foto — l'app può
eliminare l'originale anche da Immich, un po' di giorni dopo che è stato copiato in galleria. **È definitivo**: da quel
momento l'unica copia che resta è quella (compressa) che Google Foto ha caricato, non l'originale.

Per attivarlo, nella stessa sezione dell'app:
1. In Immich: *Account → Chiavi API → Nuova chiave*, permesso *Elimina risorse* (`asset.delete`). Copiala.
2. Incollala nel campo *Chiave API di Immich* e premi *Salva la chiave*; *Prova la chiave* verifica che funzioni senza
   eliminare nulla.
3. Scegli *Elimina l'originale da Immich dopo*: **Mai** (predefinito, spento), 2, 3, 5 o 7 giorni.

Elimina solo risorse che sono già state copiate con successo in galleria (non tocca mai un file che l'app non è
riuscita a copiare, per esempio un formato non supportato da Android); usa l'API di Immich, non un accesso diretto al
database, così è Immich stesso a occuparsi di miniature e coda di elaborazione. Verificato che gli endpoint rispondono
come previsto dal codice sorgente di Immich (permesso richiesto, elimina anche il file su disco con `force`); non
ancora un giro completo con una chiave reale.

**Se le foto arrivano da un altro telefono con l'app Immich** (per esempio il tuo telefono principale): quell'app carica
ogni foto che non trova sul server. Tolto l'originale da Immich, una foto ancora presente su quel telefono viene
**caricata di nuovo**, e il giro si ripete ogni N giorni. Quindi libera prima quel telefono: app Immich → *Impostazioni →
Libera spazio* (*Seleziona la data limite*, *Data personalizzata*) sposta nel cestino del telefono solo le foto già sul
server (per recuperare davvero lo spazio svuota il cestino della galleria), e fallo più spesso degli N giorni. Il
*Libera spazio* di Google Foto su quel telefono non serve se lì il backup di Google Foto è spento: libera solo quello che
ha caricato quel telefono.

## Diagnosi, log e ripristino

Nell'app: **Esegui diagnosi** verifica proot, Debian, Node, `sharp` (miniature), ffmpeg ed exiftool con prove reali e
scrive `files/immich/logs/diagnosi.txt`. Il menu sopra il riquadro mostra i log di installazione, PostgreSQL, Valkey e Immich.

Dal PC (l'app è debuggable, quindi `run-as` funziona senza root; funziona anche con il telefono bloccato):

```sh
adb shell run-as org.nasonmobile.immich tail -f files/immich/logs/immich.log
adb shell run-as org.nasonmobile.immich tail -n 100 files/immich/logs/setup.log

adb shell am start -n org.nasonmobile.immich/.MainActivity --ez start true        # avvia il server
adb shell am start -n org.nasonmobile.immich/.MainActivity --ez export_test true  # immagine di prova in DCIM/Immich
adb shell am start -n org.nasonmobile.immich/.MainActivity --ez export_dry true   # prova a secco della copia (solo conteggi nel log)
adb shell am start -n org.nasonmobile.immich/.MainActivity --ez export_purge true      # elimina le copie scadute
adb shell am start -n org.nasonmobile.immich/.MainActivity --ez export_purge_all true  # elimina tutte le copie in galleria
```

| Sintomo | Cosa fare |
|---|---|
| Si blocca all'avvio di proot | *Avanzate → proot senza seccomp* (l'app ci prova già da sola una volta) |
| Il server si ferma dopo un po' | controlla *Restrizioni sui processi figli* e *Risparmio batteria* nell'app |
| Installazione interrotta a metà | premi di nuovo *Installa e avvia*: riparte dal punto giusto |
| Debian o pacchetti rovinati | *Ripara*: reinstalla Debian e l'app Immich, **database e foto restano** |
| Foto o video senza miniatura dopo una chiusura forzata dell'app | lavori persi dagli APK precedenti al 24/9/2026 (Valkey senza AOF): in Immich *Amministrazione → Processi*: *Estrai metadati → Mancanti*, poi *Genera miniature → Mancanti* |
| Video senza miniatura, «Errore nel caricamento dell'immagine» | video HDR con un ffmpeg senza `tonemapx` (APK precedenti al 23/9/2026): aggiorna l'APK, poi in Immich *Amministrazione → Processi*: *Genera miniature → Mancanti* e *Transcodifica video → Mancanti* |
| In Amministrazione → Informazioni server il campo ImageMagick è vuoto | APK precedenti al 28/9/2026: quel Debian non ha ImageMagick (solo informativo, Immich non lo usa per altro). Aggiorna l'APK |
| Spazio in esaurimento | *Elimina ora le copie in galleria*, abbassa il tetto o i giorni della copia, o cancella dal backup sul telefono (`ImmichBackup`) quello che hai già salvato altrove; sul server: *Utilità → Revisiona file pesanti*, poi *Svuota cestino* (le risorse eliminate liberano spazio solo quando il cestino si svuota, o dopo 30 giorni) |

## Aggiornare Immich

Quando Immich pubblica una versione nuova (la pagina web la segnala agli amministratori), dal PC:

```sh
pc/update.sh --check          # versione pubblicata, quella già compilata in dist/ e quella su ogni dispositivo collegato
pc/update.sh -s SERIALE       # compila l'ultima versione (se non è già pronta) e la installa su quel dispositivo
pc/update.sh --github -s SERIALE   # uguale, ma scarica l'APK da Releases invece di compilarlo
```

`pc/update.sh` usa `pc/build-app.sh`, che di suo prende l'ultima versione pubblicata e ricava **da solo** le dipendenze
che la stessa versione usa nell'immagine Docker ufficiale, checksum compresi (`pc/build-app.sh --versions` le mostra):

| Dipendenza | Da dove la prende |
|---|---|
| immagine base | `server/Dockerfile` di Immich a quella versione (`base-server-prod:<tag>`) |
| Node | `server/Dockerfile` di [immich-app/base-images](https://github.com/immich-app/base-images) a quel tag (quello di produzione, non quello di sviluppo di `mise.toml`) |
| jellyfin-ffmpeg + sha256 | `server/packages/ffmpeg.json` di base-images a quel tag |
| VectorChord + sha256 | tag dell'immagine postgres nel `docker/docker-compose.yml` di Immich; sha256 dal rilascio su GitHub |
| pnpm, extism-js, binaryen | `mise.toml` di Immich |

Ognuna si può forzare con la sua variabile (`IMMICH_VERSION`, `NODE_VERSION`, `FFMPEG_VERSION`, …: vedi l'inizio dello
script). Sul telefono l'aggiornamento è un `adb install -r`: il server si ferma qualche minuto e riparte da solo, Immich
esegue le migrazioni del database all'avvio e l'app rifà la preparazione di Debian se il pacchetto nuovo porta .deb
diversi (ffmpeg, VectorChord: li confronta con la riga `DEBS=` di `/etc/immich-guest-ready`). `GUEST_LEVEL` in
`guest-setup.sh` va aumentato solo quando cambia lo script stesso. Immich fa ogni notte un backup del database
(`library/backups`): prima di un salto di versione grosso controlla che ce ne sia uno recente.

Cosa resta manuale: se una versione nuova cambia i passi di compilazione (pacchetti nuovi nel monorepo, cartelle da
scaricare) `pc/build-app.sh` si ferma con un errore e va adattato; Postgres resta alla versione di Debian (`PG_MAJOR`, 17).

In alternativa, senza reinstallare l'APK: `apk/build.sh --lite` una volta, poi
`adb push dist/immich-android-<versione>-arm64.tar.gz /sdcard/Android/data/org.nasonmobile.immich/files/immich-pack.tar.gz`
e riavvia il server dall'app: un pacchetto in quella cartella ha la precedenza su quello dentro l'APK.

## Rilasci automatici su GitHub

Il workflow [`.github/workflows/immich.yml`](.github/workflows/immich.yml) ogni giorno (04:23 UTC) controlla se Immich
ha pubblicato una versione nuova; se sì la compila con gli stessi script del PC (`pc/build-app.sh`, `apk/build.sh`) su
un server di GitHub e la pubblica in **Releases** con il nome di versione di Immich (es. `v3.2.4`): APK completo, APK
leggero e pacchetto. Si lancia anche a mano da *Actions → Nuova versione di Immich → Run workflow* (volendo con una
versione precisa, o *force* per ricompilare una versione già pubblicata). A ogni versione nuova il workflow fa anche un
commit del file `ULTIMA_VERSIONE`: così GitHub non sospende le esecuzioni pianificate (lo fa dopo 60 giorni senza
attività; se succede, si riattivano da *Actions*).

Installare un rilascio:

- dal telefono stesso: apri la pagina Releases, scarica `immich-server.apk` e installalo (Android chiede il permesso
  di installare app dal browser); si installa sopra la versione precedente senza perdere niente;
- dal PC: `pc/update.sh --github -s SERIALE` scarica l'APK del rilascio invece di compilarlo e lo installa con adb.

**La chiave di firma.** Gli aggiornamenti si installano sopra la versione vecchia solo se l'APK è firmato con la
stessa chiave (`apk/immich-server.keystore`, creata da `apk/build.sh` al primo build e fuori dal repository per
`.gitignore`). Il workflow la prende dal segreto del repository `KEYSTORE_BASE64`, e senza si ferma invece di crearne
una nuova. Per impostarlo o rifarlo:

```sh
base64 -w0 apk/immich-server.keystore | gh secret set KEYSTORE_BASE64 -R mich-de/immic-for-pixels-phone
```

Conserva una copia della chiave fuori dal PC: se si perde, un APK nuovo si installa solo disinstallando il vecchio, e
disinstallare cancella foto e database.

## Dati e backup

Tutto sta nella cartella privata dell'app: `files/immich/{postgres,library,valkey}`.
**Disinstallare l'app cancella tutto, foto comprese.** Immich può fare da solo dump periodici del database in
`library/backups` (controlla in Amministrazione → Impostazioni → Backup del database). Per copiare le foto sul PC:

```sh
adb exec-out run-as org.nasonmobile.immich tar cf - -C files/immich library > library.tar
```

(oppure usa l'app Immich per caricare/scaricare, o le funzioni di esportazione del server).

### Backup sul telefono (senza PC)

La sezione *Backup sul telefono* dell'app copia gli originali (non le miniature) in una cartella normale del
telefono, **`ImmichBackup`** nella memoria condivisa: si vede con qualunque app Gestione file o collegando il
telefono al PC come una chiavetta, senza passare da adb. È manuale (pulsante *Copia ora sul telefono*) e
incrementale: salta i file già presenti con la stessa dimensione, quindi si può fermare e rilanciare senza ricopiare
tutto da capo. Non elimina mai nulla a destinazione, nemmeno se l'originale è stato tolto da Immich. La prima volta
chiede il permesso *Memoria* (Android): serve perché l'app scrive fuori dalla propria cartella privata, fuori da
Immich.

Questo backup, la copia temporanea in Galleria (per Google Foto) e il comando `tar` sul PC sono tre cose diverse e
si possono usare insieme: solo `tar` include anche database e configurazione, non solo le foto.

## Come funziona

- **proot** (i binari sono quelli del pacchetto Termux, ritoccati in `apk/build.sh`) esegue una userland **Debian 13 arm64**
  senza root e senza Docker. Android non lascia eseguire file dalla propria cartella dati, ma lascia eseguire i `lib*.so`
  dell'APK: per questo proot, il suo loader e le sue due librerie sono in `lib/arm64-v8a/`. I tre pacchetti Termux sono
  fissati in `apk/vendor/` (Termux toglie dal suo repository le versioni vecchie).
- Ogni servizio gira in una sessione proot separata con il proprio utente Debian finto (`--change-id`), con riavvio automatico
  e arresto pulito (SIGTERM/SIGINT al processo vero, non a proot).
- **Immich** è compilato sul PC dai sorgenti (`pc/build-app.sh`); solo le dipendenze native (sharp con libvips, bcrypt) sono per
  arm64/glibc, prese dai binari precompilati di npm. pnpm è quello dichiarato da Immich (`mise.toml`); Node invece è quello
  della sua immagine di produzione (ricavato da `pc/build-app.sh`), che può essere più nuovo: la 3.2.4 corregge così
  la perdita di memoria della 3.2.2.
- La memoria condivisa SysV, che PostgreSQL vuole e Android non ha, la emula proot (`--sysvipc`).
- Valkey, che tiene la coda dei lavori di Immich, scrive anche il file di append (AOF, `fsync` ogni secondo): Android
  chiude l'app anche di forza (pure `adb install -r`) e con i soli snapshot si perdevano i lavori degli ultimi minuti.
- **ffmpeg** è quello di Jellyfin (`jellyfin-ffmpeg7`, lo stesso .deb dell'immagine ufficiale di Immich), non quello di Debian:
  per i video HDR (10 bit, BT.2020, come quelli dei telefoni recenti) Immich usa il filtro `tonemapx`, che esiste solo lì.
  Con l'ffmpeg di Debian quei video restano senza miniatura («Errore nel caricamento dell'immagine») e senza transcodifica.
  Sta in `/usr/lib/jellyfin-ffmpeg`, con `ffmpeg` e `ffprobe` collegati in `/usr/local/bin`.
- Quando `guest-setup.sh` cambia (`GUEST_LEVEL` più alto di quello scritto in `etc/immich-guest-ready`) o un pacchetto
  Immich nuovo porta .deb diversi (riga `DEBS=`), l'app lo rilancia anche sulle installazioni esistenti; se l'aggiornamento fallisce (per esempio senza rete) il server parte con i programmi
  di prima e ci riprova all'avvio successivo.

Sul Pixel 5 sono emersi e sono già corretti tre problemi che chiunque provi proot su Android incontra:
1. `libandroid-shmem` ha un percorso di Termux scritto nel binario e va in loop se non esiste (patch in `apk/build.sh`);
   inoltre va in deadlock al secondo `shmget` con la stessa chiave, quindi si usa l'emulazione interna di proot
   (`PROOT_DONT_SHARE_LIBANDROID_SHMEM`);
2. il loader dei binari cercava le librerie in `LD_LIBRARY_PATH`, che il linker di Android a volte ignora: ora il RUNPATH è `$ORIGIN`;
3. `SIGQUIT` è bloccato nei processi figli di un'app Android: la chiusura forzata di proot usa `SIGABRT`.

## Struttura

```
pc/build-app.sh        compila Immich per arm64 (senza Docker), con le dipendenze della sua immagine ufficiale
pc/update.sh           controlla le versioni, compila e installa l'ultima versione di Immich
.github/workflows/     immich.yml: compila e pubblica in Releases ogni versione nuova di Immich
apk/vendor/            pacchetti Termux di proot fissati (Termux toglie le versioni vecchie)
apk/build.sh           costruisce e firma l'APK (aapt2, javac, d8, apksigner: niente Gradle)
apk/install.sh         installa sul telefono via adb e prepara Android
apk/src/…              l'app: Stack (installazione e servizi), ProotCmd, Tar, Configs, servizio, schermata,
                        Exporter/Pruner (Google Foto), Backup (copia sul telefono), BatteryGuard (avviso carica)
apk/assets/guest/      lo script che installa PostgreSQL, Valkey, jellyfin-ffmpeg dentro Debian
apk/test/              prove sul PC: estrattore tar (TarCheck) e configurazioni (ConfigsDump)
```

## Stato dei test

Provato sul Pixel 5 (Android 14, build AOSP con kernel 4.19 personalizzato, 7,5 GB di RAM):

- installazione da zero dall'unico APK (~7 min fino alla prima risposta di Immich), avvio a caldo in ~40 s;
- arresto pulito in meno di 5 s (PostgreSQL con checkpoint finale), riavvio automatico di un servizio dopo un crash,
  ripresa dopo uno spegnimento brusco;
- risposta di Immich dalla rete locale (`/api/server/ping`, interfaccia web);
- **caricamento reale**: una foto JPEG e un video HEVC → il telefono genera miniature e anteprime (anche per il video),
  legge i metadati (data, dimensioni) e transcodifica il video in H.264, tutto in ~25 s;
- a riposo lo stack usa circa 730 MB di RAM.

L'estrattore tar è verificato sul PC contro GNU tar. Non ancora provato: uso prolungato (batteria, temperatura, memoria sotto
carico con librerie grandi), avvio dopo il riavvio del telefono, l'aggiornamento del pacchetto via `adb push`, altri modelli.

ImageMagick nel sistema Debian è verificato sul telefono (`magick --version` nell'ambiente di Immich, 28/9/2026). Non
ancora verificati a schermo: il backup sul telefono (in particolare il permesso *Memoria* su Android 14 con `targetSdk`
28) e l'avviso di batteria (notifica, soglie).

Aggiornamento verificato sul Pixel 5 il 29/9/2026: da 3.2.2 a 3.2.4 con `pc/update.sh -s …` (`adb install -r`), circa
4 minuti di fermo; l'app ha rifatto da sola la preparazione di Debian per annotare i .deb (riga `DEBS=`), Immich ha
eseguito le migrazioni e reimportato i dati geografici (l'avviso di *schema drift* sui `geodata_places` durante
l'importazione è momentaneo: finita l'importazione gli indici ci sono).

## Licenze

L'APK include software di terze parti con le proprie licenze: **proot** (GPL-2.0+, [termux/proot](https://github.com/termux/proot)),
**libtalloc** (LGPL-3.0+), **libandroid-shmem** (BSD), **Debian** e i suoi pacchetti (varie), **PostgreSQL**, **VectorChord**
(AGPL-3.0 o ELv2), **Valkey** (BSD), **Node.js** (MIT), **jellyfin-ffmpeg** (GPL-3.0+) e **Immich** (AGPL-3.0). Se distribuisci l'APK rispetta i loro termini,
in particolare la disponibilità dei sorgenti di proot e di Immich.
