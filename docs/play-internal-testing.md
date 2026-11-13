# Getting her phone onto Google Play's internal testing track

This is the only delivery channel in this project that updates her phone by itself. Everything
else needs a person: the `ci-latest` GitHub prerelease needs somebody with the repository and a
cable, and Firebase App Distribution sends a tester an email and an installer to tap through.
Play installs a new internal-track release in the background, over Wi-Fi, with nothing on screen.

The repository side is done — see `.github/workflows/android.yml`. Every push to `main` builds a
release bundle and uploads it to the internal track, as soon as the `PLAY_SERVICE_ACCOUNT_JSON`
secret exists. Until then that step prints a notice and skips, so nothing here goes red while the
Console side is being set up. What follows is that Console side, in the order it has to happen.

## Why internal testing, and not a normal release

The app declares `READ_SMS`, `SEND_SMS`, `READ_CALL_LOG` and `ACCESS_BACKGROUND_LOCATION`. On
production, or on an open or closed testing track, those mean a restricted-permissions declaration
and a background-location review with a demonstration video. A track of at most 100 named testers
is exempt from both, and a release rolls out in minutes rather than days. It is not a lesser
channel for our purposes: an internal tester's phone auto-updates exactly like any other Play
install.

## 1. Use the existing developer account, but not the existing listing

The account that publishes **your existing Play app** (`<your.existing.app>`,
`<owner>/<existing-app>`) is the reusable part. It is paid for and identity-verified, and
a second app entry on it costs nothing.

**Create a new app entry. Do not publish this app under an existing listing.** A Play listing
is bound to its `applicationId` permanently, so reusing it would mean renaming this app to
`<your.existing.app>` — and to Android that is a different app entirely. On her phone it
would install alongside rather than upgrade, losing the Threema client key, the WhatsApp companion
link and her message history; the watch would stop pairing, because the Wear Data Layer matches on
package name *and* signature; and everybody who has the other app would be offered a
launcher as an update to it.

## 2. At app creation, upload our own signing key

This is the one decision here that is expensive to get wrong, and it can only be made comfortably
once, while the app entry is new.

Play offers to generate an app signing key. **Don't let it.** Choose to provide one, and upload the
key CI actually signs with. Then a Play-delivered build and a sideloaded CI build carry the same
signature and can replace each other on a phone in place.

If Play generates its own key instead, the two builds are permanently incompatible in both
directions: moving her between them means uninstall, reinstall, and re-pairing Threema and
WhatsApp from scratch. Worse and less obviously, **the watch stops working** — the Wear Data Layer
only delivers between applications sharing a package name *and* a signature, and the watch APK is
built by this repository rather than by Play, so it would keep the CI signature while the phone
half changed to Play's.

**The key is `/path/to/upload.jks`**, alias `<upload-key-alias>`, `CN=<Signer Name>`,
valid until 2050. Play allows one key across several
apps; the consequence is only that the two share a fate if it ever leaks, and that a Play app
signing key cannot be rotated comfortably afterwards. It is the same key as the
`SIGNING_KEYSTORE_B64` repository secret: verified by reading the certificate straight out of the
published artifacts rather than by assuming it.

    SHA-256 <SHA-256 FINGERPRINT>

**That fingerprint is the check.** The App signing page and the Digital Asset Links JSON it offers
both show the app signing certificate; if they show anything else, Play is signing with a key of
its own and the paragraph above is your future. Read it off an artifact with
`apksigner verify --print-certs <apk>`, or `keytool -printcert -jarfile <aab>`.

To hand Play this key, download the **encryption public key** (`.pem`) from the App signing page —
it is per-app, so it cannot be kept here — and export with Play's PEPK tool:

    java -jar pepk.jar \
      --keystore=/path/to/upload.jks \
      --alias=<upload-key-alias> \
      --output=upload-key-play.zip \
      --include-cert --rsa-aes-encryption \
      --encryption-key-path=encryption_public_key.pem

The tool reads both passwords with `System.console()`, so a piped stdin gets a
`NullPointerException` and `--keystore-pass`/`--key-pass` would put them in the process list. Run
it under a pty instead and feed the two passwords in order:

    printf '%s\n%s\n' "$STORE_PASS" "$KEY_PASS" | script -qec "java -jar pepk.jar ..." /dev/null

The zip holds `encryptedPrivateKey` and `certificate.pem`; check the certificate is the one above
before uploading it. Done right, the Console reports the app signing certificate as that
fingerprint, and the assetlinks JSON changes to match.

## 3. Upload the first bundle by hand

The Publishing API cannot create an application's very first release — it answers "no application
was found for the package name" until one bundle has been uploaded through the Console.

Get that first bundle by running the Android CI workflow by hand — the **Run workflow** button on
the Actions tab, or `gh workflow run "Android CI" --repo <owner>/<your-repo>`.
A manual run builds the release bundle with no credential involved, publishes it nowhere, and
publishes `app-release.aab` as the **`bundle-latest`** release. Download it from there, upload it to
the internal track in the Console once, and CI takes over from the next push onwards.

It is deliberately not on `ci-latest`: the push job deletes and recreates that release every time,
so a bundle left there lasts only until the next commit.

(The same button is worth remembering as the way to check that the release variant still compiles
without waiting for a push to depend on it.)

## 4. Answer the App content questionnaire

`GOOGLE_PLAY_CONSOLE_FIXES.md` in your existing Play app repository covers most of this; the answers
that differ here:

- **Advertising ID — No.** Firebase pulls `com.google.android.gms.permission.AD_ID` in
  transitively, so the manifest now removes it explicitly (`tools:node="remove"`). This app uses
  Firestore and Auth only, with no ads and no analytics, so "no" is the true answer *and* the
  built manifest agrees with it.
- **Foreground service types.** This app declares `remoteMessaging` (the messenger connections,
  which Android kills the moment the launcher is backgrounded — see `MessengerService`; Threema's
  own always-connected mode uses the same type), `dataSync` (the same connections on phones older
  than Android 14, which have no `remoteMessaging`) and `location`, not `specialUse`. Justify them
  separately. `remoteMessaging` was added on 2026-09-14, because `dataSync` gets six hours a day on
  Android 15 and may not start after a restart; until it is declared in the Console, uploads to
  the internal track may be refused.
- **Data safety.** Messages, contacts, calendar and location are read; what leaves the device is
  what the caregiver portal shows. The caregiver log is deliberately written without phone numbers.

## 5. Add her as a tester

Internal testers are named by Google account. Add the account that is signed in on her phone,
send her the opt-in link, and accept it once on the device — after that, updates are silent
forever. Check that Play's auto-update setting is on (it is by default, over Wi-Fi).

## 6. Create the service account and add the secret

In the Play Console under **Users and permissions**, invite a Google Cloud service account and
grant it release access to *this app only*. Download its JSON key and add it to your
repository as the secret **`PLAY_SERVICE_ACCOUNT_JSON`**. The next push to
`main` publishes to the internal track by itself.

## Things worth knowing afterwards

**Version codes are counted, not typed** — `1000 + git rev-list --count HEAD`, see the root build
file. They always increase, which is what Play requires. Play also refuses a version code it has
already seen, so re-running CI on an unchanged commit fails the upload. That is correct behaviour,
not a bug to route around.

**Her phone stops being debuggable.** Play rejects `android:debuggable`, so builds that reach her
through Play are release builds, and `run-as ch.heuscher.gentleconnect cat databases/…` — the way
her message database gets pulled off a phone for diagnosis — no longer works there. The test phone
stays on the debug APK from the `ci-latest` release; only her phone goes through Play.

**Every push to `main` reaches her.** That was already true of the channel, and Play makes it
literal and unattended: there is no PR in front of it and nothing for anyone to accept. Read the
diff before pushing, not after. A bad release can be halted in the Console, but she may already
have it.
