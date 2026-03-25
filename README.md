# GitHub Copilot Premium Quota Monitor

An IntelliJ Platform plugin that displays your remaining **GitHub Copilot premium AI-model quota**
directly in the IDE status bar, so you always know how many premium requests you have left for
the current billing period.

---

## Table of Contents

1. [Features](#features)
2. [Requirements](#requirements)
3. [Installation](#installation)
4. [First-Time Setup](#first-time-setup)
5. [Usage](#usage)
6. [Authentication](#authentication)
7. [Status Bar States](#status-bar-states)
8. [Building from Source](#building-from-source)
9. [Architecture](#architecture)

---

## Features

- **Status bar widget** — shows remaining/total premium requests at a glance (e.g. `⊙ 150/300`).
- **Auto-refresh** — quota is fetched in the background every **5 minutes**.
- **On-demand refresh** — click the widget to force an immediate update.
- **Tooltip** — hover the widget to see used, remaining, total, and percentage consumed.
- **First-run setup** — sign-in dialog appears automatically on the first IDE run if not authenticated.
- **Context menu** — right-click the widget to access sign-in/sign-out options.
- **Standalone authentication** — implements the GitHub OAuth Device Flow (RFC 8628) directly,
  with no dependency on any other plugin.
- **Secure token storage** — the OAuth token is kept in IntelliJ's built-in PasswordSafe
  (OS keychain, KDE Wallet, or encrypted file depending on the platform).
- **Graceful error handling** — distinct visual states for loading, unlimited plans, missing
  account, and network errors.

---

## Requirements

| Requirement | Version / Notes |
|---|---|
| IntelliJ IDEA **Community** or **Ultimate** | 2025.2 (build 252) or later |
| Any other IntelliJ-based IDE | PyCharm, WebStorm, GoLand, … — build 252+ |
| GitHub account with an active Copilot subscription | — |

> [!NOTE]
> This plugin has **no dependency** on the GitHub Copilot plugin or on any other third-party
> plugin. It works on IntelliJ IDEA Community edition and any other JetBrains IDE that runs
> on build 252 or later.

---

## Installation

### From a built ZIP (recommended for local use)

1. Build the plugin (see [Building from Source](#building-from-source)).
2. In the IDE, open **Settings → Plugins → ⚙ → Install Plugin from Disk…**
3. Select the `.zip` file generated under `build/distributions/`.
4. Restart the IDE.
5. Complete the [First-Time Setup](#first-time-setup).

### From JetBrains Marketplace *(when published)*

Search for **"GitHub Copilot Premium Quota Monitor"** in **Settings → Plugins → Marketplace**.

---

## First-Time Setup

After installing the plugin, a sign-in dialog appears automatically on the first IDE run if you 
are not yet authenticated:

1. The **GitHub Device Flow dialog** opens automatically showing a one-time code.
2. A browser window opens automatically at `https://github.com/login/device`.
   If the browser does not open, copy the URL from the dialog and paste it manually.
3. Enter the **one-time code** shown in the dialog (or click **Copy Code** first).
4. Authorize the application on GitHub.
5. The dialog closes automatically and the status bar widget starts showing your quota.

If you dismiss the dialog or want to sign in later, you can always right-click the status bar widget 
and select **Sign in with GitHub**.

To revoke access, **right-click the widget** and select **Sign out**. You can re-authenticate at
any time by right-clicking and selecting **Sign in with GitHub** again.

---

## Usage

After sign-in the widget appears automatically in the **status bar** at the bottom of every
project window.

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

The plugin implements the **GitHub OAuth 2.0 Device Authorization Grant** ([RFC 8628][rfc8628])
independently — no other plugin is required.

### Flow

1. The user right-clicks the status bar widget and selects **Sign in with GitHub**.
2. The plugin requests a device code from `https://github.com/login/device/code`.
3. A dialog displays a one-time code and opens `https://github.com/login/device` in the browser.
4. The plugin polls `https://github.com/login/oauth/access_token` in a background thread
   until the user completes the authorization on GitHub.
5. The resulting OAuth token is stored securely in IntelliJ's **PasswordSafe**
   (OS keychain on macOS/Windows, KDE Wallet or encrypted file on Linux).
6. On every quota fetch the plugin calls
   `GET https://api.github.com/copilot_internal/user` using that token.

### Token lifecycle

| Event | Behaviour |
|---|---|
| First use | Token is absent → status bar shows `⊙ Copilot ⚠`; tooltip guides to right-click menu |
| Token valid | Quota is fetched and cached for 5 minutes |
| Token revoked / expired (HTTP 401 or 403) | Token is cleared automatically; user is prompted to sign in again via the tooltip |
| Sign out | Token and username are removed from PasswordSafe immediately |

> [!NOTE]
> The OAuth App client ID used for the Device Flow (`Iv1.b507a08c87ecfe98`) is the publicly
> documented identifier for GitHub Copilot IDE integrations. Device Flow does not require a
> client secret on the client side (RFC 8628 §7).

---

## Status Bar States

| Widget label | Meaning |
|---|---|
| `⊙ Copilot` | Initial load in progress |
| `⊙ 150/300` | Quota data retrieved — *remaining* / *total* |
| `⊙ Copilot ∞` | Your plan has no premium request limit |
| `⊙ Copilot ⚠` | Not signed in, or token expired — see tooltip |
| `⊙ Copilot ✗` | Network error or unexpected API response — see tooltip |

All error details are available in the tooltip and in the IDE log
(`Help → Show Log in Explorer / Finder`).

---

## Building from Source

### Prerequisites

- JDK 21+
- Internet access (Gradle downloads the IntelliJ Community SDK on first run)

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

This launches a sandboxed IntelliJ IDEA Community instance with the plugin pre-installed.
Right-click the status bar widget and select **Sign in with GitHub** to test the full 
authentication flow.

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
│   ├── GitHubAuthService.kt            # OAuth Device Flow + PasswordSafe token store
│   └── CopilotQuotaService.kt          # Quota fetch, cache, JSON parsing
├── ui/
│   └── GitHubDeviceFlowDialog.kt       # Modal dialog: shows user code, polls for token
├── startup/
│   └── CopilotSignInStartupActivity.kt # Shows sign-in dialog on first IDE run
└── statusbar/
    ├── CopilotQuotaStatusBarWidget.kt   # Status bar widget (text + tooltip + popup menu)
    └── CopilotQuotaStatusBarWidgetFactory.kt
```

### Key design decisions

| Decision | Rationale |
|---|---|
| **No plugin dependencies** | Only `com.intellij.modules.platform` is required → works on Community, Ultimate, and all other IntelliJ-based IDEs |
| **OAuth Device Flow (RFC 8628)** | Industry-standard, browser-based auth; no client secret needed on the device; same flow used by GitHub CLI and other IDE integrations |
| **PasswordSafe for token storage** | IntelliJ's built-in credential store; uses OS keychain on macOS/Windows; no plain-text secrets |
| **Build against Community SDK** | Guarantees the plugin only uses APIs available in all editions |
| **Application-scoped services** | Quota data and auth state are IDE-wide; avoids duplicate network calls across multiple open projects |
| **5-minute cache with atomic references** | Thread-safe, prevents API rate-limiting, no persistent background threads |
| **Multi-layout JSON parser** | Handles `limited_user_quotas`, nested quota objects, and flat fields across GitHub API versions |
| **Context menu** | Sign in/out actions direttamente dal widget della status bar |
| **First-run sign-in dialog** | Automates the initial setup flow; users can skip and sign in later via context menu |

[rfc8628]: https://www.rfc-editor.org/rfc/rfc8628
