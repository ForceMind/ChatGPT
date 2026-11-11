package com.forcemind.chatgpt.profiles;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class AppUpdateDeltaTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static String sha(byte[] bytes) throws Exception {
        return AppUpdateTransfer.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private File patch(long offset, boolean finish) throws Exception {
        File patch = temp.newFile();
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(patch.toPath()))) {
            out.writeBytes("STUDIOD1");
            out.writeByte(0); out.writeInt(2); out.write(new byte[]{9, 8});
            out.writeByte(1); out.writeLong(offset); out.writeInt(3);
            if (finish) out.writeByte(255);
        }
        return patch;
    }

    @Test public void exactBytesAndBaseIdentity() throws Exception {
        File base = temp.newFile(), out = new File(temp.getRoot(), "ready.apk");
        byte[] original = {0, 1, 2, 3, 4}, expected = {9, 8, 2, 3, 4};
        Files.write(base.toPath(), original);
        assertTrue(AppUpdateDelta.matchesBase(base, sha(original), () -> {}));
        assertFalse(AppUpdateDelta.matchesBase(base, sha(expected), () -> {}));
        AppUpdateDelta.apply(base, patch(2, true), out, expected.length, sha(expected), () -> {});
        assertArrayEquals(expected, Files.readAllBytes(out.toPath()));
    }

    @Test public void invalidRangeTruncationAndWrongOutputLeaveNoStagedFile() throws Exception {
        File base = temp.newFile(), out = new File(temp.getRoot(), "ready.apk");
        Files.write(base.toPath(), new byte[]{0, 1, 2, 3, 4});
        byte[] expected = {9, 8, 2, 3, 4};
        for (File malformed : new File[]{patch(4, true), patch(2, false), patch(1, true)}) {
            try {
                AppUpdateDelta.apply(base, malformed, out, expected.length, sha(expected), () -> {});
                fail("Invalid delta was accepted");
            } catch (IOException rejected) {
                assertFalse(out.exists());
            }
        }
    }
}
