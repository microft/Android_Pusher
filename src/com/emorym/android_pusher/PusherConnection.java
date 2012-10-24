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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.json.JSONException;
import org.json.JSONObject;

import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import de.roderick.weberknecht.WebSocket;
import de.roderick.weberknecht.WebSocketEventHandler;
import de.roderick.weberknecht.WebSocketException;
import de.roderick.weberknecht.WebSocketMessage;

class PusherConnection implements PusherEventEmitter {
	private static final String LOG_TAG = "PusherConnection";

	public Pusher mPusher;
	public WebSocket mWebSocket;

	protected static final String STATE_INITIALIZED ="initialized";
	protected static final String STATE_CONNECTING = "connecting";
	protected static final String STATE_CONNECTED = "connected";
	protected static final String STATE_UNAVAILABLE = "unavailable";
	protected static final String STATE_FAILED = "failed";
	protected static final String STATE_DISCONNECTED = "disconnected";
	
	public String mState = STATE_INITIALIZED;
	
	protected static final String CONNECTING_IN = "connecting_in";
	
	protected static final long UNAVAILABILITY_CHECK_TIMER = 10000L;
	
	private boolean auto_reconnect = true; 

	private List<PusherCallback> mGlobalCallbacks = new ArrayList<PusherCallback>();
	private Map<String, List<PusherCallback>> mLocalCallbacks = new HashMap<String, List<PusherCallback>>();
	
	private Timer unavailabilityTimer = new Timer();

	public PusherConnection(Pusher pusher) {
		mPusher = pusher;
	}

	public void connect() {
		try {
			this.changeConnectionState(STATE_CONNECTING);

			URI url = new URI(mPusher.getUrl());
			Log.d(LOG_TAG, "Connecting to " + url.toString());

			mWebSocket = new WebSocket(url);
			mWebSocket.setEventHandler(new WebSocketEventHandler() {
				public void onOpen() {
					// Log.d(LOG_TAG, "Successfully opened Websocket");
					PusherConnection.this
							.changeConnectionState(STATE_CONNECTED);
				}

				public void onMessage(WebSocketMessage message) {
					Log.d(LOG_TAG,
							"Received from Websocket " + message.getText());
					
					changeConnectionState(STATE_CONNECTED);

					try {
						JSONObject parsed = new JSONObject(message.getText());
						String eventName = parsed.getString("event");
						String channelName = parsed.optString("channel", null);
						String eventData = parsed.getString("data");

						if (eventName
								.equals(Pusher.PUSHER_EVENT_CONNECTION_ESTABLISHED)) {
							JSONObject parsedEventData = new JSONObject(
									eventData);
							String socketId = parsedEventData
									.getString("socket_id");
							mPusher.onConnected(socketId);
						} else {
							if (eventName.equals("pusher:ping")){
								onPing();
							}
							mPusher.dispatchEvents(eventName, eventData,
									channelName);
						}
						
					} catch (JSONException e) {
						Log.d(LOG_TAG, e.toString());
					}
				}

				public void onClose() {
					// Log.d(LOG_TAG, "Successfully closed Websocket");
					PusherConnection.this.changeConnectionState(STATE_DISCONNECTED);
				}
				
				public void onPing() {
					Log.d(LOG_TAG, "Got a Ping");
					send("pusher:pong", new JSONObject(), null);
					changeConnectionState(STATE_CONNECTED);
				}
	            public void onPong() {
	            	Log.d(LOG_TAG, "Got a Pong");
	            	changeConnectionState(STATE_CONNECTED);
	            }
					
			});
			mWebSocket.connect();

		} catch (URISyntaxException e) {
			this.changeConnectionState(STATE_FAILED);
			//e.printStackTrace();
			Log.d(LOG_TAG, e.toString());
		} catch (WebSocketException e) {
			this.connectionUnavailable();
			//e.printStackTrace();
			Log.d(LOG_TAG, e.toString());
		}
	}

	public void disconnect() {
		if (mWebSocket != null) {
			try {
				mWebSocket.close();
			} catch (WebSocketException e) {
				e.printStackTrace();
			}
		}
		mPusher.onDisconnected();
		this.changeConnectionState(STATE_DISCONNECTED);
	}

	public void send(String eventName, JSONObject eventData, String channelName) {
		if (mWebSocket == null)
			return;

		if (mWebSocket.isConnected()) {
			try {
				JSONObject message = new JSONObject();
				message.put("event", eventName);
				message.put("data", eventData);

				if (channelName != null) {
					message.put("channel", channelName);
				}

				mWebSocket.send(message.toString());

				this.changeConnectionState(STATE_CONNECTED);
				
				Log.d(LOG_TAG, "sent message " + message.toString());
			} catch (JSONException e) {
				e.printStackTrace();
			} catch (WebSocketException e) {
				//e.printStackTrace();
				this.connectionUnavailable();
			}
		} else {
			this.connectionUnavailable();
		}
	}

	private void changeConnectionState(String state) {
		if ( ! state.equals(this.mState) ) {
			this.mState = state;
			this.dispatchEvents(state);
			Log.d("PusherConnection", "State changed to " + state);
		}
	}

	public void bind(String event, PusherCallback callback) {
		/*
		 * if there are no callbacks for that event assigned yet, initialize the
		 * list
		 */
		if (!mLocalCallbacks.containsKey(event)) {
			mLocalCallbacks.put(event, new ArrayList<PusherCallback>());
		}

		/* add the callback to the event's callback list */
		mLocalCallbacks.get(event).add(callback);
		Log.d(LOG_TAG, "bound to event " + event + " on connection");
	}

	public void bindAll(PusherCallback callback) {
		mGlobalCallbacks.add(callback);
		Log.d(LOG_TAG, "bound to all events on connection ");
	}

	public void unbind(PusherCallback callback) {
		/* remove all matching callbacks from the global callback list */
		while (mGlobalCallbacks.remove(callback))
			;

		/* remove all matching callbacks from each local callback list */
		for (List<PusherCallback> localCallbacks : mLocalCallbacks.values()) {
			while (localCallbacks.remove(callback))
				;
		}
	}

	public void unbindAll() {
		/* remove all callbacks from the global callback list */
		mGlobalCallbacks.clear();
		/* remove all local callback lists, that is removes all local callbacks */
		mLocalCallbacks.clear();
	}

	private void connectionUnavailable() {
		if (! this.auto_reconnect){
			this.changeConnectionState(STATE_DISCONNECTED);
			return;
		}
		this.changeConnectionState(STATE_UNAVAILABLE);
		this.dispatchEvents(CONNECTING_IN, "{'delay': '" + UNAVAILABILITY_CHECK_TIMER + "'}");
		Log.d(LOG_TAG, "connection_in");
		// start the unavailable timer
		this.unavailabilityTimer.cancel();
		this.unavailabilityTimer = new Timer();
        this.unavailabilityTimer.schedule(new TimerTask() {
            @Override
            public void run() {
            	if ( ! mWebSocket.isConnected() ) {
            		connect();
            	}
            	if (mWebSocket.isConnected()){
            		send("pusher:ping", new JSONObject(), null);
            	}

            }
        }, UNAVAILABILITY_CHECK_TIMER);
	}
	
	private void dispatchEvents(String state){
		this.dispatchEvents(state, "{}");
	}
	
	private void dispatchEvents(String state, String eventData){
		/* Construct a message that a PusherCallback will understand */
		Bundle payload = new Bundle();
		payload.putString("eventName", state);
		payload.putString("eventData", eventData);
		payload.putString("channelName", null);
		Message msg = Message.obtain();
		msg.setData(payload);

		for (PusherCallback callback : mGlobalCallbacks) {
			callback.sendMessage(msg);
		}

		if (this.mLocalCallbacks.containsKey(state)) {

			for (PusherCallback callback : this.mLocalCallbacks.get(state)) {
				callback.sendMessage(msg);
			}
		}
		
	}
	
	public String state(){
		return this.mState;
	}
	
	public void setAutoReconnect(boolean value){
		this.auto_reconnect = value;
	}
}
