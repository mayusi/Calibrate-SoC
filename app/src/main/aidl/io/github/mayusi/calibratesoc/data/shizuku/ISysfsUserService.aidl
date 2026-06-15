// ISysfsUserService.aidl
// Binder interface for the Shizuku UserService that runs at shell UID.
// The service executes sysfs writes with shell privileges (u:r:shell:s0),
// which is more permissive than the app UID but still subject to SELinux
// decisions by the vendor kernel. Only nodes that pass the per-node probe
// will be routed here.
package io.github.mayusi.calibratesoc.data.shizuku;

interface ISysfsUserService {
    /**
     * Read the current string value from a sysfs node. Returns null
     * (empty string sentinel: "") when the path is absent or unreadable.
     */
    String readSysfsNode(String path);

    /**
     * Write value to a sysfs node. Returns 0 on success, or an errno-like
     * code on failure (EACCES=13, EPERM=1, EIO=5, etc.). A shell-level
     * SELinux denial surfaces as EACCES.
     */
    int writeSysfsNode(String path, String value);

    /**
     * No-op probe: read path, write the same value back, return 0 on success.
     * This is the honesty test — confirms shell can actually write THIS node
     * on THIS device's sepolicy without changing any kernel state.
     */
    int probeWritable(String path);

    /** Destroy the service when the app unbinds or is done. */
    void destroy();
}
