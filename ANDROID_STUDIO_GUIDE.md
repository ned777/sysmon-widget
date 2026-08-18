# From Zero to Android Developer: Android Studio, and How SysMon Works

You've never built an Android app by hand before, and everything so far has
happened through Claude Code running Gradle and adb from a terminal. This guide
fills in the piece that was skipped: **Android Studio itself** — the actual
program most Android developers use every day — starting from "what do I even
download" all the way through "here's exactly what every file in this project
does and why it's shaped that way."

There's a companion file, `LEARNING.md`, already in this project. It covers the
Python agent (the little server that reports RAM/storage/Claude usage), the
git/GitHub workflow, and deploying to a second computer, all from the
command-line side. This guide focuses on the Android/Kotlin side, through the
Android Studio GUI, in much more beginner-friendly depth. The two overlap a
little on purpose — better repeated than missing.

You don't need to read this front-to-back in one sitting. Part A gets you a
working Android Studio installation. Part B teaches you to build an app and a
widget completely from scratch using Android Studio's own wizards, so you
understand the "normal" way people start new projects. Part C is the big
one — a thorough, assume-nothing walkthrough of every file in *this* project,
tying it back to the source comments that are now sitting right there in the
code for you to read whenever you open it.

---

## Table of contents

**Part A — Getting Android Studio running**
1. [What even is Android Studio? (vocabulary first)](#1-what-even-is-android-studio-vocabulary-first)
2. [Downloading and installing Android Studio](#2-downloading-and-installing-android-studio)
3. [The first-run setup wizard](#3-the-first-run-setup-wizard)
4. [A tour of the Android Studio window](#4-a-tour-of-the-android-studio-window)
5. [Getting a device to run on: your phone, or an emulator](#5-getting-a-device-to-run-on-your-phone-or-an-emulator)
6. [Opening SysMon in Android Studio and running it](#6-opening-sysmon-in-android-studio-and-running-it)

**Part B — Building an app and a widget from scratch, with the wizards**
7. [Creating a brand new empty app](#7-creating-a-brand-new-empty-app)
8. [What Android Studio generated for you, explained](#8-what-android-studio-generated-for-you-explained)
9. [Adding an App Widget with the built-in wizard](#9-adding-an-app-widget-with-the-built-in-wizard)
10. [Comparing the wizard's widget to SysMon's real widget](#10-comparing-the-wizards-widget-to-sysmons-real-widget)

**Part C — How SysMon actually works, file by file**
11. [Programming vocabulary cheat sheet](#11-programming-vocabulary-cheat-sheet)
12. [The project folder structure, tour](#12-the-project-folder-structure-tour)
13. [The Kotlin files, one by one](#13-the-kotlin-files-one-by-one)
14. [The XML files, one by one](#14-the-xml-files-one-by-one)
15. [The Gradle files](#15-the-gradle-files)
16. [Putting it all together: what happens when you tap the widget](#16-putting-it-all-together-what-happens-when-you-tap-the-widget)

**Part D**
17. [Where to go from here](#17-where-to-go-from-here)

---

# Part A — Getting Android Studio running

## 1. What even is Android Studio? (vocabulary first)

A few words are going to come up constantly. Here they are, once, in plain
English, before we touch any software:

- **IDE** (Integrated Development Environment) — a big program that bundles a
  code editor, a way to build/compile your code, a way to run it, and a way to
  debug it, all in one window. Android Studio is Google's official IDE, built
  on top of a general-purpose IDE called IntelliJ IDEA.
- **SDK** (Software Development Kit) — a collection of tools, libraries, and
  reference files needed to build software that targets a particular platform.
  The "Android SDK" is everything needed to build Android apps: compilers,
  emulator images, documentation stubs, etc. Android Studio manages this for
  you — you install a *version* of the SDK (like "Android 14"), and your
  project says which version(s) it wants to build against and run on.
- **Kotlin** — the programming language this app is written in. It's Google's
  recommended language for Android (an older option, Java, is still supported
  but Kotlin is what nearly everyone writes now). Every file ending in `.kt`
  in this project is Kotlin source code.
- **XML** — a markup language (similar spirit to HTML) used to *describe*
  things declaratively rather than write step-by-step instructions. In this
  project, XML describes what the screens/widget LOOK like (layouts), what
  text/colors exist, and configuration metadata. Code (Kotlin) is "do this,
  then this, then this" logic; XML is "here is a static description of a
  thing."
- **Gradle** — the build tool. It's the thing that actually takes all your
  `.kt` and `.xml` files plus any external libraries, compiles everything,
  and packages the result into an installable app. Every time you click "Run"
  in Android Studio, Gradle is working behind the scenes. `gradlew` (which
  Claude has been running from the terminal this whole time) is a script that
  runs Gradle without needing Android Studio open at all — Android Studio is
  really just a nice UI wrapped around the same Gradle build you could run
  from a terminal.
- **APK** — Android's installable app file format (like a `.exe` on Windows,
  or an `.app` on Mac). Building your project produces one of these; installing
  it puts the app on a device.
- **Emulator** vs **physical device** — an emulator is a virtual Android phone
  that runs *inside* your computer (useful when you don't have a real phone
  handy, or want to test different screen sizes/Android versions). A physical
  device is your actual phone, connected either by USB cable or over Wi-Fi
  ("wireless debugging") — this is what you've been using so far.
- **ADB** (Android Debug Bridge) — the command-line tool that talks to a
  connected device or emulator: installing apps, viewing logs, etc. You've
  seen this already — it's the `adb` commands Claude has been running.
  Android Studio uses ADB internally too; it's the same tool either way.

## 2. Downloading and installing Android Studio

1. Go to `developer.android.com/studio` in a browser and download the
   installer for your operating system (Windows/Mac/Linux). It's a large
   download (roughly 1GB) — it bundles a full Java Development Kit (JDK) and
   the base IDE, so you don't need to separately install Java yourself.
2. Run the installer:
   - **Windows**: run the `.exe`, click through the installer (default
     options are fine for a first install).
   - **Mac**: open the `.dmg`, drag Android Studio into your Applications
     folder.
   - **Linux**: extract the `.tar.gz` somewhere like `~/android-studio`, then
     run `android-studio/bin/studio.sh`.
3. Launch Android Studio for the first time.

## 3. The first-run setup wizard

The very first time you open Android Studio, a setup wizard appears and asks
a few questions:

- **Import previous settings?** — choose "Do not import" on a first install.
- **Install type** — choose **Standard**. This installs a sensible default
  set of SDK components automatically (the latest stable Android SDK
  platform, build tools, and an emulator system image). "Custom" lets you
  hand-pick everything, which you don't need to bother with yet.
- **UI theme** — purely cosmetic (Light or Darcula/dark), pick whichever you
  prefer.
- **Verify settings** — a summary screen, click Next/Finish.
- Android Studio then **downloads the SDK components** it decided you need.
  This can take several minutes depending on your internet connection — it's
  downloading gigabytes of platform tools, an emulator system image, build
  tools, etc.

When this finishes, you land on the **Welcome to Android Studio** screen,
with options like "New Project," "Open," and a list of recent projects (empty
for now).

If you ever need to install *additional* SDK versions later (say, to build
against a newer or older Android release than what was auto-installed), that
lives under **More Actions → SDK Manager** from this Welcome screen (or
**Tools → SDK Manager** once a project is open).

## 4. A tour of the Android Studio window

Once you have any project open (we'll open SysMon in a moment), you'll see
roughly this layout. It's worth knowing the names of these panels because
every Android tutorial and every error message assumes you already do:

- **Project panel** (usually the left edge, a tab often labeled "Project" or
  a folder icon) — a file tree of your project. It defaults to an "Android"
  view, which reorganizes your actual folders into a more logical grouping
  (`manifests`, `java`, `res`, ...) rather than showing you the literal
  on-disk folder structure. You can switch this dropdown at the top of the
  panel to "Project" to see the real, literal folder layout instead — useful
  for matching what you see against what a terminal `ls` would show you (and
  what this guide describes in Part C, which uses the real on-disk paths).
- **Editor** (the big central area) — where you read and write code. Tabs
  across the top let you have several files open at once.
- **Logcat** (usually a tab along the bottom) — a live, scrolling stream of
  log messages from whatever device/emulator is running your app. This is
  your #1 debugging tool: when something crashes, the crash's error message
  and stack trace show up here in red. You've effectively been reading a
  version of this already, indirectly, whenever Claude has checked whether an
  install/run succeeded.
- **Build** (another bottom tab) — shows Gradle's build output: progress,
  warnings, and (critically) compile errors when something doesn't build.
- **Run / Debug buttons** — a green triangle (▶) and a green bug icon near
  the top toolbar. Run just launches the app; Debug launches it attached to
  the debugger, letting you pause execution and inspect variables — more
  than you need for now, but good to know it's there.
- **Device dropdown** — right next to the Run button, a dropdown showing
  which device/emulator your next Run will target. This is where you pick
  between your physical phone and any emulators you've created.
- **Gradle sync** — an elephant-shaped icon (🐘) that appears in the toolbar
  whenever a Gradle file has changed and Android Studio wants permission to
  re-read the project structure. You'll see "Sync Now" banners at the top of
  the editor after editing a `build.gradle.kts` file — click it, and wait for
  the small progress bar at the bottom to finish. Skipping a needed sync is
  the single most common reason "my code isn't being picked up" for
  beginners.

## 5. Getting a device to run on: your phone, or an emulator

You've already been doing the "physical phone over Wi-Fi" version of this via
adb commands. Android Studio can do the exact same thing through a friendlier
dialog, or you can use an emulator instead. Both are worth knowing.

### Option A — your physical phone, wirelessly (what we've been using)

1. On your phone: **Settings → Developer options → Wireless debugging** (if
   Developer options isn't visible yet, it's hidden behind tapping **Settings
   → About phone → Build number** seven times).
2. Turn Wireless debugging **on**.
3. In Android Studio: **View → Tool Windows → Device Manager**, then the
   **Physical** tab, then **Pair device using Wi-Fi** (or the "+" button →
   pair over Wi-Fi). Android Studio shows a QR code.
4. On your phone, inside the Wireless debugging screen, tap **Pair device
   with QR code** and scan it. (This is the GUI equivalent of the
   `adb pair <ip>:<port>` command-line flow — same underlying mechanism,
   friendlier interface.)
5. Once paired, your phone shows up in the Device dropdown next to the Run
   button, exactly like a plugged-in phone would.

Note that a wireless debugging *pairing* tends to persist, but the live
*connection* can drop (phone sleeps, switches Wi-Fi networks, etc.) — if your
phone disappears from the list, you may need to reconnect via **Device
Manager → Physical → [your phone] → Connect**, without necessarily re-pairing
from scratch. This is exactly the "device shows offline, need to reconnect"
situation you may have already run into from the terminal side.

### Option B — USB cable

1. Enable **Developer options → USB debugging** on the phone.
2. Plug the phone in with a USB cable.
3. A prompt appears on the phone ("Allow USB debugging?") — accept it.
4. The phone appears in Android Studio's device dropdown automatically.

### Option C — an emulator (no physical phone needed)

1. **View → Tool Windows → Device Manager → Virtual tab → Create device**.
2. Pick a phone definition (e.g. "Pixel 8") → Next.
3. Pick a system image (an Android version to install on the virtual phone) —
   Android Studio will offer to download one if you don't have it yet. Pick
   a recent one and click the download icon next to it, then Next.
4. Confirm and click **Finish**. The emulator now appears in your Virtual
   devices list, and in the device dropdown, ready to launch (it takes a
   little while to boot the first time, like a real phone).

## 6. Opening SysMon in Android Studio and running it

1. From the Welcome screen, click **Open** (or **File → Open** if a project
   is already open), and select the `sysmon-widget` folder (the one
   containing `settings.gradle.kts`) — that file is how Android Studio
   recognizes "this is the root of an Android project."
2. Android Studio will run a **Gradle sync** automatically on first open —
   watch the progress bar at the bottom. This can take a few minutes the
   first time (downloading dependencies), much faster afterwards.
3. Once synced, pick your device from the device dropdown (per section 5),
   and click the green **Run ▶** button (or `Shift+F10`).
4. Android Studio builds the app (you'll see progress in the **Build** tab)
   and installs + launches it on the selected device — the exact same steps
   Claude has been doing via `./gradlew assembleDebug` + `adb install`, just
   as one button instead of two terminal commands.

If you make a code change and want to see it reflected, just click Run again
— Android Studio rebuilds only what changed and reinstalls.

---

# Part B — Building an app and a widget from scratch, with the wizards

This whole project was actually built by hand, file by file, without ever
using Android Studio's project wizards (everything you've seen so far came
from Claude directly writing files and running `gradlew`/`adb` from a
terminal). That's worth knowing explicitly: **the wizards are a convenience
for scaffolding, not the only way things get built** — plenty of real
professional Android code is written and modified directly, the way SysMon
was. But walking through the wizards once is one of the best ways to build
intuition for "what pieces does an Android app fundamentally need," so let's
do exactly that, on a disposable throwaway project (don't do this inside the
SysMon folder — pick a new empty folder, or just cancel out once you've seen
each screen).

## 7. Creating a brand new empty app

1. **File → New → New Project.**
2. You'll be shown a gallery of templates (Empty Views Activity, Empty
   Compose Activity, Bottom Navigation Activity, ...). **Pick "Empty Views
   Activity."** This matters: SysMon is written in the older, XML-layouts +
   `findViewById` style ("Views"), not the newer Compose style (an
   entirely different way of building UI, purely in Kotlin code, no XML
   layouts). Picking "Empty Compose Activity" instead would generate code
   that looks nothing like what's in this guide.
3. Fill in the New Project form:
   - **Name** — the human-readable app name.
   - **Package name** — the unique reverse-domain identifier (like
     `com.sysmonwidget.app` in this project). Convention is
     `com.yourname.appname` or `com.yourcompany.appname` — it doesn't need to
     be a real domain you own for a personal project.
   - **Save location** — where the project folder gets created on disk.
   - **Language** — Kotlin (leave this as-is; Java is the legacy option).
   - **Minimum SDK** — the oldest Android version willing to run this app
     (SysMon uses API 29 / Android 10 — see section 15 for why). Android
     Studio shows you what percentage of active devices that covers.
   - **Build configuration language** — Kotlin DSL (`.gradle.kts`), matching
     what SysMon uses (the older alternative, Groovy `.gradle` files, is
     still common in older tutorials but functionally equivalent).
4. Click **Finish**. Android Studio generates a complete, runnable
   (if minimal) app and opens it.

## 8. What Android Studio generated for you, explained

Looking at the **Project** panel (switched to the real "Project" view, not
the reorganized "Android" view — see section 4), you'll find a structure that
should already look familiar, because it's the same shape as SysMon:

```
app/
  build.gradle.kts          ← module-level Gradle config (see section 15)
  src/main/
    AndroidManifest.xml     ← declares your one Activity
    java/com/.../MainActivity.kt
    res/
      layout/activity_main.xml
      values/strings.xml
      values/colors.xml
      values/themes.xml
      mipmap-.../ic_launcher...  ← app icon, several resolutions
build.gradle.kts            ← root/project-level Gradle config
settings.gradle.kts
gradle.properties
```

`MainActivity.kt` in the freshly generated project is tiny — just enough to
call `setContentView(...)` and show "Hello Android!" using either
`findViewById` directly or a generated `ViewBinding` class, depending on the
exact Android Studio version's template. Everything else exists purely to
support that one screen: the manifest registers it as the launcher Activity,
`activity_main.xml` describes its (very plain) layout, and `strings.xml` /
`colors.xml` / `themes.xml` hold the text and styling it references.

This is genuinely the same skeleton SysMon grew from — compare it against
`app/src/main/AndroidManifest.xml` and `app/src/main/java/com/sysmonwidget/app/MainActivity.kt`
in *this* project (both now have thorough comments — see Part C) and you'll
recognize the shape immediately, just with a lot more built on top.

## 9. Adding an App Widget with the built-in wizard

Still in that same throwaway project:

1. Right-click the `app/src/main/res` folder in the Project panel (or
   `res` in the Android view) → **New → Widget → App Widget**.
2. A dialog appears asking for:
   - **Class Name** — becomes your `AppWidgetProvider` subclass, the
     equivalent of `SysMonWidgetProvider.kt` in this project.
   - **Placement** — Home screen, Lock screen (deprecated on modern
     Android), or both.
   - **Minimum Width/Height** and **Resizable** — the equivalent of the
     `minWidth`/`minHeight`/`resizeMode` attributes you'll find explained in
     `app/src/main/res/xml/sysmon_widget_info.xml`.
   - **Configuration Screen** — checking this box generates a companion
     Activity (like `WidgetConfigActivity.kt`) that launches before the
     widget is placed, exactly the same purpose it serves in SysMon.
3. Click **Finish**. Android Studio generates:
   - A `<YourWidgetName>.kt` file extending `AppWidgetProvider`, with an empty
     `onUpdate()` you're expected to fill in.
   - `res/xml/<your_widget_name>_info.xml` — the metadata file, pre-filled
     with the sizes/options you picked.
   - `res/layout/<your_widget_name>.xml` — a placeholder widget layout (often
     just a single centered TextView).
   - A `<receiver>` entry automatically added to `AndroidManifest.xml`,
     wiring the new provider class to the new info.xml, the same shape as the
     `<receiver android:name=".SysMonWidgetProvider">` block you'll see
     explained in Part C.
   - If you checked "Configuration Screen," a full second Activity plus its
     own layout and its own manifest `<activity>` entry.

Run the app once (per section 6), then **long-press your home screen → Widgets
→ [your app name]**, and drag the generated widget onto the home screen to see
it appear — this is the exact same placement flow you use for the real SysMon
widget.

## 10. Comparing the wizard's widget to SysMon's real widget

The wizard gives you a fully working *skeleton*; SysMon fills that skeleton
in with real behavior. Side by side:

| Piece | Wizard-generated | SysMon's actual version |
|---|---|---|
| Provider class | Empty `onUpdate()`, usually just sets some static text | `SysMonWidgetProvider.kt` — fetches live stats over HTTP, handles tap-to-refresh, remembers per-widget device/settings |
| Widget layout | One placeholder TextView | `widget_sysmon.xml` — title row, IP address, a whole scrolling stats list, timestamp row |
| Scrolling content | Not generated by the basic wizard at all | `SysMonRemoteViewsService.kt` — a whole separate mechanism (`RemoteViewsService`) needed specifically because a widget's list can't use a normal in-process Adapter (explained in depth in Part C) |
| Config Activity | Empty layout, immediately calls `setResult(RESULT_OK)` | `WidgetConfigActivity.kt` — a real two-step wizard (pick device, pick stats) |
| Data persistence | None by default | `Device.kt`'s `DeviceStore` — SharedPreferences-backed storage shared between the app and the widget |

None of this is a criticism of the wizard — it *can't* know what your widget
should actually do, so it necessarily stops at "a button you can press that
proves the wiring works." The value of walking through it is recognizing
which pieces are "boilerplate every widget needs" (the manifest entries, the
info.xml, the basic provider shape) versus which pieces are specific to what
SysMon does (the HTTP fetch, the list of devices, the scrolling stats list).

---

# Part C — How SysMon actually works, file by file

Every source file in this project (every `.kt` file, every layout `.xml`, the
manifest, the Gradle files) now has real comments written directly into it —
open any of them in Android Studio and read alongside this section. This part
of the guide is the map; the comments in the files themselves are the
territory.

## 11. Programming vocabulary cheat sheet

A handful of words are going to come up over and over across every file.
Here they are once, so the per-file walkthrough doesn't have to keep
stopping to define them.

- **Variable** — a named slot that holds a value. `val` means "set once,
  never changes again" (most common in Kotlin — prefer this by default).
  `var` means "can be reassigned later." You'll see far more `val` than `var`
  in this codebase, which is normal, idiomatic Kotlin.
- **Function** — a named, reusable block of code that (usually) takes some
  inputs and produces an output. `fun greet(name: String): String { return
  "Hi $name" }` — `fun` starts it, `(name: String)` is the input, `: String`
  after the parenthesis is what type of thing it gives back.
- **Class** — a blueprint for creating objects that bundle related data and
  functions together. `MainActivity` is a class; when Android actually runs
  your app, it creates one *instance* of that class to represent your
  actual, currently-running screen.
- **Object** (the Kotlin keyword, lowercase `object`) — like a class, but
  Kotlin automatically ensures there's only ever ONE instance of it, ever,
  automatically created the first time it's used. `StatsFormat`,
  `StatsClient`, and `DeviceStore` in this project are all `object`s because
  there's no reason to ever have two separate "formatters" or "device
  stores" — there's naturally just one.
- **Data class** — a class whose only job is holding a bundle of values
  (like `Device`, holding an id/name/address). Kotlin auto-generates useful
  behavior for these (comparing two Devices for equality, printing them
  nicely, cloning them with `.copy()`) so you don't have to write that
  yourself.
- **If / else** — a branch: "if this condition is true, do this; otherwise do
  that." `if (name.isEmpty()) { ... }` reads exactly like English.
- **List** — an ordered collection of items, like `List<Device>` (a list
  where every item is a Device). `listOf(...)` creates a fixed, read-only
  one; `mutableListOf(...)` creates one you're allowed to `.add()` to
  afterward.
- **Set** — like a list, but unordered and with no duplicates — used in this
  project (`Set<String>`) for "which stats are enabled," where order doesn't
  matter and you'd never want the same stat listed twice.
- **Lambda** — a small, inline, anonymous function, usually passed directly
  as an argument to another function. `setOnClickListener { doSomething() }`
  — the `{ doSomething() }` part is a lambda: "here's a tiny bit of code to
  run later, whenever this button gets clicked." You'll see these constantly
  — they're how Android lets you say "when X happens, run this" without
  writing a whole separate named function every time.
- **Null / null-safety** — in many languages, accidentally using a value that
  doesn't actually exist yet ("null") is one of the most common causes of
  crashes. Kotlin makes this a compile-time concern: a type like `String` can
  NEVER be null, but `String?` (note the question mark) explicitly CAN be.
  The compiler then forces you to handle the null case before you're allowed
  to use the value — that's what operators like `?.` (only do the next thing
  if this isn't null), `?:` (use this fallback value if the left side was
  null), and `!!` (I promise this isn't null, crash immediately if I'm wrong)
  are for. You'll see all three used deliberately throughout this project.
- **Extending a class / inheriting** — `class MainActivity : AppCompatActivity()`
  means "MainActivity IS-A AppCompatActivity, plus whatever extra I add." It
  automatically gets all of AppCompatActivity's behavior for free, and can
  override specific pieces (like `onCreate()`) to customize what happens.
- **Interface / implementing** — a contract listing functions a class
  *promises* to provide, without saying how. `RemoteViewsService.RemoteViewsFactory`
  is an interface; `StatsRemoteViewsFactory` implements it by providing real
  versions of every function the interface demands (`getCount()`,
  `getViewAt()`, etc.) — this is how Android's system code can call into your
  custom logic without needing to know anything about your specific class.

## 12. The project folder structure, tour

Switch the Project panel to the real "Project" view (see section 4) and
you'll see this shape:

```
sysmon-widget/
├── app/
│   ├── build.gradle.kts             module-level build config (§15)
│   └── src/main/
│       ├── AndroidManifest.xml      the app's "table of contents" (§14)
│       ├── java/com/sysmonwidget/app/
│       │   ├── Device.kt            §13
│       │   ├── DeviceAdapter.kt     §13
│       │   ├── MainActivity.kt      §13
│       │   ├── StatsClient.kt       §13
│       │   ├── StatsFormat.kt       §13
│       │   ├── SysMonRemoteViewsService.kt   §13
│       │   ├── SysMonWidgetProvider.kt       §13
│       │   └── WidgetConfigActivity.kt       §13
│       └── res/
│           ├── layout/              screen/widget UI descriptions (§14)
│           ├── values/              strings.xml, colors.xml, themes.xml, dimens.xml (§14)
│           ├── values-v31/          Android-12+-only overrides (§14)
│           ├── drawable/            widget_background.xml (§14)
│           └── xml/                 sysmon_widget_info.xml, the widget's metadata (§14)
├── build.gradle.kts                 project-level build config (§15)
├── settings.gradle.kts              §15
├── gradle.properties                §15
├── agent/                           the Python server — see LEARNING.md §4
├── LEARNING.md                      original from-scratch walkthrough (git/agent focus)
└── ANDROID_STUDIO_GUIDE.md          this file
```

The `res/` (resources) folder deserves a quick word on its own: Android draws
a hard line between *code* (in `java/`) and *resources* (in `res/`) —
anything that's UI text, colors, images, or layout structure lives as a
resource, referenced from code by a generated `R` class (`R.layout.widget_sysmon`,
`R.string.app_title`, `R.color.retro_pink`, etc.) rather than embedded
directly in code. This is what lets things like translations or dark-mode
color swaps work by simply adding alternate resource files, with zero code
changes — you already saw a real example of this exact mechanism with the
`values-v31/dimens.xml` override for widget corner radius on newer Android
versions.

## 13. The Kotlin files, one by one

Every file below has full comments in the actual source now. This section
gives you the *big picture* for each one — what problem it exists to solve,
and how it relates to the others — so the in-file comments make sense in
context rather than being a wall of detail with no map.

### `Device.kt`

Two things live here: the `Device` data class (id, name, address — one
monitored computer), and the `DeviceStore` object, which is the *only* code
in the whole app allowed to read/write the saved list of devices. Everything
else — `MainActivity`, `WidgetConfigActivity`, `SysMonWidgetProvider` — goes
through `DeviceStore`'s functions (`loadDevices`, `addDevice`, `updateDevice`,
`removeDevice`, `findDevice`) rather than touching `SharedPreferences`
directly. That's a deliberate pattern: if we ever changed *how* devices are
stored (a real database instead of SharedPreferences, say), only this one
file would need to change.

### `DeviceAdapter.kt`

The bridge between a `List<Device>` and actual rows drawn on screen inside a
`ListView`. It's reused in two different places — the main device-management
screen, and the widget's "pick a device" screen — which is why several of its
behaviors (show delete button? show edit button?) are switches passed in by
whoever creates it, rather than hard-coded.

### `MainActivity.kt`

The screen you see when you tap the app icon. It builds a `DeviceAdapter`
pointed at the ListView from `activity_main.xml`, and provides the actual
add/edit/delete behavior as lambdas passed into that adapter. The
add-device and edit-device dialogs are the same function
(`showDeviceDialog`), parameterized by whether you're editing an existing
device or starting blank — worth reading closely as a small example of how
"add" and "edit" screens are very often just the same form in disguise.

### `StatsClient.kt`

The single place in the entire app that makes a real network request. Its
whole job: given a device's address, try to fetch `/stats` from it over
plain HTTP, and hand back parsed JSON — or `null` if literally anything went
wrong (wrong address, device off, timeout, bad response). Keeping this
"give back null on any failure" contract simple is what lets
`SysMonWidgetProvider` treat "online" vs "offline" as a single, easy check.

### `StatsFormat.kt`

Turns raw numbers (`"used_mb": 8192`) into the actual styled text rows shown
in the widget ("**RAM:** 8GB out of 16GB", with the label part colored
differently from the value part). This is where you'll find `SpannableString`
— Android's way of applying different colors/boldness to *parts* of a single
piece of text, since a plain `String` can only be one uniform style.

### `SysMonRemoteViewsService.kt`

This one exists purely because of a specific limitation: a home-screen
widget is drawn by the *launcher app*, not by SysMon itself, so a normal
in-process `ListView` Adapter (like `DeviceAdapter`) can't work for the
widget's scrolling stats list — the launcher process has no way to call
directly into SysMon's code. `RemoteViewsService`/`RemoteViewsFactory` is
Android's answer: a special cross-process-safe way to supply list rows on
demand. Conceptually it's doing the exact same job as `DeviceAdapter`, just
through a more roundabout mechanism required by widgets specifically.

### `SysMonWidgetProvider.kt`

The single most important file in the project — the widget's "brain." It's
an `AppWidgetProvider`, which is really just a specialized
`BroadcastReceiver`: Android wakes it up at specific moments (widget placed,
tap-to-refresh triggered, widget removed) rather than it running
continuously. Its central function, `updateOneWidget()`, does the real work:
figure out which device this widget watches, try to fetch fresh stats,
build the RemoteViews describing what the widget should now look like, and
hand that off to be drawn. Read this file's comments closely — it also
explains *why* fetching happens on a background `Thread` (network calls are
slow; Android's rules forbid blocking the main thread), and why
`updatePeriodMillis="0"` plus a click listener is used instead of a normal
timer-based refresh.

### `WidgetConfigActivity.kt`

The two-step wizard ("pick a device," then "pick which stats to show") that
runs the moment you drag a new SysMon widget onto your home screen — required
because `sysmon_widget_info.xml` declares a `configure` Activity. The most
important beginner lesson buried in this file: a widget-configuration
Activity that finishes *without* calling `setResult(RESULT_OK, ...)` causes
Android to silently throw the whole widget away — that single line is the
entire difference between the widget actually appearing or not.

## 14. The XML files, one by one

### `AndroidManifest.xml`

The "table of contents." Every Activity, Service, and BroadcastReceiver in
the app has to be declared here or Android won't know it exists — no amount
of correct Kotlin code makes up for a missing manifest entry. Also where
app-wide permissions (`INTERNET`, so `StatsClient.kt` is even allowed to make
network calls) and the app-wide theme are declared.

### `activity_main.xml`, `activity_widget_config.xml`, `dialog_add_device.xml`, `list_item_device.xml`

The four layouts belonging to the "regular app" side of things — screens
built from normal, unrestricted Android Views. Comments in each explain the
specific role of every child view and which Kotlin file wires up its
behavior.

### `widget_sysmon.xml`, `widget_stat_item.xml`

The two layouts belonging to the *widget* side — drawn by RemoteViews, so
restricted to the small whitelist of View types the launcher process knows
how to reconstruct (no arbitrary custom Views here, unlike the app's own
screens).

### `res/xml/sysmon_widget_info.xml`

The widget's size/behavior metadata — never drawn itself, just facts Android
reads about the widget (minimum size, whether it auto-refreshes, which
layout to show first, which Activity configures it). Every attribute is
explained line-by-line in the file's own comments.

### `res/values/colors.xml`, `themes.xml`, `strings.xml`, `dimens.xml`, `values-v31/dimens.xml`, `drawable/widget_background.xml`

The 80s neon styling (black background, yellow border, pink/cyan/yellow/teal
text) lives almost entirely in these small files rather than scattered
across layouts — which is exactly the benefit of centralizing colors/strings
the Android-recommended way. `values-v31/dimens.xml` is a nice concrete
example of Android's resource-qualifier system: the exact same
`@dimen/widget_corner_radius` reference resolves differently depending on
which Android version the app is actually running on, no code branching
required.

## 15. The Gradle files

`settings.gradle.kts`, the root `build.gradle.kts`, `app/build.gradle.kts`,
and `gradle.properties` are all commented in place now too. Briefly, in the
order Gradle actually reads them:

1. **`settings.gradle.kts`** — "here's what modules exist" (just `:app`) and
   "here's where to download plugins/dependencies from."
2. **root `build.gradle.kts`** — declares which Gradle *plugins* are
   available project-wide, without turning them on yet.
3. **`app/build.gradle.kts`** — the real configuration: SDK versions
   (`compileSdk`/`minSdk`/`targetSdk`), the app's unique `applicationId`,
   and the external library `dependencies` the app needs.
4. **`gradle.properties`** — global Gradle behavior flags, unrelated to any
   specific app logic (memory limits, AndroidX opt-in, etc.).

## 16. Putting it all together: what happens when you tap the widget

As a capstone, here's the full chain of events, file by file, for the single
most common action: tapping a SysMon widget on your home screen to refresh
it.

1. **You tap the widget.** The tap lands on `widgetRoot` in `widget_sysmon.xml`,
   which was wired (in `SysMonWidgetProvider.updateOneWidget()`) to a
   `PendingIntent` carrying our custom `ACTION_REFRESH` broadcast.
2. **`SysMonWidgetProvider.onReceive()`** wakes up, recognizes
   `ACTION_REFRESH`, calls `goAsync()` to buy extra time, and starts a
   background `Thread`.
3. That thread asks Android for every currently-placed SysMon widget id, and
   calls **`updateOneWidget()`** for each.
4. `updateOneWidget()` calls **`resolveDevice()`**, which asks
   **`DeviceStore.findDevice()`** (in `Device.kt`) which device this
   particular widget is configured to watch.
5. It calls **`StatsClient.fetchStats(device.address)`**, which makes the
   actual HTTP GET request to the Python agent running on that device.
6. On success, the raw JSON is saved to `SharedPreferences`, and
   **`StatsFormat.buildStatRows()`** (in `StatsFormat.kt`) is what will
   *eventually* turn it into styled text — but not yet, at this exact point.
7. `updateOneWidget()` calls `manager.updateAppWidget(id, views)` — this
   redraws the title/IP/timestamp immediately.
8. It separately calls `manager.notifyAppWidgetViewDataChanged(id, R.id.statsList)`
   — this is what tells Android the *list* needs refreshing too, which
   triggers **`StatsRemoteViewsFactory.onDataSetChanged()`**
   (in `SysMonRemoteViewsService.kt`) to run.
9. `onDataSetChanged()` reads the JSON that step 6 just saved, and *this* is
   where `StatsFormat.buildStatRows()` actually gets called, building the
   final colored/bold row text.
10. The launcher then calls **`getViewAt()`** once per visible row to
    actually draw them, using `widget_stat_item.xml`.

Every one of those ten steps has a fuller explanation waiting in that file's
own comments — this list is just the connective tissue between them.

---

# Part D

## 17. Where to go from here

A few ideas, roughly in order of difficulty, if you want to keep extending
this project as practice:

- **Easy**: add a new stat to the widget (e.g. battery level) — you'd touch
  `StatsFormat.kt` (add a `KEY_BATTERY` constant and a row builder), the
  Python agent (`agent/monitor_agent.py`, covered in `LEARNING.md`) to report
  it, and `activity_widget_config.xml` / `WidgetConfigActivity.kt` to add a
  checkbox for it.
- **Medium**: add a way to reorder devices, or pull-to-refresh instead of
  tap-to-refresh.
- **Harder**: swap SharedPreferences for a real local database (Room), which
  would let you store richer per-device settings without hand-rolling JSON
  parsing in `Device.kt`.
- **Different skill entirely**: try rebuilding one screen (say,
  `MainActivity`) using Jetpack Compose instead of the current XML-layout
  approach, to see how the newer UI paradigm compares — that's the "Empty
  Compose Activity" template mentioned back in section 7.

For anything about the Python agent internals, git/GitHub mechanics, or
deploying to additional computers, `LEARNING.md` in this same folder covers
that ground in the same detailed, assume-nothing style as this guide.
