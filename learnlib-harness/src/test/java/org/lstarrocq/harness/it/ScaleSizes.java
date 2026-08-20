package org.lstarrocq.harness.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Loads {@code scale-sizes.properties} (this module's basedir, next to {@code pom.xml} --
 * Maven sets the test JVM's working directory there, same assumption {@link OCamlBinary}
 * makes) so the sizes substituted for LearnLib's real-size adversarial/random examples can be
 * changed by editing that file -- no recompile needed -- instead of hardcoding a Java constant.
 * A system property of the same name overrides the file, for one-off runs (e.g. {@code -Dkeylock.lstar=30}).
 */
final class ScaleSizes {

    private static final Properties PROPERTIES = load();

    private ScaleSizes() {}

    static int get(String key) {
        String override = System.getProperty(key);
        if (override != null) {
            return Integer.parseInt(override);
        }
        String value = PROPERTIES.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("scale-sizes.properties is missing key: " + key);
        }
        return Integer.parseInt(value);
    }

    private static Properties load() {
        Path path = Paths.get(System.getProperty("user.dir")).resolve("scale-sizes.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
        return props;
    }
}
