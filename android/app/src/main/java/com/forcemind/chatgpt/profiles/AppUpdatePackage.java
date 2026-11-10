package com.forcemind.chatgpt.profiles;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.IntConsumer;
import java.util.zip.ZipFile;

/** Verify the actual APK, then hash the same bytes as they enter the system session. */
final class AppUpdatePackage {
    static PackageInfo installed(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    }

    static String certificate(PackageInfo info) throws Exception {
        if (info == null || info.signingInfo == null || info.signingInfo.hasMultipleSigners())
            throw new IOException("更新包签名不可确认");
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null || signers.length != 1) throw new IOException("更新包签名不可确认");
        return AppUpdateTransfer.hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
    }

    static void verify(Context context, AppUpdateRelease release, File file) throws Exception {
        PackageInfo current = installed(context);
        if (!AppUpdateRelease.PACKAGE.equals(context.getPackageName()) || release.code <= current.getLongVersionCode()
                || !release.certificate.equals(certificate(current)) || !Arrays.asList(Build.SUPPORTED_ABIS).contains(release.abi))
            throw new IOException("更新版本、架构或签名与当前应用不符");
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile() || file.length() != release.size)
            throw new IOException("更新文件大小不符");
        // Some Android versions collect archive certificates only when GET_SIGNATURES is set.
        // Still compare the actual current signer through SigningInfo, including on Android 9.
        @SuppressWarnings("deprecation")
        int archiveFlags = PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES;
        PackageInfo apk = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), archiveFlags);
        if (apk == null || !context.getPackageName().equals(apk.packageName) || apk.getLongVersionCode() != release.code
                || !release.name.equals(apk.versionName) || !release.certificate.equals(certificate(apk)))
            throw new IOException("APK 包名、版本或证书不符");
        try (ZipFile archive = new ZipFile(file)) {
            if (!archive.stream().anyMatch(entry -> !entry.isDirectory() && entry.getName().startsWith("lib/" + release.abi + "/")
                    && entry.getName().endsWith(".so"))) throw new IOException("APK 架构不符");
        }
    }

    static int prepare(Context context, AppUpdateRelease release, File file, IntConsumer remember) throws Exception {
        verify(context, release, file);
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(release.size);
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id);
                FileInputStream input = new FileInputStream(file);
                OutputStream output = session.openWrite("base.apk", 0, release.size)) {
            remember.accept(id);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long bytes = 0;
            for (int count; (count = input.read(buffer)) != -1;) {
                bytes += count;
                if (bytes > release.size) throw new IOException("更新文件大小不符");
                sha.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
            if (bytes != release.size || !release.sha256.equals(AppUpdateTransfer.hex(sha.digest())))
                throw new IOException("更新文件完整性校验失败");
            session.fsync(output);
            return id;
        } catch (Exception failure) {
            abandon(context, id);
            throw failure;
        }
    }

    static void commit(Context context, int id) throws Exception {
        Intent status = new Intent(context, AppUpdateInstallReceiver.class).setAction(AppUpdateInstallReceiver.ACTION)
                .putExtra("studioSession", id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent callback = PendingIntent.getBroadcast(context, id, status, flags);
        try (PackageInstaller.Session session = context.getPackageManager().getPackageInstaller().openSession(id)) {
            session.commit(callback.getIntentSender());
        }
    }

    static void abandon(Context context, int id) {
        if (id < 0) return;
        try { context.getPackageManager().getPackageInstaller().abandonSession(id); }
        catch (RuntimeException alreadyEnded) { /* A terminal system result can race cancellation. */ }
    }

}
