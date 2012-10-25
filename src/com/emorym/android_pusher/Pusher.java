package com.emorym.android_pusher;

/*	Copyright (C) 2011 Emory Myers
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *  	http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License. 
 *  
 *  Contributors: Martin Linkhorst
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONException;
import org.json.JSONObject;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
//import android.util.Log;

public class Pusher implements PusherEventEmitter {
	private static final String LOG_TAG = "Pusher";

	private static final String PUSHER_CLIENT = "android-Android_Pusher";
	//private static final String VERSION = "1.11.1";
	private static final String VERSION = "5";

	protected static final String PUSHER_EVENT_CONNECTION_ESTABLISHED = "pusher:connection_established";
	protected static final String PUSHER_EVENT_SUBSCRIBE = "pusher:subscribe";
	protected static final String PUSHER_EVENT_UNSUBSCRIBE = "pusher:unsubscribe";

	private static final String PUSHER_AUTH_ALGORITHM = "HmacSHA256";

	private static final String PUSHER_HOST = "ws.pusherapp.com";

	private static final String WS_SCHEME = "ws://";
	private static final String WSS_SCHEME = "wss://";

	private static final int WS_PORT = 80;
	private static final int WSS_PORT = 443;

	private String mPusherKey;
	private String mPusherSecret;
	private boolean mEncrypted;

	private String mSocketId;
	private PusherConnection mConnection = new PusherConnection(this);

	public PusherChannel mGlobalChannel = new PusherChannel("pusher_global_channel");
	public Map<String, PusherChannel> mLocalChannels = new HashMap<String, PusherChannel>();
	
	public String userId = "";
	private JSONObject userInfo = new JSONObject();
	//private Map<String,String> userInfo = new HashMap<String, String>();
	
	private PusherLogger mLogger = new PusherLogger() {};
	
	private String authURL = null;
	private Map<String, String> auth_headers = new HashMap<String, String>();
	private Map<String, String> auth_params = new HashMap<String, String>();

//	public Pusher(String pusherKey, String pusherSecret, boolean encrypted) {
//		init(pusherKey, pusherSecret, encrypted);
//	}
//
//	public Pusher(String pusherKey, String pusherSecret) {
//		init(pusherKey, pusherSecret, true);
//	}
//
//	public Pusher(String pusherKey, boolean encrypted) {
//		init(pusherKey, null, encrypted);
//	}
//
//	public Pusher(String pusherKey) {
//		init(pusherKey, null, true);
//	}
	
	public Pusher(String pusherKey, String authURL, boolean encrypted, Map<String,Map<String,String>> auth){
		init(pusherKey, null, encrypted);
		this.authURL = authURL;
		if (auth.containsKey("headers")){
			this.auth_headers = auth.get("headers");
		}
		if (auth.containsKey("params")){
			this.auth_params = auth.get("params");
		}
	}
	
	private void init(String pusherKey, String pusherSecret, boolean encrypted) {
		mPusherKey = pusherKey;
		mPusherSecret = pusherSecret;
		mEncrypted = encrypted;
	}

	public void connect() {
		mConnection.connect();
	}

	public boolean isConnected() {
		return mSocketId != null;
	}

	public void disconnect() {
		mConnection.disconnect();
	}

	public void onConnected(String socketId) {
		mSocketId = socketId;
		subscribeToAllChannels();
	}

	public void onDisconnected() {
		mSocketId = null;
	}

	public String getUrl() {
		return getScheme() + getHost() + ":" + getPort() + getPath();
	}

	private String getScheme() {
		return mEncrypted ? WSS_SCHEME : WS_SCHEME;
	}

	protected String getHost() {
		return PUSHER_HOST;
	}

	private int getPort() {
		return mEncrypted ? WSS_PORT : WS_PORT;
	}

	private String getPath() {
		return "/app/" + mPusherKey + "?client=" + PUSHER_CLIENT + "&version=" + VERSION;
	}

	public void bind(String event, PusherCallback callback) {
		mGlobalChannel.bind(event, callback);
	}

	public void bindAll(PusherCallback callback) {
		mGlobalChannel.bindAll(callback);
	}

	public void unbind(PusherCallback callback) {
		mGlobalChannel.unbind(callback);
	}

	public void unbindAll() {
		mGlobalChannel.unbindAll();
	}

	public PusherChannel subscribe(String channelName) {
		PusherChannel channel = createLocalChannel(channelName);
		sendSubscribeMessage(channel);
		return channel;
	}

	public void unsubscribe(String channelName) {
		/* TODO: just mark as unsubscribed in order to keep the bindings */
		PusherChannel channel = removeLocalChannel(channelName);

		if (channel == null)
			return;

		sendUnsubscribeMessage(channel);
	}

	public void subscribeToAllChannels() {
		for (PusherChannel channel : mLocalChannels.values()) {
			sendSubscribeMessage(channel);
		}
	}

	private void unsubscribeFromAllChannels() {
		for (PusherChannel channel : mLocalChannels.values()) {
			sendUnsubscribeMessage(channel);
		}
		
		/* TODO: just mark the channels as unsubscribed in order to keep the bindings */ 
		mLocalChannels.clear();
	}

	private void sendSubscribeMessage(PusherChannel channel) {
		if (!isConnected())
			return;

		try {
			String eventName = PUSHER_EVENT_SUBSCRIBE;

			JSONObject eventData = new JSONObject();
			eventData.put("channel", channel.getName());

			
			if (channel.isPrivate() || channel.isPresence()){
				String authString = authenticate(channel);
				JSONObject authInfo = new JSONObject(authString);
				Iterator<String> iter = authInfo.keys();
				while( iter.hasNext() ){
					String key = iter.next();
					String value = authInfo.getString(key);
					eventData.put(key, value);
				}
			}
			
//			if (channel.isPrivate()) {
//				String authString = authenticate(channel);
//				JSONObject authInfo = new JSONObject(authString);
//				eventData.put("auth", authInfo.getString("auth"));
//			}
			
//			if (channel.isPresence()){
//				String channelName = channel.getName();
//				JSONObject channel_data = new JSONObject();		
				//channel_data.put("user_id", userId);
				//if (userInfo.keys().hasNext()){
				//	channel_data.put("user_info", userInfo);
				//}
				//String authInfo = authenticate(channelName + ":" + channel_data.toString());
				//eventData.put("auth", authInfo);
				//eventData.put("channel_data", channel_data.toString());
//			}

			sendEvent(eventName, eventData, null);

			//Log.d(LOG_TAG, "subscribed to channel " + channel.getName());
			mLogger.log(LOG_TAG, "subscribed to channel " + channel.getName());
		} catch (JSONException e) {
			mLogger.log(e.toString());
			//e.printStackTrace();
		}
	}

	private void sendUnsubscribeMessage(PusherChannel channel) {
		if (!isConnected())
			return;

		try {
			String eventName = PUSHER_EVENT_UNSUBSCRIBE;

			JSONObject eventData = new JSONObject();
			eventData.put("channel", channel.getName());

			sendEvent(eventName, eventData, null);

			//Log.d(LOG_TAG, "unsubscribed from channel " + channel.getName());
			mLogger.log(LOG_TAG, "unsubscribed from channel " + channel.getName());
		} catch (JSONException e) {
			mLogger.log(e.toString());
			//e.printStackTrace();
		}
	}

	public void sendEvent(String eventName, JSONObject eventData, String channelName) {
		mConnection.send(eventName, eventData, channelName);
	}

	public void dispatchEvents(String eventName, String eventData, String channelName) {
		mGlobalChannel.dispatchEvents(eventName, eventData);

		PusherChannel localChannel = mLocalChannels.get(channelName);
		
		
		//Log.d( LOG_TAG, mLocalChannels.keySet().toString() );

		if (localChannel == null) {
			//Log.d(LOG_TAG, "NO channel found");
			return;
		}
		
		localChannel.dispatchEvents(eventName, eventData);
	}

	/* TODO: refactor */
	private String authenticate(PusherChannel channel){
		String channelName = channel.getName();
		
		HttpClient httpclient = new DefaultHttpClient();
	    HttpPost httppost = new HttpPost(authURL);
	    
	    // Add all extra headers to the request
	    if( !this.auth_headers.isEmpty() ){
	    	Set<String> keys = this.auth_headers.keySet();
	    	Iterator<String> iter = keys.iterator();
			while( iter.hasNext() ){
				String key = iter.next();
				String value = this.auth_headers.get(key);
				httppost.setHeader(key, value);
			}
	    }
		
	    // Prepare params 
		List<NameValuePair> namedParams = new ArrayList<NameValuePair>(2);
		namedParams.add(new BasicNameValuePair( "socket_id", this.mSocketId));
		namedParams.add(new BasicNameValuePair( "channel_name", channelName));
		
		if (channel.isPresence()){
			namedParams.add(new BasicNameValuePair( "user_id", this.userId));
			if (this.userInfo.length() > 0){
				namedParams.add(new BasicNameValuePair( "user_info", this.userInfo.toString()));
			}
			//Iterator<Entry<String,String>> iter = this.userInfo.entrySet().iterator();
			//while(iter.hasNext()){
			//	Entry<String,String> entry = iter.next();
			//	namedParams.add(new BasicNameValuePair( entry.getKey(), entry.getValue()));
			//}
		}
		
		// Add all extra params to the request
		if (! this.auth_params.isEmpty()){		
			Set<String> keys = this.auth_params.keySet();
			Iterator<String> iter = keys.iterator();
			while( iter.hasNext() ){
				String key = iter.next();
				String value = this.auth_params.get(key);
				namedParams.add(new BasicNameValuePair( key, value));
			}			
		}
		
		try {
			httppost.setEntity(new UrlEncodedFormEntity(namedParams));
		} catch (UnsupportedEncodingException e) {
			this.log(e.toString());
		}

		try {
			HttpResponse response = httpclient.execute(httppost);

			String line = "";
			StringBuilder total = new StringBuilder();
			// Wrap a BufferedReader around the InputStream
			InputStreamReader reader = new InputStreamReader(response.getEntity().getContent());
			BufferedReader rd = new BufferedReader( reader );

			// Read response until the end
			while ((line = rd.readLine()) != null) { 
				total.append(line); 
			}

			// Return full string
			return total.toString();
		} catch (ClientProtocolException e) {
			this.log(e.toString());
		} catch (IOException e) {
			this.log(e.toString());
		} 

	    return null;
	}
	
	private String authenticateLocal(String channelName){
		if (!isConnected()) {
			//Log.e(LOG_TAG, "pusher not connected, can't create auth string");
			mLogger.log(LOG_TAG, "pusher not connected, can't create auth string");
			return null;
		}
		
		if (mPusherSecret == null){
			//Log.e(LOG_TAG, "no Pusher Secret provided, can't authenticate locally");
			mLogger.log(LOG_TAG, "no Pusher Secret provided, can't authenticate locally");
			return null;
		}

		try {
			String stringToSign = mSocketId + ":" + channelName;

			SecretKey key = new SecretKeySpec(mPusherSecret.getBytes(), PUSHER_AUTH_ALGORITHM);

			Mac mac = Mac.getInstance(PUSHER_AUTH_ALGORITHM);
			mac.init(key);
			byte[] signature = mac.doFinal(stringToSign.getBytes());

			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < signature.length; ++i) {
				sb.append(Integer.toHexString((signature[i] >> 4) & 0xf));
				sb.append(Integer.toHexString(signature[i] & 0xf));
			}

			String authInfo = mPusherKey + ":" + sb.toString();

			//Log.d(LOG_TAG, "Auth Info " + authInfo);
			mLogger.log(LOG_TAG, "Auth Info " + authInfo);

			return authInfo;

		} catch (NoSuchAlgorithmException e) {
			mLogger.log(e.toString());
			//e.printStackTrace();
		} catch (InvalidKeyException e) {
			mLogger.log(e.toString());
			//e.printStackTrace();
		}

		return null;
	}
	

	private PusherChannel createLocalChannel(String channelName) {
		PusherChannel channel = new PusherChannel(channelName);
		mLocalChannels.put(channelName, channel);
		return channel;
	}

	private PusherChannel removeLocalChannel(String channelName) {
		return mLocalChannels.remove(channelName);
	}
		
	public PusherConnection connection(){
		return this.mConnection;
	}
	
	public void setUserInfo( String key, String value) {
		try {
			userInfo.put(key, value);
		} catch (JSONException e) {
			this.log(e.toString());
		}
	}
	
	public void delUserInfo( String key ){
		userInfo.remove(key);
	}
	
	public void setUserId( String value){
		this.userId = value;
	}
	
	public PusherChannel getChannel(String value){
		return mLocalChannels.get(value);
	}
	
	public void setLogger(PusherLogger logger){
		this.mLogger = logger;
	}
	
	public void log(String message){
		this.mLogger.log(message);
	}
}
