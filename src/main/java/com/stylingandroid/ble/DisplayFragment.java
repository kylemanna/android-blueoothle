package com.stylingandroid.ble;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class DisplayFragment extends Fragment {
	public static DisplayFragment newInstance() {
		return new DisplayFragment();
	}

	private TextView mTemperature = null;
	private TextView mHumidity = null;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_display, container, false);
		if (v != null) {
			mTemperature = (TextView) v.findViewById(R.id.temperature);
			mHumidity = (TextView) v.findViewById(R.id.humidity);
		}
		return v;
	}

	public void setData(float temperature, float humidity) {
		if (mTemperature != null) {
			mTemperature.setText(getString(R.string.temp_format, temperature));
		}
		if (mHumidity != null) {
			mHumidity.setText(getString(R.string.humidity_format, humidity));
		}
	}
}
