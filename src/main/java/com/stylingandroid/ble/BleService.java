package com.stylingandroid.ble;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class BleService extends Service implements BluetoothAdapter.LeScanCallback {
	public static final String TAG = "BleService";
	static final int MSG_REGISTER = 1;
	static final int MSG_UNREGISTER = 2;
	static final int MSG_START_SCAN = 3;
	static final int MSG_STATE_CHANGED = 4;
	static final int MSG_DEVICE_FOUND = 5;
	static final int MSG_DEVICE_CONNECT = 6;
	static final int MSG_DEVICE_DISCONNECT = 7;
	static final int MSG_DEVICE_DATA = 8;

	private static final long SCAN_PERIOD = 3000;

	public static final String KEY_MAC_ADDRESSES = "KEY_MAC_ADDRESSES";

	private static final String DEVICE_NAME = "SensorTag";
	private static final UUID UUID_HUMIDITY_SERVICE = UUID.fromString("f000aa20-0451-4000-b000-000000000000");
	private static final UUID UUID_HUMIDITY_DATA = UUID.fromString("f000aa21-0451-4000-b000-000000000000");
	private static final UUID UUID_HUMIDITY_CONF = UUID.fromString("f000aa22-0451-4000-b000-000000000000");
	private static final UUID UUID_CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
	private static final byte[] ENABLE_SENSOR = {0x01};

	private static final Queue<Object> sWriteQueue = new ConcurrentLinkedQueue<Object>();
	private static boolean sIsWriting = false;

	private final IncomingHandler mHandler;
	private final Messenger mMessenger;
	private final List<Messenger> mClients = new LinkedList<Messenger>();
	private final Map<String, BluetoothDevice> mDevices = new HashMap<String, BluetoothDevice>();
	private BluetoothGatt mGatt = null;

	public enum State {
		UNKNOWN,
		IDLE,
		SCANNING,
		BLUETOOTH_OFF,
		CONNECTING,
		CONNECTED,
		DISCONNECTING
	}

	private BluetoothAdapter mBluetooth = null;
	private State mState = State.UNKNOWN;

	private BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			super.onConnectionStateChange(gatt, status, newState);
			Log.v(TAG, "Connection State Changed: " + (newState == BluetoothProfile.STATE_CONNECTED ? "Connected" : "Disconnected"));
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				setState(State.CONNECTED);
				gatt.discoverServices();
			} else {
				setState(State.IDLE);
			}
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			Log.v(TAG, "onServicesDiscovered: " + status);
			if (status == BluetoothGatt.GATT_SUCCESS) {
				subscribe(gatt);
			}
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
			Log.v(TAG, "onCharacteristicWrite: " + status);
			sIsWriting = false;
			nextWrite();
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			Log.v(TAG, "onDescriptorWrite: " + status);
			sIsWriting = false;
			nextWrite();
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			Log.v(TAG, "onCharacteristicChanged: " + characteristic.getUuid());
			if (characteristic.getUuid().equals(UUID_HUMIDITY_DATA)) {
				int t = shortUnsignedAtOffset(characteristic, 0);
				int h = shortUnsignedAtOffset(characteristic, 2);
				t = t - (t % 4);
				h = h - (h % 4);

				float humidity = (-6f) + 125f * (h / 65535f);
				float temperature = -46.85f + 175.72f / 65536f * (float) t;
				Log.d(TAG, "Value: " + humidity + ":" + temperature);
				Message msg = Message.obtain(null, MSG_DEVICE_DATA);
				msg.arg1 = (int) (temperature * 100);
				msg.arg2 = (int) (humidity * 100);
				sendMessage(msg);
			}
		}
	};

	public BleService() {
		mHandler = new IncomingHandler(this);
		mMessenger = new Messenger(mHandler);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mMessenger.getBinder();
	}

	private static class IncomingHandler extends Handler {
		private final WeakReference<BleService> mService;

		public IncomingHandler(BleService service) {
			mService = new WeakReference<BleService>(service);
		}

		@Override
		public void handleMessage(Message msg) {
			BleService service = mService.get();
			if (service != null) {
				switch (msg.what) {
					case MSG_REGISTER:
						service.mClients.add(msg.replyTo);
						Log.d(TAG, "Registered");
						break;
					case MSG_UNREGISTER:
						service.mClients.remove(msg.replyTo);
						if (service.mState == State.CONNECTED && service.mGatt != null) {
							service.mGatt.disconnect();
						}
						Log.d(TAG, "Unegistered");
						break;
					case MSG_START_SCAN:
						service.startScan();
						Log.d(TAG, "Start Scan");
						break;
					case MSG_DEVICE_CONNECT:
						service.connect((String) msg.obj);
						break;
					case MSG_DEVICE_DISCONNECT:
						if (service.mState == State.CONNECTED && service.mGatt != null) {
							service.mGatt.disconnect();
						}
						break;
					default:
						super.handleMessage(msg);
				}
			}
		}
	}

	private void startScan() {
		mDevices.clear();
		setState(State.SCANNING);
		if (mBluetooth == null) {
			BluetoothManager bluetoothMgr = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
			mBluetooth = bluetoothMgr.getAdapter();
		}
		if (mBluetooth == null || !mBluetooth.isEnabled()) {
			setState(State.BLUETOOTH_OFF);
		} else {
			mHandler.postDelayed(new Runnable() {
				@Override
				public void run() {
					if (mState == State.SCANNING) {
						mBluetooth.stopLeScan(BleService.this);
						setState(State.IDLE);
					}
				}
			}, SCAN_PERIOD);
			mBluetooth.startLeScan(this);
		}
	}

	@Override
	public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {
		if (device != null && !mDevices.containsValue(device) && device.getName() != null && device.getName().equals(DEVICE_NAME)) {
			mDevices.put(device.getAddress(), device);
			Message msg = Message.obtain(null, MSG_DEVICE_FOUND);
			if (msg != null) {
				Bundle bundle = new Bundle();
				String[] addresses = mDevices.keySet().toArray(new String[mDevices.size()]);
				bundle.putStringArray(KEY_MAC_ADDRESSES, addresses);
				msg.setData(bundle);
				sendMessage(msg);
			}
			Log.d(TAG, "Added " + device.getName() + ": " + device.getAddress());
		}
	}

	public void connect(String macAddress) {
		BluetoothDevice device = mDevices.get(macAddress);
		if (device != null) {
			mGatt = device.connectGatt(this, true, mGattCallback);
		}
	}

	private void subscribe(BluetoothGatt gatt) {
		BluetoothGattService humidityService = gatt.getService(UUID_HUMIDITY_SERVICE);
		if (humidityService != null) {
			BluetoothGattCharacteristic humidityCharacteristic = humidityService.getCharacteristic(UUID_HUMIDITY_DATA);
			BluetoothGattCharacteristic humidityConf = humidityService.getCharacteristic(UUID_HUMIDITY_CONF);
			if (humidityCharacteristic != null && humidityConf != null) {
				BluetoothGattDescriptor config = humidityCharacteristic.getDescriptor(UUID_CCC);
				if (config != null) {
					gatt.setCharacteristicNotification(humidityCharacteristic, true);
					humidityConf.setValue(ENABLE_SENSOR);
					write(humidityConf);
					config.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
					write(config);
				}
			}
		}
	}

	private synchronized void write(Object o) {
		if (sWriteQueue.isEmpty() && !sIsWriting) {
			doWrite(o);
		} else {
			sWriteQueue.add(o);
		}
	}

	private synchronized void nextWrite() {
		if (!sWriteQueue.isEmpty() && !sIsWriting) {
			doWrite(sWriteQueue.poll());
		}
	}

	private synchronized void doWrite(Object o) {
		if (o instanceof BluetoothGattCharacteristic) {
			sIsWriting = true;
			mGatt.writeCharacteristic((BluetoothGattCharacteristic) o);
		} else if (o instanceof BluetoothGattDescriptor) {
			sIsWriting = true;
			mGatt.writeDescriptor((BluetoothGattDescriptor) o);
		} else {
			nextWrite();
		}
	}

	private void setState(State newState) {
		if (mState != newState) {
			mState = newState;
			Message msg = getStateMessage();
			if (msg != null) {
				sendMessage(msg);
			}
		}
	}

	private Message getStateMessage() {
		Message msg = Message.obtain(null, MSG_STATE_CHANGED);
		if (msg != null) {
			msg.arg1 = mState.ordinal();
		}
		return msg;
	}

	private void sendMessage(Message msg) {
		for (int i = mClients.size() - 1; i >= 0; i--) {
			Messenger messenger = mClients.get(i);
			if (!sendMessage(messenger, msg)) {
				mClients.remove(messenger);
			}
		}
	}

	private boolean sendMessage(Messenger messenger, Message msg) {
		boolean success = true;
		try {
			messenger.send(msg);
		} catch (RemoteException e) {
			Log.w(TAG, "Lost connection to client", e);
			success = false;
		}
		return success;
	}

	private static Integer shortUnsignedAtOffset(BluetoothGattCharacteristic characteristic, int offset) {
		Integer lowerByte = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, offset);
		Integer upperByte = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, offset + 1);

		return (upperByte << 8) + lowerByte;
	}
}