# GitHub Copilot Premium Quota Monitor

An IntelliJ Platform plugin that displays your remaining **GitHub Copilot premium** quota as a 
percentage directly in the IDE status bar, so you always know how much of your monthly allowance 
remains.

This plugin is independent from the official GitHub Copilot plugin and works with IntelliJ IDEA 
Community and any other JetBrains IDE that runs on build 252 or later.

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [First-time setup / Authentication](#first-time-setup--authentication)
- [Usage](#usage)
- [Status bar states](#status-bar-states)
- [Building from source](#building-from-source)
- [Architecture](#architecture)
- [Contributing](#contributing)
- [License](#license)

---

## Features

- **Status bar widget** — shows remaining premium quota as a percentage at a glance (e.g. `⊙ 50%`).
- **Auto-refresh** — quota is fetched in the background every 5 minutes.
- **On-demand refresh** — click the widget or use the context menu to force an immediate update.
- **Tooltip** — hover the widget to see detailed quota information and quick actions.
- **First-run setup** — sign-in dialog appears automatically on the first IDE run if not authenticated.
- **Context menu** — right-click the widget to access sign-in, sign-out, and refresh options.
- **Standalone authentication** — implements the GitHub OAuth Device Flow (RFC 8628) directly,
  with no dependency on any other plugin.
- **Secure token storage** — OAuth token is stored in IntelliJ's PasswordSafe (OS keychain, KDE
  Wallet, or encrypted file depending on platform).
- **Graceful error handling** — distinct visual states for loading, unlimited plans, missing account,
  and network errors.

---

## Requirements

| Requirement | Version / Notes |
|---|---|
| IntelliJ IDEA Community or Ultimate | 2025.2 (build 252) or later |
| Any other IntelliJ-based IDE | PyCharm, WebStorm, GoLand, … — build 252+ |
| GitHub account with an active Copilot subscription | — |

**Note:** This plugin has no dependency on the official GitHub Copilot plugin and runs on Community
edition as well.

---

## Installation

### From JetBrains Marketplace

The plugin is available on the JetBrains Marketplace. Open your IDE and go to 
`Settings / Preferences` → `Plugins` → Search for "GitHub Copilot Premium Quota" → Install.

### From Disk (manual installation)

1. Download the latest plugin ZIP from the [Releases](https://github.com/your-org/github-copilot-premium-quota-monitor-for-ij/releases) page.
2. Open `Settings / Preferences` → `Plugins` → `Gear` icon → `Install Plugin from Disk...`.
3. Select the downloaded ZIP file and follow the prompts.
4. Restart the IDE when prompted.

### Build from source

See [Building from source](#building-from-source) section below.

---

## First-time setup / Authentication

The plugin uses GitHub's OAuth Device Flow for a secure, browser-free authentication experience.

**Steps:**

1. Right-click the status bar widget and choose **Sign in with GitHub**, or open a project after
   installing the plugin — a sign-in dialog appears automatically if not authenticated.
2. The dialog displays a short device code and opens GitHub's device authorization page in your
   default browser.
3. Enter the displayed code on GitHub to approve the application. The plugin polls GitHub in the
   background and stores the token securely upon success.

To sign out, use the status bar context menu: right-click the widget and select **Sign out**.

---

## Usage

After sign-in the widget appears automatically in the status bar at the bottom of every project window.

### Widget label

The widget displays your remaining quota as a percentage:

```
⊙ 50%
```

This means 50% of your monthly premium request allowance remains unused.

### Tooltip

Hover over the widget to see the full tooltip (from `messages_en.properties`):

```
GitHub CoPilot premium quota monitor

Remaining quota: 50%

Click for options
```

The HTML source in the plugin:

```html
<html><strong>GitHub CoPilot premium quota monitor</strong><br><br>
Remaining quota: <b>{0}%</b><br><br>
<i>Click for options</i></html>
```

### Refresh behavior

| Action | Behaviour |
|---|---|
| Automatic | Background refresh every 5 minutes |
| Manual | Click the widget or right-click → **Refresh** |

### Enable/disable the widget

Right-click the status bar → **GitHub Copilot Premium Quota Monitor** to toggle the widget on/off.

---

## Status bar states

The widget displays different states depending on your authentication and quota status:

| Widget label | Meaning |
|---|---|
| `⊙ CP premium quota monitor` | Initial load in progress |
| `⊙ 50%` | Quota data retrieved — percentage of quota remaining |
| `⊙ CP premium quota monitor ∞` | Your plan has unlimited premium requests |
| `⊙ CP premium quota monitor - Sign in` | Not signed in — click to authenticate |
| `⊙ ✗` | Network error or API error — see tooltip for details |

> **Tip:** Check the IDE log (Help → Show Log in Explorer / Finder) for detailed error messages.

---

## Building from source

### Prerequisites

- **JDK 21** or later
- **Git**
- Internet access (Gradle downloads the IntelliJ Platform SDK on first build)

### Build steps

```bash
# Clone the repository
git clone https://github.com/your-org/github-copilot-premium-quota-monitor-for-ij.git
cd github-copilot-premium-quota-monitor-for-ij

# Build and package the plugin
./gradlew buildPlugin

# The distributable ZIP is created at:
# build/distributions/github-copilot-premium-quota-monitor-for-ij-<version>.zip
```

On Windows using Git Bash, use the same `./gradlew` commands.

### Run in sandbox

Test the plugin in a sandboxed IDE instance:

```bash
./gradlew runIde
```

This opens an isolated IntelliJ IDEA Community instance with the plugin pre-installed. 
Right-click the status bar widget and select **Sign in with GitHub** to test the full 
authentication flow.

---

## Architecture

### Source layout

```
src/main/kotlin/com/github/intellij/plugins/github_copilot_quota_monitor/
├── services/
│   ├── GitHubAuthService.kt            # OAuth Device Flow + PasswordSafe token store
│   └── CopilotQuotaService.kt          # Quota fetch, cache, JSON parsing
├── ui/
│   └── GitHubDeviceFlowDialog.kt       # Modal dialog: device code display & polling
├── startup/
│   └── CopilotSignInStartupActivity.kt # Shows sign-in dialog on first IDE run
└── statusbar/
    ├── CopilotQuotaStatusBarWidget.kt  # Status bar widget (text + tooltip + menu)
    └── StatusBarWidgetFactory.kt       # Widget factory registration
```

### Key design decisions

- **OAuth Device Flow:** No web callback endpoint required — works everywhere (local dev, corporate networks, etc.).
- **PasswordSafe:** Leverages the OS keychain/credential manager for secure token storage.
- **Minimal UI:** Status bar widget provides glanceable percentage at a glance; tooltip available on hover.
- **Background refresh:** Polling happens on a scheduled executor to avoid blocking the UI.

---

## Troubleshooting

### Authentication fails

- Check the IDE log for error details: **Help** → **Show Log in Explorer / Finder**.
- Ensure your system clock is accurate (OAuth device codes and timeouts are time-sensitive).
- Try signing out and signing in again: right-click the widget → **Sign out** → wait a moment → **Sign in with GitHub**.

### Widget shows error state (`⊙ ✗`)

- Network connectivity issue — check your internet connection.
- GitHub API is temporarily unavailable — try clicking the widget to refresh.
- Invalid or expired token — sign out and sign in again.

### Quota not updating

- Auto-refresh occurs every 5 minutes. Click the widget or use **Refresh** to check immediately.
- Check the tooltip for error details.

---

## Contributing

Contributions, bug reports, and pull requests are welcome!

**Guidelines:**

1. Open an issue to discuss larger changes before investing time.
2. Fork the repository and create a feature branch.
3. Keep changes small and focused; add tests where appropriate.
4. Ensure the project builds: `./gradlew build`
5. Follow the existing code style and naming conventions.

---

## License

This project is licensed under the **MIT License** — see the `LICENSE` file for details.

---

**Have questions or found a bug?** Open an issue on GitHub. Enjoy monitoring your Copilot quota! 🚀

