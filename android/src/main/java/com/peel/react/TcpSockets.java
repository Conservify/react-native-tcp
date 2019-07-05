/**
 * Copyright (c) 2015-present, Peel Technologies, Inc.
 * All rights reserved.
 */

package com.peel.react;

import android.support.annotation.Nullable;
import android.util.Base64;
import android.net.Uri;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.OutputStream;
import java.io.IOException;
import java.io.FileNotFoundException;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.Arrays;

/**
 * The NativeModule acting as an api layer for {@link TcpSocketManager}
 */
public final class TcpSockets extends ReactContextBaseJavaModule implements TcpSocketListener {
    private static final String TAG = "TcpSockets";

    private boolean mShuttingDown = false;
    private TcpSocketManager socketManager;

    private ReactContext mReactContext;

    public TcpSockets(ReactApplicationContext reactContext) {
        super(reactContext);
        mReactContext = reactContext;

        try {
            socketManager = new TcpSocketManager(this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void initialize() {
        mShuttingDown = false;
    }

    @Override
    public void onCatalystInstanceDestroy() {
        mShuttingDown = true;

        try {
            new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
                @Override
                protected void doInBackgroundGuarded(Void... params) {
                    socketManager.closeAllSockets();
                }
            }.execute().get();
        } catch (InterruptedException ioe) {
            FLog.e(TAG, "onCatalystInstanceDestroy", ioe);
        } catch (ExecutionException ee) {
            FLog.e(TAG, "onCatalystInstanceDestroy", ee);
        }
    }

    private void sendEvent(String eventName, WritableMap params) {
        mReactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
    }

    @ReactMethod
    public void listen(final Integer cId, final String host, final Integer port) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                try {
                    socketManager.listen(cId, host, port);
                } catch (UnknownHostException uhe) {
                    FLog.e(TAG, "listen", uhe);
                    onError(cId, uhe.getMessage(), new SocketSettings());
                } catch (IOException ioe) {
                    FLog.e(TAG, "listen", ioe);
                    onError(cId, ioe.getMessage(), new SocketSettings());
                }
            }
        }.execute();
    }

    @ReactMethod
    public void connect(final Integer cId, final @Nullable String host, final Integer port, final ReadableMap options) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                FLog.w(TAG, "options: " + options);
                String destination = options.getString("write");
                SocketSettings settings = new SocketSettings(destination );
                try {
                    socketManager.connect(cId, host, port, settings);
                } catch (UnknownHostException uhe) {
                    FLog.e(TAG, "connect", uhe);
                    onError(cId, uhe.getMessage(), settings);
                } catch (IOException ioe) {
                    FLog.e(TAG, "connect", ioe);
                    onError(cId, ioe.getMessage(), settings);
                }
            }
        }.execute();
    }

    @ReactMethod
    public void write(final Integer cId, final String base64String, final Callback callback) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                socketManager.write(cId, Base64.decode(base64String, Base64.NO_WRAP));
                if (callback != null) {
                    callback.invoke();
                }
            }
        }.execute();
    }

    @ReactMethod
    public void end(final Integer cId) {
        new GuardedAsyncTask<Void, Void>(getReactApplicationContext()) {
            @Override
            protected void doInBackgroundGuarded(Void... params) {
                socketManager.close(cId);
            }
        }.execute();
    }

    @ReactMethod
    public void destroy(final Integer cId) {
        end(cId);
    }

    /** TcpSocketListener */

    @Override
    public void onConnection(Integer serverId, Integer clientId, InetSocketAddress socketAddress, SocketSettings settings) {
        if (mShuttingDown) {
            return;
        }
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", serverId);

        WritableMap infoParams = Arguments.createMap();
        infoParams.putInt("id", clientId);

        final InetAddress address = socketAddress.getAddress();

        WritableMap addressParams = Arguments.createMap();
        addressParams.putString("address", address.getHostAddress());
        addressParams.putInt("port", socketAddress.getPort());
        addressParams.putString("family", address instanceof Inet6Address ? "IPv6" : "IPv4");

        infoParams.putMap("address", addressParams);
        eventParams.putMap("info", infoParams);

        sendEvent("connection", eventParams);
    }

    @Override
    public void onConnect(Integer id, InetSocketAddress socketAddress, SocketSettings settings) {
        if (mShuttingDown) {
            return;
        }
        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);

        final InetAddress address = socketAddress.getAddress();

        WritableMap addressParams = Arguments.createMap();
        addressParams.putString("address", address.getHostAddress());
        addressParams.putInt("port", socketAddress.getPort());
        addressParams.putString("family", address instanceof Inet6Address ? "IPv6" : "IPv4");

        eventParams.putMap("address", addressParams);

        sendEvent("connect", eventParams);
    }

    private Uri getFileUriIfFile(String filepath) throws IOException {
        Uri uri = Uri.parse(filepath);
        if (uri.getScheme() == null) {
            // No prefix, assuming that provided path is absolute path to file
            File file = new File(filepath);
            if (file.isDirectory()) {
                return null;
            }
            uri = Uri.parse("file://" + filepath);
        }
        return uri;
    }

    private Uri getFileUri(String filepath) throws IOException {
        Uri uri = getFileUriIfFile(filepath);
        if (uri == null) {
            throw new IOException("EISDIR: illegal operation on a directory, read '" + filepath + "'");
        }
        return uri;
    }

    private OutputStream getOutputStream(String filepath, boolean append) throws IOException {
        Uri uri = getFileUri(filepath);
        OutputStream stream;
        try {
            stream = mReactContext.getContentResolver().openOutputStream(uri, append ? "wa" : "w");
        } catch (FileNotFoundException ex) {
            throw new IOException("ENOENT: no such file or directory, open '" + filepath + "'");
        }
        if (stream == null) {
            throw new IOException("ENOENT: could not open an output stream for '" + filepath + "'");
        }
        return stream;
    }

    @Override
    public void onData(Integer id, byte[] data, SocketSettings settings) {
        if (mShuttingDown) {
            return;
        }

        if (settings.getDestination() == null) {
            WritableMap eventParams = Arguments.createMap();
            eventParams.putInt("id", id);
            eventParams.putString("data", Base64.encodeToString(data, Base64.NO_WRAP));

            sendEvent("data", eventParams);
        }
        else {
            try {
                if (settings.getStream() == null) {
                    settings.setStream(getOutputStream(settings.getDestination(), false));
                }

                if (!settings.gotHeader()) {
                    FLog.w(TAG, "Header! " + data.length);

                    int length[] = new int[1];
                    int offset = Varint.getVarInt(data, 0, length);

                    offset += length[0];

                    settings.getStream().write(data, offset, data.length - offset);
                    settings.received(data.length - offset);

                    byte[] header = Arrays.copyOfRange(data, 0, offset);
                    WritableMap eventParams = Arguments.createMap();
                    eventParams.putInt("id", id);
                    eventParams.putString("data", Base64.encodeToString(header, Base64.NO_WRAP));
                    sendEvent("header", eventParams);

                    settings.gotHeader(true);
                }
                else {
                    settings.getStream().write(data);
                    settings.received(data.length);
                }

                if (settings.updateProgress()) {
                    WritableMap eventParams = Arguments.createMap();
                    eventParams.putInt("id", id);
                    eventParams.putInt("total", settings.getTotal());
                    eventParams.putInt("received", settings.getReceived());
                    sendEvent("progress", eventParams);
                }
            }
            catch (IOException e) {
                FLog.e(TAG, "Error writing data", e);
                onError(id, e.getMessage(), settings);
            }
        }
    }

    @Override
    public void onClose(Integer id, String error, SocketSettings settings) {
        if (mShuttingDown) {
            return;
        }
        if (error != null) {
            onError(id, error, settings);
        }

        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);
        eventParams.putBoolean("hadError", error != null);

        sendEvent("close", eventParams);
    }

    @Override
    public void onError(Integer id, String error, SocketSettings settings) {
        if (mShuttingDown) {
            return;
        }

        WritableMap eventParams = Arguments.createMap();
        eventParams.putInt("id", id);
        eventParams.putString("error", error);

        sendEvent("error", eventParams);
    }
}
