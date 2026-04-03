# GitHub Copilot Premium Quota Monitor

<!-- Badges: CI / version / license placeholders - replace with real badges if available -->

An IntelliJ Platform plugin that displays your remaining GitHub Copilot Premium quota (as a percentage)
directly in the IDE status bar. This project is independent from the official GitHub Copilot plugin and
works with IntelliJ IDEA Community and other JetBrains IDEs.

---

### Project summary

- Plugin ID: `com.github.intellij.plugins.github_copilot_quota_monitor.github-copilot-premium-quota-monitor-for-ij`
- Package / group: `com.github.intellij.plugins.github_copilot_quota_monitor`
- Plugin name: `GitHub Copilot - Premium Quota Monitor`
- Vendor / Author: Antonio Petricca
- IntelliJ platform target: `2025.2.4` (build 252)
- Java target: `21`
- Repository: https://github.com/antonio-petricca/github-copilot-premium-quota-monitor-for-ij

---

### Table of Contents

- Features
- Requirements
- Installation
- Authentication (first-time setup)
- Usage
- Settings
- Status bar states
- Building from source
- Run in sandbox
- Localization
- Architecture
- Contributing
- License

---

## Features

- Status bar widget that shows remaining premium quota as a percentage (for example: `⊙ 50%`).
- Automatic background refresh (default: every 5 minutes) can be customized in the Settings panel.
- **Double-click** on the widget for an immediate quota refresh without opening any menu.
- Manual refresh via the quick-action popup menu (single left-click).
- Tooltip with detailed quota information and quick actions.
- OAuth Device Flow authentication (no redirect URI required).
- Secure token storage using IntelliJ PasswordSafe (OS keychain / KDE Wallet / encrypted file).
- Visual states for loading, unlimited plans, unauthenticated and error conditions.
- **Configurable alert thresholds and colors**: set your own *critical* (default 10%) and *warning* (default 30%) quota percentage thresholds with custom colors directly from **Settings → Tools → GitHub Copilot Premium Quota Monitor**. Each setting has a dedicated "Reset to default" button.
- Inline cross-field validation in the Settings panel ensures that the critical threshold is always strictly less than the warning threshold.

---

## Requirements

- JDK 21 or later
- IntelliJ Platform compatible IDE (target build: `2025.2.4`, `platformType=IC` configured)
- Git (for building from source)
- A GitHub account with an active Copilot subscription to retrieve quota information

---

## Installation

### From JetBrains Marketplace

Open your IDE → Settings / Preferences → Plugins → Marketplace and search for "GitHub Copilot Premium Quota Monitor".

### From Disk (manual)

1. Download the plugin ZIP from the project's Releases: https://github.com/antonio-petricca/github-copilot-premium-quota-monitor-for-ij/releases
2. In the IDE: Settings / Preferences → Plugins → Gear → Install Plugin from Disk... → select the ZIP → Restart the IDE.

### Build from source

Clone and build locally (Git Bash / WSL / macOS / Linux):

```bash
git clone https://github.com/antonio-petricca/github-copilot-premium-quota-monitor-for-ij.git
cd github-copilot-premium-quota-monitor-for-ij

# Clean and build plugin (produces a distributable ZIP)
./gradlew clean buildPlugin
```

On Windows PowerShell use:

```powershell
.\gradlew.bat clean buildPlugin
```

Build output:

The distributable ZIP is created under:

```
build/distributions/github-copilot-premium-quota-monitor-for-ij-<version>.zip
```

---

## Authentication (First-time setup)

This plugin uses GitHub's OAuth Device Flow (RFC 8628).

Steps:

1. If not authenticated, left-click the status bar widget and choose **Sign in with GitHub**, or open the IDE after installing the plugin — the sign-in dialog will appear automatically.
2. The dialog shows a one-time device code and a link to GitHub's device authorization page.
3. Enter the code on GitHub to authorize the plugin. The plugin polls GitHub until authorization is complete and stores the access token securely in PasswordSafe.

To sign out: left-click the widget → **Sign out**.

---

## Usage

- The widget appears in the IDE status bar showing remaining quota as a percentage (e.g. `⊙ 50%`).
- Hover to see the tooltip with remaining requests and renewal date.
- Left-click the widget for quick actions: `Refresh`, `Sign in with GitHub`, `Sign out`.

---

## Settings

Open **Settings → Tools → GitHub Copilot Premium Quota Monitor** to configure:

| Setting | Default | Description |
|---|---|---|
| Auto-refresh interval | 5 min | How often the quota is refreshed automatically (1–60 minutes). |
| Critical threshold | 10 % | Quota percentage at or below which the status bar label turns the critical color. |
| Critical color | Red `#D32F2F` | Color used when quota is at or below the critical threshold. |
| Warning threshold | 30 % | Quota percentage at or below which the status bar label turns the warning color. |
| Warning color | Yellow `#FFFF00` | Color used when quota is at or below the warning threshold (but above critical). |

Each setting has a **Reset to default** button (↩ icon). The constraint `1 < critical < warning < 100` is enforced with real-time inline validation.

---

## Status bar states

The widget updates its label and tooltip according to the current authentication and quota retrieval state. The concrete text values are defined in the plugin resource bundle (see `src/main/resources/messages.properties`). Below are the runtime states and what they mean:

- Loading
  - Label: `GHCP premium quota monitor` (resource key: `statusbar_widget_initial`).
  - Tooltip: `GHCP premium quota monitor - loading...` (`statusbar_tooltip_loading`).
  - Shown while the plugin is fetching quota for the first time or when an explicit refresh is in progress.

- Available (quota retrieved)
  - Label: a percentage formatted with one decimal (resource key: `statusbar_widget_available`, e.g. `50.0%`).
  - Tooltip: HTML table with "Remaining" and "Renewal" fields (resource key: `statusbar_tooltip_html`). If a renewal timestamp is provided it is shown in the user's local timezone.
  - Color coding: the percentage label is colored based on configurable thresholds (set in **Settings → Tools → GitHub Copilot Premium Quota Monitor**):
    - at or below **critical** threshold (default 10%) → critical color (default red `#D32F2F`)
    - at or below **warning** threshold (default 30%) → warning color (default yellow `#FFFF00`)
    - above warning threshold → default label color

- Unlimited
  - Label: `GHCP premium quota monitor ∞` (resource key: `statusbar_widget_unlimited`).
  - Tooltip: `Unlimited premium quota` (`statusbar_tooltip_unlimited`).

- Not signed in
  - Label: `GHCP premium quota monitor - Sign in` (resource key: `statusbar_widget_signin`).
  - Tooltip: a short HTML hint asking the user to sign in (resource key: `statusbar_tooltip_noaccount_html`).
  - Triggered when there is no saved GitHub authentication.

- Error
  - Label: a cross/error marker (resource key: `statusbar_widget_error`, e.g. `✗`).
  - Tooltip: formatted error message including the error details (resource key: `statusbar_tooltip_error`).
  - Use the tooltip and the IDE log (Help → Show Log in Explorer / Finder) to inspect error details.

Interactions
- **Left single-click** the widget to open the quick-action popup (Refresh plus Sign in / Sign out depending on auth state). The refresh is performed only if you select "Refresh" from the popup; a simple left-click does not refresh the quota.
- **Left double-click** the widget to trigger an immediate quota refresh directly, without opening any menu.
- To sign in: choose `Sign in with GitHub` from the popup (or the sign-in dialog is shown automatically on first run when not authenticated).
- To sign out: choose `Sign Out` from the popup; a confirmation dialog is shown and stored credentials are cleared on confirmation.
- To enable/disable the widget in the IDE, use the status bar context menu: right-click the IDE status bar → choose `GitHub Copilot Premium Quota Monitor` to toggle visibility.

---

## Building and Running

Common tasks (Git Bash / WSL / macOS / Linux):

```bash
# Clean and run unit tests
./gradlew clean test

# Run the plugin inside an isolated IDE (sandbox)
./gradlew runIde

# Build distributable plugin ZIP
./gradlew buildPlugin
```

On Windows PowerShell use:

```powershell
.\gradlew.bat clean test
.\gradlew.bat runIde
.\gradlew.bat buildPlugin
```

Publishing to JetBrains Marketplace requires a plugin token. Example (replace <TOKEN>):

```bash
./gradlew publishPlugin -PpublishToken=<TOKEN>
```

---

### Run in sandbox

Use `./gradlew runIde` to start an isolated instance of the target IDE where you can test sign-in and quota retrieval workflows.

---

## Localization

This plugin ships with a resource bundle (`messages.properties`) and includes translations for:

- Albanian (`messages_sq.properties`)
- Chinese (`messages_zh.properties`)
- English (default)
- French (`messages_fr.properties`)
- German (`messages_de.properties`)
- Italian (`messages_it.properties`)
- Japanese (`messages_ja.properties`)
- Portuguese (`messages_pt.properties`)
- Russian (`messages_ru.properties`)
- Spanish (`messages_es.properties`)
- Turkish (`messages_tr.properties`)

To add or update translations, add `messages_xx.properties` files and provide translations for the existing keys.

---

## Architecture

High level source layout:

```
src/main/kotlin/com/github/intellij/plugins/github_copilot_quota_monitor/
├── services/      # AuthService, PluginService (quota fetch & cache)
├── ui/            # Device Flow dialog
├── startup/       # Startup activity to prompt sign-in
└── statusbar/     # Status bar widget + factory
```

Key implementation notes:

- OAuth Device Flow for wide compatibility (no redirect URI required).
- Tokens stored in PasswordSafe for platform-specific secure storage.
- Background refresh uses a scheduled executor to avoid blocking the UI thread.

---

## Contributing

Contributions are welcome — open issues and PRs on the repository:
https://github.com/antonio-petricca/github-copilot-premium-quota-monitor-for-ij

Guidelines:

1. Fork the repo and create a feature branch.
2. Build and run tests locally: `./gradlew clean build`.
3. Keep changes small and include tests where appropriate.
4. Follow existing code style and resource bundle conventions.

---

## License

This project is licensed under the MIT License — see the `LICENSE` file for details.

---

If you found a bug or would like a feature, please open an issue on GitHub. Enjoy monitoring your Copilot quota! 🚀
