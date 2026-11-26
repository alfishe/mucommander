trolCommander
=============

Fork of muCommander file manager

See a full description at https://trolsoft.ru/en/soft/trolcommander/

Improvements in this fork (`modernization` branch)
==================================================

Network (SMB)
- SMB2/SMB3 support: switched to jcifs-ng, so modern Windows, Samba and macOS shares work
- Listing a Windows server no longer fails because of inaccessible admin shares (`ADMIN$`, `C$`...)

Credentials manager
- One central place for accounts: add, edit, delete and reuse one account for several servers
- Accounts are bound to servers or shares: known locations log in automatically, unknown ones ask
- Passwords are stored in the desktop keyring (Secret Service), with an encrypted file as fallback
- A wrong password is reported, instead of silently showing an empty folder

Usability
- Directory hotlist: `*` button at the right edge of each panel's address bar to add, remove and jump to
  bookmarked folders, including network locations (in the style of Total Commander)
- `Shift+F4` creates a new file in the current folder and opens it in the editor
  ("Edit as" moved to `Ctrl+Shift+F4`)

Large folders (100,000+ files)
- File attributes are read with a single `lstat` per file, in parallel, outside of the UI thread
- Sorting is done in the background with a faster sort; the UI stays responsive
  (100k files: UI blocked 1.6 s -> 20 ms; 300k files: 3.6 s -> 20 ms)
- Column re-sort of 300k files: ~2 s -> 0.2-0.4 s
- ~3.5x less memory per listed file (~450 bytes instead of ~1600)
- Heap starts small (32 MB) and grows only when needed, up to 512 MB (was a fixed 128 MB, which ran out of
  memory at 100k files)

System requirements
===================

- Java 21 or newer (JRE is enough to run)
- Linux, Windows or macOS
- Memory: the heap starts at 32 MB and grows on demand up to 512 MB (about a million listed files over both panels)
- Optional, Linux: `secret-tool` (package `libsecret-tools` on Debian/Ubuntu) and a running Secret Service
  (GNOME Keyring, KWallet, KeePassXC) to keep passwords in the desktop keyring. Without it, passwords are stored
  in `~/.trolcommander/accounts.xml` (owner-only file, weak encryption)

Build requirements
==================

- JDK 21 or newer
- Nothing else to install: the Gradle wrapper (Gradle 8.14) and the Kotlin plugin are downloaded automatically
- Internet access on the first build to fetch dependencies from Maven Central (a few libraries are bundled in `lib/`)

How to build
============

Build the application (single jar with all dependencies, `build/libs/trolcommander-1.0.0.jar`):
`./gradlew shadowJar`

Build a runnable install with launcher scripts (`build/install/trolcommander-shadow/bin/trolcommander`):
`./gradlew installShadowDist`

Run from the sources:
`./gradlew runApp`

Run the tests:
`./gradlew test`

Full build (compile, tests, distributions):
`./gradlew clean build`

Note: start the application with the launcher scripts (or `runApp`) rather than `java -jar`: they pass the JVM
options it needs (`--add-opens`, class loader, heap settings).

Nightly builds
==============

Latest jar file: https://trolsoft.ru/content/soft/trolcommander/nightly/trolcommander.jar

Latest MacOS dmg: https://trolsoft.ru/content/soft/trolcommander/nightly/trolCommander.dmg
