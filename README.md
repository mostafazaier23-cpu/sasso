# Ethernet Camera Viewer — Android 12

A simple Android 12 tablet app for viewing **local IP camera** streams over wired Ethernet or Wi-Fi. Built with Java, Android Gradle Plugin, and AndroidX Media3.

## What works (if the camera offers the protocol)

- **RTSP / H.264** live video using AndroidX Media3, with selectable TCP or UDP-first transport.
- **HTTP MJPEG** live video (multipart JPEG frames).
- **HTTP JPEG snapshot** mode (poll a JPEG endpoint about once per second).
- **HTTP HLS / MP4** playback (HLS requires a normal playable playlist URL).
- Display Ethernet's actual interface and local IPv4 address.
- **Prefer Ethernet** option binds application connections to the wired network even if it has no internet access.
- ONVIF WS-Discovery to discover camera IPs **only**. This cannot automatically discover video paths or bypass camera login.
- Full-screen landscape live view and save camera snapshots to `Pictures/EthernetCameraViewer`.
- Saves only the stream URL **with embedded credentials removed**, username, protocol, and Ethernet preference. The separate password field is not stored.

## IMPORTANT: Your photographed equipment

The tablet back panel shows an **ETH** RJ45 socket and **DC-12V** power input. The camera's box says **WiFi AI Alarm camera outdoor / TJVISION**; this does **not** establish the camera's RTSP or ONVIF compatibility. We could not validate the exact streaming protocol from the picture. **Check that the camera has a real RJ45 connection if you want a direct Ethernet cable.** A Wi-Fi-only camera can still work if it joins a Wi-Fi router and the tablet is wired to that same router/LAN.

A camera that only offers encrypted proprietary cloud/P2P video may **not** work with this viewer without its manufacturer's SDK or a local supported stream.

## Make an APK on your Windows PC (recommended)

1. Install **Android Studio** and open the extracted folder `EthernetCameraViewer` with **File > Open**. If Android Studio asks which Gradle installation to use, choose Gradle 8.9 or use the PowerShell build script described below.
2. Let Android Studio download/sync Gradle, Android SDK 35, and the Media3 dependencies. Use **JDK 17** if prompted.
3. Choose **Build > Build Bundle(s) / APK(s) > Build APK(s)** (wording varies by version).
4. Find the signed **debug APK** at `app/build/outputs/apk/debug/app-debug.apk`.
5. Copy the APK to the Android 12 tablet and open it using Files. When prompted, enable **Install unknown apps** for the file manager, then tap **Install**.

Debug APKs are signed for sideload testing by Android Gradle, **not** for publishing to Play Store. No Android Studio is required on the tablet.

### Alternative: build with GitHub Actions

The project includes `.github/workflows/build-apk.yml`. Put the project files into your own GitHub repository, open **Actions > Build installable Android APK > Run workflow** (or push to main), and download the artifact called `EthernetCameraViewer-Android12-debug` after the build finishes. Unzip it to get `app-debug.apk` and sideload as above. This requires your own GitHub account/repository and an internet connection during the build.

## Connecting the camera and the tablet

### Option A — both wired on the same LAN

```
       (DHCP router / Ethernet switch)
              |          |
         RJ45 tablet   RJ45 camera
```

Connect the tablet and camera to the router or switch. If both have DHCP, the router should assign compatible IP addresses. A simple switch alone does not provide DHCP.

### Option B — camera on Wi-Fi, tablet on Ethernet

```
       Wi-Fi router / access point
              |          )))
        RJ45 tablet    Wi-Fi camera
```

Both must be on the same LAN (not on an isolated guest network).

### Option C — direct cable, no router

If the **camera has Ethernet**, a direct RJ45 cable can work when both ends have static IPv4 addresses on the same subnet. For example, **only if you have configured the camera to `192.168.1.100`**, you could set the tablet to `192.168.1.50`, mask `255.255.255.0`. These are **examples, not identified defaults for your camera**. Many consumer Android builds or cameras cannot easily set static Ethernet addresses; use a router for initial testing.

Power the camera separately using its own rated supply; do **not** assume the tablet RJ45 port provides PoE.

## Find the video address

1. Configure the camera in the manufacturer app / camera web UI, if necessary, and look for **Local IP**, **RTSP**, **ONVIF**, and **H.264**. Enable RTSP/ONVIF if supported.
2. Tap **FIND CAMERAS** in this app. This sends ONVIF discovery probes. If a host is found, you can select its IP. **This fills `rtsp://HOST:554/` as an incomplete starting address**, not a guaranteed playable URL. You must enter the path documented for your camera.
3. A typical *example* is `rtsp://192.168.1.100:554/your-camera-specific-path`. It is **not** a verified URL for the photographed camera.
4. If camera credentials are needed, enter them in the separate fields. Tap **CONNECT**. Try the HTTP/MJPEG mode only if you know the camera has an HTTP MJPEG endpoint.
5. For first testing, enter the same full stream URL on a Windows PC in VLC via **Media > Open Network Stream**. If it fails there, check the camera's stream protocol first.

Run the included Windows diagnostic script for a camera IP you know:

```powershell
py camera_network_probe.py 192.168.1.100 --path /your-camera-path
```

This only checks the availability of common ports and reads RTSP status; it does not discover unknown vendor stream paths.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| App says no Ethernet | Physical cable, Android Settings > Network/Ethernet; ensure an IP is assigned |
| Ethernet connected, but camera not reachable | Both devices same IP subnet; verify camera IP; router isolation; camera cable/Wi-Fi link |
| ONVIF finds no cameras | ONVIF disabled or unsupported; multicast filtered; try manual IP from router |
| RTSP ERROR / black video | Exact stream path, username/password, port, H.264 codec; try the UDP-first RTSP mode; some cameras output only H.265 |
| HTTP MJPEG mode says no JPEG | The HTTP endpoint may be a web page or a proprietary video protocol, not MJPEG |
| Only works when Prefer Ethernet is unchecked | Verify Ethernet IP/subnet and routing; Wi-Fi may be connected to a different subnet |
| Cannot install APK | Enable Install unknown apps for the file manager, or check device administrator restrictions |

## Technical and privacy notes

- Minimum Android version: **Android 10 / API 29**; targets Android 12 behavior (API 32).
- Build SDK: **API 35**; Gradle **8.9**, AGP **8.7.3**; Media3 **1.5.1**.
- Android `INTERNET` and network permissions are required for *local networking* even with no internet connection.
- The application needs no permission to use the tablet's built-in camera because it receives IP video instead.
- Insecure HTTP and RTSP are allowed for typical local-network cameras; do not expose the camera directly to the public internet. Keep default camera passwords changed.
- Authenticated HLS endpoints using HTTP Basic credentials in the separate fields are not implemented in this v1; RTSP and HTTP JPEG/MJPEG credentials are supported.
- Android build/physical-camera runtime verification **is required** before deployment. Camera protocol not verified.

### Optional: one-command local Windows build

After installing Android Studio and Android SDK Platform 35, open PowerShell in the project folder and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\BUILD_APK_WINDOWS.ps1
```

This helper downloads official Gradle 8.9 (if needed), then builds the debug APK. It requires an internet connection on the Windows PC for Gradle/Media3 dependencies. The script is provided in source form for inspection.
