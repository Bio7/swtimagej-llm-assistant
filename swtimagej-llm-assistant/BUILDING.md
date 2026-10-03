# Building the LLM Assistant plugin with Eclipse (Maven)

The project is a plain Maven project. Eclipse imports it with the built-in
Maven support (m2e) – no extra plug-ins are needed.

## Requirements

* **Eclipse IDE** for Java developers (2024-06 or newer, includes m2e).
* **JDK 25** – SWTImageJ requires Java 25 (its bundle declares `JavaSE-25`).
  In Eclipse: *Window > Preferences > Java > Installed JREs* → add a JDK 25.
  The sources use unnamed lambda parameters (`_ -> …`, Java 22+), so Java 21
  cannot compile them.
* **The SWTImageJ bundle jar** (the `ij.*` classes). SWTImageJ is not on Maven
  Central, so the build uses a local copy:
  * from an SWTImageJ installation: `plugins/org.eclipse.swt.imagej_<version>.jar`
  * or built from source: `mvn clean verify` in the SWTImageJ repository gives
    `org.eclipse.swt.imagej/target/org.eclipse.swt.imagej-<version>.jar`

  Copy it to **`lib/org.eclipse.swt.imagej.jar`** (or pass `-Dswtimagej.jar=/path/to/jar`).

SWT itself is downloaded from Maven Central automatically, for your platform
(Linux, Windows, macOS; x86_64 or aarch64).

## Import into Eclipse

1. *File > Import... > Maven > Existing Maven Projects* → select this folder
   (the one containing `pom.xml`) → *Finish*.
2. Copy the SWTImageJ jar to `lib/org.eclipse.swt.imagej.jar`, then
   right-click the project → *Maven > Update Project...* (Alt+F5).
3. Make sure the project uses JDK 25: right-click → *Properties > Java Build Path >
   Libraries* should show *JRE System Library [JavaSE-25]*.

The sources are in `src/main/java/llmassistant`, the menu entries in
`src/main/resources/plugins.config`.

## Build

* Right-click **`Build LLM_Assistant.launch`** → *Run As > Build LLM_Assistant*,
  or right-click the project → *Run As > Maven build...* → Goals `clean package`.
* Result: **`target/LLM_Assistant.jar`**.

Command line (same result): `mvn clean package`

## Install into SWTImageJ

* Copy `target/LLM_Assistant.jar` into the SWTImageJ `plugins` folder (or use
  *Plugins > Install...* in SWTImageJ) and restart SWTImageJ. Keep only one copy
  of the plugin in the plugins folder.
* Or let the build do it: edit **`Build and install into SWTImageJ.launch`**
  (*Run > Run Configurations...* → *Maven Build* → that entry → parameter
  `swtimagej.plugins` = your SWTImageJ `plugins` folder) and run it.
  Command line: `mvn clean package -Dswtimagej.plugins=/path/to/SWTImageJ/plugins`

After installing, the chat window title shows the plugin version
(e.g. *LLM Assistant 2.4.1*).

## Options (Maven properties)

| Property | Default | Meaning |
|---|---|---|
| `swtimagej.jar` | `lib/org.eclipse.swt.imagej.jar` | Location of the SWTImageJ bundle jar |
| `swtimagej.plugins` | – | If set, the jar is copied into this SWTImageJ plugins folder |
| `java.release` | `25` | Java version to compile for (must match SWTImageJ; at least 22) |
| `swt.version` | `3.131.0` | SWT version from Maven Central used for compiling |

In Eclipse, set properties in the launch configuration (*Parameter* table) or,
for the IDE build, in the `<properties>` section of `pom.xml`.

## Troubleshooting

* **"The SWTImageJ jar was not found ..."** – the jar is missing in `lib/`
  (or `swtimagej.jar` points to the wrong file).
* **"class file has wrong version 69.0, should be 65.0"** or **"'_' is a keyword"
  / "unnamed variables are not supported"** – the project is compiled with an
  older Java version: use a JDK 25 and `java.release=25` (default). In Eclipse
  check *Project > Properties > Java Compiler* (compliance 25).
* **"class file for org.eclipse.… not found"** – a type from another Eclipse
  bundle is needed for compiling. Copy that bundle's jar from the SWTImageJ
  installation's `plugins` folder into `lib/` and add it to `pom.xml` (a
  commented template is in the `<dependencies>` section).
* **SWT cannot be downloaded** (no internet / proxy) – configure the proxy in
  *Window > Preferences > Maven > User Settings* (`settings.xml`), or set
  `swt.version` to a version available in your repository.
* Eclipse shows a warning about the *system* scope of the SWTImageJ dependency:
  harmless – it only means the jar comes from a local file instead of a repository.

## Project layout

```
pom.xml                               Maven build (Java 25, SWT from Maven Central)
lib/org.eclipse.swt.imagej.jar        SWTImageJ bundle jar (you copy it here)
src/main/java/llmassistant/*.java     plugin sources
src/main/resources/plugins.config     menu entries (Plugins > LLM Assistant)
Build LLM_Assistant.launch            Eclipse: build target/LLM_Assistant.jar
Build and install into SWTImageJ.launch  Eclipse: build and copy into SWTImageJ
docs/PLUGIN_README.md                 user documentation of the plugin
```
