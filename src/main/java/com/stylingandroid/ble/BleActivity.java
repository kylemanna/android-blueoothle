package com.stylingandroid.ble;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import java.lang.ref.WeakReference;


public class BleActivity extends Activity {
	public static final String TAG = "BluetoothLE";
	private final Messenger mMessenger;
	private Intent mServiceIntent;
	private Messenger mService = null;
	private ServiceConnection mConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			mService = new Messenger(service);
			try {
				Message msg = Message.obtain(null, BleService.MSG_REGISTER);
				if (msg != null) {
					msg.replyTo = mMessenger;
					mService.send(msg);
				} else {
					mService = null;
				}
			} catch (Exception e) {
				Log.w(TAG, "Error connecting to BleService", e);
				mService = null;
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			mService = null;
		}
	};

	public BleActivity() {
		super();
		mMessenger = new Messenger(new IncomingHandler(this));
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_ble);
		mServiceIntent = new Intent(this, BleService.class);
	}

	@Override
	protected void onStop() {
		if (mService != null) {
			try {
				Message msg = Message.obtain(null, BleService.MSG_UNREGISTER);
				if (msg != null) {
					msg.replyTo = mMessenger;
					mService.send(msg);
				}
			} catch (Exception e) {
				Log.w(TAG, "Error unregistering with BleService", e);
				mService = null;
			} finally {
				unbindService(mConnection);
			}
		}
		super.onStop();
	}

	@Override
	protected void onStart() {
		super.onStart();
		bindService(mServiceIntent, mConnection, BIND_AUTO_CREATE);
	}

	private static class IncomingHandler extends Handler {
		private final WeakReference<BleActivity> mActivity;

		public IncomingHandler(BleActivity activity) {
			mActivity = new WeakReference<BleActivity>(activity);
		}

		@Override
		public void handleMessage(Message msg) {
			BleActivity activity = mActivity.get();
			if (activity != null) {
				//TODO: Do something
			}
			super.handleMessage(msg);
		}
	}
}