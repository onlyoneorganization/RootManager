
package com.chrisplus.rootmanager;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

import android.text.TextUtils;

import com.chrisplus.rootmanager.container.Command;
import com.chrisplus.rootmanager.container.Result;
import com.chrisplus.rootmanager.container.Result.ResultBuilder;
import com.chrisplus.rootmanager.container.Shell;
import com.chrisplus.rootmanager.exception.PermissionException;
import com.chrisplus.rootmanager.utils.Remounter;
import com.chrisplus.rootmanager.utils.RootUtils;

/**
 * This class is the main interface of RootManager.
 * 
 * @author Shiqi Jiang
 */
public class RootManager {

    private static RootManager instance;

    private Boolean hasRooted = null;
    private boolean hasGivenPermission = false;
    private long lastPermissionCheck = -1;

    private RootManager() {

    }

    public static synchronized RootManager getInstance() {
        if (instance == null) {
            instance = new RootManager();
        }
        return instance;
    }

    /**
     * Check whether the device is rooted
     * <p>
     * A rooted device must have a binary file named SU, but SU might not work
     * on some devices for some reasons.
     * </p>
     * 
     * @return the result whether this device was rooted.
     */
    public boolean hasRooted() {
        if (hasRooted == null) {
            for (String path : Constants.SU_BINARY_DIRS) {
                File su = new File(path + "/su");
                if (su.exists()) {
                    hasRooted = true;
                    break;
                } else {
                    hasRooted = false;
                }
            }
        }

        return hasRooted;
    }

    /**
     * Try to obtain the root privilege.
     * <p>
     * This function might popup a dialog to the users, and wait for input
     * (grant or decline), the return the result.
     * </p>
     * 
     * @return whether your app has been granted the root permission.
     */
    public boolean obtainPermission() {
        if (!hasGivenPermission) {
            hasGivenPermission = accessRoot();
            lastPermissionCheck = System.currentTimeMillis();
        } else {
            if (lastPermissionCheck < 0
                    || System.currentTimeMillis() - lastPermissionCheck > Constants.PERMISSION_EXPIRE_TIME) {
                hasGivenPermission = accessRoot();
                lastPermissionCheck = System.currentTimeMillis();
            }
        }

        return hasGivenPermission;
    }

    /**
     * Install an application on the device.
     * <p>
     * do NOT call this function on UI thread, {@link IllegalStateException}
     * will be thrown if you do so.
     * </p>
     * 
     * @param apkPath the APK file path, do not start with <I>"file://"</I>. For
     *            example, <I>"/sdcard/Tech_test.apk"<I> is OK. Only ASCII chars
     *            are supported.
     * @return The result of run command operation or install operation.
     */
    public Result installPackage(String apkPath) {
        return installPackage(apkPath, "a");
    }

    /**
     * Install a user app on the device.
     * <p>
     * do NOT call this function on UI thread, {@link IllegalStateException}
     * will be thrown if you do so.
     * </p>
     * 
     * @param apkPath the APK file path, do not start with <I>"file://"</I>. For
     *            example, <I>"/sdcard/Tech_test.apk"<I> is OK. Only ASCII chars
     *            are supported.
     * @param installLocation the location of install.
     *            <ul>
     *            <li>auto: install location automatic.</li>
     *            <li>ex: install the app on sdcard.</li>
     *            <li>in: install the app on ram</li>
     *            </ul>
     * @return The result of run command operation or install operation.
     */
    public Result installPackage(String apkPath, String installLocation) {

        RootUtils.checkUIThread();

        final ResultBuilder builder = Result.newBuilder();

        if (TextUtils.isEmpty(apkPath)) {
            return builder.setFailed().build();
        }

        String command = Constants.COMMAND_INSTALL;
        if (RootUtils.isNeedPathSDK()) {
            command = Constants.COMMAND_INSTALL_PATCH + command;
        }

        command = command + apkPath;

        if (TextUtils.isEmpty(installLocation)) {
            if (installLocation.equalsIgnoreCase("ex")) {
                command = command + Constants.COMMAND_INSTALL_LOCATION_EXTERNAL;
            } else if (installLocation.equalsIgnoreCase("in")) {
                command = command + Constants.COMMAND_INSTALL_LOCATION_INTERNAL;
            }
        }

        final StringBuilder infoSb = new StringBuilder();
        Command commandImpl = new Command(command) {

            @Override
            public void onUpdate(int id, String message) {
                infoSb.append(message + "\n");
            }

            @Override
            public void onFinished(int id) {
                String finalInfo = infoSb.toString();
                if (TextUtils.isEmpty(finalInfo)) {
                    builder.setInstallFailed();
                } else {
                    if (finalInfo.contains("success") || finalInfo.contains("Success")) {
                        builder.setInstallSuccess();
                    } else if (finalInfo.contains("failed") || finalInfo.contains("FAILED")) {
                        if (finalInfo.contains("FAILED_INSUFFICIENT_STORAGE")) {
                            builder.setInsallFailedNoSpace();
                        } else if (finalInfo.contains("FAILED_INCONSISTENT_CERTIFICATES")) {
                            builder.setInstallFailedWrongCer();
                        } else if (finalInfo.contains("FAILED_CONTAINER_ERROR")) {
                            builder.setInstallFailedWrongCer();
                        } else {
                            builder.setInstallFailed();
                        }

                    } else {
                        builder.setInstallFailed();
                    }
                }
            }

        };

        try {
            Shell.startRootShell().add(commandImpl).waitForFinish();
        } catch (InterruptedException e) {
            e.printStackTrace();
            builder.setCommandFailedInterrupted();
        } catch (IOException e) {
            e.printStackTrace();
            builder.setCommandFailed();
        } catch (TimeoutException e) {
            e.printStackTrace();
            builder.setCommandFailedTimeout();
        } catch (PermissionException e) {
            e.printStackTrace();
            builder.setCommandFailedDenied();
        }

        return builder.build();

    }

    /**
     * Uninstall the application using its package name.
     * <p>
     * do NOT call this function on UI thread, {@link IllegalStateException}
     * will be thrown if you do so.
     * </p>
     * 
     * @param packageName the app's package.
     * @return The result of run command operation or uninstall operation.
     */
    public Result uninstallPackage(String packageName) {
        RootUtils.checkUIThread();

        final ResultBuilder builder = Result.newBuilder();

        if (TextUtils.isEmpty(packageName)) {
            return builder.setFailed().build();
        }

        String command = Constants.COMMAND_UNINSTALL + packageName;
        final StringBuilder infoSb = new StringBuilder();

        Command commandImpl = new Command(command) {

            @Override
            public void onUpdate(int id, String message) {
                infoSb.append(message + "\n");
            }

            @Override
            public void onFinished(int id) {
                String finalInfo = infoSb.toString();
                if (TextUtils.isEmpty(finalInfo)) {
                    builder.setUninstallFailed();
                } else {
                    if (finalInfo.contains("Success") || finalInfo.contains("success")) {
                        builder.setUninstallSuccess();
                    } else {
                        builder.setUninstallFailed();
                    }
                }
            }

        };

        try {
            Shell.startRootShell().add(commandImpl).waitForFinish();
        } catch (InterruptedException e) {
            e.printStackTrace();
            builder.setCommandFailedInterrupted();
        } catch (IOException e) {
            e.printStackTrace();
            builder.setCommandFailed();
        } catch (TimeoutException e) {
            e.printStackTrace();
            builder.setCommandFailedTimeout();
        } catch (PermissionException e) {
            e.printStackTrace();
            builder.setCommandFailedDenied();
        }

        return builder.build();
    }

    /**
     * Uninstall a system app.
     * <p>
     * do NOT call this function on UI thread, {@link IllegalStateException}
     * will be thrown if you do so.
     * </p>
     * 
     * @param apkPath the source apk path of system app.
     * @return The result of run command operation or uninstall operation.
     */
    public Result uninstallSystemApp(String apkPath) {
        RootUtils.checkUIThread();

        ResultBuilder builder = Result.newBuilder();
        if (TextUtils.isEmpty(apkPath)) {
            return builder.setFailed().build();
        }

        if (remount(Constants.PATH_SYSTEM, "rw")) {
            File apkFile = new File(apkPath);
            if (apkFile.exists()) {
                return runCommand("rm '" + apkPath + "'");
            }
        }

        return builder.setFailed().build();
    }

    /**
     * Install a binary file into <I>"/system/bin/"</I>
     * 
     * @param filePath The target of the binary file.
     * @return the operation result.
     */
    public boolean installBinary(String filePath) {
        if (TextUtils.isEmpty(filePath)) {
            return false;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return false;
        }

        return copyFile(filePath, Constants.PATH_SYSTEM_BIN);
    }

    /**
     * Remove a binary from <I>"/system/bin/"</I>
     * 
     * @param fileName, name of target file.
     * @return the operation result.
     */
    public boolean removeBinary(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return false;
        }

        File file = new File(Constants.PATH_SYSTEM_BIN + fileName);
        if (!file.exists()) {
            return false;
        }

        if (remount(Constants.PATH_SYSTEM, "rw")) {
            return runCommand("rm '" + Constants.PATH_SYSTEM_BIN + fileName + "'").getResult();
        } else {
            return false;
        }

    }

    /**
     * Copy a file into destination dir.
     * <p>
     * since Android do not support <I>"cp"</I> command by default,
     * <i>"cat source > destination"</i> will be used.
     * </p>
     * 
     * @param source the source file path.
     * @param destinationDir the destination dir path.
     * @return the operation result.
     */
    public boolean copyFile(String source, String destinationDir) {
        if (TextUtils.isEmpty(destinationDir) || TextUtils.isEmpty(source)) {
            return false;
        }

        File sourceFile = new File(source);
        File desFile = new File(destinationDir);
        if (!sourceFile.exists() || !desFile.isDirectory()) {
            return false;
        }

        if (remount(destinationDir, "rw")) {
            return runCommand("cat '" + source + "' > " + destinationDir).getResult();
        } else {
            return false;
        }

    }

    /**
     * Remount a path file as the type.
     * 
     * @param path the path you want to remount
     * @param mountType the mount type, including, <i>"ro", read only, "rw" ,
     *            read and write</i>
     * @return the operation result.
     */
    public boolean remount(String path, String mountType) {
        if (TextUtils.isEmpty(path) || TextUtils.isEmpty(mountType)) {
            return false;
        }

        if (mountType.equalsIgnoreCase("rw") || mountType.equalsIgnoreCase("ro")) {
            return Remounter.remount(path, mountType);
        } else {
            return false;
        }

    }

    /**
     * Run a binary in <i>"/system/bin/"</i>
     * 
     * @param binaryName the file name of binary, containing params if
     *            necessary.
     * @return the operation result.
     */
    public Result runBinBinary(String binaryName) {
        ResultBuilder builder = Result.newBuilder();
        if (TextUtils.isEmpty(binaryName)) {
            return builder.setFailed().build();
        }
        return runBinary(Constants.PATH_SYSTEM_BIN + binaryName);
    }

    /**
     * Run a binary file.
     * 
     * @param path the file path of binary, containing params if necessary.
     * @return the operation result.
     */
    public Result runBinary(String path) {
        return runCommand(path);
    }

    /**
     * Run raw commands in default shell.
     * 
     * @param command the command string.
     * @return the operation result.
     */
    public Result runCommand(String command) {

        final ResultBuilder builder = Result.newBuilder();
        if (TextUtils.isEmpty(command)) {
            return builder.setFailed().build();
        }

        final StringBuilder infoSb = new StringBuilder();
        Command commandImpl = new Command(command) {

            @Override
            public void onUpdate(int id, String message) {
                infoSb.append(message + "\n");
            }

            @Override
            public void onFinished(int id) {
                builder.setCustomMessage(infoSb.toString());
            }

        };

        try {
            Shell.startRootShell().add(commandImpl).waitForFinish();
        } catch (InterruptedException e) {
            e.printStackTrace();
            builder.setCommandFailedInterrupted();
        } catch (IOException e) {
            e.printStackTrace();
            builder.setCommandFailed();
        } catch (TimeoutException e) {
            e.printStackTrace();
            builder.setCommandFailedTimeout();
        } catch (PermissionException e) {
            e.printStackTrace();
            builder.setCommandFailedDenied();
        }

        return builder.build();
    }

    /**
     * Get screen shot.
     * 
     * @param path the path with file name and extend name.
     * @return the operation result.
     */
    public boolean screenCap(String path) {

        if (TextUtils.isEmpty(path)) {
            return false;
        }
        Result res = runCommand(Constants.COMMAND_SCREENCAP + path);
        RootUtils.Log((res == null) + "");

        return res.getResult();
    }

    /**
     * Record screen.
     *
     * @param path the path with file name and extend name.
     * @return the operation result.
     */
    public boolean screenRecord(String path){
        return false;
    }

    /**
     * Check whether a process is running.
     * 
     * @param processName the name of process. For user app, the process name is
     *            its package name.
     * @return whether this process is currently running.
     */
    public boolean isProcessRunning(String processName) {

        if (TextUtils.isEmpty(processName)) {
            return false;
        }
        Result infos = runCommand(Constants.COMMAND_PS);
        return infos.getMessage().contains(processName);
    }

    /**
     * Kill a process by its name.
     * 
     * @param processName the name of this process. For user app, the process
     *            name is its package name.
     * @return the result of operation.
     */
    public boolean killProcessByName(String processName) {
        if (TextUtils.isEmpty(processName)) {
            return false;
        }
        Result res = runCommand(Constants.COMMAND_PIDOF + processName);

        if (!TextUtils.isEmpty(res.getMessage())) {
            return killProcessById(res.getMessage());
        } else {
            return false;
        }
    }

    /**
     * Kill a process by its process id, hence pid.
     * 
     * @param processID the PID of this process.
     * @return the result of this operation.
     */
    public boolean killProcessById(String processID) {
        if (TextUtils.isEmpty(processID)) {
            return false;
        }

        Result res = runCommand(Constants.COMMAND_KILL + processID);
        return res.getResult();
    }

    /**
     * Restart the device.
     */
    public void restartDevice() {
        killProcessByName("zygote");
    }

    private static boolean accessRoot = false;

    private boolean accessRoot() {

        boolean result = false;
        accessRoot = false;

        Command commandImpl = new Command("id") {

            @Override
            public void onUpdate(int id, String message) {
                if (message != null && message.toLowerCase().contains("uid=0")) {
                    accessRoot = true;
                }
            }

            @Override
            public void onFinished(int id) {

            }

        };

        try {
            Shell.startRootShell().add(commandImpl).waitForFinish();
            result = accessRoot;
        } catch (InterruptedException e) {
            e.printStackTrace();
            result = false;
        } catch (IOException e) {
            e.printStackTrace();
            result = false;
        } catch (TimeoutException e) {
            e.printStackTrace();
            result = false;
        } catch (PermissionException e) {
            e.printStackTrace();
            result = false;
        }

        return result;

    }
}
