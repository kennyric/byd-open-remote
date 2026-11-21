package com.bydwatch.companion.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AppUpdateSignatureTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void nonApkFileIsRejectedAsAnUpdate() throws Exception {
        File fake = tempFolder.newFile("fake.apk");
        Files.write(fake.toPath(), "not an apk".getBytes(StandardCharsets.UTF_8));

        assertFalse(AppUpdateChecker.hasCanonicalSignature(
                RuntimeEnvironment.getApplication(), fake));
    }
}
