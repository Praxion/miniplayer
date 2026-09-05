package com.miniplayer;

import java.net.URL;

/**
 * Resolves the on-disk path to fake_engine.py, which Maven copies from
 * src/test/resources into target/test-classes at test time. Centralized
 * here so every fake-script-based test class shares one lookup.
 */
final class FakeEngineScript {

    private FakeEngineScript() {
    }

    static String resolvePath() {
        URL url = FakeEngineScript.class.getClassLoader().getResource("fake_engine.py");
        if (url == null) {
            throw new IllegalStateException(
                    "fake_engine.py not found on the test classpath - expected at "
                            + "src/test/resources/fake_engine.py. If you moved or renamed "
                            + "it, update this lookup too.");
        }
        return url.getFile();
    }
}
