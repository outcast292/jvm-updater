# hello-fx

A JavaFX window that shows its version and can update itself. Everything runs on your machine;
the JDK's `jwebserver` plays the part of the download server. Commands are run from the
repository root.

1. Publish version 1.0.0 (this also makes a demo key pair):

   ```sh
   ./gradlew -p examples/hello-fx publishRelease
   ```

2. Serve the feed in another terminal:

   ```sh
   jwebserver -d "$PWD/examples/hello-fx/build/feed" -p 8000
   ```

3. Start the app through the launcher, as an installed app would start:

   ```sh
   ./gradlew -p examples/hello-fx runWithLauncher
   ```

4. Publish 1.1.0 (change the label in `HelloApp` first if you like):

   ```sh
   ./gradlew -p examples/hello-fx publishRelease -PappVersion=1.1.0
   ```

5. In the window, press **Check for updates**, then **Restart on the new version**.

To try the zero-code mode instead, close the app and start it with `-PzeroCode=1`: the launcher
checks the feed before starting and opens the newest version directly. If port 8000 is taken,
add `-PfeedUrl=http://localhost:<port>/` to the Gradle commands.
