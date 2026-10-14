package com.senojmediax.uplink;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SENOJ Media Uplink - send videos straight to the studio's Google Drive.
 *
 * Flow: SAF file picker (no storage permission needed) -> OAuth 2.0 via the
 * system browser with a loopback redirect (no Google Play Services needed) ->
 * Drive REST API v3 uploads into a "SENOJ Uploads" folder.
 *
 * ONE-TIME SETUP (Corey): create an OAuth client ID in Google Cloud Console
 * (APIs & Services > Credentials > Create Credentials > OAuth client ID >
 * Application type: Desktop app), then paste it into OAUTH_CLIENT_ID below.
 */
public class MainActivity extends Activity {

    // ------------------------------------------------------------------
    // CONFIG - paste the OAuth client ID here (one-time setup, see guide)
    // ------------------------------------------------------------------
    private static final String OAUTH_CLIENT_ID = "REPLACE_WITH_YOUR_OAUTH_CLIENT_ID";

    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String UPLOAD_FOLDER_NAME = "SENOJ Uploads";

    private static final String PREFS = "uplink_prefs";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_FOLDER_ID = "folder_id";
    private static final String KEY_CLIENT_ID = "oauth_client_id";

    private static final int REQ_PICK_VIDEOS = 1001;

    private static final int RED = Color.parseColor("#ED1C24");
    private static final int GOLD = Color.parseColor("#C9A227");
    private static final int BLACK = Color.parseColor("#0A0A0A");
    private static final int DARK_CARD = Color.parseColor("#141414");
    private static final int WHITE = Color.WHITE;
    private static final int GRAY = Color.parseColor("#999999");

    private Button signInButton;
    private Button uploadButton;
    private Button signOutButton;
    private Button setupGuideButton;
    private Button saveClientIdButton;
    private android.widget.EditText clientIdInput;
    private TextView statusView;
    private TextView setupHelpView;
    private LinearLayout fileList;

    private String accessToken = null;
    private long accessTokenExpiryMs = 0;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    // ============================ UI ==================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BLACK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, dp(32), pad, dp(32));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("SENOJ MEDIA UPLINK");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(RED);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Send videos straight to the studio.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sub.setTextColor(GOLD);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(8), 0, dp(24));
        root.addView(sub);

        setupHelpView = new TextView(this);
        setupHelpView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        setupHelpView.setTextColor(GRAY);
        setupHelpView.setGravity(Gravity.CENTER);
        setupHelpView.setPadding(0, 0, 0, dp(12));
        setupHelpView.setVisibility(View.GONE);
        root.addView(setupHelpView);

        setupGuideButton = makeButton("VIEW 5-MINUTE SETUP GUIDE", DARK_CARD, GOLD);
        setupGuideButton.setVisibility(View.GONE);
        setupGuideButton.setOnClickListener(v -> openSetupGuide());
        root.addView(setupGuideButton);

        clientIdInput = new android.widget.EditText(this);
        clientIdInput.setHint("Paste your OAuth client ID here");
        clientIdInput.setTextColor(WHITE);
        clientIdInput.setHintTextColor(GRAY);
        clientIdInput.setBackgroundColor(DARK_CARD);
        clientIdInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        clientIdInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        clientIdInput.setSingleLine(true);
        clientIdInput.setVisibility(View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.setMargins(0, dp(6), 0, dp(6));
        clientIdInput.setLayoutParams(ilp);
        root.addView(clientIdInput);

        saveClientIdButton = makeButton("SAVE CLIENT ID", DARK_CARD, GOLD);
        saveClientIdButton.setVisibility(View.GONE);
        saveClientIdButton.setOnClickListener(v -> saveClientId());
        root.addView(saveClientIdButton);

        signInButton = makeButton("SIGN IN WITH GOOGLE", RED, WHITE);
        signInButton.setOnClickListener(v -> startSignIn());
        root.addView(signInButton);

        uploadButton = makeButton("UPLOAD VIDEOS", RED, WHITE);
        uploadButton.setOnClickListener(v -> pickVideos());
        root.addView(uploadButton);

        signOutButton = makeButton("Sign out", DARK_CARD, GRAY);
        signOutButton.setOnClickListener(v -> signOut());
        root.addView(signOutButton);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusView.setTextColor(WHITE);
        statusView.setPadding(0, dp(20), 0, dp(12));
        root.addView(statusView);

        fileList = new LinearLayout(this);
        fileList.setOrientation(LinearLayout.VERTICAL);
        root.addView(fileList);

        refreshUi();
    }

    private Button makeButton(String text, int bg, int fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(fg);
        b.setBackgroundColor(bg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(null, Typeface.BOLD);
        b.setAllCaps(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        b.setPadding(dp(16), dp(18), dp(16), dp(18));
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void setStatus(final String s) {
        runOnUiThread(() -> statusView.setText("Status: " + s));
    }

    private boolean isConfigured() {
        return getClientId() != null;
    }

    /** Baked-in constant wins; otherwise the ID Corey pasted in-app. */
    private String getClientId() {
        if (OAUTH_CLIENT_ID != null && !OAUTH_CLIENT_ID.startsWith("REPLACE_WITH")) {
            return OAUTH_CLIENT_ID;
        }
        String saved = getPrefs().getString(KEY_CLIENT_ID, null);
        if (saved != null && saved.contains(".apps.googleusercontent.com")) {
            return saved.trim();
        }
        return null;
    }

    private void saveClientId() {
        String v = clientIdInput.getText().toString().trim();
        if (!v.contains(".apps.googleusercontent.com")) {
            setStatus("That does not look like a Google client ID - it should end with .apps.googleusercontent.com");
            return;
        }
        getPrefs().edit().putString(KEY_CLIENT_ID, v).apply();
        clientIdInput.setText("");
        setStatus("Client ID saved. Now tap SIGN IN WITH GOOGLE.");
        refreshUi();
    }

    private void refreshUi() {
        boolean configured = isConfigured();
        boolean signedIn = getPrefs().contains(KEY_REFRESH_TOKEN);
        setupHelpView.setVisibility(configured ? View.GONE : View.VISIBLE);
        setupGuideButton.setVisibility(configured ? View.GONE : View.VISIBLE);
        clientIdInput.setVisibility(configured ? View.GONE : View.VISIBLE);
        saveClientIdButton.setVisibility(configured ? View.GONE : View.VISIBLE);
        if (!configured) {
            setupHelpView.setText("One-time setup: create a free OAuth client ID (5-minute guide below), then paste it here and tap SAVE CLIENT ID.");
        }
        signInButton.setVisibility((configured && !signedIn) ? View.VISIBLE : View.GONE);
        uploadButton.setVisibility((configured && signedIn) ? View.VISIBLE : View.GONE);
        signOutButton.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (signedIn) {
            setStatus("Signed in. Tap UPLOAD VIDEOS to send files to your Drive.");
        } else if (configured) {
            setStatus("Not signed in yet.");
        } else {
            setStatus("Waiting for one-time setup.");
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void openSetupGuide() {
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://senoj-media-x-senoj-media-x.vercel.app/apps/#uplink-setup"));
        startActivity(i);
    }

    private void signOut() {
        getPrefs().edit().remove(KEY_REFRESH_TOKEN).remove(KEY_FOLDER_ID).apply();
        accessToken = null;
        accessTokenExpiryMs = 0;
        fileList.removeAllViews();
        refreshUi();
    }

    // ====================== SIGN-IN (loopback OAuth) ==================

    private void startSignIn() {
        if (!isConfigured()) {
            setStatus("Add your OAuth client ID first - see the setup guide.");
            return;
        }
        signInButton.setEnabled(false);
        setStatus("Opening Google sign-in in your browser...");
        executor.execute(() -> {
            ServerSocket server = null;
            try {
                server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                final int port = server.getLocalPort();
                final String redirectUri = "http://127.0.0.1:" + port + "/callback";
                String authUrl = AUTH_URL
                        + "?client_id=" + URLEncoder.encode(getClientId(), "UTF-8")
                        + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8")
                        + "&response_type=code"
                        + "&scope=" + URLEncoder.encode(DRIVE_SCOPE, "UTF-8")
                        + "&access_type=offline"
                        + "&prompt=consent";
                runOnUiThread(() -> startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))));

                server.setSoTimeout(180000);
                String code = null;
                long deadline = System.currentTimeMillis() + 180000;
                while (code == null && System.currentTimeMillis() < deadline) {
                    Socket socket;
                    try {
                        socket = server.accept();
                    } catch (java.net.SocketTimeoutException ste) {
                        break;
                    }
                    try {
                        socket.setSoTimeout(10000);
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), "UTF-8"));
                        String requestLine = in.readLine();
                        String line;
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            // consume headers
                        }
                        if (requestLine != null && requestLine.startsWith("GET /callback")) {
                            code = extractQueryParam(requestLine, "code");
                            String err = extractQueryParam(requestLine, "error");
                            String html;
                            if (code != null) {
                                html = successPage("Signed in",
                                        "You can close this window and return to SENOJ Media Uplink.");
                            } else {
                                html = successPage("Sign-in stopped",
                                        "No code received (" + (err != null ? err : "cancelled")
                                                + "). Close this window and try again.");
                            }
                            byte[] body = html.getBytes("UTF-8");
                            OutputStream out = socket.getOutputStream();
                            String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                                    + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                            out.write(head.getBytes("UTF-8"));
                            out.write(body);
                            out.flush();
                        }
                    } finally {
                        try { socket.close(); } catch (Exception ignored) { }
                    }
                }
                try { server.close(); } catch (Exception ignored) { }

                if (code != null) {
                    exchangeCodeForTokens(code, redirectUri);
                } else {
                    setStatus("Sign-in timed out or was cancelled.");
                    runOnUiThread(() -> signInButton.setEnabled(true));
                }
            } catch (Exception e) {
                setStatus("Sign-in error: " + e.getMessage());
                runOnUiThread(() -> signInButton.setEnabled(true));
                try { if (server != null) server.close(); } catch (Exception ignored) { }
            }
        });
    }

    private String successPage(String h, String p) {
        return "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "</head><body style=\"background:#0A0A0A;color:#fff;font-family:sans-serif;"
                + "text-align:center;padding-top:60px\">"
                + "<h2 style=\"color:#ED1C24\">" + h + "</h2><p>" + p + "</p></body></html>";
    }

    private String extractQueryParam(String requestLine, String name) {
        try {
            int q = requestLine.indexOf('?');
            int sp = requestLine.indexOf(' ', q);
            if (q < 0) return null;
            String query = requestLine.substring(q + 1, sp < 0 ? requestLine.length() : sp);
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), "UTF-8").equals(name)) {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void exchangeCodeForTokens(String code, String redirectUri) throws Exception {
        String body = "grant_type=authorization_code"
                + "&code=" + URLEncoder.encode(code, "UTF-8")
                + "&client_id=" + URLEncoder.encode(getClientId(), "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8");
        JSONObject token = postForm(TOKEN_URL, body);
        String refresh = token.optString("refresh_token", null);
        String access = token.optString("access_token", null);
        long expiresIn = token.optLong("expires_in", 3600);
        if (access == null || access.isEmpty()) {
            throw new Exception("Google did not return an access token.");
        }
        if (refresh != null && !refresh.isEmpty()) {
            getPrefs().edit().putString(KEY_REFRESH_TOKEN, refresh).apply();
        }
        accessToken = access;
        accessTokenExpiryMs = System.currentTimeMillis() + (expiresIn - 60) * 1000;
        runOnUiThread(() -> {
            signInButton.setEnabled(true);
            refreshUi();
        });
        setStatus("Signed in. Tap UPLOAD VIDEOS to send files to your Drive.");
    }

    private JSONObject postForm(String urlStr, String formBody) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] bytes = formBody.getBytes("UTF-8");
        c.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        OutputStream os = c.getOutputStream();
        os.write(bytes);
        os.flush();
        os.close();
        return readJsonResponse(c);
    }

    private JSONObject readJsonResponse(HttpURLConnection c) throws Exception {
        int status = c.getResponseCode();
        InputStream in = (status >= 200 && status < 300) ? c.getInputStream() : c.getErrorStream();
        String text = readAll(in);
        if (status < 200 || status >= 300) {
            throw new Exception("HTTP " + status + ": " + text);
        }
        return new JSONObject(text);
    }

    private String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private synchronized String getAccessToken() throws Exception {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiryMs) {
            return accessToken;
        }
        String refresh = getPrefs().getString(KEY_REFRESH_TOKEN, null);
        if (refresh == null) throw new Exception("Not signed in.");
        String body = "grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
                + "&client_id=" + URLEncoder.encode(getClientId(), "UTF-8");
        JSONObject token = postForm(TOKEN_URL, body);
        accessToken = token.getString("access_token");
        long expiresIn = token.optLong("expires_in", 3600);
        accessTokenExpiryMs = System.currentTimeMillis() + (expiresIn - 60) * 1000;
        return accessToken;
    }

    // ====================== FILE PICKER ===============================

    private void pickVideos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(i, "Choose videos to upload"), REQ_PICK_VIDEOS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_VIDEOS || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int k = 0; k < n; k++) {
                uris.add(data.getClipData().getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (Uri uri : uris) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
            String name = displayName(uri);
            TextView row = addFileRow(name);
            executor.execute(() -> uploadOne(uri, name, row));
        }
    }

    private String displayName(Uri uri) {
        String name = "video";
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) name = n;
            }
        } catch (Exception ignored) { }
        finally {
            if (c != null) c.close();
        }
        return name;
    }

    private TextView addFileRow(String name) {
        final TextView[] holder = new TextView[1];
        runOnUiThread(() -> {
            TextView row = new TextView(MainActivity.this);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.setTextColor(WHITE);
            row.setBackgroundColor(DARK_CARD);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(8));
            row.setLayoutParams(lp);
            row.setText(name + "\nQueued...");
            fileList.addView(row);
            holder[0] = row;
        });
        while (holder[0] == null) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) { }
        }
        return holder[0];
    }

    private void setRow(final TextView row, final String name, final String state, final int color) {
        runOnUiThread(() -> {
            row.setText(name + "\n" + state);
            row.setTextColor(color);
        });
    }

    // ====================== DRIVE UPLOAD ==============================

    private void uploadOne(Uri uri, String name, TextView row) {
        try {
            setRow(row, name, "Preparing...", GOLD);
            String token = getAccessToken();
            String folderId = getOrCreateFolder(token);
            String mime = getContentResolver().getType(uri);
            if (mime == null || mime.isEmpty()) mime = "video/mp4";
            setRow(row, name, "Uploading... 0%", GOLD);
            boolean ok = false;
            for (int attempt = 0; attempt < 2 && !ok; attempt++) {
                try {
                    multipartUpload(token, folderId, uri, name, mime, row);
                    ok = true;
                } catch (AuthException ae) {
                    accessToken = null; // force refresh
                    token = getAccessToken();
                }
            }
            if (ok) {
                setRow(row, name, "DONE - in your SENOJ Uploads folder", GOLD);
            } else {
                setRow(row, name, "Upload failed. Try again.", RED);
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = "unknown error";
            if (msg.length() > 80) msg = msg.substring(0, 80);
            setRow(row, name, "Error: " + msg, RED);
        }
    }

    private static class AuthException extends Exception { }

    private String getOrCreateFolder(String token) throws Exception {
        String cached = getPrefs().getString(KEY_FOLDER_ID, null);
        if (cached != null && !cached.isEmpty()) return cached;
        String q = "name='" + UPLOAD_FOLDER_NAME.replace("'", "\\'") + "'"
                + " and mimeType='application/vnd.google-apps.folder' and trashed=false";
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://www.googleapis.com/drive/v3/files?q=" + URLEncoder.encode(q, "UTF-8")
                        + "&fields=files(id)&spaces=drive").openConnection();
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setConnectTimeout(30000);
        c.setReadTimeout(30000);
        int status = c.getResponseCode();
        if (status == 401) throw new AuthException();
        JSONObject res = new JSONObject(readAll(
                status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream()));
        if (res.optJSONArray("files") != null && res.getJSONArray("files").length() > 0) {
            String id = res.getJSONArray("files").getJSONObject(0).getString("id");
            getPrefs().edit().putString(KEY_FOLDER_ID, id).apply();
            return id;
        }
        // create it
        HttpURLConnection c2 = (HttpURLConnection) new URL(
                "https://www.googleapis.com/drive/v3/files?fields=id").openConnection();
        c2.setRequestMethod("POST");
        c2.setDoOutput(true);
        c2.setConnectTimeout(30000);
        c2.setReadTimeout(30000);
        c2.setRequestProperty("Authorization", "Bearer " + token);
        c2.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        JSONObject meta = new JSONObject();
        meta.put("name", UPLOAD_FOLDER_NAME);
        meta.put("mimeType", "application/vnd.google-apps.folder");
        byte[] body = meta.toString().getBytes("UTF-8");
        OutputStream os = c2.getOutputStream();
        os.write(body);
        os.flush();
        os.close();
        int s2 = c2.getResponseCode();
        if (s2 == 401) throw new AuthException();
        JSONObject created = new JSONObject(readAll(
                s2 >= 200 && s2 < 300 ? c2.getInputStream() : c2.getErrorStream()));
        String id = created.getString("id");
        getPrefs().edit().putString(KEY_FOLDER_ID, id).apply();
        return id;
    }

    private void multipartUpload(String token, String folderId, Uri uri,
                                 String name, String mime, TextView row) throws Exception {
        long total = contentLength(uri);
        String boundary = "----SENOJUplinkBoundary9f3a7c";
        JSONObject meta = new JSONObject();
        meta.put("name", name);
        meta.put("mimeType", mime);
        org.json.JSONArray parents = new org.json.JSONArray();
        parents.put(folderId);
        meta.put("parents", parents);
        byte[] metaBytes = meta.toString().getBytes("UTF-8");

        String part1Head = "--" + boundary + "\r\n"
                + "Content-Type: application/json; charset=UTF-8\r\n\r\n";
        String part2Head = "\r\n--" + boundary + "\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        String tail = "\r\n--" + boundary + "--\r\n";
        byte[] p1 = part1Head.getBytes("UTF-8");
        byte[] p2 = part2Head.getBytes("UTF-8");
        byte[] pt = tail.getBytes("UTF-8");

        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                .openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "multipart/related; boundary=" + boundary);
        c.setChunkedStreamingMode(0);

        OutputStream rawOut = c.getOutputStream();
        ProgressOutputStream out = new ProgressOutputStream(rawOut, total,
                (done, pct) -> setRow(row, name, "Uploading... " + pct + "%", GOLD));
        out.write(p1);
        out.write(metaBytes);
        out.write(p2);
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("Could not read the selected file.");
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) != -1) {
            out.write(buf, 0, r);
        }
        in.close();
        out.write(pt);
        out.flush();
        out.close();

        int status = c.getResponseCode();
        if (status == 401) throw new AuthException();
        InputStream resp = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(resp);
        if (status < 200 || status >= 300) {
            throw new Exception("Drive upload failed (HTTP " + status + ").");
        }
    }

    private long contentLength(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                long s = c.getLong(0);
                if (s > 0) return s;
            }
        } catch (Exception ignored) { }
        finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private interface ProgressListener {
        void onProgress(long done, int pct);
    }

    private static class ProgressOutputStream extends OutputStream {
        private final OutputStream inner;
        private final long total;
        private final ProgressListener listener;
        private long done = 0;
        private long lastReport = 0;

        ProgressOutputStream(OutputStream inner, long total, ProgressListener listener) {
            this.inner = inner;
            this.total = total;
            this.listener = listener;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            inner.write(b);
            done++;
            report();
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            inner.write(b, off, len);
            done += len;
            report();
        }

        private void report() {
            if (total <= 0 || listener == null) return;
            long now = System.currentTimeMillis();
            if (now - lastReport < 400 && done < total) return;
            lastReport = now;
            int pct = (int) Math.min(100, (done * 100) / total);
            listener.onProgress(done, pct);
        }

        @Override
        public void flush() throws java.io.IOException {
            inner.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            inner.close();
        }
    }
}
