# Pinned Termux packages

Copies of the three Termux packages (aarch64) from which `apk/build.sh` takes proot and its libraries. Termux removes
old versions from its repository when it publishes new ones: without these copies a build from scratch (for example on
GitHub Actions) would fail. `apk/build.sh` uses them instead of downloading and still checks their sha256.

| File | sha256 | License (as declared by Termux) |
|---|---|---|
| `proot_5.1.107.92_aarch64.deb` | `1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9` | GPL-2.0 |
| `libtalloc_2.4.3_aarch64.deb` | `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da` | GPL-3.0 (upstream talloc is LGPL-3.0+) |
| `libandroid-shmem_0.7_aarch64.deb` | `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6` | BSD 3-Clause |

Sources and build recipes: [termux/termux-packages](https://github.com/termux/termux-packages)
(`packages/proot`, `packages/libtalloc`, `packages/libandroid-shmem`).
