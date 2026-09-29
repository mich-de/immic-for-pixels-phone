# Pacchetti Termux fissati

Copie dei tre pacchetti Termux (aarch64) da cui `apk/build.sh` prende proot e le sue librerie. Termux toglie dal
suo repository le versioni vecchie quando ne pubblica di nuove: senza queste copie un build da zero (per esempio su
GitHub Actions) fallirebbe. `apk/build.sh` le usa al posto del download e ne controlla comunque lo sha256.

| File | sha256 | Licenza (come la dichiara Termux) |
|---|---|---|
| `proot_5.1.107.92_aarch64.deb` | `1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9` | GPL-2.0 |
| `libtalloc_2.4.3_aarch64.deb` | `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da` | GPL-3.0 (talloc in origine è LGPL-3.0+) |
| `libandroid-shmem_0.7_aarch64.deb` | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` | BSD 3-Clause |

Sorgenti e ricette di compilazione: [termux/termux-packages](https://github.com/termux/termux-packages)
(`packages/proot`, `packages/libtalloc`, `packages/libandroid-shmem`).
