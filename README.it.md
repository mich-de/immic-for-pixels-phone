# Immich Server per Android

[English](README.md) · **Italiano**

Trasforma un vecchio **Google Pixel** in un server di foto [Immich](https://immich.app) privato, e usa il suo backup
illimitato gratuito su Google Foto come copia nel cloud. Un solo APK: niente root, niente Docker.

```
 i tuoi telefoni ──app Immich──▶  vecchio Pixel: server Immich  ──DCIM/Immich──▶  Google Foto
                                  originali a piena qualità                        copia gratuita illimitata
```

**[Scarica l'ultimo rilascio](https://github.com/mich-de/immic-for-pixels-phone/releases/latest)** · sempre l'ultima versione di Immich ·
provato su un Pixel 5 (Android 14) · progetto non ufficiale, non affiliato a Immich. L'app è in inglese: qui sotto i
pulsanti sono citati con il loro nome.

## Perché un Pixel 1–5

Google Foto dà a questi telefoni il backup gratuito e illimitato delle foto e dei video caricati *dal telefono stesso*:

| Telefono | Backup illimitato gratuito su Google Foto |
|---|---|
| Pixel / Pixel XL (2016) | qualità originale |
| Pixel 2 – Pixel 5 | Risparmio spazio (foto fino a 16 MP, video fino a 1080p) |
| Pixel 6 e successivi | niente |

(Dalla [guida di Google Foto](https://support.google.com/photos/answer/6220791); Google può cambiare queste condizioni.)

Così il Pixel tiene i tuoi originali a piena qualità in Immich, per tutta la famiglia, e passa ogni foto a Google Foto
gratis. L'app funziona anche su **qualunque telefono Android arm64** come semplice server Immich, solo senza il backup
gratuito di Google Foto.

## Installazione

Serve il telefono che farà da server (arm64, Android 8+, qualche GB libero), il Wi-Fi di casa e circa 15 minuti.

1. **Installa l'APK.** Su quel telefono apri
   l'[ultimo rilascio](https://github.com/mich-de/immic-for-pixels-phone/releases/latest), scarica `immich-server.apk`
   (~290 MB) e aprilo. Quando Android lo chiede, consenti al browser di installare app; se Play Protect non conosce
   l'app, scegli *Installa comunque*.
2. **Avvialo.** Apri **Immich Server** e premi **Install and start**. La prima volta servono 10–15 minuti e internet:
   tieni il telefono in carica. Quando lo stato diventa **Running**, l'app mostra l'indirizzo del server, per esempio
   `http://192.168.1.20:2283`.
3. **Evita che Android lo fermi.** Nella stessa schermata:
   - **Exclude from battery optimization** → consenti;
   - **Child process restrictions (Android 12+)**: da Android 14 attiva *Impostazioni → Sistema → Opzioni
     sviluppatore → Disattiva restrizioni processi figli* (per vedere le *Opzioni sviluppatore* tocca 7 volte *Numero
     build* in *Informazioni sul telefono*); su Android 12–13 serve un PC con adb, e il pulsante mostra i comandi;
   - spunta **Start the server when the phone boots**;
   - sui telefoni Samsung, Xiaomi e simili lascia anche lavorare l'app in background.
4. **Crea il tuo account.** Da un dispositivo sullo stesso Wi-Fi apri quell'indirizzo nel browser e crea l'account
   amministratore.
5. **Collega i tuoi telefoni.** Installa l'app ufficiale **Immich** sugli altri telefoni, inserisci lo stesso indirizzo
   come *URL del server*, accedi e attiva il backup.
6. **Manda tutto su Google Foto** (Pixel 1–5). Sul telefono server:
   - nell'app attiva **Keep the originals in DCIM/Immich** (chiede il permesso *Memoria* e riavvia il server);
   - in Google Foto attiva il *Backup*; sui Pixel 2–5 tieni la *Qualità del backup* su **Risparmio spazio** (la qualità
     originale userebbe lo spazio del tuo account Google);
   - in Google Foto, *Impostazioni → Backup → Cartelle del dispositivo* → attiva **admin**.

   Da lì in poi ogni foto che arriva su Immich compare in Google Foto pochi minuti dopo.

## Da sapere

- **Non disinstallare mai l'app**: cancella tutte le foto e il database. Gli aggiornamenti si installano sopra: l'app ti
  avvisa quando esce una versione nuova e la installa con un tocco (oppure installa tu `immich-server.apk` del rilascio
  nuovo sopra il vecchio). Resta tutto e il server riparte da solo.
- **Tieni il telefono server sul Wi-Fi e in carica.** L'app può ricordarti di staccarlo all'80% per non stressare la
  batteria (sezione *Battery*).
- **Riserva l'indirizzo IP del telefono nel router** (DHCP statico), altrimenti l'indirizzo può cambiare. Fuori casa usa
  una VPN come Tailscale o WireGuard; non aprire la porta 2283 su internet.
- **Niente machine learning**: su un telefono è troppo pesante, quindi niente ricerca intelligente, riconoscimento dei
  volti né rilevamento dei duplicati.

## Funzioni

Tutto è nell'unica schermata dell'app; dettagli e avvertenze (in inglese) in **[docs/features.md](docs/features.md)**.

| Funzione | Cosa fa |
|---|---|
| [Originali in DCIM/Immich](docs/features.md#originals-in-dcimimmich) | Tiene gli originali dove Google Foto li vede, senza copie (consigliata per Google Foto) |
| [Copia temporanea in galleria](docs/features.md#temporary-gallery-copy) | Alternativa: copia le foto nuove in galleria per Google Foto e cancella le copie dopo N giorni |
| [Eliminare l'originale da Immich](docs/features.md#deleting-the-original-from-immich) | Passaggio facoltativo: toglie l'originale da Immich N giorni dopo che è arrivato su Google Foto (definitivo) |
| [Pulizia delle foto senza file](docs/features.md#cleaning-up-photos-whose-file-is-gone) | Ogni notte sposta nel cestino di Immich le foto il cui originale è stato cancellato da un'altra app |
| [Backup sul telefono](docs/features.md#backup-on-the-phone) | Copia tutti gli originali in una normale cartella `ImmichBackup` |
| [Promemoria batteria](docs/features.md#battery-reminder) | Ti ricorda di staccare sopra e riattaccare sotto una certa carica |
| [Aggiornamenti](docs/features.md#updates) | Ti avvisa quando esce una versione nuova e la installa con un tocco |

## Cercasi tester

Finora l'app è stata provata su un solo telefono: un Pixel 5 (Android 14, 8 GB di RAM). Se la provi su qualcos'altro —
soprattutto un **Pixel 1, 2, 3, 3a, 4 o 4a**, o qualunque altro telefono arm64 —
[apri una segnalazione](https://github.com/mich-de/immic-for-pixels-phone/issues/new?template=test-report.yml), che
funzioni o no. I telefoni con 4 GB di RAM sono la grande incognita.

## Documentazione (in inglese)

- [docs/features.md](docs/features.md) — tutte le opzioni dell'app, con le avvertenze
- [docs/troubleshooting.md](docs/troubleshooting.md) — diagnosi, log e problemi comuni
- [docs/building.md](docs/building.md) — compilare, installare con adb, aggiornare Immich, rilasci automatici
- [docs/how-it-works.md](docs/how-it-works.md) — architettura, note tecniche e stato dei test

## Licenze

L'APK include software di terze parti con le proprie licenze: proot (GPL-2.0+), libtalloc (LGPL-3.0+),
libandroid-shmem (BSD-3-Clause), Debian e i suoi pacchetti (varie), PostgreSQL (PostgreSQL License), VectorChord
(AGPL-3.0 o ELv2), Valkey (BSD), Node.js (MIT), jellyfin-ffmpeg (GPL-3.0+) e Immich (AGPL-3.0). Se ridistribuisci
l'APK rispetta i loro termini, in particolare la disponibilità dei sorgenti di proot e di Immich: ogni rilascio indica
la versione esatta di Immich, e gli script di compilazione sono qui.
