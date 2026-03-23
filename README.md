# GitHub Copilot Premium Quota Monitor

An IntelliJ IDEA plugin that displays your remaining **GitHub Copilot premium AI-model quota**
directly in the IDE status bar, so you always know how many premium requests you have left for
the current billing period.

---

## Table of Contents

1. [Features](#features)
2. [Requirements](#requirements)
3. [Installation](#installation)
4. [Usage](#usage)
5. [Authentication](#authentication)
6. [Status Bar States](#status-bar-states)
7. [Building from Source](#building-from-source)
8. [Architecture](#architecture)

---

## Features

- **Status bar widget** — shows remaining/total premium requests at a glance (e.g. `⊙ 150/300`).
- **Auto-refresh** — quota is fetched in the background every **5 minutes**.
- **On-demand refresh** — click the widget to force an immediate update.
- **Tooltip** — hover the widget to see used, remaining, total, and percentage consumed.
- **Graceful error handling** — distinct visual states for loading, unlimited plans, missing
  account, and network errors.
- **Zero extra authentication** — fully delegates sign-in to the official GitHub Copilot plugin.

---

## Requirements

| Requirement | Version |
|---|---|
| IntelliJ IDEA **Ultimate** | 2025.2 (build 252) or later |
| [GitHub Copilot plugin][gh:copilot-plugin] | any recent version |
| GitHub account signed in through the Copilot plugin | — |

> [!IMPORTANT]
> The **GitHub Copilot** plugin (`com.github.copilot`) is a **mandatory dependency**.  
> If it is not installed or is disabled, IntelliJ will refuse to load this plugin and will display
> a clear error message asking you to install it first.

---

## Installation

### From a built ZIP (recommended for local use)

1. Build the plugin (see [Building from Source](#building-from-source)).
2. In IntelliJ IDEA, open **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the `.zip` file generated under `build/distributions/`.
4. Restart the IDE.

### From JetBrains Marketplace *(when published)*

Search for **"GitHub Copilot Premium Quota Monitor"** in **Settings → Plugins → Marketplace**.

---

## Usage

After installation and IDE restart the widget appears automatically in the **status bar**
at the bottom of every project window.

```
⊙ 150/300
```

- The **left number** is the remaining premium requests for the current month.
- The **right number** is the monthly limit for your plan.

### Tooltip

Hover over the widget to see a full breakdown:

```
GitHub Copilot — Premium quota
  Remaining : 150 / 300
  Used      : 150  (50.0 %)
  Click to refresh
```

### Refresh

| Action | Behaviour |
|---|---|
| Automatic | Background refresh every **5 minutes** |
| Manual | **Click** the widget to trigger an immediate fetch |

### Enabling / Disabling the widget

The widget can be toggled via the status bar context menu:  
**Right-click the status bar → GitHub Copilot Premium Quota**.

---

## Authentication

This plugin does **not** implement its own authentication flow.

When the quota is fetched, the plugin:

1. Reads the GitHub OAuth token stored by IntelliJ's built-in **GitHub account manager**
   (`GHAccountManager`, provided by the bundled `org.jetbrains.plugins.github` plugin).
   This is the same token registered when you sign in through the official GitHub Copilot plugin.
2. Calls `GET https://api.github.com/copilot_internal/user` with that token — the same
   internal endpoint used by the Copilot plugin itself.
3. Parses the response for premium quota fields and caches the result for 5 minutes.

> [!NOTE]
> If you are signed into multiple GitHub accounts, the token of the **first** account returned
> by the account manager is used. Future versions may add account selection.

---

## Status Bar States

| Widget label | Meaning |
|---|---|
| `⊙ Copilot` | Initial load in progress |
| `⊙ 150/300` | Quota data retrieved — *remaining* / *total* |
| `⊙ Copilot ∞` | Your plan has no premium request limit |
| `⊙ Copilot ⚠` | No GitHub account found, or token is invalid |
| `⊙ Copilot ✗` | Network error or unexpected API response |

All error details are available in the tooltip and in the IDE log
(`Help → Show Log in Explorer/Finder`).

---

## Building from Source

### Prerequisites

- JDK 21+
- Internet access (to download Gradle dependencies and the IntelliJ SDK on first run)

### Steps

```bash
# Clone the repository
git clone https://github.com/<your-org>/github-copilot-premium-quota-monitor-for-ij.git
cd github-copilot-premium-quota-monitor-for-ij

# Build and package the plugin
./gradlew buildPlugin
```

The distributable ZIP is created at:

```
build/distributions/github-copilot-premium-quota-monitor-for-ij-<version>.zip
```

### Run in a sandboxed IDE

```bash
./gradlew runIde
```

This launches a fresh IntelliJ IDEA instance with the plugin pre-installed. You will need to
install and sign in to the GitHub Copilot plugin inside that sandbox instance to test the full
flow.

### Other useful tasks

| Task | Description |
|---|---|
| `./gradlew compileKotlin` | Compile Kotlin sources only |
| `./gradlew test` | Run unit tests |
| `./gradlew verifyPlugin` | Check plugin compatibility |

---

## Architecture

```
src/main/kotlin/com/github/intellij/plugins/github_copilot_quota_monitor/
├── services/
│   └── CopilotQuotaService.kt          # Application-level service
│                                        # Fetches quota, manages cache,
│                                        # resolves GitHub OAuth token
└── statusbar/
    ├── CopilotQuotaStatusBarWidget.kt   # Status bar widget (text + tooltip + click)
    └── CopilotQuotaStatusBarWidgetFactory.kt  # Factory registered in plugin.xml
```

### Key design decisions

| Decision | Rationale |
|---|---|
| `com.github.copilot` as hard `<depends>` | Guarantees the Copilot plugin is present; IntelliJ handles the error message automatically if it is missing |
| `org.jetbrains.plugins.github` for token retrieval | Stable, public IntelliJ API; avoids fragile reflection into Copilot plugin internals |
| Application-scoped service | Quota data is IDE-wide, not per-project; avoids duplicate network calls |
| 5-minute cache with atomic references | Thread-safe, prevents API rate-limiting, no background threads kept alive permanently |
| Flexible JSON parser | Handles multiple field-name variants across GitHub API versions without breaking |

[gh:copilot-plugin]: https://plugins.jetbrains.com/plugin/17718-github-copilot
