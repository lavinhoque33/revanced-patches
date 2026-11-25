<div align="center"> 
<img src="assets/rvx-logo.png" alt="RVX logo" width="128">

## 🧩 ReVanced Extended Patches
[![Static Badge](https://img.shields.io/badge/RVX_Wiki-gray?logo=github)](https://github.com/anddea/revanced-patches/wiki)   [![Static Badge](https://img.shields.io/badge/Translations-gray?logo=crowdin)](https://rvxtranslate.vercel.app/)
<br>
[![Static Badge](https://img.shields.io/badge/Telegram-Community-gray?logo=telegram&color=%2326A5E4)](https://t.me/AnddeaChat)   [![Static Badge](https://img.shields.io/badge/Reddit-YTAdvanced-gray?logo=reddit&color=red)](https://www.reddit.com/r/YTadvanced)
</div>

<!-- modified by lavinhoque33, 2026-11-25: shared Manager source instructions. -->
## Patch Managers

Add this repository as one remote patch source:

```text
https://github.com/lavinhoque33/revanced-patches
```

### Morphe Manager

1. Open **Sources → + → Remote**, paste the repository URL, and tap **Add**.
2. Leave **Pre-release patches** off on the RVX source for stable releases. Turn it on to
   follow the newest bundle from either channel. You do not need a second source.
3. Select **RVX Patches** when patching YouTube or YouTube Music. Do not combine it with
   another patch source for the same app.

The existing [stable](https://raw.githubusercontent.com/lavinhoque33/revanced-patches/rvx-latest/patches-bundle.json)
and [dev](https://raw.githubusercontent.com/lavinhoque33/revanced-patches/rvx-dev/patches-bundle.json)
JSON links still work as separate, fixed-channel sources in Morphe Manager. To use the
channel switch, add the repository URL above and select that source instead.

Both bundles declare **YouTube 21.45.90** and **YouTube Music 9.45.51** as experimental app
targets. On the repository source, Morphe offers **Experimental app versions** when
**Pre-release patches** is on. Suggested versions remain **YouTube 21.39.525** and
**YouTube Music 9.40.51**. If a download search offers `21.45.90-EXTENDED`, choose plain
`21.45.90`; the APK manifest versions differ.

### UP Manager

Add the same repository URL. In the bundle's settings, leave **Use latest** and
**Use pre-releases** off for stable. Enable **Use latest** to follow the most recently
published stable or dev release. **Use pre-releases** by itself follows dev releases only.

Update the source, then patch and install the app again. Updating a patch source alone
does not update installed apps. You can also import a release `.mpp` via Morphe Manager's
**Sources → + → Local**. Local sources must be updated manually.

<!--
Fork release maintenance: main mirrors rvx-latest, and dev mirrors rvx-dev.
Publish the .mpp first, then update each matching patches-bundle.json and CHANGELOG.md
on main/dev together. Keep a dated prerelease entry above the stable changelog on dev.
When stable supersedes dev, refresh both pairs to stable; retain a newer prerelease.
main/dev are Manager metadata mirrors, not source or release build branches.
-->

## 📋 List of patches in this repository

{{ table }}
