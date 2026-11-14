# jvm-updater

Self-updates for Java desktop apps, the way Chrome or Steam do it: new versions download in the
background, next to the installed one, and take over at the next start. If a new version crashes
right away, the previous one comes back by itself.

- No installer to run on each update, no admin rights.
- Releases are signed with Ed25519; nothing unsigned or modified ever runs.
- Only changed files are downloaded.
- Host releases anywhere: a public bucket (S3, B2, R2), GitHub Pages, any static web server, a
  folder. Or plug in your own source, for example a backend handing out private links.
- Works with JavaFX, the module path or the class path. Java 21+, no dependencies.

## How it works

Your installer (jpackage, a zip, anything) ships the Java runtime, the jvm-updater launcher and a
first release, the *baseline*. The launcher is the program users start:

```
launcher ──► picks the newest valid release ──► java --module-path ... your app
   ▲                                                   │
   └──── exit code 75: start again (new release) ◄─────┘
```

Releases are installed side by side under a folder of your choice:

```
~/.myapp/
  state                  current, previous, pending and bad versions
  versions/1.4.0/        the release in use
  versions/1.5.0/        downloaded, starts at the next launch
  logs/launcher.0.log
```

Before each start the launcher checks the release's signature and the hash of every file. A new
release that exits with an error before `--prove-after` (2 minutes by default) is marked bad and
the previous one starts instead. Once it has run that long, it becomes the current release.

## Publishing a release

Make a key pair once and keep the private key out of version control (a CI secret is fine):

```sh
java -cp jvm-updater.jar io.github.outcast292.jvmupdater.cli.Main keygen release
```

Then, for each version, put everything your app needs at run time in one folder (your jar and its
dependencies) and publish it into a feed folder:

```sh
java -cp jvm-updater.jar io.github.outcast292.jvmupdater.cli.Main publish \
  --files build/app --version 1.5.0 \
  --main-module com.example.app/com.example.app.Main \
  --runtime linux-x64 \
  --jvm-arg --enable-native-access=javafx.graphics \
  --key env:RELEASE_KEY --feed build/feed
```

`--key` takes a file or `env:VARIABLE`. Use `--main-class` instead of `--main-module` for an app
on the class path. The feed folder is plain files:

```
latest                        1.5.0
1.5.0/manifest                what the release contains and how to start it
1.5.0/manifest.sig
1.5.0/files/lib/app.jar
```

Upload it with whatever you like (`aws s3 sync`, `rclone`, a push to GitHub Pages). Upload
`latest` last, so nobody sees a version before its files are there. A `stable/` and a `beta/`
folder make two channels.

The `jvm-updater-<version>.jar` attached to each [GitHub release](../../releases) contains both
the launcher and this command line.

## Using it from an app

### Zero code: update at start

Give the launcher a feed. It checks it before starting the app, for at most `--check-timeout`
(10 s), so a slow or offline machine still starts right away; the download then continues in the
background and is used at the next start.

```sh
java -jar jvm-updater.jar \
  --root ~/.myapp --baseline /opt/myapp/lib/app/bundle \
  --public-key MCowBQYDK2VwAyEA... \
  --feed https://downloads.example.com/myapp/stable/
```

### Choose when to switch

To check while the app runs and restart at a good moment (no unsaved work, a quiet hour), use the
`Updater` the launcher prepared:

```java
Optional<Updater> updater = Updater.fromSystemProperties(); // empty when not started by the launcher
if (updater.isPresent()) {
    Updater.Result result = updater.get().checkAndStage(new HttpFeed(URI.create(FEED_URL)));
    if (result.status() == Updater.Status.STAGED) {
        // later, when it suits the user:
        System.exit(Updater.RESTART_EXIT_CODE);
    }
}
```

`checkAndStage` takes a `ProgressListener` for a progress bar. `Status.INCOMPATIBLE` means the
release needs another runtime (`--runtime` differs): only a new installer can bring it.

### Private releases

Implement `Feed` to fetch releases your own way, for example from a backend that checks who is
asking and returns short-lived links to a private bucket. The launcher and `Updater` still check
the signature and every hash, so the feed doesn't need to be trusted. `HttpFeed` also accepts
extra headers for a token.

### Dependency

Through [JitPack](https://jitpack.io/#outcast292/jvm-updater):

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.outcast292.jvm-updater:jvm-updater-core:v0.1.0' }
```

The module name is `io.github.outcast292.jvmupdater`.

## Packaging with jpackage

Ship the launcher as the main jar and the baseline release next to it:

```sh
jpackage --name myapp --input dist --main-jar jvm-updater.jar \
  --main-class io.github.outcast292.jvmupdater.launcher.Supervisor \
  --runtime-image build/runtime \
  --java-options -Xmx32m \
  --arguments '--root ~/.myapp --baseline $APPDIR/bundle --public-key MCowBQYDK2VwAyEA... --runtime linux-x64'
```

where `dist/bundle` is a release directory from your feed. Build the runtime with `jlink` and
only JDK modules (`java.se`, `jdk.unsupported`, ...): your libraries, JavaFX included, travel in
the releases, so they update too. Only a new JDK needs a new installer.

On Windows the app is started with `javaw`, so no console window opens.

## Options

| Launcher option | |
|---|---|
| `--root <dir>` | where releases are installed (`~` is the user's home) |
| `--baseline <dir>` | release shipped with the installer |
| `--public-key <key>` | trusted key, base64 or a `.pub` file; repeat it while rotating keys |
| `--runtime <id>` | refuse releases built for another runtime |
| `--prove-after <time>` | how long a new release must run before it is kept (`120s`) |
| `--feed <url>` | check this feed before each start |
| `--feed-header 'Name: value'` | extra header for the feed |
| `--check-timeout <time>` | longest wait for the feed at start (`10s`) |
| `-- <args>` | passed to the app |

The app receives `jvmupdater.version`, `jvmupdater.root`, `jvmupdater.app-dir` (its files) and the
other settings as system properties.

## Example

[`examples/hello-fx`](examples/hello-fx) is a JavaFX app you can update from your machine with
the JDK's `jwebserver`.

## Limits

- The Java runtime itself is not updated; ship a new installer for a new JDK.
- A release that starts fine and misbehaves later is not rolled back automatically: publish a
  fixed, higher version.
- Files are compared whole: a changed jar is downloaded again in full.

## Building

```sh
./gradlew build
```

## License

[Apache License 2.0](LICENSE)
