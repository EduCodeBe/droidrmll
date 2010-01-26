package org.fosdem.schedules;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Date;

import org.fosdem.R;
import org.fosdem.broadcast.FavoritesBroadcast;
import org.fosdem.db.DBAdapter;
import org.fosdem.listeners.ParserEventListener;
import org.fosdem.services.NotificationService;
import org.fosdem.util.FileUtil;
import org.fosdem.util.StringUtil;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class Main extends Activity implements ParserEventListener, OnClickListener {
	public static final String LOG_TAG = Main.class.getName();

	public static final int STARTFETCHING = -1;
	public static final int DONEFETCHING = 0;
	public static final int TAGEVENT = 1;
	public static final int DONELOADINGDB = 2;

	public static final int ROOMIMGSTART = 3;
	public static final int ROOMIMGDONE = 4;

	protected static final int DIALOG_ABOUT = 0;

	private static final int ABOUT_ID = Menu.FIRST;
	private static final int UPDATE_ID = Menu.FIRST + 1;
	private static final int SETTINGS_ID = Menu.FIRST + 2;
	private static final int PREFETCH_IMG_ID = Menu.FIRST + 5;
	private static final int QUIT_ID = Menu.FIRST + 6;

	public static final String PREFS = "org.fosdem";
	public static final String XML_URL = "http://fosdem.org/schedule/xml";
	public static final String ROOM_IMG_URL_BASE = "http://fosdem.org/2010/map/room/";

	public int counter = 0;
	protected TextView tvProgress = null, tvDbVer = null;
	protected Button btnDay1, btnDay2, btnSearch, btnFavorites;
	protected Intent service;

	private BroadcastReceiver favoritesChangedReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			long count = intent.getLongExtra(FavoritesBroadcast.EXTRA_COUNT, -1);
			if (count == 0)
				btnFavorites.setEnabled(false);
			else
				btnFavorites.setEnabled(true);
		}
	};

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		final Intent intent = getIntent();
		final String queryAction = intent.getAction();
		if (Intent.ACTION_SEARCH.equals(queryAction)) {
			EventListActivity.doSearchWithIntent(this, intent);
			finish();
		}
		if (Intent.ACTION_VIEW.equals(queryAction)) {
			Intent i = new Intent(this, DisplayEvent.class);
			i.putExtra(DisplayEvent.ID, Integer.parseInt(intent.getDataString()));
			startActivity(i);
			finish();
		}

		setContentView(R.layout.main);

		btnDay1 = (Button) findViewById(R.id.btn_day_1);
		btnDay1.setOnClickListener(this);
		btnDay2 = (Button) findViewById(R.id.btn_day_2);
		btnDay2.setOnClickListener(this);
		btnSearch = (Button) findViewById(R.id.btn_search);
		btnSearch.setOnClickListener(this);
		btnFavorites = (Button) findViewById(R.id.btn_favorites);
		btnFavorites.setOnClickListener(this);

		tvProgress = (TextView) findViewById(R.id.progress);
		tvDbVer = (TextView) findViewById(R.id.db_ver);
		tvDbVer.setText(getString(R.string.db_ver) + " " + StringUtil.dateTimeToString(getDBLastUpdated()));

		registerReceiver(favoritesChangedReceiver, new IntentFilter(FavoritesBroadcast.ACTION_FAVORITES_UPDATE));

		service = new Intent(this, NotificationService.class);
		startService(service);

		// FIXME on first startup
		// - propose user to update database
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		// menu.add(0, SETTINGS_ID, 2,
		// R.string.menu_settings).setIcon(android.R.drawable.ic_menu_preferences);
		menu.add(0, UPDATE_ID, 2, R.string.menu_update).setIcon(R.drawable.menu_refresh);
		menu.add(0, PREFETCH_IMG_ID, 2, R.string.menu_prefetch_rooms).setIcon(R.drawable.menu_refresh);
		menu.add(0, ABOUT_ID, 2, R.string.menu_about).setIcon(android.R.drawable.ic_menu_info_details);
		menu.add(0, SETTINGS_ID, 2, R.string.menu_settings).setIcon(android.R.drawable.ic_menu_preferences);
		// Quitting this way will stop the background service. Otherwise it will
		// keep running in background.
		menu.add(0, QUIT_ID, 2, R.string.menu_quit).setIcon(android.R.drawable.ic_menu_close_clear_cancel);
		return true;
	}

	@Override
	protected Dialog onCreateDialog(int id) {
		Dialog dialog;

		switch (id) {
		case DIALOG_ABOUT:
			View view = getLayoutInflater().inflate(R.layout.about, null, false);
			AlertDialog.Builder builder = new AlertDialog.Builder(this);
			builder.setTitle(getString(R.string.app_name));
			builder.setIcon(android.R.drawable.ic_dialog_info);
			builder.setView(view);
			builder.setPositiveButton(getString(android.R.string.ok), null);
			builder.setCancelable(true);

			dialog = builder.create();
			break;
		default:
			dialog = null;
		}
		return dialog;
	}

	public void onClick(View v) {
		int id = v.getId();
		switch (id) {
		case R.id.btn_day_1:
			showTracksForDay(1);
			break;
		case R.id.btn_day_2:
			showTracksForDay(2);
			break;
		case R.id.btn_search:
			// nothing to do as btn is not active
			break;
		case R.id.btn_favorites:
			showFavorites();
			break;
		default:
			Log.e(LOG_TAG, "Received a button click, but I don't know from where.");
			break;
		}
	}

	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		switch (item.getItemId()) {
		case UPDATE_ID:
			updateXML();
			return true;
		case PREFETCH_IMG_ID:
			prefetchAllRoomImages();
			return true;
		case ABOUT_ID:
			showDialog(DIALOG_ABOUT);
			break;
		case QUIT_ID:
			stopService(service);
			finish();
			break;
		case SETTINGS_ID:
			showSettings();
			break;
		}
		return super.onMenuItemSelected(featureId, item);
	}

	public void toast(String message) {
		final Context context = getApplicationContext();
		final Toast toast = Toast.makeText(context, message, Toast.LENGTH_LONG);
		toast.show();
	}

	public Handler handler = new Handler() {
		public void handleMessage(Message msg) {
			if (msg == null)
				return;
			switch (msg.what) {
			case TAGEVENT:
				tvProgress.setText("Fetched " + counter + " events.");
				break;
			case STARTFETCHING:
				tvProgress.setText("Downloading...");
				break;
			case DBAdapter.MSG_EVENT_STORED:
				tvProgress.setText("Stored " + msg.arg1 + " events.");
				break;
			case DONEFETCHING:
				tvProgress.setText("Done fetching, loading into DB");
				setDBLastUpdated();
				break;
			case DONELOADINGDB:
				final String doneDb = "Done loading into DB";
				tvProgress.setText(doneDb);
				toast(doneDb);
				tvDbVer.setText(getString(R.string.db_ver) + " " + StringUtil.dateTimeToString(getDBLastUpdated()));
				break;
			case ROOMIMGSTART:
				tvProgress.setText("Downloading room images...");
				break;
			case ROOMIMGDONE:
				final String doneRooms = "Room Images downloaded";
				tvProgress.setText(doneRooms);
				toast(doneRooms);
				break;
			}
		}
	};

	public void onTagEvent(String tag, int type) {
		if (tag.equals("event") && type == ParserEventListener.TAG_OPEN) {
			counter++;
			final Message msg = Message.obtain();
			msg.what = TAGEVENT;
			handler.sendMessage(msg);
		}
	}

	public void showTracksForDay(int day) {
		Log.d(LOG_TAG, "showTracksForDay(" + day + ");");
		Intent i = new Intent(this, TrackListActivity.class);
		i.putExtra(TrackListActivity.DAY_INDEX, day);
		startActivity(i);
	}

	public void showFavorites() {
		Intent i = new Intent(this, EventListActivity.class);
		i.putExtra(EventListActivity.FAVORITES, true);
		startActivity(i);
	}

	/**
	 * Download the new schedule from the server and import the data in the
	 * local database
	 */
	public void updateXML() {
		final Thread t = new Thread(new BackgroundUpdater(handler, this, getApplicationContext(), true, false));
		t.start();
	}

	/**
	 * This function will prefetch all the images of the rooms. This enables the
	 * user to have a fast and internet-less experience.
	 */
	public void prefetchAllRoomImages() {
		final Thread t = new Thread(new BackgroundUpdater(handler, this, getApplicationContext(), false, true));
		t.start();
	}

	/**
	 * Set NOW as the time that the Schedule database has been imported.
	 */
	private void setDBLastUpdated() {
		SharedPreferences.Editor editor = getSharedPreferences(Main.PREFS, 0).edit();
		long timestamp = System.currentTimeMillis() / 1000;
		editor.putLong("db_last_updated", timestamp);
		editor.commit(); // Don't forget to commit your edits!!!
	}

	/**
	 * Fetch the Date when the Schedule database has been imported
	 * 
	 * @return Date of the last Database update
	 */
	private Date getDBLastUpdated() {
		SharedPreferences settings = getSharedPreferences(Main.PREFS, 0);
		long timestamp = settings.getLong("db_last_updated", 0);
		if (timestamp == 0)
			return null;
		return new Date(timestamp * 1000);
	}

	@Override
	protected void onDestroy() {
		unregisterReceiver(favoritesChangedReceiver);
		super.onDestroy();
	}

	public void showSettings() {
		Intent i = new Intent(this, Preferences.class);
		startActivity(i);
	}
}