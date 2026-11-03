package com.forcemind.chatgpt.networkpreview;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Separate process/run from the original four tests; never mixes global core owners. */
@RunWith(AndroidJUnit4.class)
public final class JointProfileInstrumentedTest {
    @Test public void realProfilesFilesLifecycleAndSystemProxyRemainBound() throws Exception {
        new JointProfileCases(InstrumentationRegistry.getArguments()).run();
    }
}
