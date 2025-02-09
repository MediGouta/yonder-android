package com.vidici.android;


import com.facebook.FacebookSdk;
import com.facebook.appevents.AppEventsLogger;
import com.google.android.gms.analytics.Tracker;
import android.app.Application;
import com.google.android.gms.analytics.GoogleAnalytics;

/**
 * This is a subclass of {@link android.app.Application} used to provide shared objects for this app, such as
 * the {@link Tracker}.
 */
public class VidiciApplication extends Application {
	private Tracker mTracker;

	@Override
	public void onCreate() {
		super.onCreate();
		if (User.admin) {
			return;
		}
		FacebookSdk.sdkInitialize(getApplicationContext());
		AppEventsLogger.activateApp(this);
	}

	/**
	 * Gets the default {@link Tracker} for this {@link android.app.Application}.
	 * @return tracker
	 */
	synchronized public Tracker getDefaultTracker() {
		if (mTracker == null) {
			GoogleAnalytics analytics = GoogleAnalytics.getInstance(this);
			// Set the dispatch period in seconds.
			analytics.setLocalDispatchPeriod(15);
			if (User.admin) {
				analytics.setDryRun(true);
			}
			// To enable debug logging use: adb shell setprop log.tag.GAv4 DEBUG
			mTracker = analytics.newTracker(R.xml.app_tracker);
			// Enable Advertising Features.
			mTracker.enableAdvertisingIdCollection(true);
		}
		return mTracker;
	}
}