/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/* Object for keeping track of communications to/from Caltopo server.
 * Note that each operation returns an integer operation number for the 
 * corresponding operation.   You can block your thread on that op #,
 * waiting for the operation to finish, or establish a CaltopoListener
 * to monitor completion status for all operations.   If neither option
 * is used, all state associated with an operation is destroyed at the
 * completion of the operation.
 */
public class CaltopoOp implements Future <CaltopoOp> {
    private static final String TAG = "CaltopoOp";
	public long opNum;
    public long queuedTimestampMsec;
    public long sentTimestampMsec;
    public long receivedTimestampMsec;
	public final Consumer<CaltopoOp> onComplete;

    // the actual message to be sent - in case it needs to be resent:
    public CaltopoSession.CtsMethod_t method;
    public String url;
    public JSONObject payload;
	public static int lastOpNum;

    // response to the async message execution:
    public Future<CaltopoOp> asyncFuture; // if op was scheduled for execution.
	public int responseCode;
    public boolean goodResponse;    // Valid if receivedTimestampInMsec != 0;
    public int[] acceptedErrorCodes; // response codes to treat as success rather than firing CaltopoOpFailed
    public String response;    // if receivedTimestampInMsec && goodResponse == false;
    public JSONObject responseJson; // if receivedTimestampInMsec && goodResponse == true;
	public boolean goNaked;
    public boolean positionReport;
	private boolean isDone;
	    
    public CaltopoOp() throws RuntimeException {
		throw new RuntimeException("use: new CaltopoOp(Consumer<CaltopoOp>onComplete) instead.");
    }

	public CaltopoOp(@Nullable Consumer<CaltopoOp> onComplete) {
		opNum = ++lastOpNum;
		queuedTimestampMsec = System.currentTimeMillis();
		this.onComplete = onComplete;
//        if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo)
//            CaltopoClient.CTInfo(TAG, String.format(Locale.US, "creating op %d", opNum));
	}

    public long roundTripTimeInMsec() {
			return receivedTimestampMsec - queuedTimestampMsec;
    }
	
    public boolean success() {
        return isDone() && this.goodResponse;
	    }

    public boolean fail() {
        return isDone() && !this.goodResponse;
    }

    void discardPositionReport() {
        // Superseded live samples are neither network successes nor failures.
        isDone = true;
    }

    @Nullable
	public JSONObject getResponse() { return responseJson; }

	public void setOperationIsDone(boolean opPassed) {
		goodResponse = opPassed;
		isDone = true;
		if (null == onComplete) return;
		Handler handler = new Handler(Looper.getMainLooper());
		handler.post(() -> {
            onComplete.accept(this);
        });
	}

	@Override
	@NonNull
	public String toString() {
		String jsonStringRep = "";
		String responseJsonStringRep = "";
		if (payload != null) {
			try {
				jsonStringRep = payload.toString(2);
			} catch (JSONException e) {
				CTError(TAG, "payload.toString() raised:", e);
			}
		}
		if (responseJson != null) {
			try {
				responseJsonStringRep = responseJson.toString(2);
			} catch (JSONException e) {
				CTError(TAG, "responseJson.toString() raised:", e);
			}
		}
		return String.format(Locale.US,
			"CaltopoOp %d: %s, %s, payload:\n%s\n  queued:%d\n  sent:%d\n  " +
					"received: %d  \n  isDone:%s good:%s, response:%s\n  jsonResponse:\n%s",
			opNum, method, url, jsonStringRep,
				queuedTimestampMsec, sentTimestampMsec, receivedTimestampMsec,
				isDone, goodResponse, response, responseJsonStringRep);
    }
	
	// syncOp... options for blocking until completion for results:
	@Nullable
	public JSONObject syncOpJSONObject()
			throws ExecutionException, InterruptedException, JSONException {
		this.get();
		if (fail()) {
			throw new JSONException("Op failed - '" + response + "'");
		}
		if (null == responseJson) {
			throw new JSONException("op failed to return expected JSONObject in response.\n" + this);
		}
		return responseJson;
	}
	public void syncOp(double timeoutInSeconds)	throws ExecutionException, InterruptedException, TimeoutException {
		this.get((long)(timeoutInSeconds * 1000), TimeUnit.MILLISECONDS);
	}
	public void syncOp(long timeoutInMilliseconds)	throws ExecutionException, InterruptedException, TimeoutException {
		this.get(timeoutInMilliseconds, TimeUnit.MILLISECONDS);
	}

	@NonNull
	public String responseString() {
		String msg = "";
		if (null != responseJson) {
			try {
				msg = responseJson.toString(4);
			} catch (JSONException e) {
				msg = (null != response) ? response : "";
			}
		}
		return msg;
	}

    public JSONObject syncOpJSONObject(double timeoutInSeconds)
			throws ExecutionException, InterruptedException,
			TimeoutException, JSONException {

		this.get((long)(timeoutInSeconds * 1000), TimeUnit.MILLISECONDS);
		if (fail()) {
			throw new JSONException("Op failed - '" + response + "'");
		}
		if (null == responseJson) {
			throw new JSONException("op failed to return expected JSONObject in response.\n" + this);
		}
		return responseJson;
    }

	public String id() {
		if (null == responseJson) {
			CTError(TAG, "op failed to return expected JSONObject in response.\n" + this);
			return "";
		}
		String retval = responseJson.optString("id", "");
		if (retval.isEmpty()) CTError(TAG, "responseJson did not contain expected id.\n" + this);
		return retval;
	}

    protected void finalize() {
        if (CaltopoClient.DebugLevel >= CaltopoClient.DebugLevelInfo)
            CaltopoClient.CTInfo(TAG, String.format(Locale.US, "destroying op %d", this.opNum));
    }

    // Future interface implementation:
    public boolean cancel(boolean mayInterruptIfRunning) {
		if (null != asyncFuture) {
            return asyncFuture.cancel(mayInterruptIfRunning);
		}
		return false;
    }

	
    public CaltopoOp get()
			throws ExecutionException, InterruptedException, RuntimeException {
		if (null != asyncFuture) {
            asyncFuture.get();
		}

		return this;
    }

	public CaltopoOp get(long timeout, TimeUnit unit)
			throws RuntimeException, ExecutionException, InterruptedException, TimeoutException {
		if (null != asyncFuture) {
            asyncFuture.get(timeout, unit);
		}
		return this;
    }

    public boolean isCancelled() throws RuntimeException {
		if (null != asyncFuture) {
            return asyncFuture.isCancelled();
		}
		return false;
    }

    public boolean isDone() throws RuntimeException {
		if (null != asyncFuture) {
            isDone = isDone || asyncFuture.isDone();
		}
		return isDone;
    }
	
} // end of CaltopoOp class spec.
