package io.atomix.raft.snapshot.impl;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static org.assertj.core.api.Assertions.assertThat;

import io.atomix.raft.snapshot.SnapshotStore;
import io.atomix.utils.time.WallClockTimestamp;
import io.zeebe.util.FileUtil;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DirSnapshotStoreTest {

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();
  private DirBasedSnapshotStoreFactory factory;
  private SnapshotStore snapshotStore;
  private Path snapshotsDir;
  private Path pendingSnapshotsDir;

  @Before
  public void before() {
    factory = new DirBasedSnapshotStoreFactory();

    final var partitionName = "1";
    final var root = temporaryFolder.getRoot();

    snapshotStore = factory.createSnapshotStore(root.toPath(), partitionName);

    snapshotsDir = temporaryFolder.getRoot().toPath()
        .resolve(DirBasedSnapshotStoreFactory.SNAPSHOTS_DIRECTORY);
    pendingSnapshotsDir = temporaryFolder.getRoot().toPath()
        .resolve(DirBasedSnapshotStoreFactory.PENDING_DIRECTORY);
  }

  @Test
  public void shouldCreateSubFoldersOnCreatingDirBasedStore() {
    // given

    // when + then
    assertThat(snapshotsDir).exists();
    Assertions.assertThat(pendingSnapshotsDir).exists();
  }

  @Test
  public void shouldNotCreateDirForTakeTransientSnapshot() {
    // given
    final var index = 1L;
    final var term = 0L;
    final var time = WallClockTimestamp.from(123);

    // when
    snapshotStore.takeTransientSnapshot(index, term, time);

    // then
    assertThat(pendingSnapshotsDir.toFile().listFiles()).isEmpty();
    assertThat(snapshotsDir.toFile().listFiles()).isEmpty();
  }

  @Test
  public void shouldBeAbleToAbortNotStartedSnapshot() {
    // given
    final var index = 1L;
    final var term = 0L;
    final var time = WallClockTimestamp.from(123);
    final var transientSnapshot = snapshotStore.takeTransientSnapshot(index, term, time);

    // when
    transientSnapshot.abort();

    // then
    assertThat(snapshotsDir.toFile().listFiles()).isEmpty();
    assertThat(pendingSnapshotsDir.toFile().listFiles()).isEmpty();
  }

  @Test
  public void shouldTakePendingSnapshot() {
    // given
    final var index = 1L;
    final var term = 0L;
    final var time = WallClockTimestamp.from(123);
    final var transientSnapshot = snapshotStore.takeTransientSnapshot(index, term, time);

    // when
    transientSnapshot.take(this::createSnapshotDir);

    // then
    assertThat(snapshotsDir.toFile().listFiles()).isEmpty();
    final var snapshotDirs = pendingSnapshotsDir.toFile().listFiles();
    assertThat(snapshotDirs).isNotNull().hasSize(1);

    final var pendingSnapshotDir = snapshotDirs[0];
    assertThat(pendingSnapshotDir.getName()).isEqualTo("1-0-123");
    assertThat(pendingSnapshotDir.listFiles()).isNotNull().extracting(File::getName).containsExactly("file1.txt");
  }

  @Test
  public void shouldAbortAndDeletePendingSnapshot() {
    // given
    final var index = 1L;
    final var term = 0L;
    final var time = WallClockTimestamp.from(123);
    final var transientSnapshot = snapshotStore.takeTransientSnapshot(index, term, time);
    transientSnapshot.take(this::createSnapshotDir);

    // when
    transientSnapshot.abort();

    // then
    assertThat(snapshotsDir.toFile().listFiles()).isEmpty();
    assertThat(pendingSnapshotsDir.toFile().listFiles()).isEmpty();
  }

  @Test
  public void shouldCommitTakenSnapshot() {
    // given
    final var index = 1L;
    final var term = 0L;
    final var time = WallClockTimestamp.from(123);
    final var transientSnapshot = snapshotStore.takeTransientSnapshot(index, term, time);
    transientSnapshot.take(this::createSnapshotDir);

    // when
    transientSnapshot.commit();

    // then
    assertThat(pendingSnapshotsDir.toFile().listFiles()).isEmpty();

    final var snapshotDirs = snapshotsDir.toFile().listFiles();
    assertThat(snapshotDirs).isNotNull().hasSize(1);

    final var committedSnapshotDir = snapshotDirs[0];
    assertThat(committedSnapshotDir.getName()).isEqualTo("1-0-123");
    assertThat(committedSnapshotDir.listFiles()).isNotNull().extracting(File::getName).containsExactly("file1.txt");
  }

  @Test
  public void shouldReplaceSnapshotOnNextSnapshot() {
    // given
    final var index = 1L;
    final var term = 0L;
    final var time = WallClockTimestamp.from(123);
    final var oldTransientSnapshot = snapshotStore.takeTransientSnapshot(index, term, time);
    oldTransientSnapshot.take(this::createSnapshotDir);
    oldTransientSnapshot.commit();

    // when
    final var newSnapshot = snapshotStore.takeTransientSnapshot(index + 1, term, time);
    newSnapshot.take(this::createSnapshotDir);
    newSnapshot.commit();

    // then
    assertThat(pendingSnapshotsDir.toFile().listFiles()).isEmpty();

    final var snapshotDirs = snapshotsDir.toFile().listFiles();
    assertThat(snapshotDirs).isNotNull().hasSize(1);

    final var committedSnapshotDir = snapshotDirs[0];
    assertThat(committedSnapshotDir.getName()).isEqualTo("2-0-123");
    assertThat(committedSnapshotDir.listFiles()).isNotNull().extracting(File::getName).containsExactly("file1.txt");
  }

  @Test
  public void shouldRemovePendingSnapshotOnCommittingSnapshot() {
    // given
    final var index = 1L;
    final var term = 0L;
    final var time = WallClockTimestamp.from(123);
    final var oldTransientSnapshot = snapshotStore.takeTransientSnapshot(index, term, time);
    oldTransientSnapshot.take(this::createSnapshotDir);

    // when
    final var newSnapshot = snapshotStore.takeTransientSnapshot(index + 1, term, time);
    newSnapshot.take(this::createSnapshotDir);
    newSnapshot.commit();

    // then
    assertThat(pendingSnapshotsDir.toFile().listFiles()).isEmpty();

    final var snapshotDirs = snapshotsDir.toFile().listFiles();
    assertThat(snapshotDirs).isNotNull().hasSize(1);

    final var committedSnapshotDir = snapshotDirs[0];
    assertThat(committedSnapshotDir.getName()).isEqualTo("2-0-123");
    assertThat(committedSnapshotDir.listFiles()).isNotNull().extracting(File::getName).containsExactly("file1.txt");
  }

  @Test
  public void shouldCleanUpPendingDirOnFailingTakeSnapshot() {
    // given
    final var index = 1L;
    final var term = 0L;
    final var time = WallClockTimestamp.from(123);
    final var oldTransientSnapshot = snapshotStore.takeTransientSnapshot(index, term, time);

    // when
    oldTransientSnapshot.take(path -> {
      try {
        FileUtil.ensureDirectoryExists(path);
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
      return false;
    });

    // then
    assertThat(pendingSnapshotsDir.toFile().listFiles()).isEmpty();
    assertThat(snapshotsDir.toFile().listFiles()).isEmpty();
  }

  @Test
  public void shouldCleanUpPendingDirOnException() {
    // given
    final var index = 1L;
    final var term = 0L;
    final var time = WallClockTimestamp.from(123);
    final var oldTransientSnapshot = snapshotStore.takeTransientSnapshot(index, term, time);

    // when
    oldTransientSnapshot.take(path -> {
      try {
        FileUtil.ensureDirectoryExists(path);
        throw new RuntimeException("EXPECTED");
      } catch (final IOException e) {
        throw new UncheckedIOException(e);
      }
    });

    // then
    assertThat(pendingSnapshotsDir.toFile().listFiles()).isEmpty();
    assertThat(snapshotsDir.toFile().listFiles()).isEmpty();
  }

  private boolean createSnapshotDir(final Path path) {
    try {
      FileUtil.ensureDirectoryExists(path);
      Files.write(path.resolve("file1.txt"), "This is the content".getBytes(), CREATE_NEW, StandardOpenOption.WRITE);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return true;
  }

}
