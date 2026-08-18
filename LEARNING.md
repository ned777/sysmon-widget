# Building SysMon From Scratch: A Complete Walkthrough

This document explains, in detail, everything that went into building
**SysMon** — an Android home-screen widget that shows RAM, storage, and
Claude Code token usage for one or more computers, backed by a small
Python agent running on each of those machines. It's written for someone
who knows how to program (you've got a CS degree) but has **never touched
Android development before**, and wants to actually understand the code,
not just copy-paste it.

We'll cover, in order:

1. The big picture — what are we actually building, and why does it need
   two separate programs?
2. Tools and environment — how to build an Android app **without Android
   Studio**.
3. Android concepts you need before any of the code will make sense.
4. The Python agent, line by line.
5. The Android app, file by file, in the order we actually built it.
6. Git and GitHub — what we did, and what the commands actually do.
7. Deploying to a second computer, and making the agent survive reboots.
8. Ideas for extending it yourself.

---

## 1. The big picture

The end result is: you look at your phone's home screen, and see something
like this:

```
Home PC
RAM: 3GB out of 15GB
Storage: 10GB out of 231GB
Claude (Daily): 125K in / 40K out
Claude (Weekly): 800K in / 210K out
Status: Online
Updated 14:32
```

That data has to come from *somewhere*. Your phone has no way to directly
read another computer's memory usage — it's a completely separate device.
So we need two things:

- **Something running on the computer** that can read its own stats
  (`/proc/meminfo` for RAM, disk usage, etc.) and hand them out over the
  network when asked. This is `monitor_agent.py` — a tiny web server.
- **Something running on the phone** that asks that web server "what are
  your stats right now?" and draws the answer on the home screen. This is
  the Android widget.

They talk to each other over plain HTTP, over whatever network already
connects your phone and your computer (same Wi-Fi, or a VPN like
Tailscale). There's no cloud service, no third-party server, no account to
sign up for — just your phone directly asking your computer a question
over your own network.

This client/server split is a really common pattern in software — it's
the same shape as a web browser talking to a website, just with a Python
script standing in for the website.

---

## 2. Tools and environment (no Android Studio)

Normally, Android development happens inside **Android Studio**, a huge
IDE (like IntelliJ, because it *is* IntelliJ under the hood) that bundles
everything: a code editor, an emulator, the Android SDK, build tools, all
wired together with clicky buttons.

We didn't use it. Everything here was built from a plain terminal, because
that's what a coding agent (or a minimal server environment) can drive.
Here's what "the Android SDK" actually *is*, stripped of the IDE:

- **The JDK (Java Development Kit).** Android apps written in Kotlin still
  compile down to Java bytecode and run on a Java-like virtual machine
  (ART, Android's runtime — a descendant of the JVM). You need a real JDK
  to compile anything. On this machine it lives at
  `~/android-toolchain/jdk` (JDK 17 — Android requires a fairly recent
  JDK for modern Gradle/AGP versions).
- **The Android SDK.** This is a folder full of:
  - **Platform jars** — stub versions of the Android API for each
    Android version (`platforms/android-34/android.jar`), used only for
    *compiling against* — your code calls `RemoteViews(...)` etc., and
    the compiler needs to know that class exists and what methods it has.
  - **Build tools** (`aapt2`, `d8`, etc.) — the actual compilers/linkers
    that turn your Kotlin + XML into a real `.apk` file (Android's
    equivalent of a `.exe`).
  - **`platform-tools`** — most importantly `adb` (Android Debug Bridge),
    the command-line tool used to install apps onto a real phone and talk
    to it. This lives at `~/android-toolchain/sdk/platform-tools/adb`.

  On this machine the whole SDK is at `~/android-toolchain/sdk`.
- **Gradle.** This is the build system — the thing that actually reads
  your project's `build.gradle.kts` files, figures out what needs
  compiling, downloads any library dependencies from the internet, and
  produces the final `.apk`. It's conceptually similar to `make`,
  `webpack`, or `cargo build`, just for the JVM/Android world.

  Every Android project checks in a **Gradle wrapper** — the `gradlew` /
  `gradlew.bat` scripts plus a `gradle/wrapper/` folder. This means you
  never need Gradle *installed* on your machine at all: running
  `./gradlew assembleDebug` downloads the exact right version of Gradle
  itself (pinned in `gradle/wrapper/gradle-wrapper.properties`) the first
  time you run it, and caches it. This is why we could copy the wrapper
  files byte-for-byte from the `quickcapture` project into this one and
  it just worked — the wrapper doesn't care what project it's building.

To build anything, you need two environment variables set (or their
Gradle-config equivalents):

```sh
export JAVA_HOME=~/android-toolchain/jdk
```

and a file called `local.properties` in the project root (this file is
**gitignored** — it's specific to your machine, not something to share)
containing:

```properties
sdk.dir=/home/nnguyen/android-toolchain/sdk
```

With those two things in place, `./gradlew assembleDebug` builds a
debug-signed `.apk` at
`app/build/outputs/apk/debug/app-debug.apk` — an installable Android app,
built with zero IDE.

---

## 3. Android concepts you need first

Before the code makes sense, here are the building blocks. If you've done
any web development, a lot of this will feel familiar with different
names.

### 3.1 A project is Kotlin + XML

Android UI is normally described two ways at once:

- **XML layout files** (`res/layout/*.xml`) describe *what views exist and
  how they're arranged* — think of it like HTML: a tree of `<TextView>`,
  `<Button>`, `<LinearLayout>` (a `<div>` that stacks children
  vertically or horizontally) etc.
- **Kotlin code** finds those views at runtime by ID (`findViewById`) and
  wires up behavior — reading text out of them, setting click listeners,
  etc.

The IDs you assign in XML (`android:id="@+id/foo"`) get compiled into a
generated Kotlin/Java class called `R` (for "resources") — so
`R.id.foo`, `R.layout.activity_main`, `R.string.save`, etc., are all
auto-generated constants pointing at things you declared in XML.

### 3.2 Activities

An **Activity** is roughly "one screen the user can be looking at." Our
app has two:

- `MainActivity` — the screen you see when you tap the app icon; here,
  it's the device-management screen (add/remove computers to monitor).
- `WidgetConfigActivity` — a screen Android itself launches automatically
  when you drag the widget onto your home screen, asking "which device,
  and which stats?"

### 3.3 Widgets are NOT activities

This is the single most important thing to understand, and the source of
almost every weird constraint in this codebase: **a home-screen widget is
not your app running**. Your app isn't "open." Nothing of yours is
actively executing most of the time. Instead:

- You give Android a `RemoteViews` object — basically a *serializable
  description* of a layout ("a TextView with this text, styled this
  way") — not a live view object.
- Android's home-screen process (the launcher app, a completely different
  app than yours) receives that description and renders it itself, inside
  *its own* process.

This is a security/stability boundary: a buggy or malicious widget can't
crash or freeze your home screen, because your code never actually runs
inside the launcher. It only ever hands over inert *descriptions* of UI.

Consequences of this that show up directly in our code:

- **You can't use arbitrary custom views** in a widget layout. Only a
  small whitelisted set works: `TextView`, `Button`, `ImageView`,
  `LinearLayout`, `FrameLayout`, `RelativeLayout`, `GridLayout`,
  `ListView`/`GridView` (for scrolling collections), and a few others.
  **`ScrollView` is not on that list, at any Android version** — there is
  no way to make a widget scroll by just wrapping content in a
  `ScrollView`, which is why our stat rows use a `ListView` instead (more
  on that in §5.7).
- **Any click on a widget has to be a `PendingIntent`**, not a normal
  Kotlin lambda — because the click happens inside the *launcher's*
  process, which then has to hand control back to *your* app via an
  Android-mediated "intent" (a cross-process message), not by directly
  calling a function of yours (it doesn't have your code loaded at all).
- **All rendering logic runs on a schedule/trigger, not continuously.**
  There's no "your widget's code is just sitting there running." Android
  wakes your `AppWidgetProvider` up for brief bursts (via `onUpdate`,
  or when you register a click), and then it goes back to not running.

### 3.4 `AppWidgetProvider` — the widget's "controller"

`SysMonWidgetProvider.kt` extends `AppWidgetProvider`, a class Android
calls into at specific moments:

- `onUpdate(context, appWidgetManager, appWidgetIds)` — called when a
  widget instance needs (re)rendering: right after it's added to the home
  screen, and (if you had periodic updates enabled — we don't; see §5.5)
  on a timer.
- `onDeleted(context, appWidgetIds)` — called when the user removes a
  widget from their home screen. We use this to clean up leftover data.
- `onReceive(context, intent)` — the lowest-level entry point; every
  `AppWidgetProvider` *is* a `BroadcastReceiver` under the hood (see
  §3.5), and `onUpdate`/`onDeleted`/etc. are really just `onReceive`
  pre-parsing certain well-known broadcast messages for you and calling
  the right method. We override `onReceive` directly too, to handle our
  own custom "please refresh" message (§5.5).

### 3.5 Broadcasts, Intents, and PendingIntents

Android apps talk to each other (and to themselves, across process
boundaries like widget ↔ launcher) using **Intents** — a small bundle of
"what to do" (an action string) plus optional data. A `BroadcastReceiver`
is a piece of code that's woken up whenever some intent matching what it's
registered for gets broadcast system-wide — like a pub/sub event bus
built into the OS.

Our widget defines its own custom broadcast action:

```kotlin
const val ACTION_REFRESH = "com.sysmonwidget.app.ACTION_REFRESH"
```

and registers to receive it in `AndroidManifest.xml`:

```xml
<receiver android:name=".SysMonWidgetProvider" android:exported="false">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        <action android:name="com.sysmonwidget.app.ACTION_REFRESH" />
    </intent-filter>
    ...
</receiver>
```

A **`PendingIntent`** is how you hand a "do this later, from another
process" capability to code you don't own — like the launcher. When we
attach a click handler to the widget:

```kotlin
val refreshIntent = Intent(context, SysMonWidgetProvider::class.java).setAction(ACTION_REFRESH)
val pendingIntent = PendingIntent.getBroadcast(
    context, 0, refreshIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
)
views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
```

we're saying: "launcher, when the user taps this view, please broadcast
this specific intent on my behalf." The launcher can do that without
needing to know anything about our app's internals — it's just re-firing
a pre-packaged message. That message then arrives back in our own
`onReceive`, which is how tapping the widget ends up re-fetching stats.

`FLAG_IMMUTABLE` is a newer Android security requirement — it tells the
OS the launcher isn't allowed to modify the intent's contents before
firing it, closing off a class of vulnerability where a malicious launcher
could tamper with your intent.

### 3.6 `goAsync()` and why we spin up a `Thread`

`onReceive`/`onUpdate` calls have a hard ~10 second limit before Android
considers the app "Application Not Responding" and may kill it — and
critically, **they run on the main/UI thread**, so anything slow you do
in there (like a network request!) would freeze the whole system
momentarily. `goAsync()` is an escape hatch: it tells Android "I'm not
done yet, don't tear down my process, but I'll finish soon on a
background thread." We use it every time we need to make an HTTP request:

```kotlin
override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
    val pending = goAsync()
    Thread {
        try {
            appWidgetIds.forEach { updateOneWidget(context, appWidgetManager, it) }
        } finally {
            pending.finish()
        }
    }.start()
}
```

We use a plain `Thread` rather than coroutines/`Executor`s here
deliberately — refreshes only happen when tapped (or once when a widget
is first added), so there's no meaningful volume of concurrent work to
justify a thread pool. `pending.finish()` in the `finally` block tells
Android "okay, now you can consider this done" — and it's in a `finally`
so it still gets called even if the network request throws.

### 3.7 `SharedPreferences` — Android's simplest persistent storage

Android apps get a small private key-value store per app, backed by an
XML file on disk that the OS manages for you — no database setup, no
file-path management:

```kotlin
val prefs = context.getSharedPreferences("sysmon", Context.MODE_PRIVATE)
prefs.edit().putString("some_key", "some_value").apply()
val value = prefs.getString("some_key", null)   // null = default if missing
```

We lean on this heavily instead of a real database, because everything we
need to remember is small: a list of devices (serialized as JSON text),
and a handful of small values per widget instance (which device it shows,
its last successful reading, etc.). `.apply()` writes asynchronously in
the background; there's also a `.commit()` that blocks until the write is
done, which we don't need here.

### 3.8 RemoteViewsService — how the widget scrolls

Covered in depth in §5.7, but the short version: since a widget's
`RemoteViews` can't contain a `ScrollView`, Android provides exactly one
way to get scrollable content into a widget — a `ListView` (or
`GridView`/`StackView`) whose rows are supplied by a background
`RemoteViewsService`, the same general shape as a `RecyclerView.Adapter`
in normal Android UI code, just running out-of-process.

---

## 4. The Python agent, line by line

File: `agent/monitor_agent.py`. This is a single self-contained script —
**no `pip install` needed, no dependencies beyond the Python standard
library** — deliberately, so it can be dropped onto any Linux machine
with Python 3 and just run.

### 4.1 The HTTP server

```python
from http.server import HTTPServer, BaseHTTPRequestHandler

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/stats":
            self.send_response(404)
            self.end_headers()
            return
        payload = { ... }
        body = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

if __name__ == "__main__":
    port = int(os.environ.get("MONITOR_PORT", "8765"))
    server = HTTPServer(("0.0.0.0", port), Handler)
    server.serve_forever()
```

`http.server` is a *very* bare-bones built-in HTTP server — you subclass
`BaseHTTPRequestHandler` and override `do_GET` (there's also `do_POST`
etc., unused here since we only need one read-only endpoint). `HTTPServer`
gets a brand-new `Handler` instance per incoming request and calls the
matching `do_*` method.

`("0.0.0.0", port)` means "listen on every network interface this machine
has" (loopback, Wi-Fi, Ethernet, VPN tunnels, all of them) rather than
just `127.0.0.1` (which would only accept connections *from the same
machine* — useless here, since the whole point is your *phone* connecting
in). `serve_forever()` blocks forever, handling one request at a time
(this server is intentionally single-threaded — see §4.5).

### 4.2 Reading RAM usage

```python
def read_meminfo():
    info = {}
    with open("/proc/meminfo") as f:
        for line in f:
            key, _, rest = line.partition(":")
            info[key] = int(rest.strip().split()[0])  # kB
    total_kb = info.get("MemTotal", 0)
    avail_kb = info.get("MemAvailable")
    if avail_kb is None:
        avail_kb = info.get("MemFree", 0) + info.get("Buffers", 0) + info.get("Cached", 0)
    used_kb = total_kb - avail_kb
    percent = round(100 * used_kb / total_kb, 1) if total_kb else 0.0
    return {"total_mb": total_kb // 1024, "used_mb": used_kb // 1024, "percent": percent}
```

`/proc/meminfo` is a virtual file the Linux kernel generates on the fly —
reading it doesn't touch a real disk, it's the kernel handing you live
stats in plain text, one `Key: value kB` pair per line. We parse each
line by splitting on the first `:`.

The tricky bit: "used RAM" is *not* `MemTotal - MemFree`. Linux
aggressively uses spare RAM for disk caching (`Cached`) and I/O buffers
(`Buffers`) — memory that's *technically* in use but instantly reclaimable
the moment an application actually needs it. `MemAvailable` (a
kernel-computed estimate, present on any reasonably modern kernel) already
accounts for this and represents "how much RAM could a new process actually
get right now" — which is what "used" should really be measured against.
We fall back to manually approximating it (`MemFree + Buffers + Cached`)
only if `MemAvailable` isn't present, for older kernels.

### 4.3 Reading disk usage

```python
def read_disk_usage():
    total, used, _free = shutil.disk_usage("/")
    gib = 1024 ** 3
    total_gb = total // gib
    used_gb = used // gib
    percent = round(100 * used / total, 1) if total else 0.0
    return {"total_gb": total_gb, "used_gb": used_gb, "percent": percent}
```

Unlike RAM, Python's standard library has a portable built-in for this —
`shutil.disk_usage(path)` — so there's no need to parse `/proc` or shell
out to `df`. It reports bytes for the filesystem containing `/` (the root
filesystem); `1024 ** 3` converts bytes → gibibytes.

### 4.4 Parsing Claude Code's local usage logs

This is the most involved part, and worth understanding well since it's
directly reading Claude Code's own internal data format.

Claude Code (the CLI you're using right now) keeps a transcript of every
conversation as a `.jsonl` file — "JSON Lines," meaning *one independent
JSON object per line*, not one big JSON document — under
`~/.claude/projects/<encoded-project-path>/<session-uuid>.jsonl`. Using
one-JSON-object-per-line instead of one big JSON array is a common format
for logs/streams: it means a program can append new lines cheaply without
ever having to re-parse or rewrite the whole file, and a reader can
process it a line at a time without loading the whole thing into memory.

Each line that represents something the assistant said includes a
`"usage"` object with token counts, e.g.:

```json
{"type": "assistant", "timestamp": "2026-08-18T02:26:18.921Z", "sessionId": "...",
 "message": {"model": "claude-sonnet-5", "usage": {
     "input_tokens": 2, "output_tokens": 704,
     "cache_read_input_tokens": 43494, "cache_creation_input_tokens": 1509
 }}}
```

```python
def compute_claude_stats():
    today = date.today()
    cutoff = today - timedelta(days=6)  # rolling 7-day window, inclusive of today

    daily = {"input": 0, "output": 0, "cache_read": 0, "cache_creation": 0}
    weekly = {"input": 0, "output": 0, "cache_read": 0, "cache_creation": 0}
    daily_sessions = set()
    weekly_sessions = set()

    root = Path.home() / ".claude" / "projects"
    if root.is_dir():
        for path in root.glob("**/*.jsonl"):
            try:
                with open(path, "r", errors="ignore") as f:
                    for line in f:
                        line = line.strip()
                        if not line:
                            continue
                        try:
                            rec = json.loads(line)
                        except json.JSONDecodeError:
                            continue
                        if rec.get("type") != "assistant":
                            continue
                        message = rec.get("message") or {}
                        usage = message.get("usage")
                        ts = rec.get("timestamp")
                        if not usage or not ts:
                            continue
                        try:
                            local_date = datetime.fromisoformat(ts.replace("Z", "+00:00")).astimezone().date()
                        except ValueError:
                            continue
                        if local_date < cutoff:
                            continue
                        # ... accumulate into daily/weekly, see below
            except OSError:
                continue
```

Things worth calling out:

- **`Path.home() / ".claude" / "projects"`** — `pathlib.Path` overloads
  the `/` operator to join path components, which reads much more
  naturally than `os.path.join(...)` chains.
- **`root.glob("**/*.jsonl")`** — recursively finds every `.jsonl` file
  under that directory, regardless of how deep it's nested (one
  subdirectory per project you've used Claude Code in).
- **`try: json.loads(line) except json.JSONDecodeError: continue`** —
  this is *defensive* parsing, and it's necessary, not paranoid: these
  files are being **actively appended to** by any Claude Code session
  that's currently running (like the one that wrote this very sentence).
  If we happen to read the file at the exact moment a line is
  half-written, `json.loads` on that partial line would throw — and
  since this server might get polled at literally any moment, we can't
  assume we'll never catch a file mid-write. Skipping a bad line instead
  of crashing means one bad line just doesn't count toward the totals
  yet; the *next* poll will pick it up once it's fully written.
- **`except OSError: continue`** at the file level, similarly, guards
  against a file disappearing or becoming briefly unreadable between
  when `glob` found it and when we tried to `open` it.
- **Converting the timestamp**: `rec["timestamp"]` is UTC, formatted like
  `"2026-08-18T02:26:18.921Z"`. Python's `datetime.fromisoformat` doesn't
  accept a trailing `Z` directly (it wants `+00:00`), hence
  `.replace("Z", "+00:00")`. `.astimezone()` with no argument converts
  from UTC into the *local* system timezone — important because "today"
  should mean *your* today, not UTC's today, which could be off by a day
  depending on the time and your timezone.
- **The rolling 7-day window**: `cutoff = today - timedelta(days=6)`
  means "today and the 6 days before it" = 7 days total, inclusive. Any
  record older than that is skipped outright (`continue`) before we
  even look at whether it's part of "daily" or "weekly" — so old
  conversations don't inflate the numbers forever.

The accumulation itself (inside the loop, replacing the `# ...` comment
above):

```python
session_id = rec.get("sessionId") or rec.get("session_id")
input_tok = usage.get("input_tokens", 0) or 0
output_tok = usage.get("output_tokens", 0) or 0
cache_read_tok = usage.get("cache_read_input_tokens", 0) or 0
cache_creation_tok = usage.get("cache_creation_input_tokens", 0) or 0

weekly["input"] += input_tok
weekly["output"] += output_tok
weekly["cache_read"] += cache_read_tok
weekly["cache_creation"] += cache_creation_tok
if session_id:
    weekly_sessions.add(session_id)

if local_date == today:
    daily["input"] += input_tok
    # ...and so on for the rest of the daily fields
    if session_id:
        daily_sessions.add(session_id)
```

Every record within the 7-day window always counts toward `weekly`; it
*additionally* counts toward `daily` only if its date is exactly today.
This means we only need one pass over the files to compute both numbers,
rather than scanning twice.

`rec.get("sessionId") or rec.get("session_id")` handles the fact that
Claude Code's JSONL format has used both a camelCase and a snake_case key
for the session ID across different versions — this defends against
either one being present. Session IDs get collected into a Python `set`
(so duplicates — many lines belong to the same session — automatically
collapse), and `len(daily_sessions)` at the end gives "how many distinct
conversations did I have today."

### 4.5 Why the server is single-threaded

`HTTPServer` (as opposed to `ThreadingHTTPServer`, also in the standard
library) handles one request completely before starting the next — no
concurrency, no locking needed anywhere in this code. This is a
deliberate, simple choice: the only client is your phone's widget,
polling roughly whenever you tap it, which is nowhere near enough load to
need concurrent request handling. Reaching for `ThreadingHTTPServer` here
would just be complexity with no payoff — a good general instinct: match
the tool to the actual load, not the load you could hypothetically have.

### 4.6 Wiring it together

```python
payload = {
    "ram": read_meminfo(),
    "storage": read_disk_usage(),
    "claude": compute_claude_stats(),
    "generated_at": datetime.now().astimezone().isoformat(),
}
```

`do_GET` just calls each of these three functions fresh, every single
request — there's no caching on the agent side at all. Each one is cheap
(reading a couple of small files, or scanning some log files), so
recomputing from scratch on every poll is simpler and more obviously
correct than trying to cache and invalidate.

---

## 5. The Android app, file by file

We'll go roughly in the order we actually built things, since later
pieces build on earlier ones.

### 5.1 Project scaffolding

```
sysmon-widget/
├── settings.gradle.kts     — declares which modules exist ("app") and where to fetch libraries from
├── build.gradle.kts        — top-level: which Gradle plugins are available to sub-projects
├── gradle.properties       — misc project-wide build flags
├── gradlew, gradlew.bat    — the Gradle wrapper launcher scripts (§2)
├── gradle/wrapper/         — pinned Gradle version + its downloadable jar
├── local.properties        — GITIGNORED — points at your local Android SDK install
└── app/                    — the actual Android module
    ├── build.gradle.kts    — this module's config: package name, SDK versions, dependencies
    └── src/main/
        ├── AndroidManifest.xml   — declares every component (Activity/Service/Receiver) and permission
        ├── java/com/sysmonwidget/app/   — all Kotlin source
        └── res/                   — XML resources: layouts, strings, colors, icons, widget metadata
```

`app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sysmonwidget.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sysmonwidget.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // ...
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
```

A few things to unpack:

- **`namespace` vs `applicationId`** — `namespace` is the package your
  generated `R` class and other codegen live under (a compile-time
  concept); `applicationId` is the unique ID the Play Store / Android
  itself uses to identify this specific app on a device (a
  runtime/install-time concept). They're usually the same string, but
  they don't *have* to be — e.g., you could ship "Free" and "Pro"
  variants of one codebase as two different `applicationId`s from the
  same `namespace`.
- **`minSdk = 29`** — the oldest Android version (10, API level 29) this
  app will even install on. Anything using a newer API than what
  `minSdk` guarantees has to be guarded at runtime.
- **`compileSdk` / `targetSdk = 34`** — build against, and declare
  intent to run correctly on, Android 14's API surface and behavior
  rules (e.g., the `FLAG_IMMUTABLE` requirement from §3.5 is a
  targetSdk-34-and-up rule).
- **`dependencies { implementation(...) }`** — this is Gradle's version
  of `pip install` / `npm install`, except the "package registry" is
  Maven Central / Google's Maven repo (declared in `settings.gradle.kts`),
  and each dependency is `group:artifact:version`. `androidx.core:core-ktx`
  adds Kotlin-friendly extension functions over the base Android APIs;
  `appcompat` backports newer UI behavior onto older Android versions;
  `material` gives you Google's Material Design components/theme. We
  deliberately kept this list short — no networking library
  (OkHttp/Retrofit), no JSON library (Gson/Moshi), no background-work
  library (WorkManager) — because Android already ships adequate
  built-in equivalents for a project this size (`HttpURLConnection`,
  `org.json`), and every extra dependency is one more thing that has to
  successfully download and resolve.

### 5.2 The `Device` model and storage

File: `Device.kt`. Since you can now monitor *multiple* computers, we
need somewhere to keep track of "which computers has the user told us
about." This is genuinely just data + persistence, no Android-specific
magic:

```kotlin
data class Device(val id: String, val name: String, val address: String)
```

A Kotlin `data class` is a class where the compiler auto-generates
`equals()`, `hashCode()`, `toString()`, and a `copy()` method for you,
based on the constructor properties — exactly the right tool for "a
plain bag of fields with no real behavior," which is all a `Device` is.

```kotlin
object DeviceStore {
    private const val PREFS = "sysmon"
    private const val KEY_DEVICES = "devices"

    fun loadDevices(context: Context): List<Device> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEVICES, null) ?: return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Device(o.getString("id"), o.getString("name"), o.getString("address"))
        }
    }

    fun saveDevices(context: Context, devices: List<Device>) {
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(JSONObject().put("id", d.id).put("name", d.name).put("address", d.address))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    fun addDevice(context: Context, name: String, address: String): Device {
        val device = Device(UUID.randomUUID().toString(), name, address)
        saveDevices(context, loadDevices(context) + device)
        return device
    }

    fun removeDevice(context: Context, id: String) {
        saveDevices(context, loadDevices(context).filterNot { it.id == id })
    }

    fun findDevice(context: Context, id: String): Device? =
        loadDevices(context).firstOrNull { it.id == id }
}
```

`SharedPreferences` (§3.7) only stores simple types (strings, ints,
booleans, sets of strings) — it has no concept of "a list of structured
objects." So the whole devices list gets serialized as one JSON array
string under a single key, and deserialized back into `Device` objects on
read. `object DeviceStore` (a Kotlin `object`, not `class`) makes this a
singleton — there's only ever one device store, so there's no reason to
instantiate it; you just call `DeviceStore.loadDevices(context)` directly
as if it were a namespaced set of top-level functions.

Notice **every device gets a random UUID `id`**, separate from its
`address`. This matters: a widget remembers *which device* it's showing
by this stable `id`, not by the raw IP address — so if you later edit a
device's address (say, its IP changes), every widget already pointing at
that device automatically picks up the new address next time it refreshes,
instead of needing to be reconfigured one by one.

### 5.3 `MainActivity` — the device manager screen

This is the screen you see when you tap the app icon. Structurally, it's
a very ordinary Android screen: a layout XML, a `ListView`, and an "Add
device" button that pops up a dialog with two text fields.

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var adapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        adapter = DeviceAdapter(this, DeviceStore.loadDevices(this), showDelete = true) { device ->
            DeviceStore.removeDevice(this, device.id)
            adapter.updateDevices(DeviceStore.loadDevices(this))
            SysMonWidgetProvider.refreshAllWidgets(this)
        }
        findViewById<ListView>(R.id.deviceListView).adapter = adapter
        findViewById<Button>(R.id.addDeviceButton).setOnClickListener { showAddDeviceDialog() }
    }

    override fun onResume() {
        super.onResume()
        adapter.updateDevices(DeviceStore.loadDevices(this))
    }
    // ... showAddDeviceDialog() below
}
```

`AppCompatActivity` is the standard base class for any screen you build —
it gives you the backport behavior mentioned in §5.1's `appcompat`
dependency. `setContentView(R.layout.activity_main)` inflates the XML
layout (turns the XML description into real Android `View` objects) and
makes it the screen's content.

**Why `onResume()` re-loads the device list**: `onCreate` only runs
*once*, the very first time the screen is created. But you might leave
this screen, go add/remove a device from a *different* path (there isn't
one here currently, but this is generally the safe habit), or — more
relevantly — come back to this screen after it was already created
earlier in the session. `onResume()` is called every single time the
screen becomes visible again, so refreshing the list there guarantees
you're never looking at stale data.

The `showAddDeviceDialog()` method uses `AlertDialog.Builder`, inflates
a small custom layout (`dialog_add_device.xml`, just two `EditText`
fields for name and address) as the dialog's content, and on the positive
("Save") button, reads the text out of both fields and calls
`DeviceStore.addDevice(...)`. A dialog is a lightweight floating window —
it doesn't get its own `Activity`/back-stack entry, which is the right
weight for "quickly capture two strings," versus building a whole second
screen for it.

### 5.4 `DeviceAdapter` — one list adapter, two screens

`DeviceAdapter.kt` extends `BaseAdapter`, the classic (pre-`RecyclerView`)
Android pattern for "here's how to turn a list of data objects into a
list of on-screen rows, one at a time, on demand" — `ListView` calls
`getView(position, ...)` only for the rows currently visible on screen
(plus a small buffer), recycling old row views as you scroll instead of
creating one view per data item up front. We used the older
`ListView`/`BaseAdapter` pair instead of the more modern
`RecyclerView`/`Adapter` specifically to avoid adding the
`androidx.recyclerview` dependency for what's ultimately a very short,
simple list — `ListView` ships as part of the base Android framework,
no extra dependency needed.

This one adapter is reused in two places — `MainActivity`'s device list
(with a delete button per row) and `WidgetConfigActivity`'s device
*picker* (no delete button, just tap-to-select) — via a constructor flag:

```kotlin
class DeviceAdapter(
    private val context: Context,
    private var devices: List<Device>,
    private val showDelete: Boolean,
    private val onDelete: ((Device) -> Unit)? = null
) : BaseAdapter() {
    // ...
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.list_item_device, parent, false)
        val device = devices[position]
        view.findViewById<TextView>(R.id.deviceNameText).text = device.name
        view.findViewById<TextView>(R.id.deviceAddressText).text = device.address

        val deleteButton = view.findViewById<View>(R.id.deleteButton)
        if (showDelete) {
            deleteButton.visibility = View.VISIBLE
            deleteButton.setOnClickListener { onDelete?.invoke(device) }
        } else {
            deleteButton.visibility = View.GONE
        }
        return view
    }
}
```

`convertView ?: LayoutInflater.from(context).inflate(...)` is the "view
recycling" part: Android hands back an *already-inflated* off-screen row
view (`convertView`) whenever one's available to reuse, and we only pay
the cost of inflating brand-new XML when there isn't one yet (e.g. the
very first screenful).

### 5.5 The widget's own layout, and turning off auto-refresh

`res/xml/sysmon_widget_info.xml` is metadata *about* the widget itself —
not a UI layout, but configuration Android reads to know how to host it:

```xml
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:minWidth="250dp"
    android:minHeight="140dp"
    android:minResizeWidth="110dp"
    android:minResizeHeight="60dp"
    android:targetCellWidth="3"
    android:targetCellHeight="2"
    android:updatePeriodMillis="0"
    android:initialLayout="@layout/widget_sysmon"
    android:configure="com.sysmonwidget.app.WidgetConfigActivity"
    android:resizeMode="horizontal|vertical"
    android:widgetCategory="home_screen" />
```

- `minWidth`/`minHeight` — the size the widget starts at when first
  added; `minResizeWidth`/`minResizeHeight` — how small the user is
  allowed to *shrink* it afterward (dragging the resize handles).
- `resizeMode="horizontal|vertical"` — allow resizing in both directions
  (versus locking one dimension).
- **`updatePeriodMillis="0"`** — this is the setting that makes the
  widget *only* update when you tap it. Nonzero values here would tell
  Android "wake this widget's `onUpdate` up automatically every N
  milliseconds" (and Android silently enforces a 30-minute floor even if
  you ask for less) — `0` disables that timer entirely. The *only* things
  that still trigger a refresh are: the widget being freshly added, and
  our own tap-triggered broadcast (§3.5/§3.6). This was a deliberate
  choice: a widget that quietly polls in the background every 30 minutes
  implies it's "monitoring" continuously, which isn't true of a resource
  snapshot taken once every half hour — better to be an honest
  "check-now" tool than a misleading "live dashboard."
- **`android:configure="...WidgetConfigActivity"`** — this is what makes
  Android automatically launch our config screen (§5.6) the moment the
  user drags the widget onto their home screen, *before* the widget is
  fully added.

`res/layout/widget_sysmon.xml` is the actual on-screen layout — remember,
only the whitelisted "remote view" widget types from §3.3 are legal here:

```xml
<LinearLayout android:id="@+id/widgetRoot" android:orientation="vertical" ...>
    <TextView android:id="@+id/titleText" android:gravity="center" android:textStyle="bold" ... />
    <ListView android:id="@+id/statsList" android:layout_height="0dp" android:layout_weight="1" ... />
    <TextView android:id="@+id/updatedText" android:gravity="center" ... />
</LinearLayout>
```

`layout_height="0dp"` + `layout_weight="1"` on the `ListView` is a common
Android layout trick: in a `LinearLayout`, giving a child `0dp` for the
dimension being distributed and a nonzero `layout_weight` means "give this
child *all the remaining space* after every sibling with a fixed size has
taken what it needs" — here, that's everything between the fixed-height
title at top and the fixed-height "updated" line at bottom.

### 5.6 `WidgetConfigActivity` — device picker, then stat picker

This is the screen Android launches automatically (because of
`android:configure` above) right when you drag the widget onto your home
screen — before the widget instance is actually finalized. It has two
steps, implemented as two sibling `<LinearLayout>` groups in one XML file
(`activity_widget_config.xml`) that we show/hide with
`View.visibility = View.VISIBLE` / `View.GONE`:

```kotlin
class WidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)   // (*) see below
        setContentView(R.layout.activity_widget_config)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // ... build device list, show it in configDeviceListView ...

        listView.setOnItemClickListener { _, _, position, _ ->
            val device = adapter.getItem(position)
            getSharedPreferences("sysmon", MODE_PRIVATE)
                .edit().putString("widget_${appWidgetId}_device_id", device.id).apply()
            showStatsStep()
        }
    }

    private fun showStatsStep() {
        findViewById<View>(R.id.deviceStepGroup).visibility = View.GONE
        findViewById<View>(R.id.statsStepGroup).visibility = View.VISIBLE

        findViewById<Button>(R.id.doneConfigButton).setOnClickListener {
            val enabled = mutableListOf<String>()
            if (findViewById<CheckBox>(R.id.checkRam).isChecked) enabled.add(StatsFormat.KEY_RAM)
            // ... one such check per checkbox ...

            getSharedPreferences("sysmon", MODE_PRIVATE)
                .edit().putString("widget_${appWidgetId}_stats", enabled.joinToString(",")).apply()

            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}
```

**`(*) setResult(RESULT_CANCELED)` at the very top, before anything else**
is an important defensive habit specific to widget-configure activities:
Android's contract here is that if the user backs out of this screen
*without* your code explicitly calling `setResult(RESULT_OK, ...)` first,
Android **deletes the half-added widget** rather than leaving a broken
one on the home screen. Setting `RESULT_CANCELED` immediately means "if
anything goes wrong or the user just presses back, do the safe/expected
thing" — and then the only way to reach `RESULT_OK` is by successfully
completing both steps.

`AppWidgetManager.EXTRA_APPWIDGET_ID` is how this activity finds out
*which* widget instance it's configuring — Android generates a unique
integer ID per widget instance (so if you add the same widget twice,
they're two different `appWidgetId`s, each independently configurable),
and passes it in via the launching `Intent`'s extras.

Every per-widget setting is stored under a key that embeds this ID —
`"widget_${appWidgetId}_device_id"`, `"widget_${appWidgetId}_stats"` — so
one `SharedPreferences` file can hold independent settings for as many
widget instances as you add, without them colliding.

### 5.7 `SysMonRemoteViewsService` — the scrollable stat list

Back to the constraint from §3.3: widgets can't contain a `ScrollView`.
The platform's actual answer for "I need scrollable content in a widget"
is a `ListView` (or `GridView`/`StackView`) whose row content comes from
a **`RemoteViewsService`** — conceptually identical to a normal
`RecyclerView.Adapter`, just running in a different process because,
again, nothing of yours is "running" inside the widget/launcher normally.

```kotlin
class SysMonRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return StatsRemoteViewsFactory(applicationContext, appWidgetId)
    }
}

class StatsRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int
) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<CharSequence> = emptyList()

    override fun onDataSetChanged() {
        val prefs = context.getSharedPreferences("sysmon", Context.MODE_PRIVATE)
        val cached = prefs.getString("widget_${appWidgetId}_last_stats_json", null)
        val reachable = prefs.getBoolean("widget_${appWidgetId}_reachable", true)
        val statsPref = prefs.getString("widget_${appWidgetId}_stats", null)
        val enabledStats = if (statsPref.isNullOrBlank()) StatsFormat.ALL_KEYS.toSet()
                            else statsPref.split(",").toSet()

        rows = if (cached != null) {
            try { StatsFormat.buildStatRows(JSONObject(cached), reachable, enabledStats) }
            catch (e: Exception) { emptyList() }
        } else {
            emptyList()
        }
    }

    override fun getCount(): Int = rows.size
    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_stat_item)
        views.setTextViewText(R.id.statText, rows[position])
        return views
    }
    // getLoadingView, getViewTypeCount, getItemId, hasStableIds — small boilerplate methods
}
```

The `RemoteViewsFactory` interface is deliberately close to a classic
`ListAdapter`: `getCount()` (how many rows), `getViewAt(position)`
(produce row number N as its own tiny `RemoteViews`), `onDataSetChanged()`
(called before Android re-reads the list, your chance to refresh
whatever `rows` is built from).

**Notice this factory doesn't make any network request at all** — it
only reads the already-cached `widget_${id}_last_stats_json` from
`SharedPreferences`. The actual HTTP fetch happens over in
`SysMonWidgetProvider` (§5.8), which writes the freshly-fetched JSON into
that same preferences key *before* telling Android "hey, the list data
changed, go re-read it":

```kotlin
manager.updateAppWidget(id, views)
manager.notifyAppWidgetViewDataChanged(id, R.id.statsList)
```

`notifyAppWidgetViewDataChanged` is the trigger that makes Android call
`onDataSetChanged()` on the factory again — splitting "go fetch new
data" (provider) from "turn whatever data is currently cached into rows"
(factory) into two separate concerns that only communicate through
`SharedPreferences`.

### 5.8 `StatsFormat` — building the actual text, with bold labels

`StatsFormat.kt` is pure formatting logic, with no Android-widget
specifics in it at all — deliberately factored out so both the factory
above (§5.7) and (in earlier, since-removed versions) other call sites
could share it without duplicating the "how do I turn a JSON blob into
readable text" logic.

```kotlin
fun buildStatRows(json: JSONObject, reachable: Boolean, enabledStats: Set<String>): List<CharSequence> {
    val rows = mutableListOf<CharSequence>()

    if (KEY_RAM in enabledStats) {
        val ram = json.getJSONObject("ram")
        val ramUsedGb = ram.getInt("used_mb") / 1024.0
        val ramTotalGb = ram.getInt("total_mb") / 1024.0
        rows.add(boldLabel("RAM: ", "%.0fGB out of %.0fGB".format(ramUsedGb, ramTotalGb)))
    }
    if (KEY_STORAGE in enabledStats) { /* same shape */ }
    if (KEY_CLAUDE_DAILY in enabledStats) { /* ... */ }
    if (KEY_CLAUDE_WEEKLY in enabledStats) { /* ... */ }
    if (KEY_STATUS in enabledStats) {
        rows.add(boldLabel("Status: ", if (reachable) "Online" else "Offline"))
    }
    return rows
}
```

Each `if (KEY_X in enabledStats)` block is skipped entirely for stats the
user unchecked in `WidgetConfigActivity` (§5.6) — that's the whole
mechanism behind "choose what to show": the *set of rows produced* is
just filtered by what's enabled, in a fixed order, rather than there
being any per-widget "layout" to manage.

**Bold labels, regular values, inside one `TextView`:**

```kotlin
fun boldLabel(label: String, value: String): SpannableString {
    val spannable = SpannableString(label + value)
    spannable.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    return spannable
}
```

A plain Kotlin `String` can only be *one* style throughout. A
`SpannableString` wraps a string and lets you attach "spans" — style
instructions that apply to just a sub-range of characters. Here we build
the full text (`"RAM: " + "3GB out of 15GB"`), then attach a
`StyleSpan(Typeface.BOLD)` covering only characters `[0, label.length)` —
i.e., just `"RAM: "` — leaving the rest at normal weight. `RemoteViews`
happily accepts a `SpannableString` anywhere it accepts a `CharSequence`
(which `setTextViewText` does), so this "just works" inside a widget the
same as it would in a normal in-app `TextView`.

### 5.9 `StatsClient` — the actual HTTP call

```kotlin
object StatsClient {
    fun fetchStats(serverAddress: String): JSONObject? {
        return try {
            val url = URL("http://$serverAddress/stats")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            try {
                if (connection.responseCode != 200) return null
                val body = connection.inputStream.bufferedReader().readText()
                JSONObject(body)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
```

`HttpURLConnection` is the JDK's original, low-level HTTP client — no
extra library needed. We deliberately used it instead of a nicer, more
ergonomic library like OkHttp because this app only ever makes *one* kind
of request (a simple `GET`, no auth headers, no retries, no connection
pooling worth optimizing) — reaching for a fuller-featured HTTP client
would add a dependency (and its own resolution risk) for capabilities we
don't use.

Both timeouts (`connectTimeout`, `readTimeout`) matter specifically
*because* this talks to a server that might be turned off, unplugged, or
just unreachable on a given network — without a timeout, a dead server
would leave this call hanging indefinitely, well past the `goAsync()`
window from §3.6. `return null` on any failure (bad response code, or
any exception at all — timeout, connection refused, malformed JSON) is
the signal `SysMonWidgetProvider` uses to know "couldn't reach it,"
which is what drives the whole `Status: Offline` / stale-cache-fallback
behavior.

### 5.10 `SysMonWidgetProvider` — tying it all together

We've already seen pieces of this (`onUpdate`, `onReceive`,
`attachListAdapter`) in earlier sections. The core method,
`updateOneWidget`, is the one place all the previous pieces meet:

```kotlin
private fun updateOneWidget(context: Context, manager: AppWidgetManager, id: Int) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val views = RemoteViews(context.packageName, R.layout.widget_sysmon)
    attachListAdapter(context, views, id)   // §5.7

    // ... attach the tap-to-refresh PendingIntent (§3.5) ...

    val device = resolveDevice(context, prefs, id)   // §5.2's Device, looked up by this widget's saved device_id
    if (device == null) {
        views.setTextViewText(R.id.titleText, context.getString(R.string.widget_no_device))
        manager.updateAppWidget(id, views)
        manager.notifyAppWidgetViewDataChanged(id, R.id.statsList)
        return
    }

    val json = StatsClient.fetchStats(device.address)   // §5.9
    if (json != null) {
        prefs.edit()
            .putString("widget_${id}_last_stats_json", json.toString())
            .putLong("widget_${id}_last_stats_time", System.currentTimeMillis())
            .putBoolean("widget_${id}_reachable", true)
            .apply()
        views.setTextViewText(R.id.titleText, device.name)
        views.setTextViewText(R.id.updatedText, "Updated ${timeString(System.currentTimeMillis())}")
    } else {
        prefs.edit().putBoolean("widget_${id}_reachable", false).apply()
        val cached = prefs.getString("widget_${id}_last_stats_json", null)
        if (cached != null) {
            // still show the device name + last successful data, just mark it stale
        } else {
            // never successfully fetched even once — show "Unreachable" in the title instead
        }
    }

    manager.updateAppWidget(id, views)                       // push the RemoteViews to the home screen
    manager.notifyAppWidgetViewDataChanged(id, R.id.statsList) // tell the ListView's factory to re-read the cache (§5.7)
}
```

Notice the graceful-degradation ladder: fresh data → stale cached data
(marked `Unreachable — last HH:MM`) → no data ever (`Device — Unreachable`
in the title, empty list). At no point does a failed fetch crash anything
or leave the widget blank; it always shows the most useful thing it has.

**`resolveDevice` and the legacy migration:**

```kotlin
private fun resolveDevice(context: Context, prefs: SharedPreferences, id: Int): Device? {
    val deviceId = prefs.getString("widget_${id}_device_id", null)
    if (deviceId != null) {
        return DeviceStore.findDevice(context, deviceId)
    }
    val legacyAddress = prefs.getString("server_address", null)
    if (!legacyAddress.isNullOrBlank()) {
        val device = DeviceStore.addDevice(context, "This computer", legacyAddress)
        prefs.edit().putString("widget_${id}_device_id", device.id).apply()
        return device
    }
    return null
}
```

Earlier in this project's life, before multi-device support existed,
there was only ever *one* global `"server_address"` setting shared by
every widget. When we added per-widget device selection, any
already-installed widget from that earlier version wouldn't have a
`widget_${id}_device_id` yet. Rather than forcing you to remove and
re-add your existing widget, this code notices "no per-widget device set,
but there's an old-style global address" and automatically creates a
`Device` named "This computer" from it, on the very next refresh — a
small but genuinely useful bit of self-healing backward compatibility.

**`onDeleted` — cleaning up after yourself:**

```kotlin
override fun onDeleted(context: Context, appWidgetIds: IntArray) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val editor = prefs.edit()
    appWidgetIds.forEach { id ->
        editor.remove("widget_${id}_device_id")
        editor.remove("widget_${id}_last_stats_json")
        editor.remove("widget_${id}_last_stats_time")
        editor.remove("widget_${id}_reachable")
        editor.remove("widget_${id}_stats")
    }
    editor.apply()
}
```

Without this, every widget you ever add-and-remove would leave its
settings orphaned in `SharedPreferences` forever — small amounts of data,
but unbounded growth over the app's lifetime is exactly the kind of leak
worth avoiding on principle, and it's cheap to just clean it up when
Android tells us a widget was removed.

---

## 6. Git and GitHub — what we actually did

If you're newer to git as a *workflow* (not just the commands), here's
the shape of everything we did, and why.

### 6.1 The mental model

Git tracks **snapshots** of your project over time, called **commits**.
Each commit records: what every tracked file looked like at that moment,
who made the change and when, a short message explaining why, and a
pointer back to the commit(s) before it — so the whole history forms a
chain you can walk backward through. A **repository** ("repo") is just a
project directory plus a hidden `.git/` folder holding that entire chain
of commits, plus git's own bookkeeping.

Your repo can exist purely on your own machine ("local"), and/or be
mirrored to a hosting service like GitHub ("remote"). `git push` uploads
commits you have locally that the remote doesn't have yet; `git pull`
does the reverse. Nothing you do locally (editing files, even committing)
touches GitHub until you explicitly `push`.

### 6.2 Turning a plain folder into a git repo

```sh
git init
```

creates the `.git/` folder — from this point, `git status` will start
telling you what's changed. Right after `git init`, everything is
"untracked" — git sees the files exist but isn't recording their history
yet.

### 6.3 `.gitignore` — telling git what *not* to track

```
.gradle/
/local.properties
build/
app/build/
*.apk
__pycache__/
```

Not everything in a project directory belongs in version control. Two
categories:

- **Machine-specific config** — `local.properties` (§2) points at *your*
  SDK path; committing it would break the build for anyone else (or a
  future you, on a different machine) whose SDK lives somewhere else.
- **Generated/build output** — everything under `build/`, compiled `.apk`
  files, Python's `__pycache__/`. These are all *derived* from the
  source files that *are* tracked — re-running the build regenerates
  them byte-for-byte (well, not literally byte-for-byte, but
  functionally). Committing generated files bloats the repo and creates
  a second source of truth that can drift out of sync with the real one.

Any file matching a `.gitignore` pattern is invisible to `git status`/
`git add` unless you force it.

### 6.4 Staging and committing

```sh
git add path/to/file.kt another/file.xml
git commit -m "Short description of what changed and why"
```

`git add` moves a file into the **staging area** — a middle ground
between "changed on disk" and "permanently recorded in history." This
two-step process (`add`, then `commit`) exists so you can build up
exactly the set of changes you want in one commit, even if you have other
unrelated edits sitting around unstaged. `git status` always shows you
what's staged vs. not.

A good commit message answers "why," not just "what" — `git diff` (or
just reading the diff) already shows *what* changed; the message is your
chance to record the reasoning that isn't visible in the diff itself.
That's why most of the commit messages in this project's history look
like:

```
Replace CPU row with an online/offline Status indicator

Each widget now tracks per-instance reachability (set on every fetch
attempt) and shows "Status: Online"/"Offline" as the first stat row...
```

— a one-line summary, a blank line, then a paragraph of *reasoning*,
which is genuinely useful later when you (or anyone else) are trying to
understand why a piece of code looks the way it does, six months from
now.

### 6.5 Connecting to GitHub

We used the `gh` CLI (GitHub's official command-line tool), already
authenticated on this machine:

```sh
gh repo create ned777/sysmon-widget --private --source=. --remote=origin --push
```

This one command: created a new (initially empty) repository on GitHub
under your account, registered it as this local repo's `origin` remote
(a nickname for "the URL of that GitHub repo" — you can have multiple
remotes, but `origin` is the conventional name for "the main one"), and
immediately pushed your existing local commits up to it.

From then on, `git push` (with no other arguments, once a branch is
"tracking" a remote branch) is enough to send new commits up.

### 6.6 Setting the commit author identity

```sh
git config user.name "Ned Nguyen"
git config user.email "nmdcnn@gmail.com"
```

Every commit records an author name/email. Without any config, git either
refuses to commit or guesses something from your OS username/hostname —
you saw this happen (a commit landed as `Linux-AI
<nnguyen@linuxai.tailf366c8.ts.net>` before we fixed it). Running
`git config` *without* `--global` sets it just for **this one repository**
— useful if different projects should be attributed differently (this
repo uses your Gmail address, matching `quickcapture`; the separate
`nedportfolio` repo, which had older commits already using your GitHub
noreply email, got configured to match *that* repo's existing
convention instead, rather than forcing one identity everywhere).

To fix the one commit that already had the wrong identity, we used:

```sh
git commit --amend --reset-author --no-edit
```

`--amend` replaces the most recent commit instead of creating a new one;
`--reset-author` updates the recorded author to whatever `git config`
currently says; `--no-edit` keeps the existing commit message unchanged.
**This only rewrites the most recent, not-yet-pushed commit** — amending
history that's already been pushed and might be in use elsewhere is a
much bigger deal (see §6.7).

### 6.7 Rewriting history to scrub real IP addresses

At one point, the repo was made public, and it had two real (if
low-sensitivity) IP addresses baked into old commits — a Tailscale
address and a home LAN address — inside README examples and a hint
string. You asked to scrub them entirely, not just fix the *current*
files (which is easy — normal edit + commit) but remove them from every
past commit's history too (much less routine).

This required **rewriting history**, using `git filter-branch`:

```sh
git filter-branch --force --tree-filter '
  for f in README.md app/src/main/res/values/strings.xml; do
    if [ -f "$f" ]; then
      sed -i "s/100\.71\.15\.93/192.168.1.50/g; s/192\.168\.1\.20/192.168.1.50/g" "$f"
    fi
  done
' --tag-name-filter cat -- --all
```

This walks through **every single commit** in the repo's history, checks
out those two specific files as they existed *at that point in history*,
runs a text substitution (`sed`) replacing the real IPs with a
placeholder, and re-commits — for every commit, not just the latest.
Since the content of even one file changed, **every commit's identifying
hash changes too** — a commit's hash is derived from its content plus its
parent's hash, so changing anything early in the chain cascades forward
through everything after it. This is why rewriting history is
fundamentally different from a normal commit: it doesn't add to the
history, it **replaces** it, generating an entirely new set of commit
IDs.

After that:

```sh
git update-ref -d refs/original/refs/heads/main   # filter-branch's automatic backup ref
git reflog expire --expire=now --all               # drop git's local "undo" log of old positions
git gc --prune=now --aggressive                     # actually delete now-unreferenced old data
```

`filter-branch` keeps a safety backup (`refs/original/...`) of what the
branch pointed to *before* the rewrite, specifically so a mistake is
recoverable — deleting that ref, plus expiring the reflog, plus garbage
collection, is what actually purges the old commit objects (the ones
still containing the real IPs) from the local repository.

Finally:

```sh
git push --force origin main
```

A normal `git push` is refused if it would make history "disappear" from
the remote's perspective (a safety check) — which is exactly what we
wanted here, so `--force` overrides that check and makes GitHub's copy of
the branch match our locally-rewritten one exactly, discarding the old
commit objects there too.

**This is a genuinely risky class of operation** — anyone who'd already
cloned the repo, or any other tool that had it open, would now have a
history that's diverged from the rewritten one, and reconciling that is
messy. It's the right call for "this repo is brand new, I'm the only
one with a clone, and I need this data gone" — not something to reach
for casually on a shared/collaborative repository.

### 6.8 Our ongoing workflow

For the rest of this project, once you asked for it, the pattern for
every change became:

1. Edit the relevant file(s).
2. Rebuild (`./gradlew assembleDebug`) and reinstall on your phone via
   `adb`, so you could actually see the change working.
3. `git add -A` (stage everything changed).
4. `git commit -m "..."` with a message explaining the change and why.
5. `git push`.

— committing and pushing after essentially every change, rather than
batching many changes into one big commit, so the history reads as a
sequence of individually-understandable, individually-revertible steps.

---

## 7. Deploying to a second computer

Everything above described *building* the software once. Running it on
more than one machine is just: **copy `monitor_agent.py` there, and run
it** — the script has zero dependencies beyond a Python 3 interpreter and
a Linux `/proc` filesystem (§4), so there's nothing else to install.
Claude Code specifically does **not** need to be installed on a monitored
machine — `compute_claude_stats()` (§4.4) checks `if root.is_dir()`
before doing anything, so a machine with no `~/.claude/projects` at all
just reports all-zero Claude stats instead of erroring.

### 7.1 Wireless ADB (installing the app without a cable)

`adb` (§2) normally talks to a phone over USB. It can also talk over
Wi-Fi ("wireless debugging"), which is how we installed updates onto
your phone throughout this whole project without a cable:

```sh
adb pair <phone-ip>:<pairing-port> <6-digit-code>   # one-time: establishes trust
adb connect <phone-ip>:<connect-port>                 # each session: opens the actual connection
adb -s <phone-ip>:<connect-port> install -r app-debug.apk
```

The pairing port/code come from a one-time "Pair device with pairing
code" flow on the phone (Settings → Developer options → Wireless
debugging); the **connect port is different and changes** essentially
every time wireless debugging is toggled or the phone reconnects to
Wi-Fi — which is why, over the course of this project, you had to keep
reading a fresh port off that same settings screen. Once `adb connect`
succeeds, `adb -s <address> install -r <apk>` installs (or, with `-r`,
reinstalls-over-the-existing-copy-preserving-its-data) the app.

### 7.2 Making the agent survive reboots, without needing anyone logged in

Running `python3 monitor_agent.py` directly in a terminal only lasts as
long as that terminal session does — close the SSH connection (or log
out, or the machine reboots) and it's gone. `systemd` — the service
manager built into virtually every modern Linux distribution, including
Mint — is the standard solution for "run this program in the background,
start it automatically at boot, restart it if it crashes."

A **system-level** unit (as opposed to a `--user` unit, which is tied to
a specific user's login session and by default *doesn't* run unless that
user is logged in, or has "lingering" specially enabled) is the right
choice here specifically because you wanted it running **even if nobody
is logged in** — e.g. recovering automatically after a power outage,
with no one there to log back in:

```ini
[Unit]
Description=SysMon local stats agent
After=network.target

[Service]
Type=simple
User=nnguyen
ExecStart=/usr/bin/python3 /home/nnguyen/monitor_agent.py
Restart=on-failure
Environment=MONITOR_PORT=8765

[Install]
WantedBy=multi-user.target
```

- `After=network.target` — don't even try starting until basic networking
  is up (no point listening on a socket before the network stack exists).
- `User=nnguyen` — system units run as `root` by default; explicitly
  dropping to a normal user is good practice (this script doesn't need
  root privileges for anything it does, so it shouldn't have them).
- `Restart=on-failure` — if the process ever crashes/exits with an
  error, systemd relaunches it automatically.
- `WantedBy=multi-user.target` — `multi-user.target` is the standard
  "system is fully booted into a normal, non-graphical-login-required
  state" milestone; wanting to be started as part of reaching it is what
  makes this start **during boot itself**, independent of whether anyone
  ever logs into a desktop session.

Installing it:

```sh
sudo cp sysmon-agent.service /etc/systemd/system/
sudo systemctl daemon-reload      # re-scan unit files for anything new/changed
sudo systemctl enable --now sysmon-agent
```

`enable` creates the symlink that makes it start automatically on future
boots; `--now` *also* starts it immediately, so you don't have to reboot
just to test it. `systemctl status sysmon-agent` / `journalctl -u
sysmon-agent -f` let you check on it (the latter tails its logs live,
the same way `tail -f` would a log file).

---

## 8. Ideas for extending this yourself

A few natural next steps, roughly in order of how much new ground they'd
cover:

- **Add a new stat.** Add a new function to `monitor_agent.py` (following
  the shape of `read_meminfo`/`read_disk_usage`), add its key to
  `payload` in `do_GET`, add a new `KEY_*` constant + row-building block
  in `StatsFormat.buildStatRows`, and a new checkbox in
  `activity_widget_config.xml` + `WidgetConfigActivity`. Every layer
  we've built already knows how to plumb an arbitrary new stat through.
- **Authenticate the agent.** Right now anyone who can reach the port
  gets the data, no credential required (documented, deliberate
  tradeoff for a personal tool on a private network). A simple shared
  secret — a `MONITOR_TOKEN` environment variable the agent checks
  against a request header, and a matching field in the Android app's
  device model — would close that gap without much new code.
- **Historical graphs.** Right now the widget only ever shows the
  *current* reading. You could have the agent append each reading to a
  small local log/SQLite file, and add a chart to the app (not the
  widget itself — RemoteViews can't draw arbitrary graphics, but a
  normal in-app screen could) showing RAM/storage over time.
- **Push notifications instead of polling.** Right now the phone always
  initiates contact. A more advanced version could have the agent notify
  the phone (e.g. via Firebase Cloud Messaging) when something crosses a
  threshold — "RAM over 90%" — without waiting for a tap.
