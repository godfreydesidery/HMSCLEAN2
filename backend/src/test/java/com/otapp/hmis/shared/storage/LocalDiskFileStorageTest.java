package com.otapp.hmis.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.otapp.hmis.shared.error.InvalidPatientOperationException;
import com.otapp.hmis.shared.error.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link LocalDiskFileStorage} (inc-06A C7 review F-06 — path-traversal hardening
 * had no direct coverage). Plain JUnit (no Spring context) — exercises store/read/delete and the
 * SEC-03 traversal guards directly.
 */
class LocalDiskFileStorageTest {

    private LocalDiskFileStorage storage(Path base) {
        return new LocalDiskFileStorage(new AttachmentStorageProperties(base.toString(), 10_485_760L));
    }

    @Test
    void store_thenRead_roundTrips(@TempDir Path base) {
        LocalDiskFileStorage s = storage(base);
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);

        String name = s.store(bytes, "scan.pdf", "LT", "01ABCDEF0123456789ABCDEF01");

        assertThat(name).startsWith("LT01ABCDEF0123456789ABCDEF01-").endsWith(".pdf");
        assertThat(s.read(name)).isEqualTo(bytes);
    }

    @Test
    void store_createsBaseDirIfAbsent(@TempDir Path base) {
        Path nested = base.resolve("does/not/exist/yet");
        LocalDiskFileStorage s = storage(nested);
        String name = s.store("x".getBytes(StandardCharsets.UTF_8), "a.txt", "LT", "UID0000000000000000000001");
        assertThat(s.read(name)).isEqualTo("x".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void read_missingFile_throwsNotFound(@TempDir Path base) {
        LocalDiskFileStorage s = storage(base);
        assertThatThrownBy(() -> s.read("LTno-such-file.pdf"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void read_pathTraversal_rejected(@TempDir Path base) {
        LocalDiskFileStorage s = storage(base);
        for (String evil : new String[]{"../secret", "..\\secret", "a/b", "a\\b", ".."}) {
            assertThatThrownBy(() -> s.read(evil))
                    .as("traversal input %s must be rejected", evil)
                    .isInstanceOf(InvalidPatientOperationException.class);
        }
    }

    @Test
    void read_pathTraversal_doesNotReflectRawFilename(@TempDir Path base) {
        LocalDiskFileStorage s = storage(base);
        // SEC-03: a distinctive attacker token must NOT be echoed in the 422 message.
        String evil = "../zzAttackerTokenzz/secret";
        assertThatThrownBy(() -> s.read(evil))
                .isInstanceOf(InvalidPatientOperationException.class)
                .matches(ex -> !ex.getMessage().contains("zzAttackerTokenzz"),
                        "message must not reflect the raw attacker filename");
    }

    @Test
    void read_blankFilename_rejected(@TempDir Path base) {
        LocalDiskFileStorage s = storage(base);
        assertThatThrownBy(() -> s.read("  "))
                .isInstanceOf(InvalidPatientOperationException.class);
    }

    @Test
    void delete_isBestEffort_missingIgnored(@TempDir Path base) {
        LocalDiskFileStorage s = storage(base);
        // Deleting a never-stored file must not throw.
        s.delete("LTnever-stored.pdf");

        // Round-trip then delete removes it.
        String name = s.store("z".getBytes(StandardCharsets.UTF_8), "z.dat", "LT", "UID0000000000000000000002");
        s.delete(name);
        assertThatThrownBy(() -> s.read(name)).isInstanceOf(NotFoundException.class);
    }
}
