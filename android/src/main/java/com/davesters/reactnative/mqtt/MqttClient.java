package com.davesters.reactnative.mqtt;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;


class MqttClient {

    private final String TAG = "HD";

    private static final String EVENT_NAME_CONNECT = "rn-native-mqtt_connect";
    private static final String EVENT_NAME_ERROR = "rn-native-mqtt_error";
    private static final String EVENT_NAME_DISCONNECT = "rn-native-mqtt_disconnect";
    private static final String EVENT_NAME_MESSAGE = "rn-native-mqtt_message";

    private final ReactApplicationContext reactContext;
    private final String id;

    private final AtomicReference<IMqttAsyncClient> client;
    private final AtomicReference<Callback> connectCallback;

    private String address;

    MqttClient(final ReactApplicationContext reactContext, final String id) {

        this.reactContext = reactContext;
        this.client = new AtomicReference<>();
        this.connectCallback = new AtomicReference<>();
        this.id = id;
    }


    void connectMqtt(final ReadableMap options, Callback callback) {
        connectCallback.set(callback);

        try {
            this.client.set(new MqttAsyncClient(address, options.getString("clientId"), new MemoryPersistence()));

            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(!options.hasKey("cleanSession") || options.getBoolean("cleanSession"));
            connOpts.setKeepAliveInterval(options.hasKey("keepAliveInterval") ? options.getInt("keepAliveInterval") : 60);
            connOpts.setConnectionTimeout(options.hasKey("timeout") ? options.getInt("timeout") : 10);
            connOpts.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            connOpts.setMaxInflight(options.hasKey("maxInFlightMessages") ? options.getInt("maxInFlightMessages") : 10);
            connOpts.setAutomaticReconnect(options.hasKey("autoReconnect") && options.getBoolean("autoReconnect"));
            connOpts.setUserName(options.hasKey("username") ? options.getString("username") : "");
            connOpts.setPassword("change_me".toCharArray());

            if (options.hasKey("tls")) {
                ReadableMap tlsOptions = options.getMap("tls");
                String ca = tlsOptions.hasKey("caDer") ? tlsOptions.getString("caDer") : null;
                String cert = tlsOptions.hasKey("cert") ? tlsOptions.getString("cert") : null;
                String key = tlsOptions.hasKey("key") ? tlsOptions.getString("key") : null;

                SSLSocketFactory factory = getSocketFactory(ca, cert, key, "change_me");
                connOpts.setSocketFactory(factory);
            }

            this.client.get().setCallback(new MqttEventCallback());
            this.client.get().connect(connOpts, null, new ConnectMqttActionListener());
        } catch (Exception ex) {
            callback.invoke(ex.getMessage());
            ex.printStackTrace();
        }
    }

    void connect(final String host, final ReadableMap options, final Callback callback) {
        Log.d(TAG, "host: " + host);

        ConnectivityManager connMgr = (ConnectivityManager) reactContext.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest.Builder request = new NetworkRequest.Builder();

        boolean isBluetooth = host.startsWith("bt://");

        address = host;
        if (isBluetooth) {
            request.addTransportType(NetworkCapabilities.TRANSPORT_BLUETOOTH);
            address = host.replace("bt://", "tcp://");
            Log.d(TAG, "Setting Bluetooth");
        } else {
            request.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
            request.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);
            Log.d(TAG, "Setting WiFi/Cellular");
        }

        connMgr.registerNetworkCallback(request.build(), new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        ConnectivityManager.setProcessDefaultNetwork(network);
                        connectMqtt(options, callback);
                    }
                }
        );
    }

    void subscribe(final ReadableArray topicList, final ReadableArray qosList) {
        try {
            String[] topic = new String[topicList.size()];
            int[] qos = new int[qosList.size()];

            for (int x = 0; x < topicList.size(); x++) {
                topic[x] = topicList.getString(x);
            }
            for (int y = 0; y < qosList.size(); y++) {
                qos[y] = qosList.getInt(y);
            }

            this.client.get().subscribe(topic, qos, null, new SubscribeMqttActionListener());
        } catch (Exception ex) {
            WritableMap params = Arguments.createMap();
            params.putString("message", "Error subscribing");
            params.putString("error", ex.getMessage());

            sendEvent(EVENT_NAME_ERROR, params);
        }
    }

    void unsubscribe(final ReadableArray topicList) {
        try {
            String[] topic = new String[topicList.size()];

            for (int x = 0; x < topicList.size(); x++) {
                topic[x] = topicList.getString(x);
            }

            this.client.get().unsubscribe(topic, null, new UnsubscribeMqttActionListener());
        } catch (Exception ex) {
            WritableMap params = Arguments.createMap();
            params.putString("message", "Error unsubscribing");
            params.putString("error", ex.getMessage());

            sendEvent(EVENT_NAME_ERROR, params);
        }
    }

    void publish(final String topic, final String base64Payload, final int qos, final boolean retained) {
        MqttMessage message = new MqttMessage(Base64.decode(base64Payload, Base64.DEFAULT));
        message.setQos(qos);
        message.setRetained(retained);

        try {
            this.client.get().publish(topic, message);
        } catch (MqttException ex) {
            WritableMap params = Arguments.createMap();
            params.putString("message", "Error publishing message");
            params.putString("error", ex.getMessage());

            sendEvent(EVENT_NAME_ERROR, params);
        }
    }

    void disconnect() {
        try {
            this.client.get().disconnect(null, new DisconnectMqttActionListener());
        } catch (MqttException ex) {
            WritableMap params = Arguments.createMap();
            params.putString("message", "Error disconnecting");
            params.putString("error", ex.getMessage());

            sendEvent(EVENT_NAME_ERROR, params);
        }
    }

    void close() {
        try {
            this.client.get().close();
        } catch (MqttException ex) {
            WritableMap params = Arguments.createMap();
            params.putString("message", "Error closing");
            params.putString("error", ex.getMessage());

            sendEvent(EVENT_NAME_ERROR, params);
        }
    }

    private void sendEvent(String eventName, WritableMap params) {
        params.putString("id", this.id);

        this.reactContext
                .getJSModule(RCTNativeAppEventEmitter.class)
                .emit(eventName, params);
    }

    private class MqttEventCallback implements MqttCallbackExtended {
        @Override
        public void connectionLost(Throwable cause) {
            WritableMap params = Arguments.createMap();
            params.putString("cause", cause.getMessage());

            sendEvent(EVENT_NAME_DISCONNECT, params);
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            WritableMap params = Arguments.createMap();
            params.putString("topic", topic);
            params.putString("message", Base64.encodeToString(message.getPayload(), Base64.DEFAULT));

            sendEvent(EVENT_NAME_MESSAGE, params);
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {}

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            WritableMap params = Arguments.createMap();
            params.putBoolean("reconnect", reconnect);

            sendEvent(EVENT_NAME_CONNECT, params);
        }
    }

    private class ConnectMqttActionListener implements IMqttActionListener {
        @Override
        public void onSuccess(IMqttToken asyncActionToken) {
            connectCallback.get().invoke();
        }

        @Override
        public void onFailure(IMqttToken asyncActionToken, Throwable ex) {
            connectCallback.get().invoke(ex.getMessage());
            ex.printStackTrace();
        }
    }

    private class DisconnectMqttActionListener implements IMqttActionListener {
        @Override
        public void onSuccess(IMqttToken asyncActionToken) {
            WritableMap params = Arguments.createMap();
            params.putString("cause", "User disconnected");

            sendEvent(EVENT_NAME_DISCONNECT, params);
        }

        @Override
        public void onFailure(IMqttToken asyncActionToken, Throwable ex) {
            WritableMap params = Arguments.createMap();
            params.putString("message", "Error connecting");
            params.putString("error", ex.getMessage());

            sendEvent(EVENT_NAME_ERROR, params);
        }
    }

    private class SubscribeMqttActionListener implements IMqttActionListener {
        @Override
        public void onSuccess(IMqttToken asyncActionToken) {}

        @Override
        public void onFailure(IMqttToken asyncActionToken, Throwable ex) {
            WritableMap params = Arguments.createMap();
            params.putString("message", "Error subscribing");
            params.putString("error", ex.getMessage());

            sendEvent(EVENT_NAME_ERROR, params);
        }
    }

    private class UnsubscribeMqttActionListener implements IMqttActionListener {
        @Override
        public void onSuccess(IMqttToken asyncActionToken) {}

        @Override
        public void onFailure(IMqttToken asyncActionToken, Throwable ex) {
            WritableMap params = Arguments.createMap();
            params.putString("message", "Error unsubscribing");
            params.putString("error", ex.getMessage());

            sendEvent(EVENT_NAME_ERROR, params);
        }
    }

    public static final SSLSocketFactory getSocketFactory(String caCrtFile, String crtFile, String keyFile, String password) throws Exception {
        String ca = caCrtFile.replace("-----BEGIN CERTIFICATE-----", "");
        String var10001 = System.lineSeparator();
        String encodedCaCert = ca.replace(var10001, "").replace("-----END CERTIFICATE-----", "");
        ca = crtFile.replace("-----BEGIN CERTIFICATE-----", "");
        var10001 = System.lineSeparator();
        String encodedClientCert = ca.replace(var10001, "").replace("-----END CERTIFICATE-----", "");
        ca = keyFile.replace("-----BEGIN PRIVATE KEY-----", "");
        var10001 = System.lineSeparator();
        String encodedKey = ca.replace(var10001, "").replace( "-----END PRIVATE KEY-----", "");

        Certificate caCert = CertificateFactory.getInstance("X.509").generateCertificate((InputStream)(new ByteArrayInputStream(Base64.decode(encodedCaCert, Base64.DEFAULT))));
        Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate((InputStream)(new ByteArrayInputStream(Base64.decode(encodedClientCert, Base64.DEFAULT))));
        KeyFactory kf = KeyFactory.getInstance("RSA");

        PKCS8EncodedKeySpec keySpecPKCS8 = new PKCS8EncodedKeySpec(Base64.decode(encodedKey, Base64.DEFAULT));
        PrivateKey var19 = kf.generatePrivate((KeySpec)keySpecPKCS8);
        PrivateKey privKey = var19;
        KeyStore caKs = KeyStore.getInstance(KeyStore.getDefaultType());
        caKs.load((InputStream)null, (char[])null);
        caKs.setCertificateEntry("ca-certificate", caCert);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(caKs);
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load((InputStream)null, (char[])null);
        ks.setCertificateEntry("certificate", cert);
        Key var10002 = (Key)privKey;
        boolean var16 = false;
        char[] var10003 = password.toCharArray();
        Certificate[] var10004 = new Certificate[1];
        var10004[0] = cert;
        ks.setKeyEntry("private-key", var10002, var10003, var10004);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        boolean var17 = false;
        char[] var21 = password.toCharArray();
        kmf.init(ks, var21);
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        KeyManager[] var20 = kmf.getKeyManagers();

        TrustManager trustManager = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        };

        context.init(var20, new TrustManager[]{ trustManager }, (SecureRandom)null);
        return context.getSocketFactory();
    }

}
