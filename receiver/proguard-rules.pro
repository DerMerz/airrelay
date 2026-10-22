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
    public boolean onClientRequest();
    public void onVideoPlay(java.lang.String, float);
    public void onVideoScrub(float);
    public void onVideoRate(float);
    public void onVideoStop();
}
-keep class de.merzkevin.airrelay.bridge.NativeBridge { native <methods>; }

# Amazon Appstore SDK, rules as given by Amazon (iap-obfuscate-the-code): the SDK finds
# its own classes by name and checks signed answers; obfuscating it breaks purchases
# (AUTH_TOKEN_VERIFICATION_FAILURE).
-dontwarn com.amazon.**
-keep class com.amazon.** {*;}
-keepattributes *Annotation*
