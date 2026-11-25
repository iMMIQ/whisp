# libnfs, libsmb2, and native NEON audio DSP JNI entry points and record constructors.
-keep class app.whisp.data.NfsNative { *; }
-keep class app.whisp.data.NfsNativeEntry {
    public <init>(byte[], int, long, long);
}
-keep class app.whisp.data.SmbNative { *; }
-keep class app.whisp.data.SmbNativeEntry {
    public <init>(byte[], boolean, long, long);
}
-keep class app.whisp.playback.DspNative { *; }
