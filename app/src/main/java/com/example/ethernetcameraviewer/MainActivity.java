package com.example.ethernetcameraviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Android 12 local-network camera viewer. No cloud account or CAMERA permission.
 * RTSP/H264 and HLS use Media3; HTTP MJPEG and JPEG snapshots use HTTP directly.
 */
public final class MainActivity extends Activity {
    private static final String[] MODES = {
            "RTSP / H.264 (TCP)", "HTTP / MJPEG", "HTTP / JPEG snapshots",
            "HTTP / HLS or MP4", "RTSP / H.264 (UDP first)"
    };
    private static final int MAX_JPEG_BYTES = 5 * 1024 * 1024;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService streamingExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private Future<?> streamTask;
    private volatile boolean imageStreaming;
    private volatile int imageSession;
    private volatile HttpURLConnection activeConnection;
    private Bitmap currentFrame;
    private ExoPlayer player;
    private boolean fullscreen;

    private PlayerView playerView;
    private ImageView jpegView;
    private View emptyScreen;
    private FrameLayout videoFrame;
    private ScrollView rootScroll;
    private LinearLayout headerArea;
    private LinearLayout controlsArea;
    private LinearLayout helpArea;
    private TextView ethernetStatus;
    private TextView playbackStatus;
    private TextView liveIndicator;
    private EditText urlInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private Spinner modeSpinner;
    private CheckBox preferEthernet;
    private Button fullscreenButton;
    private SharedPreferences settings;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        rootScroll = findViewById(R.id.rootScroll);
        headerArea = findViewById(R.id.headerArea);
        controlsArea = findViewById(R.id.controlsArea);
        helpArea = findViewById(R.id.helpArea);
        videoFrame = findViewById(R.id.videoFrame);
        playerView = findViewById(R.id.videoPlayer);
        jpegView = findViewById(R.id.jpegPlayer);
        emptyScreen = findViewById(R.id.emptyScreen);
        ethernetStatus = findViewById(R.id.networkStatus);
        playbackStatus = findViewById(R.id.playbackStatus);
        liveIndicator = findViewById(R.id.liveIndicator);
        modeSpinner = findViewById(R.id.protocolSpinner);
        urlInput = findViewById(R.id.streamUrl);
        usernameInput = findViewById(R.id.username);
        passwordInput = findViewById(R.id.password);
        preferEthernet = findViewById(R.id.forceEthernet);
        fullscreenButton = findViewById(R.id.fullscreenButton);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        settings = getSharedPreferences("camera_config", MODE_PRIVATE);

        ArrayAdapter<String> modes = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MODES);
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modes);
        modeSpinner.setSelection(settings.getInt("mode", 0));
        urlInput.setText(settings.getString("url", ""));
        usernameInput.setText(settings.getString("user", ""));
        preferEthernet.setChecked(settings.getBoolean("ethernet", true));

        findViewById(R.id.connectButton).setOnClickListener(v -> connect());
        findViewById(R.id.stopButton).setOnClickListener(v -> disconnect());
        findViewById(R.id.discoverButton).setOnClickListener(v -> discoverCameras());
        findViewById(R.id.snapshotButton).setOnClickListener(v -> savePhoto());
        findViewById(R.id.settingsButton).setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        });
        fullscreenButton.setOnClickListener(v -> setFullscreen(!fullscreen));
        updateEthernetStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                ui.post(() -> updateEthernetStatus());
            }
            @Override public void onLost(Network network) {
                ui.post(() -> updateEthernetStatus());
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties links) {
                ui.post(() -> updateEthernetStatus());
            }
        };
        try {
            connectivity.registerNetworkCallback(request, networkCallback);
        } catch (RuntimeException ignored) {
            // Some industrial Android builds expose Ethernet but omit callbacks.
        }
        updateEthernetStatus();
    }

    @Override
    protected void onStop() {
        disconnect();
        if (networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); }
            catch (RuntimeException ignored) { }
            networkCallback = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        disconnect();
        streamingExecutor.shutdownNow();
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    private Network getEthernetNetwork() {
        // getAllNetworks is deprecated as a polling strategy, but still works on
        // Android 12 and gives an immediate snapshot when Connect is pressed.
        for (Network net : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(net);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return net;
            }
        }
        return null;
    }

    private void updateEthernetStatus() {
        Network net = getEthernetNetwork();
        if (net == null) {
            ethernetStatus.setText("○  Ethernet not detected  •  Check RJ45 cable / Android Ethernet settings");
            return;
        }
        LinkProperties properties = connectivity.getLinkProperties(net);
        String ipv4 = null;
        String iface = "Ethernet";
        if (properties != null) {
            if (properties.getInterfaceName() != null) iface = properties.getInterfaceName();
            for (LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof Inet4Address) {
                    ipv4 = address.getAddress().getHostAddress();
                    break;
                }
            }
        }
        ethernetStatus.setText("●  Ethernet connected  •  " + iface + "  •  "
                + (ipv4 == null ? "No IPv4 address — configure static IP or DHCP" : ipv4)
                + "  •  Internet not required");
    }

    private boolean setPreferredNetwork() {
        if (preferEthernet.isChecked()) {
            Network ethernet = getEthernetNetwork();
            if (ethernet == null) {
                setStatus("Ethernet not available. Check cable and IP settings, or untick Prefer Ethernet.");
                return false;
            }
            LinkProperties links = connectivity.getLinkProperties(ethernet);
            boolean hasIpv4 = false;
            if (links != null) {
                for (LinkAddress addr : links.getLinkAddresses()) {
                    if (addr.getAddress() instanceof Inet4Address) hasIpv4 = true;
                }
            }
            if (!hasIpv4) {
                setStatus("Ethernet has no IPv4. Configure a static IP or enable DHCP in Android settings.");
                return false;
            }
            if (!connectivity.bindProcessToNetwork(ethernet)) {
                setStatus("Could not route app traffic through Ethernet.");
                return false;
            }
        } else {
            connectivity.bindProcessToNetwork(null);
        }
        return true;
    }

    private void connect() {
        String url = urlInput.getText().toString().trim();
        int mode = modeSpinner.getSelectedItemPosition();
        String scheme = Uri.parse(url).getScheme();
        boolean isRtsp = mode == 0 || mode == 4;
        boolean valid = scheme != null && (isRtsp
                ? (scheme.equalsIgnoreCase("rtsp") || scheme.equalsIgnoreCase("rtspt"))
                : (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")));
        if (!valid || Uri.parse(url).getHost() == null) {
            setStatus(isRtsp
                    ? "Enter your actual rtsp://CAMERA_IP:554/STREAM_PATH URL."
                    : "Enter your actual http:// or https:// camera image/stream URL.");
            return;
        }

        disconnect();
        if (!setPreferredNetwork()) return;
        settings.edit()
                .putInt("mode", mode)
                .putString("url", urlWithoutCredentials(url))
                .putString("user", usernameInput.getText().toString().trim())
                .putBoolean("ethernet", preferEthernet.isChecked())
                .apply();
        liveIndicator.setText("●  CONNECTING");
        emptyScreen.setVisibility(View.GONE);
        setStatus("Connecting to the camera on your local network...");

        if (isRtsp || mode == 3) {
            startVideo(url, isRtsp, mode == 0);
        } else {
            startHttpImages(url, mode == 1);
        }
    }

    private static String urlWithoutCredentials(String value) {
        Uri uri = Uri.parse(value);
        String authority = uri.getEncodedAuthority();
        if (authority == null || !authority.contains("@")) return value;
        return uri.buildUpon()
                .encodedAuthority(authority.substring(authority.lastIndexOf('@') + 1))
                .build().toString();
    }

    private String rtspUrlWithCredentials(String raw) {
        String username = usernameInput.getText().toString();
        String password = passwordInput.getText().toString();
        Uri parsed = Uri.parse(raw);
        String authority = parsed.getEncodedAuthority();
        if (username.isEmpty() || authority == null || authority.contains("@")) return raw;
        String auth = Uri.encode(username) + ":" + Uri.encode(password);
        return parsed.buildUpon().encodedAuthority(auth + "@" + authority).build().toString();
    }

    private void startVideo(String rawUrl, boolean rtsp, boolean forceTcp) {
        jpegView.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    liveIndicator.setText("●  LIVE");
                    setStatus("Video stream connected. Use ⛶ for full screen.");
                } else if (state == Player.STATE_BUFFERING) {
                    liveIndicator.setText("●  BUFFERING");
                } else if (state == Player.STATE_ENDED) {
                    liveIndicator.setText("●  ENDED");
                    setStatus("The camera stream ended. Tap CONNECT to retry.");
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                liveIndicator.setText("●  STREAM ERROR");
                setStatus("Could not play stream (" + error.getErrorCodeName()
                        + "). Check IP, exact stream path, login, and camera H.264 settings.");
            }
        });
        try {
            if (rtsp) {
                // TCP is often best for wired LANs. Alternate mode tries UDP first.
                player.setMediaSource(new RtspMediaSource.Factory()
                        .setForceUseRtpTcp(forceTcp)
                        .createMediaSource(MediaItem.fromUri(rtspUrlWithCredentials(rawUrl))));
            } else {
                player.setMediaItem(MediaItem.fromUri(rawUrl));
            }
            player.prepare();
            player.play();
        } catch (RuntimeException error) {
            liveIndicator.setText("●  STREAM ERROR");
            setStatus("Cannot open this video URL. Confirm that the camera supports RTSP/H.264 or HLS.");
        }
    }

    private void startHttpImages(String address, boolean mjpeg) {
        imageStreaming = true;
        final int session = ++imageSession;
        jpegView.setVisibility(View.VISIBLE);
        playerView.setVisibility(View.GONE);
        final String user = usernameInput.getText().toString();
        final String password = passwordInput.getText().toString();
        streamTask = streamingExecutor.submit(() -> {
            int framesSeen = 0;
            while (imageStreaming && session == imageSession
                    && !Thread.currentThread().isInterrupted()) {
                HttpURLConnection connection = null;
                try {
                    connection = makeHttpConnection(address, user, password);
                    activeConnection = connection;
                    try (InputStream in = connection.getInputStream()) {
                        if (mjpeg) {
                            while (imageStreaming && session == imageSession
                    && !Thread.currentThread().isInterrupted()) {
                                byte[] bytes = MjpegFrames.readFrame(in, MAX_JPEG_BYTES);
                                if (bytes == null) break;
                                if (showJpeg(bytes, session)) framesSeen++;
                            }
                        } else {
                            if (showJpeg(readLimited(in, MAX_JPEG_BYTES), session)) framesSeen++;
                        }
                    }
                    if (framesSeen == 0 && imageStreaming && session == imageSession) {
                        setStatus("No JPEG frames found. Check the HTTP MJPEG/snapshot path.");
                    }
                } catch (IOException error) {
                    if (imageStreaming && session == imageSession) {
                        setStatus("Camera HTTP error (" + error.getClass().getSimpleName()
                                + "). Check the camera URL, port, or login.");
                    }
                } finally {
                    if (connection != null) connection.disconnect();
                    if (activeConnection == connection) activeConnection = null;
                }
                if (!imageStreaming || session != imageSession) break;
                try { Thread.sleep(mjpeg ? 1300 : 800); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private static HttpURLConnection makeHttpConnection(String address, String user, String pass)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(6500);
        conn.setReadTimeout(9000);
        conn.setRequestProperty("Accept", "multipart/x-mixed-replace,image/jpeg,*/*");
        if (!user.isEmpty()) {
            String auth = Base64.encodeToString((user + ":" + pass)
                    .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            conn.setRequestProperty("Authorization", "Basic " + auth);
        }
        conn.connect();
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("HTTP " + code);
        }
        return conn;
    }

    private static byte[] readLimited(InputStream stream, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(48 * 1024);
        byte[] bytes = new byte[8192];
        int size;
        while ((size = stream.read(bytes)) != -1) {
            if (output.size() + size > maximum) {
                throw new IOException("JPEG image exceeds size limit");
            }
            output.write(bytes, 0, size);
        }
        return output.toByteArray();
    }

    private boolean showJpeg(byte[] jpeg, int session) {
        if (jpeg.length == 0 || !imageStreaming || session != imageSession) return false;
        Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (frame == null) return false;
        ui.post(() -> {
            if (!imageStreaming || session != imageSession) return;
            currentFrame = frame;
            jpegView.setImageBitmap(frame);
            liveIndicator.setText("●  LIVE");
            playbackStatus.setText("Receiving JPEG images from camera");
        });
        return true;
    }

    private void disconnect() {
        imageStreaming = false;
        ++imageSession;
        HttpURLConnection connection = activeConnection;
        activeConnection = null;
        if (connection != null) connection.disconnect();
        if (streamTask != null) {
            streamTask.cancel(true);
            streamTask = null;
        }
        if (player != null) {
            playerView.setPlayer(null);
            player.release();
            player = null;
        }
        connectivity.bindProcessToNetwork(null);
        currentFrame = null;
        jpegView.setImageDrawable(null);
        jpegView.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);
        emptyScreen.setVisibility(View.VISIBLE);
        liveIndicator.setText("●  DISCONNECTED");
    }

    private void discoverCameras() {
        if (!setPreferredNetwork()) return;
        setStatus("Searching for ONVIF cameras on the local network...");
        backgroundExecutor.execute(() -> {
            WifiManager.MulticastLock multicastLock = null;
            try {
                // Some Android Wi-Fi drivers suppress multicast unless a lock is held.
                WifiManager wifi = (WifiManager) getApplicationContext()
                        .getSystemService(WIFI_SERVICE);
                if (wifi != null) {
                    try {
                        multicastLock = wifi.createMulticastLock("camera-onvif-discovery");
                        multicastLock.setReferenceCounted(false);
                        multicastLock.acquire();
                    } catch (RuntimeException ignored) {
                        // Ethernet discovery may work without a Wi-Fi multicast lock.
                        multicastLock = null;
                    }
                }
                List<String> hosts = CameraDiscovery.discover();
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (hosts.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("No ONVIF camera found")
                                .setMessage("This does not mean the camera is offline. "
                                        + "The camera may use a proprietary app, have ONVIF disabled, "
                                        + "or be on a different subnet. Check its app/manual for "
                                        + "the local IP and RTSP/ONVIF settings.")
                                .setPositiveButton("OK", null)
                                .show();
                        setStatus("Discovery finished — no ONVIF response. Enter the camera URL manually.");
                    } else {
                        String[] results = hosts.toArray(new String[0]);
                        new AlertDialog.Builder(this)
                                .setTitle("Found camera IP addresses")
                                .setItems(results, (dialog, selected) -> {
                                    modeSpinner.setSelection(0);
                                    urlInput.setText("rtsp://" + results[selected] + ":554/");
                                    setStatus("IP selected. Replace '/' with this camera's ACTUAL RTSP path. "
                                            + "ONVIF discovery does not tell us the RTSP path.");
                                })
                                .setNegativeButton("CANCEL", null)
                                .show();
                        setStatus("Found " + hosts.size() + " ONVIF device(s). Select one IP.");
                    }
                });
            } catch (IOException error) {
                setStatus("ONVIF search failed. Check local-network access / multicast support.");
            } finally {
                if (multicastLock != null && multicastLock.isHeld()) {
                    try { multicastLock.release(); } catch (RuntimeException ignored) { }
                }
            }
        });
    }

    private void savePhoto() {
        Bitmap image = null;
        if (jpegView.getVisibility() == View.VISIBLE) {
            image = currentFrame;
        } else if (player != null && playerView.getVisibility() == View.VISIBLE) {
            View surface = playerView.getVideoSurfaceView();
            if (surface instanceof TextureView) {
                image = ((TextureView) surface).getBitmap();
            }
        }
        if (image == null) {
            toast("Connect to a camera and wait for a visible frame first.");
            return;
        }
        final Bitmap snapshot = image;
        backgroundExecutor.execute(() -> {
            Uri created = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME,
                        "camera_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(new Date()) + ".jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/EthernetCameraViewer");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                created = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (created == null) throw new IOException("Could not create image");
                try (OutputStream output = getContentResolver().openOutputStream(created)) {
                    if (output == null || !snapshot.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                        throw new IOException("Could not write JPEG");
                    }
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(created, values, null, null);
                ui.post(() -> toast("Photo saved to Pictures/EthernetCameraViewer"));
            } catch (Exception ex) {
                if (created != null) getContentResolver().delete(created, null, null);
                ui.post(() -> toast("Could not save photo"));
            }
        });
    }

    private void setStatus(String message) {
        ui.post(() -> playbackStatus.setText(message));
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void setFullscreen(boolean enabled) {
        fullscreen = enabled;
        headerArea.setVisibility(enabled ? View.GONE : View.VISIBLE);
        ethernetStatus.setVisibility(enabled ? View.GONE : View.VISIBLE);
        playbackStatus.setVisibility(enabled ? View.GONE : View.VISIBLE);
        controlsArea.setVisibility(enabled ? View.GONE : View.VISIBLE);
        helpArea.setVisibility(enabled ? View.GONE : View.VISIBLE);
        fullscreenButton.setText(enabled ? "×" : "⛶");
        if (enabled) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(0);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        rootScroll.scrollTo(0, 0);
        ui.postDelayed(this::resizeVideoFrame, 250);
    }

    private void resizeVideoFrame() {
        android.view.ViewGroup.LayoutParams lp = videoFrame.getLayoutParams();
        float density = getResources().getDisplayMetrics().density;
        lp.height = fullscreen
                ? getResources().getDisplayMetrics().heightPixels
                : (int) (250 * density + 0.5f);
        videoFrame.setLayoutParams(lp);
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        ui.post(this::resizeVideoFrame);
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) setFullscreen(false);
        else super.onBackPressed();
    }
}
