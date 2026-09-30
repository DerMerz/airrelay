# Native code looks these up by name (android_raop_callbacks.c, native_bridge.cpp).
-keep interface de.merzkevin.airrelay.bridge.NativeCallbacks { *; }
-keepclassmembers class * implements de.merzkevin.airrelay.bridge.NativeCallbacks {
    public void onVideoData(byte[], long);
    public void onAudioFormat(int, int, boolean);
    public void onVolume(float);
    public float onClientVolume();
    public void onAudioTeardown();
    public void onConnectionInit();
    public void onConnectionDestroy();
    public void onMirrorRunning(boolean);
}
-keep class de.merzkevin.airrelay.bridge.NativeBridge { native <methods>; }
