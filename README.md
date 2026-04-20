# Galerinio — Advanced Android Multimedia Gallery

## 📱 About

**Galerinio** is a powerful, privacy-focused gallery app for Android. It combines a smooth media browsing experience with robust tools for power users: cloud/NAS synchronization, a built-in editor, and Map View.

> [!IMPORTANT]  
> This project is a fork of the **Fossify Gallery**. It maintains the spirit of open-source software while adding advanced synchronization features (SMB, SFTP, WebDAV) and modern UI enhancements.

## ✨ Key Features

- **NAS & Cloud Sync** — Backup and sync your media via Google Drive, WebDAV, SFTP, and SMB (Samba/Windows Share).
- **Interactive Geo-Map** — Browse your memories on an integrated Google Maps view using EXIF metadata.
- **Privacy First** — Biometric/PIN protection for the app and a dedicated hidden vault.
- **Media Browsing** — Highly customizable grid/list views with "pinch-to-zoom" layout scaling.
- **Built-in Editor** — Non-destructive photo editing (crop, rotate, resize).
- **Video Excellence** — High-performance playback powered by Media3 ExoPlayer.
- **Custom Splash** — Premium feel with a custom MP4 video intro on cold start.
- **Smart Trash** — Recover accidentally deleted files with optional auto-cleanup.

## 📋 Requirements

- Android 7.0+ (API 24)
- Target SDK 34
- Kotlin 2.0 / Java 11

## 🏗️ Architecture & Tech Stack

Following Clean Architecture with MVVM:
- **UI:** Fragments/ViewBinding with Material Design 3.
- **Database:** Room for local indexing and metadata.
- **Background Work:** WorkManager for reliable cloud syncing.
- **Image/Video:** Glide, Coil, and Media3 ExoPlayer.
- **Protocols:** Sardine (WebDAV), sshj (SFTP), smbj (SMB).

## 🔧 Setup & Build

1. **Clone the repo:**
   ```bash
   git clone [https://github.com/YOUR_USERNAME/Galerinio.git](https://github.com/YOUR_USERNAME/Galerinio.git)
   
2.  Google Maps API:
Add your key in app/src/main/res/values/strings.xml:

3.  XML
<string name="google_maps_api_key">YOUR_KEY_HERE</string>
Google Drive API:
Follow the official guide to obtain your credentials.json and place it in the app's root directory or configure via secrets.properties.

⚖️ License & Attribution
This project is licensed under the GNU General Public License v3.0 (GPLv3).

Original Project: Based on the excellent Fossify Gallery.

Changes in Galerinio: Added SMB/SFTP/WebDAV providers, Google Maps integration, video splash screen, and custom UI themes.

You can find the full text of the license in the LICENSE file.
