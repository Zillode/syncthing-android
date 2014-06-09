package com.nutomic.syncthingandroid.syncthing;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.nutomic.syncthingandroid.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides functions to interact with the syncthing REST API.
 */
public class RestApi implements SyncthingService.OnWebGuiAvailableListener {

	private static final String TAG = "RestApi";

	/**
	 * Parameter for {@link #getValue} or {@link #setValue} referring to "options" config item.
	 */
	public static final String TYPE_OPTIONS = "Options";

	/**
	 * Parameter for {@link #getValue} or {@link #setValue} referring to "gui" config item.
	 */
	public static final String TYPE_GUI = "GUI";

	/**
	 * Key of the map element containing connection info for the local node, in the return
	 * value of {@link #getConnections}
	 */
	public static final String LOCAL_NODE_CONNECTIONS = "total";

	public static class Node {
		public String Addresses;
		public String Name;
		public String NodeID;
	}

	public static class SystemInfo {
		public long alloc;
		public double cpuPercent;
		public boolean extAnnounceOK;
		public int goroutines;
		public String myID;
		public long sys;
	}

	public static class Repository {
		public String Directory;
		public String ID;
		public final boolean IgnorePerms = true;
		public String Invalid;
		public List<Node> Nodes;
		public boolean ReadOnly;
		public Versioning Versioning;
	}

	public static class Versioning {
		protected final Map<String, String> mParams = new HashMap<String, String>();
		public String getType() {
			return "";
		}
		public Map<String, String> getParams() {
			return mParams;
		}
	}

	public static class SimpleVersioning extends Versioning {
		@Override
		public String getType() {
			return "simple";
		}
		public void setParams(int keep) {
			mParams.put("keep", Integer.toString(keep));
		}
	}

	public static class Connection {
		public String At;
		public long InBytesTotal;
		public long OutBytesTotal;
		public String Address;
		public String ClientVersion;
		public double Completion;
	}

	public interface OnApiAvailableListener {
		public void onApiAvailable();
	}

	private final LinkedList<OnApiAvailableListener> mOnApiAvailableListeners =
			new LinkedList<OnApiAvailableListener>();

	private static final int NOTIFICATION_RESTART = 2;

	private final Context mContext;

	private String mVersion;

	private final String mUrl;

	private String mApiKey;

	private JSONObject mConfig;

	private String mLocalNodeId;

	private final NotificationManager mNotificationManager;

	public RestApi(Context context, String url, String apiKey) {
		mContext = context;
		mUrl = url;
		mApiKey = apiKey;
		mNotificationManager = (NotificationManager)
				mContext.getSystemService(Context.NOTIFICATION_SERVICE);
	}

	/**
	 * Returns the full URL of the web gui.
	 */
	public String getUrl() {
		return mUrl;
	}

	/**
	 * Number of previous calls to {@link #tryIsAvailable()}.
	 */
	private AtomicInteger mAvailableCount = new AtomicInteger(0);

	/**
	 * Number of asynchronous calls performed in {@link #onWebGuiAvailable()}.
	 */
	private static final int TOTAL_STARTUP_CALLS = 3;

	/**
	 * Gets local node id, syncthing version and config, then calls all OnApiAvailableListeners.
	 */
	@Override
	public void onWebGuiAvailable() {
		new GetTask() {
			@Override
			protected void onPostExecute(String version) {
				mVersion = version;
				Log.i(TAG, "Syncthing version is " + mVersion);
				tryIsAvailable();
			}
		}.execute(mUrl, GetTask.URI_VERSION, mApiKey);
		new GetTask() {
			@Override
			protected void onPostExecute(String config) {
				try {
					mConfig = new JSONObject(config);
					tryIsAvailable();
				}
				catch (JSONException e) {
					Log.w(TAG, "Failed to parse config", e);
				}
			}
		}.execute(mUrl, GetTask.URI_CONFIG, mApiKey);
		getSystemInfo(new OnReceiveSystemInfoListener() {
			@Override
			public void onReceiveSystemInfo(SystemInfo info) {
				mLocalNodeId = info.myID;
				tryIsAvailable();
			}
		});
	}


	/**
	 * Increments mAvailableCount by one, and, if it reached TOTAL_STARTUP_CALLS, notifies
	 * all registered {@link OnApiAvailableListener} listeners.
	 */
	private void tryIsAvailable() {
		int value = mAvailableCount.incrementAndGet();
		if (value == TOTAL_STARTUP_CALLS) {
			for (OnApiAvailableListener listener : mOnApiAvailableListeners) {
				listener.onApiAvailable();
			}
			mOnApiAvailableListeners.clear();
		}
	}

	/**
	 * Returns the version name, or a (text) error message on failure.
	 */
	public String getVersion() {
		return mVersion;
	}

	/**
	 * Stops syncthing. You should probably use SyncthingService.stopService() instead.
	 */
	public void shutdown() {
		mNotificationManager.cancel(NOTIFICATION_RESTART);
		new PostTask().execute(mUrl, PostTask.URI_SHUTDOWN, mApiKey, "");
	}

	/**
	 * Restarts the syncthing binary.
	 */
	public void restart() {
		new PostTask().execute(mUrl, PostTask.URI_RESTART);
	}

	/**
	 * Gets a value from config,
	 *
	 * Booleans are returned as {@link }Boolean#toString}, arrays as space seperated string.
	 *
	 * @param name {@link #TYPE_OPTIONS} or {@link #TYPE_GUI}
	 * @param key The key to read from.
	 * @return The value as a String, or null on failure.
	 */
	public String getValue(String name, String key) {
		try {
			Object value = mConfig.getJSONObject(name).get(key);
			return (value instanceof JSONArray)
					? ((JSONArray) value).join(" ").replace("\"", "")
					: String.valueOf(value);
		}
		catch (JSONException e) {
			Log.w(TAG, "Failed to get value for " + key, e);
			return null;
		}
	}

	/**
	 * Sets a value to config and sends it via Rest API.
	 *
	 * Booleans must be passed as {@link Boolean}, arrays as space seperated string
	 * with isArray true.
	 *
	 * @param name {@link #TYPE_OPTIONS} or {@link #TYPE_GUI}
	 * @param key The key to write to.
	 * @param value The new value to set, either String, Boolean or Integer.
	 * @param isArray True iff value is a space seperated String that should be converted to array.
	 */
	public <T> void setValue(String name, String key, T value, boolean isArray) {
		try {
			mConfig.getJSONObject(name).put(key, (isArray)
					? listToJson(((String) value).split(" "))
					: value);
			configUpdated();
		}
		catch (JSONException e) {
			Log.w(TAG, "Failed to set value for " + key, e);
		}
	}

	/**
	 * Converts an array of strings to JSON array. Like JSONArray#JSONArray(Object array), but
	 * works on all API levels.
	 */
	private JSONArray listToJson(String[] list) {
		JSONArray json = new JSONArray();
		for (String s : list) {
			json.put(s);
		}
		return json;
	}

	/**
	 * Sends the updated mConfig via Rest API to syncthing and displays a "restart" notification.
	 */
	private void configUpdated() {
		new PostTask().execute(mUrl, PostTask.URI_CONFIG, mConfig.toString());

		Intent i = new Intent(mContext, SyncthingService.class)
				.setAction(SyncthingService.ACTION_RESTART);
		PendingIntent pi = PendingIntent.getService(mContext, 0, i, 0);

		Notification n = new NotificationCompat.Builder(mContext)
				.setContentTitle(mContext.getString(R.string.restart_notif_title))
				.setContentText(mContext.getString(R.string.restart_notif_text))
				.setSmallIcon(R.drawable.ic_launcher)
				.setContentIntent(pi)
				.build();
		n.flags |= Notification.FLAG_ONLY_ALERT_ONCE | Notification.FLAG_AUTO_CANCEL;
		mNotificationManager.notify(NOTIFICATION_RESTART, n);
	}

	/**
	 * Returns a list of all existing nodes.
	 */
	public List<Node> getNodes() {
		try {
			return getNodes(mConfig.getJSONArray("Nodes"));
		}
		catch (JSONException e) {
			Log.w(TAG, "Failed to read nodes", e);
			return null;
		}
	}

	/**
	 * Result listener for {@link #getSystemInfo(OnReceiveSystemInfoListener)}.
	 */
	public interface OnReceiveSystemInfoListener {
		public void onReceiveSystemInfo(SystemInfo info);
	}

	/**
	 * Requests and parses information about current system status and resource usage.
	 *
	 * @param listener Callback invoked when the result is received.
	 */
	public void getSystemInfo(final OnReceiveSystemInfoListener listener) {
		new GetTask() {
			@Override
			protected void onPostExecute(String s) {
				try {
					JSONObject system = new JSONObject(s);
					SystemInfo si = new SystemInfo();
					si.alloc = system.getLong("alloc");
					si.cpuPercent = system.getDouble("cpuPercent");
					si.extAnnounceOK = system.optBoolean("extAnnounceOK", false);
					si.goroutines = system.getInt("goroutines");
					si.myID = system.getString("myID");
					si.sys = system.getLong("sys");
					listener.onReceiveSystemInfo(si);
				}
				catch (JSONException e) {
					Log.w(TAG, "Failed to read system info", e);
				}
			}
		}.execute(mUrl, GetTask.URI_SYSTEM, mApiKey);
	}

	/**
	 * Returns a list of all nodes in the array nodes, excluding the local node.
	 */
	private List<Node> getNodes(JSONArray nodes) throws JSONException {
		List<Node> ret;
		ret = new ArrayList<Node>(nodes.length());
		for (int i = 0; i < nodes.length(); i++) {
			JSONObject json = nodes.getJSONObject(i);
			Node n = new Node();
			if (!json.isNull("Addresses")) {
				n.Addresses = json.getJSONArray("Addresses").join(" ").replace("\"", "");
			}
			n.Name = json.getString("Name");
			n.NodeID = json.getString("NodeID");
			if (!n.NodeID.equals(mLocalNodeId)) {
				ret.add(n);
			}
		}
		return ret;
	}

	/**
	 * Returns a list of all existing repositores.
	 */
	public List<Repository> getRepositories() {
		List<Repository> ret = null;
		try {
			JSONArray repos = mConfig.getJSONArray("Repositories");
			ret = new ArrayList<Repository>(repos.length());
			for (int i = 0; i < repos.length(); i++) {
				JSONObject json = repos.getJSONObject(i);
				Repository r = new Repository();
				r.Directory = json.getString("Directory");
				r.ID = json.getString("ID");
				// Hardcoded to true because missing permissions support.
				// r.IgnorePerms = json.getBoolean("IgnorePerms");
				r.Invalid = json.getString("Invalid");
				r.Nodes = getNodes(json.getJSONArray("Nodes"));

				r.ReadOnly = json.getBoolean("ReadOnly");
				JSONObject versioning = json.getJSONObject("Versioning");
				if (versioning.getString("Type").equals("simple")) {
					SimpleVersioning sv = new SimpleVersioning();
					JSONObject params = versioning.getJSONObject("Params");
					sv.setParams(params.getInt("keep"));
					r.Versioning = sv;
				}
				else {
					r.Versioning = new Versioning();
				}

				ret.add(r);
			}
		}
		catch (JSONException e) {
			Log.w(TAG, "Failed to read nodes", e);
		}
		return ret;
	}

	/**
	 * Register a listener for the web gui becoming available..
	 *
	 * If the web gui is already available, listener will be called immediately.
	 * Listeners are unregistered automatically after being called.
	 */
	public void registerOnApiAvailableListener(OnApiAvailableListener listener) {
		if (mConfig != null) {
			listener.onApiAvailable();
		}
		else {
			mOnApiAvailableListeners.addLast(listener);
		}
	}

	/**
	 * Converts a number of bytes to a human readable file size (eg 3.5 GB).
	 */
	public String readableFileSize(long bytes) {
		final String[] units = mContext.getResources().getStringArray(R.array.file_size_units);
		if (bytes <= 0) return "0 " + units[0];
		int digitGroups = (int) (Math.log10(bytes)/Math.log10(1024));
		return new DecimalFormat("#,##0.#")
				.format(bytes/Math.pow(1024, digitGroups)) + " " + units[digitGroups];
	}

	/**
	 * Converts a number of bytes to a human readable transfer rate in bits (eg 100 Kb/s).
	 */
	public String readableTransferRate(long bytes) {
		bytes *= 8;
		final String[] units = mContext.getResources().getStringArray(R.array.transfer_rate_units);
		if (bytes <= 0) return "0 " + units[0];
		int digitGroups = (int) (Math.log10(bytes)/Math.log10(1024));
		return new DecimalFormat("#,##0.#")
				.format(bytes/Math.pow(1024, digitGroups)) + " " + units[digitGroups];
	}

	/**
	 * Listener for {@link #getConnections}.
	 */
	public interface OnReceiveConnectionsListener {
		public void onReceiveConnections(Map<String, Connection> connections);
	}

	/**
	 * Returns connection info for the local node and all connected nodes.
	 *
	 * Use the key {@link }LOCAL_NODE_CONNECTIONS} to get connection info for the local node.
	 */
	public void getConnections(final OnReceiveConnectionsListener listener) {
		new GetTask() {
			@Override
			protected void onPostExecute(String s) {
				try {
					JSONObject json = new JSONObject(s);
					String[] names = json.names().join(" ").replace("\"", "").split(" ");
					HashMap<String, Connection> connections = new HashMap<String, Connection>();
					for (String address : names) {
						Connection c = new Connection();
						JSONObject conn = json.getJSONObject(address);
						c.Address = address;
						c.At = conn.getString("At");
						c.InBytesTotal = conn.getLong("InBytesTotal");
						c.OutBytesTotal = conn.getLong("OutBytesTotal");
						c.Address = conn.getString("Address");
						c.ClientVersion = conn.getString("ClientVersion");
						c.Completion = conn.getDouble("Completion");
						connections.put(address, c);

					}
					listener.onReceiveConnections(connections);
				}
				catch (JSONException e) {
					Log.w(TAG, "Failed to parse connections", e);
				}
			}
		}.execute(mUrl, GetTask.URI_CONNECTIONS, mApiKey);
	}

}
