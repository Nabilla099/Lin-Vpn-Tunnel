package hev.htproxy;

/**
 * Jembatan JNI ke tun2socks (hev-socks5-tunnel).
 *
 * Nama paket/kelas dan signature HARUS identik dengan tabel RegisterNatives di
 * src/hev-jni.c milik hev-socks5-tunnel. scripts/build-natives.sh memverifikasinya
 * saat build, supaya ketidakcocokan ketahuan di CI, bukan saat aplikasi crash.
 */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    public static native boolean TProxyStartService(String configPath, int fd);

    public static native boolean TProxyStopService();

    public static native boolean TProxyIsRunning();

    /** [txPackets, txBytes, rxPackets, rxBytes]; tx = upload, rx = download. */
    public static native long[] TProxyGetStats();
}
