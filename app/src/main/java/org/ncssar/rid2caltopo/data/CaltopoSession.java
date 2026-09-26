
/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */


package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.GetTodaysTrackDir;
import static java.lang.Math.round;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.lang.Thread.sleep;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.BuildConfig;
import org.ncssar.rid2caltopo.app.R2CApplication;
import org.opendroneid.android.data.Util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Dns;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.util.UUID;


/* Based on www.github.com/ncssar/caltopo_python (Tom Grundy).  The same 
 * caveats apply here.
 *
 *  Developed for search-and-rescue operations
 *  Copyright (c) 2025 Ken Taylor 1SAR7
 *
 *   Caltopo currently does not have a publicly available API;
 *    this code calls the non-publicized API that could change at any time.
 *
 *   This module is intended to provide a simple, API-version-agnostic caltopo
 *    interface to other applications.
 *
 *   This code is in no way supported or maintained by caltopo LLC or the
 *   authors of caltopo.com, but it does make use of the Caltopo Team API:
 *       https://training.caltopo.com/all_users/team-accounts/teamapi
 *
 *  Eventual home:
 *     www.github.com/ncssar/caltopo_java
 *
 *
 *  Contact the author at kjt@uas4sar.com
 *   Attribution, feedback, bug reports and feature requests are appreciated
 *
 *
 * A session instance attempts to maintain a secure connection with a caltopo 
 * server if connectivity is available.  Any operation that involves
 * communication with the caltopo server occurs asynchronously in a
 * background thread and immediately returns a CaltopoOp that can be polled
 * or blocked upon for a return value.  You can use CaltopoOp.syncOpId() to
 * block until the operation completes, but this will cause your app to
 * freeze if network connectivity is unavailable or very poor.
 * Any basic consistency checking of arguments is performed locally on this
 * side of that communication and will raise a RuntimeException on error
 * instead of returning a CaltopoOp.  If the caller to this module receives
 * a CaltopoOp instead of an exception, the message has been queued for
 * transmission.
 *
 * The session user is free to balance latency and overhead by monitoring
 * message execution status.  Consider that the Caltopo server can get busy
 * at times and that network connectivity can be spotty.  Before sending a 
 * new message, check to see if the previous message completed first.  For
 * example, if you're adding segments to a line and the message containing
 * the previous segments hasn't completed yet, hold off on sending the next 
 * message and continue to accumulate points for it until the preceding 
 * message completes.  Reducing the number of messages the server has to
 * process should speed up overall throughput for everyone.
 * 
 *
 * Without connectivity, all updates are queued locally until connectivity
 * is established and any specified map is found.  If mapId is not found 
 * after getting connectivity, permit user to change mapId or wait until
 * the mapId shows up.  Temporary loss of connectivity results in queued 
 * messages that are flushed upon return of connectivity.
 *
 * Examples:
 *    CaltopoConfig config = CaltopoConfig.fromFile("team.ct");
 *    String mapId = "H61AVOG";
 *    CaltopoSession cts = new CaltopoSession(config);
 *    JSONObject rj = cts.openSyncMap(mapId).syncOp()
 *                       .syncOpJSONObject();
 *    logger.info("Connected to caltopo map '" + mapId + "'.");
 *    defaultFolderId = cts.addFolder("DroneTracks")
 *                         .syncOpId();
 *  ...
 *    void newDroneWaypoint(double lng, double lat) {
 *        if (null == pointList) {
 *           pointList = new ArrayList(1000);
 *        }
 *        ArrayList l = new ArrayList(2);
 *        l.add(lng.toString());
 *        l.add(lat.toString());
 *        pointlist.add(l);
 *        if (lastOp && !lastOp.isDone()) return;
 *        if (null == lineId && lastOp && lastOp.isDone()) {
 *          lineId = lastOp.syncOpId();
 *        }
 *        lastOp = cts.AddLine(pointList.clone(), trackName, trackDescription,
 *                             lineId, defaultFolderId, lineProp);
 *        pointList.clear();
 *     } 
 *
 */


public class CaltopoSession {
	private static final double FEET_PER_METER = 3.280839895013123;
	private static final double INVALID_RID_ALTITUDE_METERS = -1000.0;
	public enum CtsMethod_t {
		GET,
		POST,
		DELETE
	}

    private static final String TAG = "CaltopoSession";
    private static final int DEFAULT_TIMEOUT_MS = 2 * 60 * 1000;
    private static final int MAX_RETRY_ATTEMPTS = 4;
    private static final long BASE_RETRY_DELAY_MS = 1000;
    private static final long MAX_RETRY_DELAY_MS = 8000;
    private static final long RETRY_JITTER_MAX_MS = 500;
    private static ExecutorService MainExecutorPool = null;
    private static ExecutorService PhotoWaypointExecutorPool = null;
	private static final CtLineProperty CtLinePropertyDefault = new CtLineProperty();
    private static final String CALTOPO_MAP_API_V1 = "/api/v1/map/";
    private static final String CALTOPO_MEDIA_API_V1 = "/api/v1/media/";

	private static final CtLineProperty LiveTrackLineProp =
			new CtLineProperty(2, 1F, "#0000ff", "solid");
    private static CaltopoCredentials Cred;
    private static String DomainAndPort;
	// instance variables:
	private static String MapId;

    public static final OkHttpClient MyOkHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .dns(Dns.SYSTEM) // This uses OkHttp's more robust DNS logic
            .build();

    public static void Init(@NonNull CaltopoCredentials cred, @NonNull String domainAndPort) {
        Cred = cred;
        DomainAndPort = domainAndPort;
    }
    /*** Initiializes and returns a session with information used for all subsequent interactions.
     *   Caller must specify onComplete procedure to receive the team
     */
    public CaltopoSession()  {
        throw new RuntimeException("CaltopoSession(): No instances permitted.");
    }

    public static CaltopoOp VerifyAccount(@Nullable Consumer<CaltopoOp> onComplete) {
        CaltopoOp op = new CaltopoOp(onComplete);
        String urlEnd = "/api/v1/acct/" + Cred.teamId + "/since/0";
        return SendRequest(op, CtsMethod_t.GET, urlEnd, null, false);
    }

	public static void Shutdown() {
		if (MainExecutorPool != null) {
			MainExecutorPool.shutdown();
            MainExecutorPool = null;
        }
        if (null != PhotoWaypointExecutorPool) {
            PhotoWaypointExecutorPool.shutdown();
            PhotoWaypointExecutorPool = null;
        }
    }

    @NonNull
    private static synchronized ExecutorService GetMainExecutorPool() {
        if (MainExecutorPool == null || MainExecutorPool.isShutdown() || MainExecutorPool.isTerminated()) {
            MainExecutorPool = Executors.newFixedThreadPool(1);
        }
        return MainExecutorPool;
    }

    @NonNull
    private static synchronized ExecutorService GetPhotoWaypointExecutorPool() {
        if (PhotoWaypointExecutorPool == null || PhotoWaypointExecutorPool.isShutdown() || PhotoWaypointExecutorPool.isTerminated()) {
            PhotoWaypointExecutorPool = Executors.newFixedThreadPool(1);
        }
        return PhotoWaypointExecutorPool;
    }

    private static boolean IsTransientResponseCode(int responseCode) {
        return responseCode == 408 || responseCode == 429 || responseCode >= 500;
    }

    private static boolean isAcceptedErrorCode(CaltopoOp op) {
        if (op.acceptedErrorCodes == null) return false;
        for (int code : op.acceptedErrorCodes) {
            if (code == op.responseCode) return true;
        }
        return false;
    }

    private static long RetryDelayMsecForAttempt(int attemptNum) {
        long expDelay = BASE_RETRY_DELAY_MS << Math.max(0, attemptNum - 1);
        expDelay = Math.min(expDelay, MAX_RETRY_DELAY_MS);
        long jitter = ThreadLocalRandom.current().nextLong(RETRY_JITTER_MAX_MS + 1);
        return expDelay + jitter;
    }

    private static boolean RetryAfterDelay(@NonNull String reason, int attemptNum) {
        if (attemptNum >= MAX_RETRY_ATTEMPTS) return false;
        long retryDelay = RetryDelayMsecForAttempt(attemptNum);
        CTInfo(TAG, String.format(Locale.US,
                "BgSendRequest(): transient %s (attempt %d/%d), retrying in %.3f seconds",
                reason, attemptNum, MAX_RETRY_ATTEMPTS, retryDelay / 1000.0));
        try {
            Thread.sleep(retryDelay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            CTInfo(TAG, "BgSendRequest(): retry sleep interrupted");
            return false;
        }
    }

    private static String SafeResponseText(@Nullable String response) {
        return response == null ? "" : response;
    }

    private static String ExceptionMessage(@NonNull Exception e) {
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg;
    }

    private static final java.util.concurrent.ScheduledExecutorService PositionTimer =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private static final LatestPositionReports<CaltopoOp> PositionReports = new LatestPositionReports<>(
            task -> GetMainExecutorPool().execute(task),
            (task, delay) -> PositionTimer.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS),
            () -> java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),
            CaltopoSession::BgSendRequest, CaltopoOp::discardPositionReport);

    private static String positionReportKey(String deviceId) {
        return DomainAndPort + ":" + CaltopoClient.GetConnectKey().trim() + ":" + deviceId;
    }

    public static void CancelLiveTrackPoints(String deviceId) {
        PositionReports.cancel(positionReportKey(deviceId));
    }

    private static void SubmitRequest(@NonNull CaltopoOp op) {
        try {
            op.asyncFuture = GetMainExecutorPool().submit(() -> BgSendRequest(op));
        } catch (RejectedExecutionException e) {
            CTError(TAG, "SendRequest(): main executor rejected task, rebuilding pool", e);
            synchronized (CaltopoSession.class) {
                MainExecutorPool = null;
            }
            op.asyncFuture = GetMainExecutorPool().submit(() -> BgSendRequest(op));
        }
    }

    private static void SubmitPhotoRequest(@NonNull CaltopoOp op,
                                           @NonNull Callable<CaltopoOp> task) {
        try {
            op.asyncFuture = GetPhotoWaypointExecutorPool().submit(task);
        } catch (RejectedExecutionException e) {
            CTError(TAG, "AddPhotoMarker(): photo executor rejected task, rebuilding pool", e);
            synchronized (CaltopoSession.class) {
                PhotoWaypointExecutorPool = null;
            }
            op.asyncFuture = GetPhotoWaypointExecutorPool().submit(task);
        }
    }

    private static String Sign(CtsMethod_t method, String url, long expiresMsec,
                               String payload) {
        try {
            // Construct the message
            String message = method + " " + url + "\n" + expiresMsec + "\n";
            if (payload != null && !payload.isEmpty()) {
                message += payload;
            }

            // Use android.util.Base64 for maximum compatibility across all Android versions
            // and to avoid Java 8 compatibility issues on older tablets.
            byte[] secretKey = android.util.Base64.decode(Cred.credentialSecret, android.util.Base64.DEFAULT);

            // Create a Mac instance with the HMAC-SHA256 algorithm
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secretKey, "HmacSHA256");
            hmac.init(keySpec);

            // Generate the signature
            byte[] signatureBytes = hmac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            // IMPORTANT: Use NO_WRAP to prevent the encoder from adding newlines
            // (which java.util.Base64 sometimes does for long strings)
            return android.util.Base64.encodeToString(signatureBytes, android.util.Base64.NO_WRAP);

        } catch (Exception e) {
            throw new RuntimeException("Error while generating HMAC signature", e);
        }
    }

	@NonNull
	private static String EncodeParm(@NonNull String key, @NonNull String val) {
        return key + "=" + Uri.encode(val);
	}

	@NonNull
    private static String EncodeParams(@NonNull Map<String,String> params) {
		StringBuilder paramString = new StringBuilder();
		for (Map.Entry<String,String> entry : params.entrySet()) {
            paramString
                    .append(entry.getKey())
					.append("=")
                    .append(Uri.encode(entry.getValue()))
					.append("&");
		}
		return paramString.substring(0, paramString.length()-1);
    }

	// this needs to be run in background thread to prevent blocking the app thread.
	private static CaltopoOp BgSendRequest(CaltopoOp op) {
        boolean goodResponse = false;
        for (int attemptNum = 1; attemptNum <= (op.positionReport ? 1 : MAX_RETRY_ATTEMPTS); attemptNum++) {
            try {
                op.sentTimestampMsec = System.currentTimeMillis();
                long expires = op.sentTimestampMsec + DEFAULT_TIMEOUT_MS;
                String payloadString = (null == op.payload) ? "" : op.payload.toString();

                // 1. Prepare Base Parameters
                Map<String, String> params = new HashMap<>();
                if (!op.goNaked) {
                    String signature = Sign(op.method, op.url, expires, payloadString);
                    params.put("id", Cred.credentialId);
                    params.put("expires", String.valueOf(expires));
                    params.put("signature", signature);
                }

                // 2. Build the URL and Request Body
                HttpUrl.Builder urlBuilder;
                if (op.goNaked) {
                    urlBuilder = HttpUrl.parse(op.url).newBuilder();
                } else {
                    urlBuilder = new HttpUrl.Builder()
                            .scheme("https")
                            .host(DomainAndPort.split(":")[0]) // Handle domain vs domain:port
                            .addPathSegments(op.url.startsWith("/") ? op.url.substring(1) : op.url);

                    // Port handling if present in DomainAndPort
                    if (DomainAndPort.contains(":")) {
                        urlBuilder.port(Integer.parseInt(DomainAndPort.split(":")[1]));
                    }
                }

                RequestBody requestBody = null;

                if (op.method == CtsMethod_t.POST && op.payload != null) {
                    // For Caltopo POSTs, the 'json' param contains the payload
                    params.put("json", payloadString);

                    FormBody.Builder formBuilder = new FormBody.Builder();
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        formBuilder.add(entry.getKey(), entry.getValue());
                    }
                    requestBody = formBuilder.build();
                } else {
                    // For GET/other, params go into the Query String
                    for (Map.Entry<String, String> entry : params.entrySet()) {
                        urlBuilder.addQueryParameter(entry.getKey(), entry.getValue());
                    }
                }

                HttpUrl finalUrl = urlBuilder.build();
                if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) {
                    CTInfo(TAG, String.format(Locale.US,
                            "BgSendRequest(%s): fullUrl: '%s'\n payload:\n%s",
                            op.method, finalUrl, payloadString));
                }

                // 3. Build and Execute Request
                Request.Builder requestBuilder = new Request.Builder()
                        .url(finalUrl)
                        .header("User-Agent", "RID2Caltopo/0.2")
                        .method(op.method.toString(), requestBody);

                try (Response response = MyOkHttpClient.newCall(requestBuilder.build()).execute()) {
                    op.responseCode = response.code();
                    op.receivedTimestampMsec = System.currentTimeMillis();

                    // Response.body().string() handles the stream reading and closing automatically
                    op.response = (response.body() != null) ? response.body().string() : "";

                    if (response.isSuccessful()) {
                        goodResponse = true;
                        if (!op.response.isEmpty()) {
                            try {
                                JSONObject responseJson = new JSONObject(op.response);
                                op.responseJson = responseJson.getJSONObject("result");
                                if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) {
                                    CTInfo(TAG, "Good Response:\n  " + op.responseJson.toString(2));
                                }
                            } catch (JSONException e) {
                                CTError(TAG, "parse JSON result raised: ", e);
                            }
                        }
                    } else if (isAcceptedErrorCode(op)) {
                        goodResponse = true;
                        CTInfo(TAG, "BgSendRequest() treating code " + op.responseCode + " as 'already gone' for " + op.url);
                    } else {
                        CTError(TAG, "BgSendRequest() failed w/code " + op.responseCode + ":\n" + op.response);
                        Bundle eventParams = new Bundle();
                        eventParams.putInt("r2c_responseCode", op.responseCode);
                        eventParams.putString("r2c_response", op.response);
                        eventParams.putString("r2c_url", op.url);
                        eventParams.putString("r2c_method", op.method.toString());
                        CaltopoClient.CTEvent(TAG, "CaltopoOpFailed", eventParams);
                        if (!op.positionReport && IsTransientResponseCode(op.responseCode)
                                && RetryAfterDelay("HTTP " + op.responseCode, attemptNum)) {
                            continue;
                        }
                    }
                    if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo) {
                        CTInfo(TAG, "BgSendRequest(): Normal Completion:\n  " + op);
                    }
                    break;
                }

            } catch (UnknownHostException e) {
                op.response = "UnknownHostException during request: " + ExceptionMessage(e);
                if (op.positionReport || !RetryAfterDelay("DNS lookup failure", attemptNum)) {
                    break;
                }
            } catch (IOException e) {
                op.response = "IOException during request: " + ExceptionMessage(e);
                CTError(TAG, "IOException raised during request", e);
                if (op.positionReport || !RetryAfterDelay("I/O exception", attemptNum)) {
                    break;
                }
            } catch (Exception e) {
                op.response = "Exception raised during request:\n  " + e;
                CTError(TAG, "Exception raised during request:", e);
                break;
            }
        }

        op.goodResponse = goodResponse;
        op.setOperationIsDone(goodResponse);
        if (!goodResponse && op.responseCode > 0 && op.response.isEmpty()) {
            op.response = SafeResponseText(op.response);
        }
        return op;
    }

	// CaltopoSession Instance methods:

	/** posts message to the background executor pool and returns immediately.
	 *
	 * @param op This is the data structure used to keep track of each asynchronous
	 *   communication with caltopo.
	 *
	 * @param method This enum specifies the http operation see <CtsMethod_t></CtsMethod_t>
	 *
	 * @param url url suffix if goNaked false, otherwise the complete url to send to.
	 *
	 * @param payload The JSON structure to be sent as the payload.
	 *
	 * @param goNaked If true, then just perform simple http transfer.  If false, then
	 *                build a credentialed message based on the Caltopo API.
     */
    @NonNull
    private static CaltopoOp SendRequest(CaltopoOp op, CtsMethod_t method,
								  String url, JSONObject payload, boolean goNaked) {
        op.goNaked = goNaked;
		op.method = method;
		op.url = url;
		op.payload = payload;
        // NOTE: only one bg thread to communicate w/caltopo - we are one of many users...
        SubmitRequest(op);
		return op;
    }

    /*** BgOp()
     *  Send BgSendRequest() directly (blocking):
     * @param method
     * @param urlEnd
     * @param payload
     * @param goNaked
     * @return Returns CaltopoOp.
     */
    @NonNull
    private static CaltopoOp BgOp(CtsMethod_t method, @NonNull String urlEnd, @Nullable JSONObject payload, boolean goNaked) {
        CaltopoOp op = new CaltopoOp(null);
        op.goNaked = goNaked;
        op.method = method;
        op.url = urlEnd;
        op.payload = payload;
        BgSendRequest(op);
        return op;
    }

    /**
     * Open/sync with the specified map.   If mapId is new, then a full
     * dump of the specified map is requested, otherwise only the changes
     * since the previous open/synch.
     *
     * @param mapNode contains the identifier for the map we want to interact with.
	 *
     * @param lastSyncTimestamp set to zero to bring in entire map.
	 *
     *
     * @return CaltopoOp responseJson on success will contain all the map info.
     *  User will likely check it to see if it needs anything.
     */
    static public CaltopoOp OpenMap(@NonNull CaltopoNode.MapNode mapNode, long lastSyncTimestamp, @Nullable Consumer<CaltopoOp> onComplete)
            throws RuntimeException {

        String mapIdIn = mapNode.getId();
        if (MapId != null && !mapIdIn.equals(MapId)) {
            CTDebug(TAG, "openMap(): changing mapId from " + MapId + " to " + mapIdIn);
        } else {
            CTDebug(TAG, "openMap(): Setting mapid to " + mapIdIn);
        }
        MapId = mapIdIn;

        // remove any update key delimiter:
        String urlEnd = CALTOPO_MAP_API_V1 + MapId + "/since/" +
                Math.max(0, lastSyncTimestamp - 500);

        CaltopoOp op = new CaltopoOp(onComplete);
        return SendRequest(op, CtsMethod_t.GET, urlEnd, null, false);
    }

    /** Add a folder.
     * @param folderName - Label for the folder.
     * @param contentsVisible - determines if new objects added to folder will be visible.
     * @param contentLabelsVisible - determines if labels of new objects added to folder will be visible.
     * @return CaltopoOp on success and null if parsing/configuring args
     */
	@Nullable
    public static CaltopoOp AddFolder(@NonNull String folderName, boolean contentsVisible,
			boolean contentLabelsVisible, @Nullable Consumer<CaltopoOp> onComplete){
        if (null == MapId)
            throw new RuntimeException("AddFolder(): Map not specified - call OpenMap() first");

        if (folderName.isEmpty()) {
			CTError(TAG, "Folder name must be specified.");
			return null;
		}
		JSONObject prop = new JSONObject();
		JSONObject top = new JSONObject();
		String urlEnd = CALTOPO_MAP_API_V1 + MapId + "/Folder";
		try {
			prop.put("class", "Folder");
			prop.put("title", folderName);
			prop.put("visible", contentsVisible);
			prop.put("labelVisible", contentLabelsVisible);
			top.put("type", "Feature");
			top.put("properties", prop);
		} catch (Exception e) {
			CTError(TAG, "addFolder() raised.", e);
			return null;
		}

		CaltopoOp op = new CaltopoOp(onComplete);
		return SendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
    }


    /** AddLine() - add line to the session's selected map.
	 *
	 * @param pointArray - array of {lng,lat] arrays.
     * @param lineLabel - text label for line.
     * @param existingLineId - line ID - if already existing.
     * @param folderId - ID of the folder this line s/b created in.
     * @param description - Description text for line.
     * @return CaltopoOp on success and null if configuring/sending msg failed.
     */
    @NonNull
	public static CaltopoOp AddLine(@NonNull JSONArray pointArray, @NonNull String lineLabel, @Nullable String description,
                                    @Nullable String existingLineId, @Nullable String folderId,
                                    @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("AddLine(): Map not specified - call OpenMap() first");

        if (0 == pointArray.length()) {
			CTError(TAG, "Can't add a line without any points");
			return null;
		}

		JSONObject prop = new JSONObject();
		JSONObject geometry = new JSONObject();
		JSONObject top = new JSONObject();
		String objid = "";
		if (lineProp == null) lineProp = CtLinePropertyDefault;
		try {
			prop.put("class", "Shape");
			prop.put("updated", System.currentTimeMillis());
			prop.put("title", lineLabel);
			prop.put("description", description);
			if (folderId != null && !folderId.isEmpty()) prop.put("folderId", folderId);
			prop.put("stroke-width", lineProp.width);
			prop.put("stroke-opacity", lineProp.opacity);
			prop.put("stroke", lineProp.color);
			prop.put("pattern", lineProp.pattern);

			geometry.put("type", "LineString");
			geometry.put("coordinates", pointArray);
			geometry.put("size", pointArray.length());

			if (existingLineId != null && !existingLineId.isEmpty()) {
				top.put("id", existingLineId);
				objid = "/" + existingLineId;
				geometry.put("incremental", "true");
			}
			top.put("type", "Feature");
			top.put("properties", prop);
			top.put("geometry", geometry);
		} catch (Exception e){
			CTError(TAG, "addLine() .put raised - for no apparent reason", e);
			return null;
		}
		String urlEnd = CALTOPO_MAP_API_V1 + MapId + "/Shape" + objid;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
		return op;
    }

	@Nullable
	public static CaltopoOp AddMarker(double lat, double lng, @NonNull String markerTitle,
                                      @Nullable String symbol, @Nullable String folderId,
                                      @Nullable String existingMarkerId, @Nullable JSONObject extraProperties,
                                      @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("AddMarker(): Map not specified - call OpenMap() first");

        JSONObject prop = new JSONObject();
		JSONObject geometry = new JSONObject();
		JSONObject top = new JSONObject();
		String objid = "";
		try {
			prop.put("class", "Marker");
			prop.put("updated", System.currentTimeMillis());
			prop.put("title", markerTitle);
			prop.put("marker-color", "#FF0000");
			if (null == symbol || symbol.isEmpty()) symbol = "point";
			prop.put("marker-symbol", symbol);
			prop.put("marker-size", "1");
			prop.put("marker-visibility", "visible");
			if (folderId != null && !folderId.isEmpty()) {
				prop.put("folderId", folderId);
			}
			if (null != extraProperties) try {
                for (Iterator<String> it = extraProperties.keys(); it.hasNext(); ) {
                    String key = it.next();
                    prop.put(key, extraProperties.get(key));
                }
			} catch (Exception e) {
				CTError(TAG, "exception processing extraProperties.", e);
			}
			JSONArray point = new JSONArray(String.format(Locale.US, "[%.7f,%.7f]", lng, lat));
			geometry.put("coordinates", point);
			geometry.put("type", "Point");

			top.put("type", "Feature");
			top.put("properties", prop);
			top.put("geometry", geometry);
			if (existingMarkerId != null && !existingMarkerId.isEmpty()) {
				top.put("id", existingMarkerId);
				objid = "/" + existingMarkerId;
			}
		} catch (Exception e) {
			CTError(TAG, "AddMarker() raised.", e);
			return null;
		}
		String urlEnd = CALTOPO_MAP_API_V1 + MapId + "/Marker" + objid;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
		return op;
	}

	@NonNull
	public static CaltopoOp DeleteShapeWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("DeleteShapeWithId(): Map not specified - call OpenMap() first");

        String urlEnd = CALTOPO_MAP_API_V1 + MapId + "/Shape/" + objId;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.DELETE, urlEnd, null, false);
		return op;
	}

	@NonNull
	public static CaltopoOp DeleteMarkerWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("DeleteMarkerWithId(): Map not specified - call OpenMap() first");

        String urlEnd = CALTOPO_MAP_API_V1 + MapId + "/Marker/" + objId;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.DELETE, urlEnd, null, false);
		return op;
	}

	@NonNull
	public static CaltopoOp DeleteLiveTrackWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete) {
        return DeleteLiveTrackWithId(objId, onComplete, (int[]) null);
    }

    public static CaltopoOp DeleteLiveTrackWithId(@NonNull String objId, @Nullable Consumer<CaltopoOp> onComplete,
                                                   int... acceptedErrorCodes) {
        if (null == MapId)
            throw new RuntimeException("DeleteLiveTrackWithId(): Map not specified - call OpenMap() first");

        String urlEnd = CALTOPO_MAP_API_V1 + MapId + "/LiveTrack/" + objId;
		CaltopoOp op  = new CaltopoOp(onComplete);
        op.acceptedErrorCodes = acceptedErrorCodes;
		SendRequest(op, CtsMethod_t.DELETE, urlEnd, null, false);
		return op;
	}

	@NonNull
	public static CaltopoOp EditObjectWithId(@NonNull String objectType, @NonNull String objId,
                                             @NonNull JSONObject featureSet, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("EditObjectWithId(): Map not specified - call OpenMap() first");

        String urlEnd = CALTOPO_MAP_API_V1 + MapId + "/" + objectType + "/" + objId;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.POST, urlEnd, featureSet, false);
		return op;
	}

	@NonNull
	public static CaltopoOp StartLiveTrack(@NonNull String liveTrackId, @NonNull String deviceId, @NonNull String label,
                                           @Nullable String folderId, @Nullable String description,
                                           @Nullable CtLineProperty lineProp, @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("StartLiveTrack(): Map not specified - call OpenMap() first");
        JSONObject prop = new JSONObject();

		if (lineProp == null) lineProp = LiveTrackLineProp;
		JSONObject top = new JSONObject();
		try {
			prop.put("title", label);
			prop.put("stroke-width", lineProp.width);
			prop.put("stroke-opacity", lineProp.opacity);
			prop.put("stroke", lineProp.color);
			prop.put("pattern", lineProp.pattern);
            prop.put("marker-symbol", "icon-8T781R60-12-0.5-0.5-tf");
            prop.put("marker-size", 2);
			if (null != description && !description.isEmpty()) prop.put("descripion", description);
			prop.put("class", "LiveTrack");
			if (folderId != null && !folderId.isEmpty()) {
				prop.put("folderId", folderId);
			}
			prop.put("deviceId", liveTrackDeviceId(deviceId, CaltopoClient.GetConnectKey()));

			top.put("id", liveTrackId);
			top.put("type", "Feature");
			top.put("properties", prop);
		} catch (Exception e) {
			CTError(TAG, "startLiveTrack(): raised.", e);
			return null;
		}

		String urlEnd = CALTOPO_MAP_API_V1 + MapId + "/LiveTrack/" + liveTrackId;
		CaltopoOp op = new CaltopoOp(onComplete);
		SendRequest(op, CtsMethod_t.POST, urlEnd, top, false);
		return op;
	}

    /***
     * FIXME: As of 6Feb, Caltopo now supports the following additional parameters:
     *      * aircraft:altitude (height above MSL in feet, if both elevation and altitude are specified, altitude wins)
     *      * aircraft:altitude_rate (rate of climb in ft/min)
     *      * aircraft:pitch (in degrees, positive is nose up)
     *      * aircraft:roll (posiitive is left wing up)
     *      * aircraft:gs (speed over the ground in knots)
     *      * aircraft:heading (direction the nose is pointing)
     *      * aircraft:track (direction of travel over the ground)
     *      * camera:azimuth (angle the camera is facing, degrees true north)
     *      * camera:tilt (up/down angle relative to horizon, -90 is straight down)
     *      * camera:fov_width (horizontal field of view in degrees)
     *      * camera:fov_height (vertical field of view in degrees)
     *      * camera:external_url (link to an external website showing the camera livestream)
     *      * camera:thumbnail_url (direct link to camera thumbnail image)
     * @param deviceId
     * @param lat
     * @param lng
     * @param eleMeters
     * @param onComplete
     * @return
     */

	@NonNull
	public static CaltopoOp AddLiveTrackPoint(@NonNull String deviceId,
									   double lat, double lng, double eleMeters,
                                       @Nullable CtDroneSpec.PositionTelemetry telemetry,
                                       @Nullable CaltopoCameraMetadata cameraMetadata,
                                       @Nullable Consumer<CaltopoOp> onComplete) {
        if (null == MapId)
            throw new RuntimeException("AddLiveTrackPoint(): Map not specified - call OpenMap() first");

		String latStr = String.format(Locale.US, "%.7f", lat);
		String lngStr = String.format(Locale.US, "%.7f", lng);
		Long ele = (long)eleMeters;
		String connectKey = CaltopoClient.GetConnectKey().trim();
		StringBuilder urlBuilder = new StringBuilder("https://" + DomainAndPort +
				positionReportPath(connectKey) + "?" +
				EncodeParm("id", deviceId) + "&" +
				EncodeParm("lat", latStr) + "&" +
				EncodeParm("lng", lngStr) + "&" +
				EncodeParm("elevation", ele.toString()));
        JSONObject queryParameters = buildPositionQueryParameters(eleMeters, telemetry, cameraMetadata);
        appendPositionQueryParameters(urlBuilder, queryParameters);
        String url = urlBuilder.toString();

		CaltopoOp op = new CaltopoOp(onComplete);
		op.positionReport = true;
        op.goNaked = true;
        op.method = positionReportMethod();
        op.url = url;
        PositionReports.submit(positionReportKey(deviceId), op);
		return op;
	}

    @NonNull
    static String positionReportPath(@Nullable String configuredConnectKey) {
        String connectKey = effectiveConnectKey(configuredConnectKey);
        try {
            String encoded = URLEncoder.encode(connectKey, StandardCharsets.UTF_8.name())
                    .replace("+", "%20");
            return "/api/v1/position/report/" + encoded;
        } catch (Exception error) {
            throw new IllegalArgumentException("Unable to encode CalTopo Connect Key", error);
        }
    }

    @NonNull
    static String effectiveConnectKey(@Nullable String configuredConnectKey) {
        String connectKey = configuredConnectKey == null ? "" : configuredConnectKey.trim();
        return connectKey.isEmpty() ? "DRONE" : connectKey;
    }

    @NonNull
    static String liveTrackDeviceId(@NonNull String deviceId,
                                    @Nullable String configuredConnectKey) {
        return String.format(Locale.US, "FLEET:%s-%s",
                effectiveConnectKey(configuredConnectKey), deviceId);
    }

    @NonNull
    static CtsMethod_t positionReportMethod() {
		return CtsMethod_t.GET;
    }

    @NonNull
    static JSONObject buildPositionQueryParameters(
            double aircraftAltitudeMeters,
            @Nullable CtDroneSpec.PositionTelemetry telemetry,
            @Nullable CaltopoCameraMetadata cameraMetadata) {
        JSONObject parameters = new JSONObject();
        try {
            if (Double.isFinite(aircraftAltitudeMeters)
                    && Double.compare(aircraftAltitudeMeters, INVALID_RID_ALTITUDE_METERS) != 0) {
                parameters.put("aircraft:altitude", Math.round(aircraftAltitudeMeters * FEET_PER_METER));
            }
            if (telemetry != null) {
                putFinite(parameters, "aircraft:altitude_rate", telemetry.aircraftAltitudeRateFpm);
                putFinite(parameters, "aircraft:gs", telemetry.aircraftGsKnots);
                putFinite(parameters, "aircraft:track", telemetry.aircraftTrackDeg);
            }
            if (cameraMetadata != null) {
                putString(parameters, "camera:external_url", cameraMetadata.externalUrl);
                putString(parameters, "camera:thumbnail_url", cameraMetadata.thumbnailUrl);
                putFinite(parameters, "camera:azimuth", cameraMetadata.azimuthDegrees);
                putFinite(parameters, "camera:tilt", cameraMetadata.tiltDegrees);
                putFinite(parameters, "camera:fov_width", cameraMetadata.horizontalFovDegrees);
                putFinite(parameters, "camera:fov_height", cameraMetadata.verticalFovDegrees);
            }
        } catch (Exception e) {
			CTError(TAG, "buildPositionQueryParameters() JSON put raised", e);
            return parameters;
        }
        return parameters;
    }

    @NonNull
    static String positionReportDiagnosticSummary(@NonNull JSONObject parameters) {
        String[] numericNames = {
                "aircraft:altitude",
                "aircraft:altitude_rate",
                "aircraft:gs",
                "aircraft:track",
                "camera:azimuth",
                "camera:tilt",
                "camera:fov_width",
                "camera:fov_height"
        };
        StringBuilder summary = new StringBuilder("positionReport(GET) fields:");
        for (String name : numericNames) {
            summary.append(' ').append(name).append('=');
            summary.append(parameters.has(name) ? parameters.opt(name) : "absent");
        }
        appendRedactedUrlSummary(summary, parameters, "camera:external_url");
        appendRedactedUrlSummary(summary, parameters, "camera:thumbnail_url");
        return summary.toString();
    }

    private static void appendRedactedUrlSummary(@NonNull StringBuilder summary,
                                                 @NonNull JSONObject parameters,
                                                 @NonNull String name) {
        String rawUrl = parameters.optString(name, "");
        summary.append(' ').append(name).append('=');
        if (rawUrl.isEmpty()) {
            summary.append("absent");
            return;
        }
        try {
            URI parsed = URI.create(rawUrl);
            summary.append("present(scheme=")
                    .append(parsed.getScheme() == null ? "missing" : parsed.getScheme())
                    .append(",host=")
                    .append(parsed.getHost() == null ? "missing" : parsed.getHost());
            if ("camera:thumbnail_url".equals(name)) {
                String rawQuery = parsed.getRawQuery();
                if (rawQuery != null && rawQuery.startsWith("timestamp=")) {
                    summary.append(",cacheBust=").append(rawQuery);
                } else {
                    summary.append(",cacheBust=missing");
                }
            }
            summary.append(')');
        } catch (IllegalArgumentException error) {
            summary.append("present(invalid-url)");
        }
    }

    private static void appendPositionQueryParameters(@NonNull StringBuilder urlBuilder,
                                                      @NonNull JSONObject parameters) {
        Iterator<String> keys = parameters.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            Object value = parameters.opt(name);
            if (value != null) {
                urlBuilder.append("&").append(EncodeParm(name, value.toString()));
            }
        }
    }

    private static void putFinite(@NonNull JSONObject jo, @NonNull String key,
                                  @Nullable Double value) throws JSONException {
        if (value != null && Double.isFinite(value)) {
            jo.put(key, value);
        }
    }

    private static void putString(@NonNull JSONObject jo, @NonNull String key,
                                  @Nullable String value) throws JSONException {
        if (value != null && !value.isEmpty()) {
            jo.put(key, value);
        }
    }

    /*** CompressScaledBitmapAsJpeg()
     * Build a compressed copy of the input, optionally scaled down first.  The
     * result is rendered into a byte array in jpeg format.  N.B. this procedure can
     * block for seconds on a big image, so don't run it in the main app thread.
     *
     * @param bitmap      Required source bitmap is not molested.
     * @param quality     0-100, where 0 is max compression and 100 is max quality.
     * @param rdxPercent  Preserve aspect ratio, but scale down the source image by
     *                    specified percent.  Use <= 0.0 or >= 1.0 to preserve the
     *                    original image size. (i.e. 0.2 == 20% of original image).
     * @param pixWidth    Preserve aspect ratio, but make the resulting image this
     *                    many pixels wide.  Ignored if valid rdxPercent specified.
     */
    @Nullable
    private static byte[] CompressScaledBitmapAsJpeg(@NonNull Bitmap bitmap,
                                              int quality, double rdxPercent, int pixWidth) {
        int inWidth = bitmap.getWidth();
        int inHeight = bitmap.getHeight();
        int dstWidth, dstHeight;
        Bitmap sourceBitmap = bitmap;

        if (rdxPercent > .05 && rdxPercent < 1.0) {
            dstHeight = (int)round(rdxPercent * inHeight);
            dstWidth = (int)round(rdxPercent * inWidth);
        } else if (pixWidth <= 0) {
            dstWidth = inWidth;
            dstHeight = inHeight;
        } else {
            dstWidth = pixWidth;
            dstHeight = (int)round((float)inHeight/(float)inWidth * (float)pixWidth);
        }
        if (inWidth != dstWidth) {
            sourceBitmap = Bitmap.createScaledBitmap(sourceBitmap, dstWidth, dstHeight, true);
        }
        if (quality < 0) quality = 0;
        if (quality > 100) quality = 100;
        ByteArrayOutputStream oStream = new ByteArrayOutputStream();
        // Compress the bitmap to JPEG format with a specific quality
        sourceBitmap.compress(Bitmap.CompressFormat.JPEG, quality, oStream);
        byte[] byteArray = oStream.toByteArray();
        try {
            oStream.close();
        } catch (IOException e) {
            CTDebug(TAG, "bitmapAsCompressedJpeg(): compress raised.");
            return null;
        }
        return byteArray;
    }

    /***
     * Returns the response for the MapMediaObject on success, otherwise returns
     * the failed op response.
     *
     * FIXME: add support for the new Caltopo parameters in extraParameters:
     * starting with heading
     * aircraft:altitude (height above MSL in feet, if both elevation and altitude are specified, altitude wins)
     * aircraft:altitude_rate (rate of climb in ft/min)
     * aircraft:pitch (in degrees, positive is nose up)
     * aircraft:roll (posiitive is left wing up)
     * aircraft:gs (speed over the ground in knots)
     * aircraft:heading (direction the nose is pointing)
     * aircraft:track (direction of travel over the ground)

     * camera:azimuth (angle the camera is facing, degrees true north)
     * camera:tilt (up/down angle relative to horizon, -90 is straight down)
     * camera:fov_width (horizontal field of view in degrees)
     * camera:fov_height (vertical field of view in degrees)
     * camera:external_url (link to an external website showing the camera livestream)
     * camera:thumbnail_url (direct link to camera thumbnail image)
     *
     */
    static private CaltopoOp BgAttachPhotoToMarker(@NonNull String base64ImageRep,
                                                   double lat, double lng,
                                                   @NonNull String parentMarkerId,
                                                   @NonNull String title,
                                                   @Nullable String description,
                                                   @Nullable JSONObject extraParameters ) {
        CaltopoOp apOp = new CaltopoOp(null);
        String mediaId = UUID.randomUUID().toString();
                CTDebug(TAG, "BgAttachPhotoToMarker() parent: " + parentMarkerId);
        try {
            // create the backend media object:
            Util.SafeJSONObject creator = new Util.SafeJSONObject();
            creator.put("creator", Cred.teamId);
            Util.SafeJSONObject mediaPayload = new Util.SafeJSONObject();
            mediaPayload.put("properties", creator);
            String moUrl = CALTOPO_MEDIA_API_V1 + mediaId;
            CTDebug(TAG, "BgAttachPhotoToMarker() Step 1: Create backend media object: " + moUrl);
            CaltopoOp mediaOp = BgOp(CtsMethod_t.POST, moUrl, mediaPayload, false);
            if (mediaOp.fail())  return mediaOp;
            CTDebug(TAG, "BgAttachPhotoToMarker() backend media object created.");

            // upload the media data:
            JSONObject dataPayload = new JSONObject();
            dataPayload.put("creator", Cred.teamId);
            dataPayload.put("data", base64ImageRep);
            String dataUrl = moUrl + "/data";
            CTDebug(TAG, "BgAttachPhotoToMarker() Step 2: Upload media: " + dataUrl);
            CaltopoOp dataOp = BgOp(CtsMethod_t.POST, dataUrl, dataPayload, false);
            if (dataOp.fail()) return dataOp;
            CTDebug(TAG, "BgAttachPhotoToMarker() media uploaded w/o error.");

            // Attach the data to the parent marker:
            JSONObject prop = new JSONObject();
            prop.put("title", title);
            prop.put("parentId", "Marker:" + parentMarkerId);
            prop.put("backendMediaId", mediaId);
            prop.put("heading", JSONObject.NULL);
            prop.put("class", "MapMediaObject");
            if (null != description) prop.put("description", description);
            prop.put("marker-symbol", "aperture");
            prop.put("marker-color", "#FF00FF");
            prop.put("marker-size", 1);
            prop.put("created", System.currentTimeMillis());
            JSONObject geo = new JSONObject();
            geo.put("type", "Point");
            JSONArray pts = new JSONArray();
            pts.put(lng); pts.put(lat);
            geo.put("coordinates", pts);
            JSONObject moPayload = new JSONObject();
            moPayload.put("type", "Feature");
            moPayload.put("geometry", geo);
            moPayload.put("properties", prop);
            String linkUrl = CALTOPO_MAP_API_V1 + MapId + "/MapMediaObject";
            CTDebug(TAG, "BgAttachPhotoToMarker() Step 3: Attach to parent: " + moUrl);
            CaltopoOp moOp = BgOp(CtsMethod_t.POST, linkUrl, moPayload, false);
            if (moOp.fail()) return moOp;
            CTDebug(TAG, "BgAttachPhotoToMarker() All operations completed w/o error.");
            return moOp;

        } catch (JSONException e) {
            String resp = "BgAttachPhotoToMarker(): error sending message(s)";
            CTError(TAG, resp, e);
            apOp.response = resp;
            apOp.setOperationIsDone(false);
            return apOp;
        }
    }

    private static void ArchiveJpeg(byte[] bytes, @NonNull String markerTitle) {
        Context ctxt = R2CApplication.getAppCtxt();
        DocumentFile todaysArchiveDir = GetTodaysTrackDir();
        if (null == ctxt || null == todaysArchiveDir) {
            CTError(TAG, "ArchiveJpeg(): missing required context.");
            return;
        }
        String JPEG_MIME_TYPE = "image/jpeg";
        SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy-HHmmss", Locale.US);
        String timeStr = sdf.format(new Date());
// FIXME: The .replaceAll() hangs on android 10
//        String capitalized = String.valueOf(Pattern.compile("\\b(\\w)")
//                .matcher(markerTitle)
//                .replaceAll(m -> m.group().toUpperCase()));
//        String filename = capitalized.replaceAll("[^a-zA-Z0-9-]+", "") + "_" + timeStr +".jpeg";
        String filename = markerTitle.replaceAll("[^a-zA-Z0-9-]+", "") + "_" + timeStr +".jpeg";
        try {
            DocumentFile jpegFile = todaysArchiveDir.createFile(JPEG_MIME_TYPE, filename);
            ContentResolver resolver = ctxt.getContentResolver();
            OutputStream outputStream = resolver.openOutputStream(jpegFile.getUri());
            outputStream.write(bytes);
            outputStream.flush();
            outputStream.close();
            CTDebug(TAG, "ArchiveJpeg() Wrote:" + filename);
        } catch (Exception e) {
            CTError(TAG, "ArchiveJpeg() Not able to write jpeg file: " + filename);
        }
    }

    static CaltopoOp BgAddPhotoWaypoint(CaltopoOp apwOp,
                                      double lat, double lng, @NonNull String markerTitle, @Nullable String markerDesc,
                                   @NonNull String folderId, long createdTimestamp, @NonNull Bitmap photoBitmap

    ) {
        String markerId = UUID.randomUUID().toString();
        String mediaId = UUID.randomUUID().toString();

        CTDebug(TAG, "BgAddPhotoWaypoint() " + markerTitle);
        Util.SafeJSONObject jo = new Util.SafeJSONObject();
        jo.put("created", createdTimestamp);
        if (markerDesc != null) jo.put("description", markerDesc);
        CaltopoOp markerOp = AddMarker(lat, lng, markerTitle, "Drone", folderId, markerId, jo, null);
        if (null == markerOp) {
            String emsg = "BgAddPhotoWaypoint(): Need to use Util.SafeJSONObject() in AddMarker.";
            CTError(TAG, emsg );
            apwOp.response = emsg;
            apwOp.setOperationIsDone(false);
            return apwOp;
        }

       /* byte[] thumbNailBitmap = CompressScaledBitmapAsJpeg(photoBitmap, 80, 0, 1024);
        String thumbEnc = android.util.Base64.encodeToString(thumbNailBitmap, android.util.Base64.NO_WRAP);
        CaltopoOp op = BgAttachPhotoToMarker(thumbEnc, lat, lng, markerId, markerTitle + "_small", markerDesc, jo);
        if (op.fail()) return op;
        */
        byte[] fullSizedBitmap = CompressScaledBitmapAsJpeg(photoBitmap, 80, 100, 0);
        String fullEnc = android.util.Base64.encodeToString(fullSizedBitmap, android.util.Base64.NO_WRAP);
        ArchiveJpeg(fullSizedBitmap, markerTitle);
        // FIXME: Add metadata to image, i.e. lat,lng, description, drone designation,
        //    map name, incident, op_period timestamp when captured,

        // Attach Images:
        CTDebug(TAG, "BgAddPhotoWaypoint() " + markerTitle + " - Attaching full sized...");
        CaltopoOp op = BgAttachPhotoToMarker(fullEnc, lat, lng, markerId, markerTitle, markerDesc, jo);
        if (op.fail()) return op;

        apwOp.response = "Successfully attached two photos to marker.";
        apwOp.setOperationIsDone(true);
        return apwOp;
    }

    /*** AddPhotoMarker().
     * Add a Marker with an attached photo to the current map.
     * Note that this is a bit of a process, so we have a dedicated background thread that
     * just handles photo markers.   First we need to create a normal clue marker, with title
     * and description in our ArchiveFolder (n.b. not in active folder). While that effort is
     * under way, we need to resize and compress the supplied bitmap to create both a thumbnail
     * rep with same title as the marker, but '_small' suffix.  When that completes, we also
     * compress the full-sized
     *
     *   The CaltopOp that is returned (and callback if supplied), will be invoked once the
     * marker has been completed and the photos have been added to it.
     *
     *   Since this is such an involved process, we just sanity check args here and then
     * hand everything off to a dedicated photo processing background thread to implement.
     * The reason we use a separate thread is we don't want to significantly impact the
     * flow of the main caltopo interaction thread that is busy with waypoint updates.
     *
     * @param lat
     * @param lng
     * @param markerTitle
     * @param markerDesc
     * @param folderId
     * @param clueTimestamp
     * @param photoBitmap
     * @param onComplete   Ignored if you passed in non-null apmOp
     * @return
     */
    @Nullable
    public static CaltopoOp AddPhotoMarker(double lat, double lng, @NonNull String markerTitle, @NonNull String markerDesc,
                                           @NonNull String folderId, long clueTimestamp, @NonNull Bitmap photoBitmap,
                                           @Nullable Consumer<CaltopoOp> onComplete) {

        CaltopoOp apmOp = new CaltopoOp(onComplete);
        if (null == MapId) {
            apmOp.response = "AddPhotoMarker(): Map not specified - call OpenMap() first";
            apmOp.setOperationIsDone(false);
            return apmOp;
        }

        SubmitPhotoRequest(apmOp, () ->
                BgAddPhotoWaypoint(apmOp, lat, lng, markerTitle, markerDesc, folderId, clueTimestamp, photoBitmap));

        return apmOp;
    }

} // end of CaltopoSession class spec.
