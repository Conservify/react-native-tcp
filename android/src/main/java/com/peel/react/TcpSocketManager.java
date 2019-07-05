package com.peel.react;

import android.support.annotation.Nullable;
import android.util.SparseArray;

import com.facebook.common.logging.FLog;

import com.koushikdutta.async.future.Cancellable;
import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.AsyncServerSocket;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.Util;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.ConnectCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.callback.ListenCallback;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.io.OutputStream;

class SocketSettings  {
    private static final String TAG = "TcpSockets";

    private String destination;
    private int total;
    private int received;
    private OutputStream saving;
    private long lastProgress;
    private boolean gotHeader;

    public SocketSettings() {
    }

    public SocketSettings(String d) {
        this.destination = d;
    }

    public String getDestination() {
        return destination;
    }

    public boolean gotHeader() {
        return gotHeader;
    }

    public void gotHeader(boolean header) {
        this.gotHeader = header;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getReceived() {
        return received;
    }

    public void received(int bytes) {
        received += bytes;
    }

    public void close() throws IOException {
        if (saving != null) {
            FLog.e(TAG, "Closing OutputStream");
            saving.close();
            saving = null;
        }
    }

    public OutputStream getStream() {
        return saving;
    }

    public void setStream(OutputStream stream) {
        saving = stream;
    }

    public boolean updateProgress() {
        if (lastProgress == 0 || System.currentTimeMillis() - lastProgress > 1000) {
            lastProgress = System.currentTimeMillis();
            return true;
        }
        return false;
    }
}

class Varint {
    public static int getVarInt(byte[] src, int offset, int[] dst) {
        int result = 0;
        int shift = 0;
        int b;
        do {
            if (shift >= 32) {
                // Out of range
                throw new IndexOutOfBoundsException("varint too long");
            }
            // Get 7 bits from next byte
            b = src[offset++];
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        dst[0] = result;
        return offset;
    }
}

class ClientEntry {
    private Object socket;
    private SocketSettings settings;

    public ClientEntry(Object socket, SocketSettings settings) {
        this.socket = socket;
        this.settings = settings;
    }

    public Object getSocket() {
        return socket;
    }

    public SocketSettings getSettings() {
        return settings;
    }

    public void close() throws IOException {
        if (settings != null) {
            settings.close();
        }
    }
};

/**
 * Created by aprock on 12/29/15.
 */
public final class TcpSocketManager {
    private static final String TAG = "TcpSockets";

    private SparseArray<ClientEntry> mClients = new SparseArray<ClientEntry>();

    private WeakReference<TcpSocketListener> mListener;
    private AsyncServer mServer = AsyncServer.getDefault();

    private int mInstances = 5000;

    public TcpSocketManager(TcpSocketListener listener) throws IOException {
        mListener = new WeakReference<TcpSocketListener>(listener);
    }

    private void setSocketCallbacks(final Integer cId, final AsyncSocket socket) {
        socket.setClosedCallback(new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onClose(cId, ex==null?null:ex.getMessage(), mClients.get(cId).getSettings());
                }
            }
        });

        socket.setDataCallback(new DataCallback() {
            @Override
            public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onData(cId, bb.getAllByteArray(), mClients.get(cId).getSettings());
                }
            }
        });

        socket.setEndCallback(new CompletedCallback() {
            @Override
            public void onCompleted(Exception ex) {
                SocketSettings settings = mClients.get(cId).getSettings();
                if (ex != null) {
                    TcpSocketListener listener = mListener.get();
                    if (listener != null) {
                        listener.onError(cId, ex.getMessage(), settings);
                    }
                }
                try {
                    settings.close();
                }
                catch (IOException e) {
                    FLog.e(TAG, "Error closing", e);
                }
                socket.close();
            }
        });
    }

    public void listen(final Integer cId, final String host, final Integer port) throws UnknownHostException, IOException {
        // resolve the address
        final InetSocketAddress socketAddress;
        if (host != null) {
            socketAddress = new InetSocketAddress(InetAddress.getByName(host), port);
        } else {
            socketAddress = new InetSocketAddress(port);
        }

        mServer.listen(InetAddress.getByName(host), port, new ListenCallback() {
            @Override
            public void onListening(AsyncServerSocket socket) {
                mClients.put(cId, new ClientEntry(socket, new SocketSettings(null)));

                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onConnect(cId, socketAddress, mClients.get(cId).getSettings());
                }
            }

            @Override
            public void onAccepted(AsyncSocket socket) {
                setSocketCallbacks(mInstances, socket);
                mClients.put(cId, new ClientEntry(socket, new SocketSettings(null)));

                AsyncNetworkSocket socketConverted = Util.getWrappedSocket(socket, AsyncNetworkSocket.class);
                InetSocketAddress remoteAddress = socketConverted != null ? socketConverted.getRemoteAddress() : socketAddress;

                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onConnection(cId, mInstances, remoteAddress, mClients.get(cId).getSettings());
                }

                mInstances++;
            }

            @Override
            public void onCompleted(Exception ex) {
                try {
                    mClients.get(cId).close();
                }
                catch (IOException e) {
                    FLog.e(TAG, "Close failed!", e);
                }
                mClients.delete(cId);

                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onClose(cId, ex != null ? ex.getMessage() : null, mClients.get(cId).getSettings());
                }
            }
        });
    }

    class TimeoutConnect {
        private Object mTimeout;
        private Cancellable mConnection;

        void connect(final Integer cId, final @Nullable String host, final Integer port, final SocketSettings settings) throws UnknownHostException, IOException {
            Runnable timeoutHandler = new Runnable() {
                @Override
                public void run() {
                    TcpSocketListener listener = mListener.get();
                    if (mConnection != null) {
                        mConnection.cancel();
                        mConnection = null;
                        listener.onError(cId, "Timeout", settings);
                        FLog.w(TAG, "Timeout " + host + ":" + port);
                    }
                    else {
                        FLog.w(TAG, "Timeout ignored " + host + ":" + port);
                    }
                }
            };

            FLog.w(TAG, "Connecting 1 " + host + ":" + port);

            // resolve the address
            final InetSocketAddress socketAddress;
            if (host != null) {
                socketAddress = new InetSocketAddress(InetAddress.getByName(host), port);
            } else {
                socketAddress = new InetSocketAddress(port);
            }


            ConnectCallback connectCallback =  new ConnectCallback() {
                @Override
                public void onConnectCompleted(Exception ex, AsyncSocket socket) {
                    FLog.w(TAG, "Connecting 2 " + host + ":" + port + " " + socketAddress.isUnresolved());

                    TcpSocketListener listener = mListener.get();
                    if (ex == null) {
                        FLog.w(TAG, "Connected");
                        mClients.put(cId, new ClientEntry(socket, settings));
                        setSocketCallbacks(cId, socket);

                        if (listener != null) {
                            listener.onConnect(cId, socketAddress, settings);
                        }
                    } else if (listener != null) {
                        FLog.e(TAG, "Connecting Error", ex);
                        listener.onError(cId, ex.getMessage(), settings);
                    }
                    if (mTimeout != null) {
                        mServer.removeAllCallbacks(mTimeout);
                        mTimeout = null;
                    }
                }
            };

            mConnection = mServer.connectSocket(socketAddress, connectCallback);
            mTimeout = mServer.postDelayed(timeoutHandler, 5000);
        }
    }

    public void connect(final Integer cId, final @Nullable String host, final Integer port, final SocketSettings settings) throws UnknownHostException, IOException {
        new TimeoutConnect().connect(cId, host, port, settings);
    }

    public void write(final Integer cId, final byte[] data) {
        ClientEntry entry = mClients.get(cId);
        if (entry != null) {
            Object socket = entry.getSocket();
            if (socket != null && socket instanceof AsyncSocket) {
                ((AsyncSocket) socket).write(new ByteBufferList(data));
            }
        }
    }

    public void close(final Integer cId) {
        ClientEntry entry = mClients.get(cId);
        if (entry != null) {
            Object socket = entry.getSocket();
            try {
                entry.close();
            }
            catch (IOException e) {
                FLog.e(TAG, "Close failed!", e);
            }
            if (socket != null) {
                if (socket instanceof AsyncSocket) {
                    ((AsyncSocket) socket).close();
                } else if (socket instanceof AsyncServerSocket) {
                    ((AsyncServerSocket) socket).stop();
                }
            } else {
                TcpSocketListener listener = mListener.get();
                if (listener != null) {
                    listener.onError(cId, "unable to find socket", entry.getSettings());
                }
            }
        }
    }

    public void closeAllSockets() {
        for (int i = 0; i < mClients.size(); i++) {
            close(mClients.keyAt(i));
        }
        mClients.clear();
    }
}
