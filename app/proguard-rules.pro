# Media3 ships its own consumer ProGuard rules bundled in its AAR, so no
# manual keep rules are needed for it here.
#
# Keep the WebView JS bridge's public interface methods — R8/ProGuard cannot
# see that JS calls these by reflection-like binding, so without this the
# optimizer could strip or rename them, silently breaking window.AndroidMedia
# from the JS side with no compile-time warning.
-keepclassmembers class com.starcrossed.app.StarCrossedWebBridge {
    public *;
}
