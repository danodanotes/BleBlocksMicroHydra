package com.cardputer.bleblocks;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class NusClient {
    public static final UUID NUS = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID NUS_RX = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); // write to device
    public static final UUID NUS_TX = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"); // notify from device
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onConnected();
        void onDisconnected();
        void onRx(String text);
        void onLog(String line);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxChar;
    private boolean ready;

    public NusClient(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void connect(BluetoothDevice device) {
        close();
        log("conectando " + device.getName());
        gatt = device.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE);
    }

    public void close() {
        ready = false;
        rxChar = null;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
    }

    public boolean isReady() {
        return ready && rxChar != null;
    }

    /** Send UTF-8 in small ATT slices with retry. Fast writes drop the link. */
    public void write(String text) {
        if (!isReady()) throw new IllegalStateException("no listo");
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int i = 0;
        while (i < data.length) {
            int n = Math.min(20, data.length - i);
            byte[] slice = new byte[n];
            System.arraycopy(data, i, slice, 0, n);
            rxChar.setValue(slice);
            rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            boolean ok = false;
            int tries = 0;
            while (!ok && tries < 8) {
                if (!isReady()) throw new IllegalStateException("se cortó BLE");
                ok = gatt.writeCharacteristic(rxChar);
                if (!ok) {
                    tries++;
                    try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                }
            }
            if (!ok) throw new IllegalStateException("write falló");
            i += n;
            try { Thread.sleep(40); } catch (InterruptedException ignored) {}
        }
    }

    private void log(String s) {
        main.post(() -> listener.onLog(s));
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("GATT ok, descubriendo…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                main.post(listener::onDisconnected);
                log("desconectado");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(NUS);
            if (svc == null) {
                log("sin servicio NUS");
                return;
            }
            rxChar = svc.getCharacteristic(NUS_RX);
            BluetoothGattCharacteristic tx = svc.getCharacteristic(NUS_TX);
            if (rxChar == null || tx == null) {
                log("faltan RX/TX NUS");
                return;
            }
            g.setCharacteristicNotification(tx, true);
            BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            } else {
                ready = true;
                main.post(listener::onConnected);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            ready = true;
            main.post(listener::onConnected);
            log("NUS listo (notify ON)");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] v = characteristic.getValue();
            if (v == null) return;
            String s = new String(v, StandardCharsets.UTF_8);
            main.post(() -> listener.onRx(s));
        }
    };
}
