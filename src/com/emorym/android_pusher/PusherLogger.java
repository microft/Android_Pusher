package com.emorym.android_pusher;

import android.util.Log;

public abstract class PusherLogger {
	
	private String tag = "PusherLogger";

	public PusherLogger() {
	}
	
	public void log(String message){	
		Log.d(this.tag, message);
	}
	
	public void log(String tag, String message){
		Log.d(tag, message);
	}
	
}
