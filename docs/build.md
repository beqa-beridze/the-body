# Building it on the phone

## Before you start

You need the Termux and proot side of [spirit](https://github.com/beqa-beridze/spirit) already working, which is steps 1 and 2 of [its setup guide](https://github.com/beqa-beridze/spirit/blob/main/docs/setup.md). Concretely:

- Termux installed from F-Droid, not from the Play Store
- a Fedora container from `proot-distro`, or at minimum `pkg install openjdk-17` in Termux
- a JDK on whichever side you build. spirit's setup script does not install one, so inside Fedora that's `dnf -y install java-25-openjdk-devel`
- about 1 GB free, 147 MB of which is a one time toolchain download
- a computer with adb is not needed for the build, only for one optional step later

You do not need Android Studio, Gradle, a Google account or a desktop machine.

## Where to put the repo, and why it matters

Clone it into **Termux's home**, not into the Fedora container:

```sh
# in Termux
cd ~
git clone https://github.com/beqa-beridze/the-body
cd the-body
```

Termux's home is `/data/data/com.termux/files/home`, and that path is visible **from inside the Fedora container too**, at exactly the same absolute path. Fedora's `/root` is reachable from Termux as well, but buried under `$PREFIX/var/lib/proot-distro/containers/fedora/rootfs/root`, so it is not the same path on both sides. One path works from both sides, the other only from one. The build script hops into the proot by itself and expects the repo where it left it. So: Termux's home.

## The pipeline

No CI either, it all happens on the phone. The toolchain download is a one time 147 MB and after that a build is about a minute, most of which is kotlinc chewing through the Kotlin.

It's just the raw steps Gradle normally does for you. `aapt2` compiles the resources and links them with the manifest, which also spits out `R.java`, and `javac` compiles that. Then `kotlinc` does the app itself, which is the slow bit. `d8` turns all the class files plus the Kotlin stdlib into one `classes.dex`, a few lines of Python shove the dex into the apk, and `apksigner` signs it.

## Get the toolchain, once

```sh
bash termux/fetch-toolchain.sh
```

That installs `aapt2`, `d8` and `apksigner` as Termux packages, and because of the path thing above, the proot finds them too. Then it downloads JetBrains' plain `kotlinc` zip and pulls `android.jar` out of Google's platform-33 package, both into `tools/`, which is gitignored. About 147 MB of download, 117 MB once it's unpacked.

Run it from Termux. It installs Termux packages, so from inside the container it just tells you to go and do that first.

You also need a JDK wherever you're building. Inside Fedora that's `dnf install java-25-openjdk-devel`, in Termux `pkg install openjdk-17`, though installing `d8` and `apksigner` drags one in anyway.

## Build

```sh
bash termux/build-apk.sh
```

If it finds a JDK it just builds. Run it from plain Termux and it hops into the Fedora proot by itself. Output is `app-debug.apk` in the repo root, around 770 KB.

Install it **from Termux**, not from inside the container:

```sh
# in Termux, from the repo root
termux-open app-debug.apk
```

That matters: `termux-open` is a Termux:API command, and those do nothing at all from inside the proot except pop an error notification. If the build hopped into Fedora for you, type `exit` first. Or just find the apk in a file manager and tap it.

There is no prebuilt APK in the releases, on purpose. An apk is only useful to you if it is signed with a key you control, and an app with an accessibility service and SMS permission is not something you should install from a stranger's binary anyway.

## The signing key

The first build generates one in `tools/body.keystore` with a random password sitting next to it in `tools/body.keystore.pass`. Neither is committed, and `tools/` is in `.gitignore`.

Keep both files. Android refuses to install an update signed by a different key, so if you lose them your next build won't go over the top of the installed app and you'll have to uninstall first, which also wipes the bridge token and every permission you granted.

That happened here. The original key got committed to this repo by mistake, so I took it out of the history and made a new one. Everything since is signed with that.

## If it breaks

- `missing $TERMUX/bin/aapt2` and friends means `fetch-toolchain.sh` hasn't run, or it ran in the proot instead of Termux. The Termux packages have to be installed from Termux.
- `missing: unzip` from the fetch script. `pkg install unzip` in Termux, it isn't part of the base install.
- The build needs `python3` on whichever side is doing the work. Fedora's is in the setup line, but Termux's base has none, so if you're building on the Termux side, `pkg install python` as well. Without it you don't even reach step 1: the version numbers get read out of `BuildConfig.kt` with Python before anything else happens.
- `build-apk.sh` is the entry point and it delegates. If you want to skip the delegation and run the real pipeline directly, that's `termux/build-apk-fedora.sh`, which is what the failure message points you at.
- `no JVM at ...` means there's no JDK where you're building, or it's somewhere else. Install one (`dnf -y install java-25-openjdk-devel` in Fedora, `pkg install openjdk-17` in Termux) or point `BODY_JAVA_HOME` at the one you have.
- If `kotlinc` runs out of memory, close things. It's a JVM compiler running on a phone and it's by far the slowest step.
- Bumping the version: `VERSION_CODE` and `VERSION_NAME` live in `app/src/main/kotlin/com/beqa/body/BuildConfig.kt` and the build script reads them out of that file, so there's nowhere else to change them.

## Getting rid of it

Delete the repo and `tools/`. Removing the app itself is in [install.md](install.md), and removing the container is in [spirit's setup guide](https://github.com/beqa-beridze/spirit/blob/main/docs/setup.md).
